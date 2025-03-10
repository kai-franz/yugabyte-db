# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# The following only applies to changes made to this file as part of YugaByte development.
#
# Portions Copyright (c) YugaByte, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied.  See the License for the specific language governing permissions and limitations
# under the License.
#

set(YB_PCH_PREFIX integration-tests)
set(YB_PCH_DEP_LIBS boost_regex opid_proto)

file(COPY
  ${CMAKE_SOURCE_DIR}/test_certs DESTINATION
  ${CMAKE_BINARY_DIR}
  FILE_PERMISSIONS OWNER_WRITE OWNER_READ)

ADD_YB_TEST_LIBRARY(
    cdc_test_util
    SRCS cdc_test_util.cc
    DEPS yb_test_util cdc_service_proto tserver)

set(INTEGRATION_TESTS_SRCS
  cluster_itest_util.cc
  cluster_verifier.cc
  create-table-itest-base.cc
  external_mini_cluster.cc
  external_mini_cluster_fs_inspector.cc
  load_balancer_test_util.cc
  load_generator.cc
  mini_cluster.cc
  mini_cluster_base.cc
  mini_cluster_utils.cc
  packed_row_test_base.cc
  redis_table_test_base.cc
  test_workload.cc
  ts_itest-base.cc
  yb_mini_cluster_test_base.cc
  yb_table_test_base.cc
  xcluster/xcluster_test_base.cc
  cdcsdk_test_base.cc
  cdcsdk_ysql_test_base.cc
  xcluster/xcluster_ysql_test_base.cc
  xcluster/xcluster_ycql_test_base.cc
)

ADD_YB_TEST_LIBRARY(integration-tests SRCS ${INTEGRATION_TESTS_SRCS})
target_link_libraries(integration-tests
  tserver
  tserver_test_util
  master
  pq_utils
  ql_api
  ysck
  yb-admin_lib
  yb_client
  yb_client_test_util
  yb_fs
  yb-redisserver-test
  yb_test_util
  yb-redis
  yb_pgwrapper
  master_test_common
  master_proto
  cdc_test_util
  test_echo_service_proto)

add_dependencies(integration-tests
  "${YB_MASTER_DYNAMIC_EXE_NAME}"
  "${YB_TSERVER_DYNAMIC_EXE_NAME}"
  yb-admin
  gen_auto_flags_json
  yb-ts-cli)

ADD_YB_TEST_LIBRARY(
    cql_test_util
    SRCS cql_test_base.cc cql_test_util.cc
    DEPS cassandra integration-tests yb-cql yb_test_util tools_test_utils)

# Requires ql-dml-test-base, so separating from integration-tests.
add_library(tablet-split-itest-base tablet-split-itest-base.cc)
target_link_libraries(tablet-split-itest-base integration-tests ql-dml-test-base)

# Tests
set(YB_TEST_LINK_LIBS
    cql_test_util
    integration-tests
    ql-dml-test-base
    rpc_test_util
    tablet-split-itest-base
    yb_client
    yb_common_test_util
    yb_docdb_test_common
    yb_tools_util
    ${YB_MIN_TEST_LIBS})

ADD_YB_TEST(alter_table-test)
ADD_YB_TEST(alter_table-randomized-test)
ADD_YB_TEST(auto_flags-itest)
ADD_YB_TEST(registration-test)
ADD_YB_TEST(clock_synchronization-itest)
ADD_YB_TEST(client_failover-itest)
ADD_YB_TEST(client-stress-test)
ADD_YB_TEST(cluster_trace-test)
ADD_YB_TEST(tablet_server-itest)
ADD_YB_TEST(tablet-split-itest)
ADD_YB_TEST(cdcsdk_gflag-test)
ADD_YB_TEST(stateful_services/stateful_service-itest)
ADD_YB_TEST(master_heartbeat-itest)

# Tests which fail on purpose for checking Jenkins test failures reporting, disabled
# (commented out) by default:
# ADD_YB_TEST(test_failures-test)
# ADD_YB_TEST(non_gtest_failures-test.sh)

# Not sure if we really need RUN_SERIAL here as this might not be a resource-intensive test.
ADD_YB_TEST(tserver_metrics_heartbeat_data_provider-itest)
ADD_YB_TEST(are_leaders_on_preferred_only-itest)
ADD_YB_TEST(compaction-test)
ADD_YB_TEST(compressed_stream-test)
ADD_YB_TEST(logging-test)
ADD_YB_TEST(master_replication-itest)
ADD_YB_TEST(master_sysnamespace-itest)
ADD_YB_TEST(master_path_handlers-itest)
ADD_YB_TEST(tserver_path_handlers-itest)
ADD_YB_TEST(master_failover-itest)
ADD_YB_TEST(master_config-itest)
ADD_YB_TEST(master_tasks-test)
ADD_YB_TEST(network_failure-test)
ADD_YB_TEST(system_table_fault_tolerance)
ADD_YB_TEST(raft_consensus-itest)
ADD_YB_TEST(flush-test)
ADD_YB_TEST(ts_tablet_manager-itest)
ADD_YB_TEST(ts_recovery-itest)
ADD_YB_TEST(create-table-stress-test)
ADD_YB_TEST(master-partitioned-test)
ADD_YB_TEST(minicluster-snapshot-test)
ADD_YB_TEST(delete_table-test)
ADD_YB_TEST(external_mini_cluster-test)
ADD_YB_TEST(linked_list-test)
ADD_YB_TEST(all_types-itest)
ADD_YB_TEST(remote_bootstrap-itest)
ADD_YB_TEST(tablet_replacement-itest)
ADD_YB_TEST(create-table-itest)
ADD_YB_TEST(placement_info-itest)
ADD_YB_TEST(kv_table-test)
ADD_YB_TEST(kv_table_ts_failover-test)
ADD_YB_TEST(kv_table_ts_failover_write_if-test)
ADD_YB_TEST(flush_under_load-test)
ADD_YB_TEST(stepdown_under_load-test)
ADD_YB_TEST(full_stack-insert-scan-test)
ADD_YB_TEST(redis_table-test)
ADD_YB_TEST(update_scan_delta_compact-test)
ADD_YB_TEST(log_version-test)
ADD_YB_TEST(load_balancer-test)
ADD_YB_TEST(load_balancer_mini_cluster-test)
ADD_YB_TEST(load_balancer_multi_table-test)
ADD_YB_TEST(load_balancer_colocated_tables-test)
ADD_YB_TEST(load_balancer_respect_affinity-test)
ADD_YB_TEST(load_balancer_placement_policy-test)
ADD_YB_TEST(sys_catalog_respect_affinity-test)
ADD_YB_TEST(restart-test)
ADD_YB_TEST(yb-ts-cli-itest)
ADD_YB_TEST_DEPENDENCIES(yb-ts-cli-itest
  yb-ts-cli)
ADD_YB_TEST(cql-backup-test)
ADD_YB_TEST(snapshot-test)
ADD_YB_TEST(cdc_service-int-test)
ADD_YB_TEST(cdc_service-txn-test)
ADD_YB_TEST(cdcsdk_stream-test)
ADD_YB_TEST(cdcsdk_ysql-test)
ADD_YB_TEST(cdcsdk_before_image-test)
ADD_YB_TEST(cdcsdk_consistent_stream-test)
ADD_YB_TEST(cdcsdk_snapshot-test)
ADD_YB_TEST(cdcsdk_tablet_split-test)
ADD_YB_TEST(transaction-test)
ADD_YB_TEST(encryption-test)
ADD_YB_TEST(secure_connection_test)
ADD_YB_TEST(xcluster/xcluster_consistency-test)
ADD_YB_TEST(xcluster/xcluster_dr-itest)
ADD_YB_TEST(xcluster/xcluster_safe_time-itest)
ADD_YB_TEST(xcluster/xcluster_topologies-test)
ADD_YB_TEST(xcluster/xcluster_ysql_index-test)
ADD_YB_TEST(xcluster/xcluster_ysql-test)
ADD_YB_TEST(xcluster/xcluster-tablet-split-itest)
ADD_YB_TEST(xcluster/xcluster-test)
ADD_YB_TEST(retryable_request-test)

set(YB_TEST_LINK_LIBS_SAVED ${YB_TEST_LINK_LIBS})
set(YB_TEST_LINK_LIBS ${YB_TEST_LINK_LIBS} cassandra)
set(CASS_DRIVER_VERSION "2.9.0-yb-4")
set(CASS_DRIVER_INTERNAL_INCLUDE
    "${YB_THIRDPARTY_DIR}/src/cassandra-cpp-driver-${CASS_DRIVER_VERSION}/src")

ADD_YB_TEST(cassandra_cpp_driver-test)
YB_TEST_TARGET_INCLUDE_DIRECTORIES(cassandra_cpp_driver-test PUBLIC
  ${CASS_DRIVER_INTERNAL_INCLUDE}
  "${CASS_DRIVER_INTERNAL_INCLUDE}/third_party/sparsehash/src")

if("${COMPILER_FAMILY}" STREQUAL "gcc8")
  YB_TEST_TARGET_COMPILE_OPTIONS(cassandra_cpp_driver-test PUBLIC "-Wno-class-memaccess")
endif()

function(ADD_YB_CQL_TEST TEST_NAME)
  ADD_YB_TEST(${TEST_NAME})
  YB_TEST_TARGET_INCLUDE_DIRECTORIES(${TEST_NAME} PUBLIC ${CASS_DRIVER_INTERNAL_INCLUDE})
  YB_TEST_TARGET_LINK_LIBRARIES(${TEST_NAME} yb-cql)
endfunction()

ADD_YB_CQL_TEST(cql-index-test)
ADD_YB_CQL_TEST(cql-packed-row-test)
ADD_YB_CQL_TEST(cql-tablet-split-test)
ADD_YB_CQL_TEST(cql-test)
ADD_YB_CQL_TEST(cql_geo_transactions-test)
ADD_YB_CQL_TEST(external_mini_cluster_secure_test)

set(YB_TEST_LINK_LIBS ${YB_TEST_LINK_LIBS_SAVED})
