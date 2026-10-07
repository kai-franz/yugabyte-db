# Copyright (c) YugabyteDB, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on the "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied.  See the License for the specific language governing permissions and limitations
# under the License.

"""
Unit tests for how scripts/installation/bin/yb-ctl waits for YSQL to accept connections.
"""

import subprocess
import types
import unittest
from typing import Any, ClassVar, List, Optional
from unittest import mock

from yugabyte.test_yb_ctl_data_dir import _load_yb_ctl_module

FAKE_PG_ISREADY = "/fake/postgres/bin/pg_isready"


class TestYbCtlWaitForYsql(unittest.TestCase):
    yb_ctl: ClassVar[types.ModuleType]

    @classmethod
    def setUpClass(cls) -> None:
        cls.yb_ctl = _load_yb_ctl_module()

    def make_control(
            self,
            num_tservers: int = 3,
            stopped_tservers: Optional[List[int]] = None,
            tserver_flags: Optional[List[str]] = None,
            enable_ysql: bool = True) -> Any:
        control = self.yb_ctl.ClusterControl()
        control.args = types.SimpleNamespace(verbose=False)
        control.creating_cluster = True
        control.cluster_config = {
            "enable_ysql": enable_ysql,
            "replication_factor": num_tservers,
        }
        control.options.set_cluster_config(control.cluster_config)
        control.options.tserver_flags = tserver_flags or []
        control.options.timeout_yb_admin_sec = 1.0
        control.options.get_binary_path = mock.Mock(return_value=FAKE_PG_ISREADY)
        stopped = set(stopped_tservers or [])
        control.get_pid = mock.Mock(
            side_effect=lambda daemon_id: None if daemon_id.index in stopped else 1000)
        return control

    def probed_hosts(self, probe: mock.Mock) -> List[str]:
        hosts = []
        for call in probe.call_args_list:
            cmd = call.args[0]
            self.assertEqual(cmd[0], FAKE_PG_ISREADY)
            self.assertEqual(cmd[cmd.index("-p") + 1], "5433")
            hosts.append(cmd[cmd.index("-h") + 1])
        return hosts

    def test_probes_every_running_tserver(self) -> None:
        control = self.make_control(stopped_tservers=[2])
        with mock.patch.object(self.yb_ctl, "call_get_output_maybe_error") as probe:
            self.assertTrue(control.wait_for_ysql())
        self.assertEqual(self.probed_hosts(probe), ["127.0.0.1", "127.0.0.3"])

    def test_retries_until_ysql_accepts_connections(self) -> None:
        control = self.make_control(num_tservers=1)
        not_ready = subprocess.CalledProcessError(1, [FAKE_PG_ISREADY])
        with mock.patch.object(
                self.yb_ctl, "call_get_output_maybe_error",
                side_effect=[not_ready, b""]) as probe:
            self.assertTrue(control.wait_for_ysql())
        self.assertEqual(probe.call_count, 2)

    def test_fails_if_ysql_never_accepts_connections(self) -> None:
        control = self.make_control(num_tservers=1)
        not_ready = subprocess.CalledProcessError(2, [FAKE_PG_ISREADY])
        with mock.patch.object(
                self.yb_ctl, "call_get_output_maybe_error", side_effect=not_ready):
            self.assertFalse(control.wait_for_ysql())

    def test_does_not_wait_without_ysql(self) -> None:
        control = self.make_control(enable_ysql=False)
        with mock.patch.object(self.yb_ctl, "call_get_output_maybe_error") as probe:
            self.assertTrue(control.wait_for_ysql())
        probe.assert_not_called()

    def test_does_not_wait_when_tserver_flags_override_ysql(self) -> None:
        for flag in ("enable_ysql=false",
                     "start_pgsql_proxy=true",
                     "pgsql_proxy_bind_address=127.0.0.1:5555"):
            with self.subTest(flag=flag):
                control = self.make_control(tserver_flags=[flag])
                with mock.patch.object(self.yb_ctl, "call_get_output_maybe_error") as probe:
                    self.assertTrue(control.wait_for_ysql())
                probe.assert_not_called()

    def test_does_not_wait_without_pg_isready(self) -> None:
        control = self.make_control()
        control.options.get_binary_path = mock.Mock(side_effect=RuntimeError("not found"))
        with mock.patch.object(self.yb_ctl, "call_get_output_maybe_error") as probe:
            self.assertTrue(control.wait_for_ysql())
        probe.assert_not_called()


if __name__ == "__main__":
    unittest.main()
