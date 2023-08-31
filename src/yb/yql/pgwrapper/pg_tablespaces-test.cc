// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.

#include "yb/client/transaction.h"
#include "yb/client/transaction_manager.h"
#include "yb/client/transaction_pool.h"
#include "yb/client/yb_table_name.h"
#include "yb/tserver/tablet_server.h"
#include "yb/master/catalog_manager.h"
#include "yb/master/mini_master.h"
#include "yb/master/master_client.pb.h"
#include "yb/yql/pgwrapper/geo_transactions_test_base.h"
#include "yb/util/backoff_waiter.h"
#include "yb/util/tsan_util.h"

using std::string;

DECLARE_int32(master_ts_rpc_timeout_ms);
DECLARE_bool(auto_create_local_transaction_tables);
DECLARE_bool(auto_promote_nonlocal_transactions_to_global);
DECLARE_bool(force_global_transactions);
DECLARE_bool(transaction_tables_use_preferred_zones);

using namespace std::literals;

namespace yb {

namespace client {

namespace {

// 90 leaders per zone and a total of 3 zones so 270 leader distributions. Worst-case even if the LB
// is doing 1 leader move per run (it does more than that in practice) then at max it will take 270
// runs i.e. 270 secs (1 run = 1 sec)
const auto kWaitLeaderDistributionTimeout = MonoDelta::FromMilliseconds(270000);

} // namespace

class PlacementBlock {
 public:
    std::string cloud;
    std::string region;
    std::string zone;
    size_t minNumReplicas;
    std::optional<size_t> leaderPreference;

    std::string json;

    PlacementBlock(std::string cloud, std::string region, std::string zone, size_t minNumReplicas,
                   std::optional<size_t> leaderPreference = std::nullopt)
        : cloud(std::move(cloud)), region(std::move(region)), zone(std::move(zone)),
          minNumReplicas(minNumReplicas), leaderPreference(leaderPreference) {}

    // Creates a placement block with the given region ID. For the purposes of this test, the cloud
    // is always "cloud0" and the zone is always "zone". The region is "rack<regionId>".
    PlacementBlock(size_t regionId, size_t minNumReplicas,
                   std::optional<size_t> leaderPreference = std::nullopt)
        : minNumReplicas(minNumReplicas), leaderPreference(leaderPreference) {
        cloud = "cloud0";

        std::ostringstream oss;
        oss << "rack" << regionId;
        region = oss.str();

        zone = "zone";

        json = generateJson();
    }

  const std::string& toJson() const {
    return json;
  }

 private:
    /*
    * Returns the JSON representation of the placement block. For example:
    * {
    *   "cloud": "aws",
    *   "region": "us-east-1",
    *   "zone": "us-east-1a",
    *   "min_num_replicas": 1,
    *   "leader_preference": 1
    * }
    */
    std::string generateJson() const {
        std::ostringstream os;
        os << "{\"cloud\":\"" << cloud << "\",\"region\":\"" << region << "\",\"zone\":\"" << zone
           << "\",\"min_num_replicas\":" << minNumReplicas;

        if (leaderPreference) {
            os << ",\"leader_preference\":" << *leaderPreference;
        }

        os << "}";
        return os.str();
    }
};

class Tablespace {
 public:
    std::string name;
    size_t numReplicas;
    std::vector<PlacementBlock> placementBlocks;

    std::string json;
    std::string createCmd;

    Tablespace(std::string name, size_t numReplicas, std::vector<PlacementBlock> placementBlocks)
    : name(name),
      numReplicas(numReplicas),
      placementBlocks(placementBlocks) {
        json = generateJson();
        createCmd = generateCreateCmd();
    }

    const std::string &toJson() const {
        return json;
    }

    const std::string &getCreateCmd() const {
        return createCmd;
    }

 private:
    /*
    * Generates the JSON representation of the tablespace. For example:
    * {
    *   "num_replicas": 1,
    *   "placement_blocks": [
    *     {
    *       "cloud": "aws",
    *       "region": "us-east-1",
    *       "zone": "us-east-1a",
    *       "min_num_replicas": 1,
    *       "leader_preference": 1
    *     }
    *   ]
    * }
    */
    std::string generateJson() const {
        std::ostringstream os;
        os << "{\"num_replicas\":" << numReplicas << ",\"placement_blocks\":[";

        for (size_t i = 0; i < placementBlocks.size(); ++i) {
            os << placementBlocks[i].toJson();
            if (i < placementBlocks.size() - 1) {
                os << ",";
            }
        }

        os << "]}";
        return os.str();
    }

    // Generates the SQL command to create this tablespace.
    std::string generateCreateCmd() const {
        std::ostringstream os;
        os << "CREATE TABLESPACE " << name << " WITH (replica_placement='" << toJson() << "');";
        return os.str();
    }
};

class PgTablespacesTest : public GeoTransactionsTestBase {};

TEST_F(PgTablespacesTest, YB_DISABLE_TEST_IN_TSAN(TestIndexPreferredZone)) {
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_force_global_transactions) = false;
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_auto_create_local_transaction_tables) = true;
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_transaction_tables_use_preferred_zones) = true;

  // Create tablespaces and tables.
  auto conn = ASSERT_RESULT(Connect());
  auto current_version = GetCurrentVersion();
  string table_name = kTablePrefix;
  string index_name = table_name + "_idx";

  // Create two tablespaces.
  std::vector<PlacementBlock> placement_blocks_1;
  std::vector<PlacementBlock> placement_blocks_2;

  // In tablespace 1, region 1 is most preferred.
  // In tablespace 2, region 3 is most preferred, with region 1 being the next most preferred.
  for (size_t i = 1; i <= NumRegions(); ++i) {
    placement_blocks_1.emplace_back(i, 1, i);
    placement_blocks_2.emplace_back(i, 1, i == NumRegions() ? 1 : (i + 1));
  }

  size_t num_replicas = NumTabletServers();
  Tablespace tablespace_1("tablespace1", num_replicas, placement_blocks_1);
  Tablespace tablespace_2("tablespace2", num_replicas, placement_blocks_2);

  ASSERT_OK(conn.Execute(tablespace_1.getCreateCmd()));
  ASSERT_OK(conn.Execute(tablespace_2.getCreateCmd()));

  // Create a table and an index on that table. Assign the index to tablespace1.
  ASSERT_OK(conn.ExecuteFormat("CREATE TABLE $0(value int)", table_name));
  ASSERT_OK(conn.ExecuteFormat("CREATE INDEX $0 ON $1(value) TABLESPACE tablespace1",
    index_name, table_name));

  auto index_id = ASSERT_RESULT(GetTableIDFromTableName(index_name));
  auto tablet_uuid_set = ListTabletIdsForTable(cluster_.get(), index_id);
  auto index_uuids = std::vector<TabletId>(tablet_uuid_set.begin(), tablet_uuid_set.end());

  WaitForStatusTabletsVersion(++current_version);
  WaitForLoadBalanceCompletion();

  // Verify that all the tablet leaders are in region 1.
  auto status_tablet_ids = ASSERT_RESULT(GetStatusTablets(1, ExpectedLocality::kLocal));
  ValidateAllTabletLeaderinZone(index_uuids, 1);
  ValidateAllTabletLeaderinZone(status_tablet_ids, 1);

  // Move the index to tablespace2.
  ASSERT_OK(conn.ExecuteFormat("ALTER INDEX $0 SET TABLESPACE tablespace2", index_name));

  WaitForStatusTabletsVersion(++current_version);
  WaitForLoadBalanceCompletion();

  // Now the tablet leaders should be in region 3, which is the most preferred block
  // for tablespace 2.
  status_tablet_ids = ASSERT_RESULT(GetStatusTablets(2, ExpectedLocality::kLocal));
  ValidateAllTabletLeaderinZone(index_uuids, 3);
  ValidateAllTabletLeaderinZone(status_tablet_ids, 3);

  // Shut down region 3.
  ASSERT_OK(ShutdownTabletServersByRegion(3));
  WaitForLoadBalanceCompletion();

  // Now the tablet leaders should be in region 1, which is the next most preferred block
  // for tablespace 2.
  ValidateAllTabletLeaderinZone(index_uuids, 1);
  ValidateAllTabletLeaderinZone(status_tablet_ids, 1);
}

TEST_F(PgTablespacesTest, YB_DISABLE_TEST_IN_TSAN(TestMatViewPreferredZone)) {
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_force_global_transactions) = false;
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_auto_create_local_transaction_tables) = true;
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_transaction_tables_use_preferred_zones) = true;

  // Create tablespaces and tables.
  auto conn = ASSERT_RESULT(Connect());
  auto current_version = GetCurrentVersion();
  string table_name = kTablePrefix;
  string mv_name = table_name + "_mv";

  // Create two tablespaces.
  std::vector<PlacementBlock> placement_blocks_1;
  std::vector<PlacementBlock> placement_blocks_2;

  // Tablespace 1 has the highest leader preference for region 1.
  // Tablespace 2 has the highest leader preference for region 2, with
  // region 1 being the next highest.
  for (size_t i = 1; i <= NumRegions(); ++i) {
    placement_blocks_1.emplace_back(i, 1, i);
    placement_blocks_2.emplace_back(i, 1, i == NumRegions() ? 1 : (i + 1));
  }

  size_t num_replicas = NumTabletServers();
  Tablespace tablespace_1("tablespace1", num_replicas, placement_blocks_1);
  Tablespace tablespace_2("tablespace2", num_replicas, placement_blocks_2);

  ASSERT_OK(conn.Execute(tablespace_1.getCreateCmd()));
  ASSERT_OK(conn.Execute(tablespace_2.getCreateCmd()));

  // Create a table and an index on that table. Assign the index to tablespace1.
  ASSERT_OK(conn.ExecuteFormat("CREATE TABLE $0(value int)", table_name));
  ASSERT_OK(conn.ExecuteFormat("CREATE MATERIALIZED VIEW $0 TABLESPACE tablespace1 AS "
                               "SELECT * FROM $1", mv_name, table_name));

  auto mv_id = ASSERT_RESULT(GetTableIDFromTableName(mv_name));
  auto tablet_uuid_set = ListTabletIdsForTable(cluster_.get(), mv_id);
  auto mv_uuids = std::vector<TabletId>(tablet_uuid_set.begin(), tablet_uuid_set.end());

  WaitForStatusTabletsVersion(++current_version);
  WaitForLoadBalanceCompletion();

  // Verify that all the tablet leaders are in region 1.
  auto status_tablet_ids = ASSERT_RESULT(GetStatusTablets(1, ExpectedLocality::kLocal));
  ValidateAllTabletLeaderinZone(mv_uuids, 1);
  ValidateAllTabletLeaderinZone(status_tablet_ids, 1);

  // Move the index to tablespace2.
  ASSERT_OK(conn.ExecuteFormat("ALTER MATERIALIZED VIEW $0 SET TABLESPACE tablespace2", mv_name));

  WaitForStatusTabletsVersion(++current_version);
  WaitForLoadBalanceCompletion();

  // Now the tablet leaders should be in region 3, which is the most preferred block
  // for tablespace 2.
  status_tablet_ids = ASSERT_RESULT(GetStatusTablets(2, ExpectedLocality::kLocal));
  ValidateAllTabletLeaderinZone(mv_uuids, 3);
  ValidateAllTabletLeaderinZone(status_tablet_ids, 3);

  // Shut down region 3.
  ASSERT_OK(ShutdownTabletServersByRegion(3));
  WaitForLoadBalanceCompletion();

  // Now the tablet leaders should be in region 1, which is the next most preferred block
  // for tablespace 2.
  ValidateAllTabletLeaderinZone(mv_uuids, 1);
  ValidateAllTabletLeaderinZone(status_tablet_ids, 1);
}

} // namespace client
} // namespace yb
