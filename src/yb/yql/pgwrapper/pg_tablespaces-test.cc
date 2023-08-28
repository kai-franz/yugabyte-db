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

YB_DEFINE_ENUM(ExpectedLocality, (kLocal)(kGlobal)(kNoCheck));
YB_STRONGLY_TYPED_BOOL(SetGlobalTransactionsGFlag);
YB_STRONGLY_TYPED_BOOL(SetGlobalTransactionSessionVar);
YB_STRONGLY_TYPED_BOOL(WaitForHashChange);
YB_STRONGLY_TYPED_BOOL(InsertToLocalFirst);
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

class PgTablespacesTest : public GeoTransactionsTestBase {
 protected:
  // Sets up a table by doing CREATE TABLE followed by ALTER TABLE SET TABLESPACE.
  void SetupTablesWithAlter(size_t tables_per_region) {
    // Create tablespaces and tables.
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_force_global_transactions) = true;
    tables_per_region_ = tables_per_region;

    auto conn = ASSERT_RESULT(Connect());
    bool wait_for_version = ANNOTATE_UNPROTECTED_READ(FLAGS_auto_create_local_transaction_tables);
    auto current_version = GetCurrentVersion();
    for (size_t i = 1; i <= NumRegions(); ++i) {
      ASSERT_OK(conn.ExecuteFormat(R"#(
          CREATE TABLESPACE tablespace$0 WITH (replica_placement='{
            "num_replicas": 1,
            "placement_blocks":[{
              "cloud": "cloud0",
              "region": "rack$0",
              "zone": "zone",
              "min_num_replicas": 1
            }]
          }')
      )#", i));

      for (size_t j = 1; j <= tables_per_region; ++j) {
        ASSERT_OK(conn.ExecuteFormat(
            "CREATE TABLE $0$1_$2(value int)", kTablePrefix, i, j));
        ASSERT_OK(conn.ExecuteFormat(
            "ALTER TABLE $0$1_$2 SET TABLESPACE tablespace$1", kTablePrefix, i, j));
      }

      WaitForLoadBalanceCompletion();
      if (wait_for_version) {
        WaitForStatusTabletsVersion(current_version + 1);
        ++current_version;
      }
    }
  }

  // Checks that for all tablets in tablet_uuids, their leaders are all in the given region.
  void ValidateAllTabletLeaderinZone(std::vector<TabletId> tablet_uuids, int region) {
    std::string region_str = yb::Format("rack$0", region);
    auto& catalog_manager = ASSERT_RESULT(cluster_->GetLeaderMiniMaster())->catalog_manager();
    for (const auto& tablet_id : tablet_uuids) {
      auto table_info = ASSERT_RESULT(catalog_manager.GetTabletInfo(tablet_id));
      auto leader = ASSERT_RESULT(table_info->GetLeader());
      auto server_reg_pb = leader->GetRegistration();
      ASSERT_EQ(server_reg_pb.common().cloud_info().placement_region(), region_str);
    }
  }

  Result<uint32_t> GetTablespaceOidForRegion(int region) {
    auto conn = EXPECT_RESULT(Connect());
    uint32_t tablespace_oid = EXPECT_RESULT(conn.FetchValue<int32_t>(strings::Substitute(
        "SELECT oid FROM pg_catalog.pg_tablespace WHERE spcname = 'tablespace$0'", region)));
    return tablespace_oid;
  }

  Result<std::vector<TabletId>> GetStatusTablets(int region, ExpectedLocality locality) {
    YBTableName table_name;
    if (locality == ExpectedLocality::kNoCheck) {
      return std::vector<TabletId>();
    } else if (locality == ExpectedLocality::kGlobal) {
      table_name = YBTableName(
          YQL_DATABASE_CQL, master::kSystemNamespaceName, kGlobalTransactionsTableName);
    } else if (ANNOTATE_UNPROTECTED_READ(FLAGS_auto_create_local_transaction_tables)) {
      auto tablespace_oid = EXPECT_RESULT(GetTablespaceOidForRegion(region));
      table_name = YBTableName(
          YQL_DATABASE_CQL, master::kSystemNamespaceName,
          yb::Format("transactions_$0", tablespace_oid));
    } else {
      table_name = YBTableName(
          YQL_DATABASE_CQL, master::kSystemNamespaceName,
          yb::Format("transactions_region$0", region));
    }
    std::vector<TabletId> tablet_uuids;
    RETURN_NOT_OK(client_->GetTablets(
        table_name, 1000 /* max_tablets */, &tablet_uuids, nullptr /* ranges */));
    return tablet_uuids;
  }

  void CheckSuccess(int to_region, SetGlobalTransactionsGFlag set_global_transactions_gflag,
                   SetGlobalTransactionSessionVar session_var, InsertToLocalFirst local_first,
                   ExpectedLocality expected) {
    auto expected_status_tablets = ASSERT_RESULT(GetStatusTablets(to_region, expected));
    if (expected != ExpectedLocality::kNoCheck) {
      ASSERT_FALSE(expected_status_tablets.empty());
    }
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_force_global_transactions) =
        (set_global_transactions_gflag == SetGlobalTransactionsGFlag::kTrue);

    auto conn = ASSERT_RESULT(Connect());
    ASSERT_OK(conn.ExecuteFormat("SET force_global_transaction = $0", ToString(session_var)));
    ASSERT_OK(conn.StartTransaction(IsolationLevel::SERIALIZABLE_ISOLATION));
    if (local_first) {
      ASSERT_OK(conn.ExecuteFormat(
          "INSERT INTO $0$1_1(value) VALUES (0)", kTablePrefix, kLocalRegion));
    }
    ASSERT_OK(conn.ExecuteFormat("INSERT INTO $0$1_1(value) VALUES (0)", kTablePrefix, to_region));
    ASSERT_OK(conn.CommitTransaction());

    if (expected != ExpectedLocality::kNoCheck) {
      auto last_transaction = transaction_pool_->TEST_GetLastTransaction();
      auto metadata = last_transaction->GetMetadata(TransactionRpcDeadline()).get();
      ASSERT_OK(metadata);
      ASSERT_TRUE(std::find(expected_status_tablets.begin(),
                            expected_status_tablets.end(),
                            metadata->status_tablet) != expected_status_tablets.end());
    }

    ASSERT_OK(conn.StartTransaction(IsolationLevel::SERIALIZABLE_ISOLATION));
    if (local_first) {
      ASSERT_OK(conn.FetchFormat("SELECT * FROM $0$1_1", kTablePrefix, kLocalRegion));
    }
    ASSERT_OK(conn.FetchFormat("SELECT * FROM $0$1_1", kTablePrefix, to_region));
    ASSERT_OK(conn.CommitTransaction());

    if (expected != ExpectedLocality::kNoCheck) {
      auto last_transaction = transaction_pool_->TEST_GetLastTransaction();
      auto metadata = last_transaction->GetMetadata(TransactionRpcDeadline()).get();
      ASSERT_OK(metadata);
      ASSERT_TRUE(std::find(expected_status_tablets.begin(),
                            expected_status_tablets.end(),
                            metadata->status_tablet) != expected_status_tablets.end());
    }

    ASSERT_OK(conn.StartTransaction(IsolationLevel::READ_COMMITTED));
    if (local_first) {
      ASSERT_OK(conn.FetchFormat("SELECT * FROM $0$1_1", kTablePrefix, kLocalRegion));
    }
    ASSERT_OK(conn.FetchFormat("SELECT * FROM $0$1_1", kTablePrefix, to_region));
    ASSERT_OK(conn.CommitTransaction());
  }

  void CheckAbort(int to_region, SetGlobalTransactionsGFlag set_global_transactions_gflag,
                  SetGlobalTransactionSessionVar session_var, InsertToLocalFirst local_first,
                  size_t num_aborts) {
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_force_global_transactions) = set_global_transactions_gflag;

    auto conn = ASSERT_RESULT(Connect());
    ASSERT_OK(conn.ExecuteFormat("SET force_global_transaction = $0", ToString(session_var)));
    for (size_t i = 0; i < num_aborts; ++i) {
      ASSERT_OK(conn.StartTransaction(IsolationLevel::SERIALIZABLE_ISOLATION));
      if (local_first) {
        ASSERT_OK(conn.ExecuteFormat(
            "INSERT INTO $0$1_1(value) VALUES (0)", kTablePrefix, kLocalRegion));
      }
      ASSERT_NOK(conn.ExecuteFormat(
          "INSERT INTO $0$1_1(value) VALUES (0)", kTablePrefix, to_region));
      ASSERT_OK(conn.RollbackTransaction());
    }

    for (size_t i = 0; i < num_aborts; ++i) {
      ASSERT_OK(conn.StartTransaction(IsolationLevel::SERIALIZABLE_ISOLATION));
      if (local_first) {
        ASSERT_OK(conn.FetchFormat("SELECT * FROM $0$1_1", kTablePrefix, kLocalRegion));
      }
      ASSERT_NOK(conn.FetchFormat("SELECT * FROM $0$1_1", kTablePrefix, to_region));
      ASSERT_OK(conn.RollbackTransaction());
    }

    for (size_t i = 0; i < num_aborts; ++i) {
      ASSERT_OK(conn.StartTransaction(IsolationLevel::READ_COMMITTED));
      if (local_first) {
        ASSERT_OK(conn.FetchFormat("SELECT * FROM $0$1_1", kTablePrefix, kLocalRegion));
      }
      ASSERT_OK(conn.FetchFormat("SELECT * FROM $0$1_1", kTablePrefix, to_region));
      ASSERT_OK(conn.CommitTransaction());
    }
  }

  // Get the leader replica count and total replica count of a group of tablets belongs to a table
  // on a tserver.
  Result<std::pair<size_t, size_t>> GetTServerReplicaCount(
      tserver::MiniTabletServer* tserver,
      const google::protobuf::RepeatedPtrField<master::TabletLocationsPB>& tablets) {
    size_t leader_count = 0;
    size_t total_count = 0;

    for (const auto& tablet : tablets) {
      for (const auto& replica : tablet.replicas()) {
        if (replica.ts_info().permanent_uuid() == tserver->server()->permanent_uuid()) {
          if (replica.role() == PeerRole::LEADER) {
            leader_count++;
          }
          total_count++;
        }
      }
    }
    return std::make_pair(leader_count, total_count);
  }

  // Verify that the replicas of each table are evenly distributed across zones.
  Result<bool> VerifyTableReplicaDistributionInZone(
      const google::protobuf::RepeatedPtrField<master::TabletLocationsPB>& tablets,
      const std::vector<size_t>& current_zone_tserver_indexes,
      bool is_table_in_current_zone) {
    const auto expected_leaders_per_server = tablets.size() / current_zone_tserver_indexes.size();
    for (auto tserver_idx : current_zone_tserver_indexes) {
      const auto& [leader_count, total_count] =
          VERIFY_RESULT(GetTServerReplicaCount(cluster_->mini_tablet_server(tserver_idx), tablets));

      if (is_table_in_current_zone) {
        // If table is pinned to the same zone as this tserver, check that replicas are evenly
        // distributed.
        if (leader_count != expected_leaders_per_server ||
            static_cast<int>(total_count) != tablets.size()) {
          return false;
        }
      } else if (total_count != 0) {
        // If table is pinned to a different zone and has replicas on this tserver, then load
        // balancer is not respecting tablespaces.
        return false;
      }
    }
    return true;
  }

  // Verify the replicas of each table should be evenly distributed across each zone.
  Result<bool> VerifyReplicaDistribution(
      const std::vector<YBTableName> tables,
      const std::vector<std::pair<std::string, std::vector<size_t>>>& servers_group_by_zone) {
    for (const auto& table : tables) {
      google::protobuf::RepeatedPtrField<master::TabletLocationsPB> tablets;
      RETURN_NOT_OK(
          client_->GetTabletsFromTableId(table.table_id(), /* max_tablets = */ 0, &tablets));
      for (const auto& [current_zone_table_name, current_zone_tserver_indexes] :
           servers_group_by_zone) {
        bool is_table_in_current_zone = table.table_name() == current_zone_table_name;
        if (!VERIFY_RESULT(VerifyTableReplicaDistributionInZone(
                tablets, current_zone_tserver_indexes, is_table_in_current_zone))) {
          return false;
        }
      }
    }
    return true;
  }
};

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
