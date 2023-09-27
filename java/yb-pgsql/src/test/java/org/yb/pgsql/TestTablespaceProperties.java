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
//

package org.yb.pgsql;

import static org.yb.AssertionWrappers.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yugabyte.util.PSQLException;

import org.yb.CommonNet.CloudInfoPB;
import org.yb.client.LeaderStepDownResponse;
import org.yb.client.LocatedTablet;
import org.yb.client.ModifyClusterConfigLiveReplicas;
import org.yb.client.ModifyClusterConfigReadReplicas;
import org.yb.client.YBClient;
import org.yb.client.YBTable;
import org.yb.master.CatalogEntityInfo;
import org.yb.master.MasterDdlOuterClass;
import org.yb.minicluster.MiniYBCluster;
import org.yb.minicluster.MiniYBClusterBuilder;
import org.yb.YBTestRunner;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.google.protobuf.ByteString;

@RunWith(value = YBTestRunner.class)
public class TestTablespaceProperties extends BasePgSQLTest {
  private static final Logger LOG = LoggerFactory.getLogger(TestTablespaceProperties.class);

  ArrayList<String> tablesWithDefaultPlacement = new ArrayList<String>();
  ArrayList<String> tablesWithCustomPlacement = new ArrayList<String>();

  private static final int MASTER_REFRESH_TABLESPACE_INFO_SECS = 2;

  private static final int MASTER_LOAD_BALANCER_WAIT_TIME_MS = 60 * 1000;

  private static final int LOAD_BALANCER_MAX_CONCURRENT = 10;

  private static final String tablespaceName = "testTablespace";

  private static final List<Map<String, String>> perTserverZonePlacementFlags = Arrays.asList(
      ImmutableMap.of(
          "placement_cloud", "cloud1",
          "placement_region", "region1",
          "placement_zone", "zone1"),
      ImmutableMap.of(
          "placement_cloud", "cloud2",
          "placement_region", "region2",
          "placement_zone", "zone2"),
      ImmutableMap.of(
          "placement_cloud", "cloud3",
          "placement_region", "region3",
          "placement_zone", "zone3"));

  /**
   * Verify that the leader replica of the table is placed in the given placement block.
   */
  void verifyLeaderPlacement(final String table, PlacementBlock placementBlock) throws Exception {
    final YBClient client = miniCluster.getClient();
    client.waitForReplicaCount(getTableFromName(table), 2, 30_000);

    List<LocatedTablet> tabletLocations = getTableFromName(table).getTabletsLocations(30_000);

    for (LocatedTablet tablet : tabletLocations) {
      LocatedTablet.Replica leaderReplica = tablet.getLeaderReplica();
      assertTrue(leaderReplica != null);

      CloudInfoPB cloudInfo = leaderReplica.getCloudInfo();
      assertTrue(isReplicaInPlacement(placementBlock, cloudInfo));
    }
  }

  /**
   * Verify that the tablets for a table all satisfy the minimum replica constraints
   * for the given tablespace placement policy.
   */
  void verifyCustomPlacement(final String table, Tablespace tablespace) throws Exception {
    final YBClient client = miniCluster.getClient();
    client.waitForReplicaCount(getTableFromName(table), 2, 30_000);

    List<LocatedTablet> tabletLocations = getTableFromName(table).getTabletsLocations(30_000);

    for (LocatedTablet tablet : tabletLocations) {
      List<LocatedTablet.Replica> replicas = tablet.getReplicas();
      assertTrue(replicas.size() >= tablespace.numReplicas);

      for (PlacementBlock placementBlock : tablespace.placementBlocks) {
        int numReplicasInPlacement = countReplicasInPlacement(replicas, placementBlock);
        assertTrue(numReplicasInPlacement >= placementBlock.minNumReplicas);
      }
    }
  }

  @Override
  public int getTestMethodTimeoutSec() {
    return getPerfMaxRuntime(1500, 1700, 2000, 2000, 2000);
  }

  @Override
  protected void customizeMiniClusterBuilder(MiniYBClusterBuilder builder) {
    super.customizeMiniClusterBuilder(builder);
    builder.enablePgTransactions(true);
    builder.addMasterFlag("vmodule", "sys_catalog=5,cluster_balance=1");
    builder.addMasterFlag("ysql_tablespace_info_refresh_secs",
                          Integer.toString(MASTER_REFRESH_TABLESPACE_INFO_SECS));
    builder.addMasterFlag("auto_create_local_transaction_tables", "true");
    builder.addMasterFlag("TEST_name_transaction_tables_with_tablespace_id", "true");

    // We wait for the load balancer whenever it gets triggered anyways, so there's
    // no concerns about the load balancer taking too many resources.
    builder.addMasterFlag("load_balancer_max_concurrent_tablet_remote_bootstraps",
                          Integer.toString(LOAD_BALANCER_MAX_CONCURRENT));
    builder.addMasterFlag("load_balancer_max_concurrent_tablet_remote_bootstraps_per_table",
                          Integer.toString(LOAD_BALANCER_MAX_CONCURRENT));
    builder.addMasterFlag("load_balancer_max_concurrent_adds",
                          Integer.toString(LOAD_BALANCER_MAX_CONCURRENT));
    builder.addMasterFlag("load_balancer_max_concurrent_removals",
                          Integer.toString(LOAD_BALANCER_MAX_CONCURRENT));
    builder.addMasterFlag("load_balancer_max_concurrent_moves",
                          Integer.toString(LOAD_BALANCER_MAX_CONCURRENT));
    builder.addMasterFlag("load_balancer_max_concurrent_moves_per_table",
                          Integer.toString(LOAD_BALANCER_MAX_CONCURRENT));

    builder.perTServerFlags(perTserverZonePlacementFlags);
  }

  @Before
  public void setupTablespaces() throws Exception {
    try (Statement setupStatement = connection.createStatement()) {
      setupStatement.execute("DROP TABLESPACE IF EXISTS " + tablespaceName);
      setupStatement.execute(
          " CREATE TABLESPACE " + tablespaceName +
          "  WITH (replica_placement=" +
          "'{\"num_replicas\":2, \"placement_blocks\":" +
          "[{\"cloud\":\"cloud1\",\"region\":\"region1\",\"zone\":\"zone1\"," +
          "\"min_num_replicas\":1}," +
          "{\"cloud\":\"cloud2\",\"region\":\"region2\",\"zone\":\"zone2\"," +
          "\"min_num_replicas\":1}]}')");
    }
  }

  private void createTestData (String prefixName) throws Exception {
    // Setup tables.
    String defaultTable = prefixName + "_default_table";
    String customTable = prefixName + "_custom_table";
    String defaultIndex = prefixName + "_default_index";
    String customIndex = prefixName + "_custom_index";
    String defaultTablegroup = prefixName + "_default_tablegroup";
    String customTablegroup = prefixName + "_custom_tablegroup";
    String tableInDefaultTablegroup = prefixName + "_table_in_default_tablegroup";
    String tableInCustomTablegroup = prefixName + "_table_in_custom_tablegroup";
    String defaultIndexCustomTable = prefixName + "_default_idx_on_custom_table";
    String customIndexCustomTable = prefixName + "_custom_idx_on_custom_table";
    try (Statement setupStatement = connection.createStatement()) {
      // Create tablegroups in default and custom tablegroups
      setupStatement.execute("CREATE TABLEGROUP " +  customTablegroup +
          " TABLESPACE testTablespace");
      setupStatement.execute("CREATE TABLEGROUP " +  defaultTablegroup);

      // Create tables in default and custom tablespaces.
      setupStatement.execute(
          "CREATE TABLE " +  customTable + "(a SERIAL) TABLESPACE testTablespace");

      setupStatement.execute(
          "CREATE TABLE " + defaultTable + "(a int CONSTRAINT " + customIndex +
          " UNIQUE USING INDEX TABLESPACE testTablespace)");

      // Create indexes in default and custom tablespaces.
      setupStatement.execute("CREATE INDEX " + defaultIndexCustomTable + " on " +
          customTable + "(a)");

      setupStatement.execute("CREATE INDEX " + customIndexCustomTable + " on " +
          customTable + "(a) TABLESPACE testTablespace");

      setupStatement.execute("CREATE INDEX " + defaultIndex + " on " +
          defaultTable + "(a)");

      // Create tables in tablegroups (in default and custom tablespaces)
      setupStatement.execute(
        "CREATE TABLE " +  tableInDefaultTablegroup + "(a SERIAL) TABLEGROUP " + defaultTablegroup);

      setupStatement.execute(
        "CREATE TABLE " +  tableInCustomTablegroup + "(a SERIAL) TABLEGROUP " + customTablegroup);
    }
    String transactionTableName = getTablespaceTransactionTableName();
    tablesWithDefaultPlacement.clear();
    tablesWithDefaultPlacement.addAll(Arrays.asList(defaultTable, defaultIndex,
          defaultIndexCustomTable, tableInDefaultTablegroup));

    tablesWithCustomPlacement.clear();
    tablesWithCustomPlacement.addAll(Arrays.asList(customTable, customIndex,
          customIndexCustomTable, tableInCustomTablegroup, transactionTableName));
  }

  private void addTserversAndWaitForLB() throws Exception {
    int expectedTServers = miniCluster.getTabletServers().size() + 1;
    miniCluster.startTServer(perTserverZonePlacementFlags.get(1));
    miniCluster.waitForTabletServers(expectedTServers);

    // Wait for loadbalancer to run.
    assertTrue(miniCluster.getClient().waitForLoadBalancerActive(
      MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    // Wait for load balancer to become idle.
    assertTrue(miniCluster.getClient().waitForLoadBalance(Long.MAX_VALUE, expectedTServers));
  }

  /**
   * Negative test: Create an index for a table (created inside a tablegroup) and specify its
   * tablespace. This would throw an error as we do not support tablespaces for indexes on
   * Colocated tables.
   */
  @Test
  public void disallowTableSpaceForIndexOnColocatedTables() throws Exception {
    String customTablegroup =  "test_custom_tablegroup";
    try (Statement setupStatement = connection.createStatement()) {
      setupStatement.execute("CREATE TABLEGROUP " +  customTablegroup +
          " TABLESPACE testTablespace");
      setupStatement.execute("CREATE TABLE t (a INT, b FLOAT) TABLEGROUP " + customTablegroup);

      // Create index without specifying tablespace.
      setupStatement.execute("CREATE INDEX t_idx1 ON t(a)");

      // Create an index and also specify its tablespace.
      String errorMsg = "TABLESPACE is not supported for indexes on colocated tables.";
      executeAndAssertErrorThrown("CREATE INDEX t_idx2 ON t(b) TABLESPACE testTablespace",
                                  errorMsg);

      // ALTER INDEX SET TABLESPACE.
      errorMsg = "ALTER INDEX SET TABLESPACE is not supported for indexes on colocated tables.";
      executeAndAssertErrorThrown("ALTER INDEX t_idx2 SET TABLESPACE testTablespace", errorMsg);
    }
  }

  @Test
  public void testTablesOptOutOfColocation() throws Exception {
    final String dbname = "testdatabase";
    try (Statement stmt = connection.createStatement()) {
      stmt.execute(String.format("CREATE DATABASE %s COLOCATED=TRUE", dbname));
    }
    final String colocatedTableName = "colocated_table";
    final String nonColocatedTable = "colocation_opt_out_table";
    final String nonColocatedIndex = "colocation_opt_out_idx";
    try (Connection connection2 = getConnectionBuilder().withDatabase(dbname).connect();
         Statement stmt = connection2.createStatement()) {
      stmt.execute(String.format("CREATE TABLE %s (h INT PRIMARY KEY, a INT, b FLOAT) " +
                                 "WITH (colocated = false) TABLESPACE testTablespace",
                                 nonColocatedTable));
      stmt.execute(String.format("CREATE INDEX %s ON %s (a) TABLESPACE testTablespace",
        nonColocatedIndex, nonColocatedTable));
      stmt.execute(String.format("CREATE TABLE %s (h INT PRIMARY KEY, a INT, b FLOAT)",
                                 colocatedTableName));
    }

    verifyDefaultPlacement(colocatedTableName);
    verifyCustomPlacement(nonColocatedTable);

    // Wait for tablespace info to be refreshed in load balancer.
    Thread.sleep(5 * MASTER_REFRESH_TABLESPACE_INFO_SECS);

    // Verify that load balancer is indeed idle.
    assertTrue(miniCluster.getClient().waitForLoadBalancerIdle(
      MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    // Test that ALTER ... SET TABLESPACE works on the non-colocated table/index.
    String ts1Name = "testTablespaceOptOutColocation";
    PlacementBlock block1 = new PlacementBlock("cloud1", "region1", "zone1", 1);
    Tablespace ts1 = new Tablespace(ts1Name, 1, Collections.singletonList(block1));
    ts1.create();

    try (Connection connection2 = getConnectionBuilder().withDatabase(dbname).connect();
         Statement stmt = connection2.createStatement()) {
      stmt.execute(String.format("ALTER TABLE %s SET TABLESPACE %s",
        nonColocatedTable, ts1Name));
      stmt.execute(String.format("ALTER INDEX %s SET TABLESPACE %s",
        nonColocatedIndex, ts1Name));
    }

    // Wait for load balancer to run.
    Thread.sleep(5 * MASTER_REFRESH_TABLESPACE_INFO_SECS);
    assertTrue(miniCluster.getClient().waitForLoadBalancerIdle(
      MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    verifyCustomPlacement(nonColocatedTable, ts1);
    verifyCustomPlacement(nonColocatedIndex, ts1);
  }

  @Test
  public void sanityTest() throws Exception {
    markClusterNeedsRecreation();
    YBClient client = miniCluster.getClient();

    // Set required YB-Master flags.
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "enable_ysql_tablespaces_for_placement", "true"));
    }

    createTestData("sanity_test");

    // Verify that the table was created and its tablets were placed
    // according to the tablespace replication info.
    LOG.info("Verify whether tablet replicas were placed correctly at creation time");
    verifyPlacement();

    // Wait for tablespace info to be refreshed in load balancer.
    Thread.sleep(2 * MASTER_REFRESH_TABLESPACE_INFO_SECS);

    addTserversAndWaitForLB();

    // Verify that the loadbalancer also placed the tablets of the table based on the
    // tablespace replication info.
    LOG.info("Verify whether the load balancer maintained the placement of tablet replicas" +
             " after TServers were added");
    verifyPlacement();

    // Trigger a master leader change.
    LeaderStepDownResponse resp = client.masterLeaderStepDown();
    assertFalse(resp.hasError());

    Thread.sleep(10 * MiniYBCluster.TSERVER_HEARTBEAT_INTERVAL_MS);

    LOG.info("Verify that tablets have been placed correctly even after master leader changed");
    verifyPlacement();
  }

  @Test
  public void testDisabledTablespaces() throws Exception {
    // Create some tables with custom and default placements.
    // These tables will be placed according to their tablespaces
    // by the create table path.
    createTestData("pre_disabling_tblspcs");

    // Disable tablespaces
    YBClient client = miniCluster.getClient();
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "enable_ysql_tablespaces_for_placement", "false"));
    }

    // At this point, since tablespaces are disabled, the LB will detect that the older
    // tables have not been correctly placed. Wait until the load balancer is active.
    assertTrue(miniCluster.getClient().waitForLoadBalancerActive(
      MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    // Wait for LB to finish its run.
    assertTrue(miniCluster.getClient().waitForLoadBalancerIdle(
      MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    // Now create some new tables. The table creation path will place these
    // tables according to the cluster config.
    createTestData("disabled_tablespace_test");

    // Verify that both the table creation path and load balancer have placed all the tables
    // based on cluster config.
    LOG.info("Verify placement of tablets after tablespaces have been disabled");
    verifyDefaultPlacementForAll();
  }

  @Test
  public void testLBTablespacePlacement() throws Exception {
    // This test disables using tablespaces at creation time. Thus, the tablets of the table will be
    // incorrectly placed based on cluster config at creation time, and we will rely on the LB to
    // correctly place the table based on its tablespace.
    // Set master flags.
    YBClient client = miniCluster.getClient();
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "enable_ysql_tablespaces_for_placement", "false"));
    }

    createTestData("test_lb_placement");

    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "enable_ysql_tablespaces_for_placement", "true"));
    }

    // Since the tablespace-id was not checked during creation, the tablet replicas
    // would have been placed wrongly. This condition will be detected by the load
    // balancer. Wait until it starts running to fix these wrongly placed tablets.
    assertTrue(miniCluster.getClient().waitForLoadBalancerActive(
      MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    // Wait for LB to finish its run.
    assertTrue(miniCluster.getClient().waitForLoadBalancerIdle(
      MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    // Verify that the loadbalancer placed the tablets of the table based on the
    // tablespace replication info. Skip checking transaction tables, as they would
    // not have been created when tablespaces were disabled.
    LOG.info("Verify whether tablet replicas placed incorrectly at creation time are moved to " +
             "their appropriate placement by the load balancer");
    verifyPlacement(true /* skipTransactionTables */);
  }

  @Test
  public void negativeTest() throws Exception {
    YBClient client = miniCluster.getClient();

    // Set required YB-Master flags.
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "enable_ysql_tablespaces_for_placement", "true"));
    }

    // Create tablespaces with invalid placement.
    try (Statement setupStatement = connection.createStatement()) {
      // Create a tablespace specifying a cloud that does not exist.
      setupStatement.execute(
        "CREATE TABLESPACE invalid_tblspc WITH (replica_placement=" +
          "'{\"num_replicas\":2, \"placement_blocks\":" +
          "[{\"cloud\":\"cloud3\",\"region\":\"region1\",\"zone\":\"zone1\"," +
          "\"min_num_replicas\":1}," +
          "{\"cloud\":\"cloud2\",\"region\":\"region2\",\"zone\":\"zone2\"," +
          "\"min_num_replicas\":1}]}')");

      // Create a tablespace wherein the individual min_num_replicas can be
      // satisfied, but the total replication factor cannot.
      setupStatement.execute(
        "CREATE TABLESPACE insufficient_rf_tblspc WITH (replica_placement=" +
          "'{\"num_replicas\":5, \"placement_blocks\":" +
          "[{\"cloud\":\"cloud1\",\"region\":\"region1\",\"zone\":\"zone1\"," +
          "\"min_num_replicas\":1}," +
          "{\"cloud\":\"cloud2\",\"region\":\"region2\",\"zone\":\"zone2\"," +
          "\"min_num_replicas\":1}]}')");

      // Create a valid tablespace.
      setupStatement.execute(
        "CREATE TABLESPACE valid_tblspc WITH (replica_placement=" +
          "'{\"num_replicas\":2, \"placement_blocks\":" +
          "[{\"cloud\":\"cloud1\",\"region\":\"region1\",\"zone\":\"zone1\"," +
          "\"min_num_replicas\":1}," +
          "{\"cloud\":\"cloud2\",\"region\":\"region2\",\"zone\":\"zone2\"," +
          "\"min_num_replicas\":1}]}')");

      // Create a table that can be used for the index test below.
      setupStatement.execute("CREATE TABLE negativeTestTable (a int)");
    }

    final String not_enough_tservers_msg =
      "Not enough tablet servers in the requested placements";

    // Test creation of table in invalid tablespace.
    executeAndAssertErrorThrown(
      "CREATE TABLE invalidPlacementTable (a int) TABLESPACE invalid_tblspc",
      not_enough_tservers_msg);

    // Test creation of index in invalid tablespace.
    executeAndAssertErrorThrown(
      "CREATE INDEX invalidPlacementIdx ON negativeTestTable(a) TABLESPACE invalid_tblspc",
      not_enough_tservers_msg);

    // Test creation of tablegroup in invalid tablespace.
    executeAndAssertErrorThrown(
      "CREATE TABLEGROUP invalidPlacementTablegroup TABLESPACE invalid_tblspc",
      not_enough_tservers_msg);

    // Test creation of table when the replication factor cannot be satisfied.
    executeAndAssertErrorThrown(
      "CREATE TABLE insufficentRfTable (a int) TABLESPACE insufficient_rf_tblspc",
      not_enough_tservers_msg);

    // Test creation of tablegroup when the replication factor cannot be satisfied.
    executeAndAssertErrorThrown(
      "CREATE TABLEGROUP insufficientRfTablegroup TABLESPACE insufficient_rf_tblspc",
      not_enough_tservers_msg);

    // Test creation of index when the replication factor cannot be satisfied.
    executeAndAssertErrorThrown(
      "CREATE INDEX invalidPlacementIdx ON negativeTestTable(a) " +
        "TABLESPACE insufficient_rf_tblspc",
      not_enough_tservers_msg);

    // Test creation of materialized view when the replication factor cannot be satisfied.
    executeAndAssertErrorThrown(
      "CREATE MATERIALIZED VIEW invalidPlacementMv TABLESPACE insufficient_rf_tblspc AS " +
        "SELECT * FROM negativeTestTable",
      not_enough_tservers_msg);

    // Test ALTER ... SET TABLESPACE

    // Create a table, index, and materialized view in the valid tablespace.
    try (Statement setupStatement = connection.createStatement()) {
      setupStatement.execute(
        "CREATE TABLE validPlacementTable (a int) TABLESPACE valid_tblspc");
      setupStatement.execute(
        "CREATE INDEX validPlacementIdx ON validPlacementTable(a) TABLESPACE valid_tblspc");
      setupStatement.execute(
        "CREATE MATERIALIZED VIEW validPlacementMv TABLESPACE valid_tblspc AS " +
          "SELECT * FROM validPlacementTable");
    }

    // Test ALTER ... SET TABLESPACE to invalid tablespace.
    executeAndAssertErrorThrown(
      "ALTER TABLE validPlacementTable SET TABLESPACE invalid_tblspc",
      not_enough_tservers_msg);

    executeAndAssertErrorThrown(
      "ALTER INDEX validPlacementIdx SET TABLESPACE invalid_tblspc",
      not_enough_tservers_msg);

    executeAndAssertErrorThrown(
      "ALTER MATERIALIZED VIEW validPlacementMv SET TABLESPACE invalid_tblspc",
      not_enough_tservers_msg);

    // Test ALTER ... SET TABLESPACE to insufficient replication factor tablespace.
    executeAndAssertErrorThrown(
      "ALTER TABLE validPlacementTable SET TABLESPACE insufficient_rf_tblspc",
      not_enough_tservers_msg);

    executeAndAssertErrorThrown(
      "ALTER INDEX validPlacementIdx SET TABLESPACE insufficient_rf_tblspc",
      not_enough_tservers_msg);

    executeAndAssertErrorThrown(
      "ALTER MATERIALIZED VIEW validPlacementMv SET TABLESPACE insufficient_rf_tblspc",
      not_enough_tservers_msg);

    // Reset YB-Master flags.
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "enable_ysql_tablespaces_for_placement", "false"));
    }
  }

  public void executeAndAssertErrorThrown(String statement, String err_msg) throws Exception{
    boolean error_thrown = false;
    try (Statement setupStatement = connection.createStatement()) {
      setupStatement.execute(statement);
    } catch (PSQLException e) {
      String actualError = e.getMessage();
      assertTrue("Expected: " + err_msg + " Actual: " + actualError, actualError.contains(err_msg));
      error_thrown = true;
    }

    // Verify that error was indeed thrown.
    assertTrue(error_thrown);
  }

  @Test
  public void testTableCreationFailure() throws Exception {
    final YBClient client = miniCluster.getClient();
    int previousBGWait = MiniYBCluster.CATALOG_MANAGER_BG_TASK_WAIT_MS;

    try (Statement stmt = connection.createStatement()) {
      for (HostAndPort hp : miniCluster.getMasters().keySet()) {
        // Increase the interval between subsequent runs of bg thread so that
        // it assigns replicas for tablets of both the tables concurrently.
        assertTrue(client.setFlag(hp, "catalog_manager_bg_task_wait_ms", "10000"));
        assertTrue(client.setFlag(
              hp, "TEST_skip_placement_validation_createtable_api", "true", true));
      }
      LOG.info("Increased the delay between successive runs of bg threads.");
      // Create tablespace with valid placement.
      stmt.execute(
          " CREATE TABLESPACE valid_ts " +
          "  WITH (replica_placement=" +
          "'{\"num_replicas\":2, \"placement_blocks\":" +
          "[{\"cloud\":\"cloud1\",\"region\":\"region1\",\"zone\":\"zone1\"," +
          "\"min_num_replicas\":1}," +
          "{\"cloud\":\"cloud2\",\"region\":\"region2\",\"zone\":\"zone2\"," +
          "\"min_num_replicas\":1}]}')");
      LOG.info("Created a tablespace with valid placement information.");
      // Create tablespace with invalid placement.
      stmt.execute(
          " CREATE TABLESPACE invalid_ts " +
          "  WITH (replica_placement=" +
          "'{\"num_replicas\":2, \"placement_blocks\":" +
          "[{\"cloud\":\"cloud1\",\"region\":\"region1\",\"zone\":\"zone1\"," +
          "\"min_num_replicas\":1}," +
          "{\"cloud\":\"cloud4\",\"region\":\"region4\",\"zone\":\"zone4\"," +
          "\"min_num_replicas\":1}]}')");
      LOG.info("Created a tablespace with invalid placement information.");

      int count = 2;
      final AtomicBoolean errorsDetectedForValidTable = new AtomicBoolean(false);
      final AtomicBoolean errorsDetectedForInvalidTable = new AtomicBoolean(false);
      final CyclicBarrier barrier = new CyclicBarrier(count);
      final Thread[] threads = new Thread[count];
      final Connection[] connections = new Connection[count];

      // Create connections for concurrent create table.
      for (int i = 0; i < count; ++i) {
        ConnectionBuilder b = getConnectionBuilder();
        b.withTServer(count % miniCluster.getNumTServers());
        connections[i] = b.connect();
      }

      // Enqueue a couple of concurrent table creation jobs.
      // Thread that creates an invalid table.
      threads[0] = new Thread(() -> {
        try (Statement lstmt = connections[0].createStatement()) {
          barrier.await();
          try {
            LOG.info("Creating a table with invalid placement information.");
            lstmt.execute("CREATE TABLE invalidplacementtable (a int) TABLESPACE invalid_ts");
          } catch (Exception e) {
            LOG.error(e.getMessage());
            errorsDetectedForInvalidTable.set(true);
          }
        } catch (InterruptedException | BrokenBarrierException | SQLException throwables) {
          LOG.info("Infrastructure exception, can be ignored", throwables);
        } finally {
          barrier.reset();
        }
      });

      // Thread that creates a valid table.
      threads[1] = new Thread(() -> {
        try (Statement lstmt = connections[1].createStatement()) {
          barrier.await();
          try {
            LOG.info("Creating a table with valid placement information.");
            lstmt.execute("CREATE TABLE validplacementtable (a int) TABLESPACE valid_ts");
          } catch (Exception e) {
            LOG.error(e.getMessage());
            errorsDetectedForValidTable.set(true);
          }
        } catch (InterruptedException | BrokenBarrierException | SQLException throwables) {
          LOG.info("Infrastructure exception, can be ignored", throwables);
        } finally {
          barrier.reset();
        }
      });

      // Wait for join.
      Arrays.stream(threads).forEach(t -> t.start());
      for (Thread t : threads) {
        t.join();
      }

      // Reset the bg threads delay.
      for (HostAndPort hp : miniCluster.getMasters().keySet()) {
        assertTrue(client.setFlag(
              hp, "catalog_manager_bg_task_wait_ms", Integer.toString(previousBGWait)));
        assertTrue(client.setFlag(
              hp, "TEST_skip_placement_validation_createtable_api", "false", true));
      }
      // Verify that the transaction DDL garbage collector removes this table.
      assertTrue(client.waitForTableRemoval(30000, "invalidplacementtable"));

      LOG.info("Valid table created successfully: " + !errorsDetectedForValidTable.get());
      LOG.info("Invalid table created successfully: " + !errorsDetectedForInvalidTable.get());
      assertFalse(errorsDetectedForValidTable.get());
      assertTrue(errorsDetectedForInvalidTable.get());
    }
  }

  // Verify that the tables and indices have been placed in appropriate
  // zones.
  void verifyPlacement() throws Exception {
    verifyPlacement(false /* skipTransactionTables */);
  }

  void verifyPlacement(boolean skipTransactionTables) throws Exception {
    for (final String table : tablesWithDefaultPlacement) {
      verifyDefaultPlacement(table);
    }
    for (final String table : tablesWithCustomPlacement) {
      if (skipTransactionTables && table.contains("transaction")) {
        continue;
      }
      verifyCustomPlacement(table);
    }
  }

  void verifyDefaultPlacementForAll() throws Exception {
    for (final String table : tablesWithDefaultPlacement) {
      verifyDefaultPlacement(table);
    }
    for (final String table : tablesWithCustomPlacement) {
      verifyDefaultPlacement(table);
    }
  }

  void verifyCustomPlacement(final String table) throws Exception {
    final YBClient client = miniCluster.getClient();
    client.waitForReplicaCount(getTableFromName(table), 2, 30_000);

    List<LocatedTablet> tabletLocations = getTableFromName(table).getTabletsLocations(30_000);
    String errorMsg = "Invalid custom placement for table '" + table + "': "
        + getPlacementInfoString(tabletLocations);

    // Get tablets for table.
    for (LocatedTablet tablet : tabletLocations) {
      List<LocatedTablet.Replica> replicas = tablet.getReplicas();

      // Verify that both tablets either belong to zone1 or zone2.
      for (LocatedTablet.Replica replica : replicas) {
        final String role = replica.getRole();
        assertFalse(role, role.contains("READ_REPLICA"));
        CloudInfoPB cloudInfo = replica.getCloudInfo();
        if ("cloud1".equals(cloudInfo.getPlacementCloud())) {
          assertEquals(errorMsg, "region1", cloudInfo.getPlacementRegion());
          assertEquals(errorMsg, "zone1", cloudInfo.getPlacementZone());
          continue;
        }
        assertEquals(errorMsg, "cloud2", cloudInfo.getPlacementCloud());
        assertEquals(errorMsg, "region2", cloudInfo.getPlacementRegion());
        assertEquals(errorMsg, "zone2", cloudInfo.getPlacementZone());
      }
    }
  }

  private int countReplicasInPlacement(List<LocatedTablet.Replica> replicas,
                                       PlacementBlock placementBlock) {
    int numReplicas = 0;
    for (LocatedTablet.Replica replica : replicas) {
      CloudInfoPB cloudInfo = replica.getCloudInfo();
      if (isReplicaInPlacement(placementBlock, cloudInfo)) {
        numReplicas++;
      }
    }

    return numReplicas;
  }

  private boolean isReplicaInPlacement(PlacementBlock placementBlock, CloudInfoPB cloudInfo) {
    return placementBlock.cloud.equals(cloudInfo.getPlacementCloud()) &&
      placementBlock.region.equals(cloudInfo.getPlacementRegion()) &&
      placementBlock.zone.equals(cloudInfo.getPlacementZone());
  }

  /**
   * Get the number of rows in the given table.
   */
  private long getNumRows(String tbl) throws Exception {
    try (Statement stmt = connection.createStatement()) {
      Row countRow = getSingleRow(stmt, "SELECT COUNT(*) FROM " + tbl);
      return countRow.getLong(0);
    }
  }
  void verifyDefaultPlacement(final String table) throws Exception {
    final YBClient client = miniCluster.getClient();
    client.waitForReplicaCount(getTableFromName(table), 3, 30_000);

    List<LocatedTablet> tabletLocations = getTableFromName(table).getTabletsLocations(30_000);
    String errorMsg = "Invalid default placement for table '" + table + "': "
        + getPlacementInfoString(tabletLocations);

    // Get tablets for table.
    for (LocatedTablet tablet : tabletLocations) {
      List<LocatedTablet.Replica> replicas = tablet.getReplicas();
      // Verify that tablets can be present in any zone.
      for (LocatedTablet.Replica replica : replicas) {
        CloudInfoPB cloudInfo = replica.getCloudInfo();
        if ("cloud1".equals(cloudInfo.getPlacementCloud())) {
          assertEquals(errorMsg, "region1", cloudInfo.getPlacementRegion());
          assertEquals(errorMsg, "zone1", cloudInfo.getPlacementZone());
          continue;
        } else if ("cloud2".equals(cloudInfo.getPlacementCloud())) {
          assertEquals(errorMsg, "region2", cloudInfo.getPlacementRegion());
          assertEquals(errorMsg, "zone2", cloudInfo.getPlacementZone());
          continue;
        }
        assertEquals(errorMsg, "cloud3", cloudInfo.getPlacementCloud());
        assertEquals(errorMsg, "region3", cloudInfo.getPlacementRegion());
        assertEquals(errorMsg, "zone3", cloudInfo.getPlacementZone());
      }
    }
  }

  public String getPlacementInfoString(List<LocatedTablet> locatedTablets) {
    StringBuilder sb = new StringBuilder("[");
    for (LocatedTablet tablet : locatedTablets) {
      sb.append("[");
      List<LocatedTablet.Replica> replicas = new ArrayList<>(tablet.getReplicas());
      // Somewhat dirty but would do.
      replicas
          .sort((r1, r2) -> r1.getCloudInfo().toString().compareTo(r2.getCloudInfo().toString()));
      for (LocatedTablet.Replica replica : replicas) {
        if (sb.charAt(sb.length() - 1) != '[') {
          sb.append(", ");
        }
        sb.append("{\n");
        sb.append(Stream.<String>of(replica.getCloudInfo().toString().trim().split("\n"))
            .map((s) -> " " + s)
            .collect(Collectors.joining(",\n")));
        sb.append("\n}");
      }
      sb.append("]");
    }
    sb.append("]");
    return sb.toString();
  }

  public String getTablespaceTransactionTableName() throws Exception {
    try (Statement setupStatement = connection.createStatement()) {
      ResultSet s = setupStatement.executeQuery(
          "SELECT oid FROM pg_catalog.pg_tablespace " +
          "WHERE UPPER(spcname) = UPPER('" + tablespaceName + "')");
      assertTrue(s.next());
      return "transactions_" + s.getInt(1);
    }
  }

  YBTable getTableFromName(final String table) throws Exception {
    final YBClient client = miniCluster.getClient();
    List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tables =
      client.getTablesList(table).getTableInfoList();
    assertEquals("More than one table found with name " + table, 1, tables.size());
    return client.openTableByUUID(
      tables.get(0).getId().toStringUtf8());
  }


  @Test
  public void readReplicaWithTablespaces() throws Exception {
    markClusterNeedsRecreation();
    final YBClient client = miniCluster.getClient();
    // Set required YB-Master flags.
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "enable_ysql_tablespaces_for_placement", "true"));
    }

    createTestData("read_replica");
    int expectedTServers = miniCluster.getTabletServers().size() + 1;

    LOG.info("Adding a TServer for read-replica cluster");
    Map<String, String> readReplicaPlacement = ImmutableMap.of(
        "placement_cloud", "cloud1",
        "placement_region", "region1",
        "placement_zone", "zone1",
        "placement_uuid", "readcluster");
    miniCluster.startTServer(readReplicaPlacement);
    miniCluster.waitForTabletServers(expectedTServers);

    CloudInfoPB cloudInfo0 = CloudInfoPB.newBuilder()
            .setPlacementCloud("cloud1")
            .setPlacementRegion("region1")
            .setPlacementZone("zone1")
            .build();

    CatalogEntityInfo.PlacementBlockPB placementBlock0 = CatalogEntityInfo.PlacementBlockPB.
        newBuilder().setCloudInfo(cloudInfo0).setMinNumReplicas(1).build();

    List<CatalogEntityInfo.PlacementBlockPB> placementBlocksLive =
        new ArrayList<CatalogEntityInfo.PlacementBlockPB>();
    placementBlocksLive.add(placementBlock0);

    List<CatalogEntityInfo.PlacementBlockPB> placementBlocksReadOnly =
            new ArrayList<CatalogEntityInfo.PlacementBlockPB>();
    placementBlocksReadOnly.add(placementBlock0);

    CatalogEntityInfo.PlacementInfoPB livePlacementInfo = CatalogEntityInfo.PlacementInfoPB.
        newBuilder().addAllPlacementBlocks(placementBlocksLive).setNumReplicas(1).
        setPlacementUuid(ByteString.copyFromUtf8("")).build();

    CatalogEntityInfo.PlacementInfoPB readOnlyPlacementInfo = CatalogEntityInfo.PlacementInfoPB.
        newBuilder().addAllPlacementBlocks(placementBlocksReadOnly).setNumReplicas(1).
        setPlacementUuid(ByteString.copyFromUtf8("readcluster")).build();

    List<CatalogEntityInfo.PlacementInfoPB> readOnlyPlacements = Arrays.asList(
        readOnlyPlacementInfo);

    ModifyClusterConfigReadReplicas readOnlyOperation =
        new ModifyClusterConfigReadReplicas(client, readOnlyPlacements);
    ModifyClusterConfigLiveReplicas liveOperation =
        new ModifyClusterConfigLiveReplicas(client, livePlacementInfo);

    try {
      LOG.info("Setting read only cluster configuration");
      readOnlyOperation.doCall();

      LOG.info("Setting cluster configuration for live replicas");
      liveOperation.doCall();
    } catch (Exception e) {
      LOG.warn("Failed with error:", e);
      assertTrue(false);
    }

    LOG.info("Waiting for the loadbalancer to become active...");

    // Wait for loadbalancer to run.
    assertTrue(miniCluster.getClient().waitForLoadBalancerActive(
      MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    LOG.info("Waiting for the load balancer to become idle...");
    assertTrue(miniCluster.getClient().waitForLoadBalance(Long.MAX_VALUE, expectedTServers));

    for (final String table : tablesWithDefaultPlacement) {
      verifyPlacementForReadReplica(table);
    }
    for (final String table : tablesWithCustomPlacement) {
      verifyCustomPlacement(table);
    }

    // ALTER TABLE SET TABLESPACE pg_default and then verify its placement.
    try (Statement setupStatement = connection.createStatement()) {
      for (final String table : tablesWithDefaultPlacement) {
        setupStatement.execute("ALTER TABLE " + table + " SET TABLESPACE pg_default");
      }
    }

    for (final String table : tablesWithDefaultPlacement) {
      verifyPlacementForReadReplica(table);
    }
    for (final String table : tablesWithCustomPlacement) {
      verifyCustomPlacement(table);
    }
  }

  void verifyPlacementForReadReplica(final String table) throws Exception {
    final YBClient client = miniCluster.getClient();
    client.waitForReplicaCount(getTableFromName(table), 1, 30_000);

    List<LocatedTablet> tabletLocations = getTableFromName(table).getTabletsLocations(30_000);
    // Get tablets for table.
    for (LocatedTablet tablet : tabletLocations) {
      boolean foundReadOnlyReplica = false;
      boolean foundLiveReplica = false;
      List<LocatedTablet.Replica> replicas = tablet.getReplicas();
      // Verify that tablets can be present in any zone.
      for (LocatedTablet.Replica replica : replicas) {
        CloudInfoPB cloudInfo = replica.getCloudInfo();
        final String errorMsg = "Unexpected cloud.region.zone: " + cloudInfo.toString();

        if (replica.getRole().contains("READ_REPLICA")) {
          assertFalse(foundReadOnlyReplica);
          foundReadOnlyReplica = true;
        } else {
          assertFalse(foundLiveReplica);
          foundLiveReplica = true;
        }
        assertEquals(errorMsg, "cloud1", cloudInfo.getPlacementCloud());
        assertEquals(errorMsg, "region1", cloudInfo.getPlacementRegion());
        assertEquals(errorMsg, "zone1", cloudInfo.getPlacementZone());
      }
      // A live replica must be found.
      assertTrue(foundLiveReplica);

      // A read-only replica must be found.
      assertTrue(foundReadOnlyReplica);
    }
  }

  /**
   * Test for ALTER TABLE SET TABLESPACE
   * <p>
   * Changes the tablespace of a table and verifies that the tablets are moved.
   * This test creates a cluster with three nodes, each with its own tablespace.
   * It moves the table to each of the tablespaces and verifies that the tablets
   * are moved to the correct tablespace each time.
   */
  @Test
  public void testAlterTableSetTablespace() throws Exception {
    markClusterNeedsRecreation();
    final YBClient client = miniCluster.getClient();

    // Set YB-Master flags enabling tablespaces.
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "enable_ysql_tablespaces_for_placement", "true"));
    }

    createTestData("alter_test");

    LOG.info("Verifying whether tablet replicas were placed correctly at creation time");
    verifyPlacement();

    // Create a new placement block on cloud3.region3.zone3
    // Define a new tablespace with just this placement block.
    PlacementBlock block = new PlacementBlock("cloud3", "region3", "zone3", 1);
    String tablespaceName = "testTablespaceZone3";
    Tablespace ts = new Tablespace(tablespaceName, 1, Collections.singletonList(block));
    ts.create();

    // Execute the ALTER TABLE SET TABLESPACE command
    LOG.info("Moving table to new tablespace " + tablespaceName);
    try (Statement setupStatement = connection.createStatement()) {
      setupStatement.execute("ALTER TABLE alter_test_default_table SET TABLESPACE " +
        tablespaceName);
    }

    // Wait for loadbalancer to run and then verify that the tablets have been moved.
    LOG.info("Waiting for the loadbalancer to become active...");
    assertTrue(miniCluster.getClient().waitForLoadBalancerActive(
      MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    LOG.info("Waiting for the load balancer to become idle...");
    assertTrue(miniCluster.getClient().waitForLoadBalancerIdle(MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    LOG.info("Verifying whether tablet replicas were moved to the new tablespace");
    verifyCustomPlacement("alter_test_default_table", ts);
  }


  /**
   * Test for ALTER INDEX SET TABLESPACE
   * <p>
   * Changes the tablespace of an index and verifies that the tablets are moved.
   * This test creates a cluster with three nodes, each with its own tablespace.
   * It moves the table to each of the tablespaces and verifies that the tablets
   * are moved to the correct tablespace each time.
   */
  @Test
  public void testAlterIndexSetTablespace() throws Exception {
    markClusterNeedsRecreation();
    final YBClient client = miniCluster.getClient();

    // Set YB-Master flags enabling tablespaces.
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "enable_ysql_tablespaces_for_placement", "true"));
    }

    createTestData("alter_test");

    LOG.info("Verifying whether tablet replicas were placed correctly at creation time");
    verifyPlacement();

    // Create original tablespace with a single placement block on cloud1.region1.zone1
    String originalTablespaceName = "testTablespaceZone1";
    PlacementBlock block1 = new PlacementBlock("cloud1", "region1", "zone1", 1);
    Tablespace origTs = new Tablespace(originalTablespaceName, 1,
      Collections.singletonList(block1));
    origTs.create();

    // Create new tablespace with a single placment block on cloud3.region3.zone3
    String newTablespaceName = "testTablespaceZone3";
    PlacementBlock block3 = new PlacementBlock("cloud3", "region3", "zone3", 1);
    Tablespace newTs = new Tablespace(newTablespaceName, 1, Collections.singletonList(block3));
    newTs.create();

    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE INDEX alter_test_default_table_idx ON " +
        "alter_test_default_table(a) TABLESPACE " + originalTablespaceName);
    }

    // Execute the ALTER TABLE SET TABLESPACE command
    LOG.info("Moving table to new tablespace " + newTablespaceName);
    try (Statement setupStatement = connection.createStatement()) {
      setupStatement.execute("ALTER INDEX alter_test_default_table_idx SET TABLESPACE " +
        newTablespaceName);
    }

    LOG.info("Waiting for the loadbalancer to become active...");
    assertTrue(miniCluster.getClient()
      .waitForLoadBalancerActive(MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    LOG.info("Waiting for the load balancer to become idle...");
    assertTrue(miniCluster.getClient().waitForLoadBalancerIdle(MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    LOG.info("Verifying whether tablet replicas were moved to the new tablespace");
    verifyCustomPlacement("alter_test_default_table_idx", newTs);
  }

  /**
   * Test for ALTER MATERIALIZED VIEW SET TABLESPACE
   * <p>
   * Changes the tablespace of a materialized view and verifies that the tablets are moved.
   * This test creates a cluster with three nodes, each with its own tablespace.
   * It creates a materialized view in one tablespace, moves it to a different tablespace,
   * and verifies that the tablets are moved to the correct tablespace each time.
   */
  @Test
  public void testAlterMatViewSetTablespace() throws Exception {
    markClusterNeedsRecreation();
    final YBClient client = miniCluster.getClient();

    // Set YB-Master flags enabling tablespaces.
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "enable_ysql_tablespaces_for_placement", "true"));
    }

    // Create original tablespace with a single placement block on cloud1.region1.zone1
    String originalTablespaceName = "testTablespaceZone1";
    PlacementBlock block1 = new PlacementBlock("cloud1", "region1", "zone1", 1);
    Tablespace origTs = new Tablespace(originalTablespaceName, 1,
      Collections.singletonList(block1));
    origTs.create();

    // Create new tablespace with a single placment block on cloud3.region3.zone3
    String newTablespaceName = "testTablespaceZone3";
    PlacementBlock block3 = new PlacementBlock("cloud3", "region3", "zone3", 1);
    Tablespace newTs = new Tablespace(newTablespaceName, 1, Collections.singletonList(block3));
    newTs.create();

    createTestData("mv_tblspc");

    LOG.info("Verifying whether tablet replicas were placed correctly at creation time");
    verifyPlacement();

    // Insert some data into the table
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("INSERT INTO mv_tblspc_default_table (a) " +
        "SELECT i FROM generate_series(1, 100) i");
    }

    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE MATERIALIZED VIEW alter_tablespace_test_mv TABLESPACE " +
        originalTablespaceName + " AS SELECT * FROM mv_tblspc_default_table");
    }

    verifyCustomPlacement("mv_tblspc_default_table", origTs);

    // Execute the ALTER TABLE SET TABLESPACE command
    LOG.info("Moving table to new tablespace " + newTablespaceName);
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("ALTER MATERIALIZED VIEW alter_tablespace_test_mv SET TABLESPACE " +
        newTablespaceName);
    }

    LOG.info("Waiting for the loadbalancer to become active...");
    assertTrue(miniCluster.getClient()
      .waitForLoadBalancerActive(MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    LOG.info("Waiting for the load balancer to become idle...");
    assertTrue(miniCluster.getClient().waitForLoadBalancerIdle(MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    LOG.info("Verifying whether tablet replicas were moved to the new tablespace");
    verifyCustomPlacement("mv_tblspc_default_table", newTs);
  }

  /**
   * Geo-partitioned table test
   * <p>
   * Creates a table with two partitions, each with its own tablespace,
   * then moves one of the partitions to a new tablespace.
   */
  @Test
  public void testTablespacePartitioning() throws Exception {
    markClusterNeedsRecreation();
    final YBClient client = miniCluster.getClient();

    // Set YB-Master flags enabling tablespaces.
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "enable_ysql_tablespaces_for_placement", "true"));
    }

    // Create original tablespace with a single placement block on cloud1.region1.zone1
    String ts1Name = "testTablespaceZone1";
    PlacementBlock block1 = new PlacementBlock("cloud1", "region1", "zone1", 1);
    Tablespace ts1 = new Tablespace(ts1Name, 1, Collections.singletonList(block1));
    ts1.create();

    // Create new tablespace with a single placment block on cloud2.region2.zone2
    String ts2Name = "testTablespaceZone2";
    PlacementBlock block2 = new PlacementBlock("cloud2", "region2", "zone2", 1);
    Tablespace ts2 = new Tablespace(ts2Name, 1, Collections.singletonList(block2));
    ts2.create();

    // Create new tablespace with a single placment block on cloud3.region3.zone3
    String ts3Name = "testTablespaceZone3";
    PlacementBlock block3 = new PlacementBlock("cloud3", "region3", "zone3", 1);
    Tablespace ts3 = new Tablespace(ts3Name, 1, Collections.singletonList(block3));
    ts3.create();

    // Create a table with two partitions, each with its own tablespace.
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE geo_partition_test_table (a int) " + "PARTITION BY RANGE (a)");
    }

    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE geo_partition_test_table_p1 " +
        "PARTITION OF geo_partition_test_table " +
        "FOR VALUES FROM (0) TO (100) TABLESPACE " + ts1Name);
    }

    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE geo_partition_test_table_p2 " +
        "PARTITION OF geo_partition_test_table " +
        "FOR VALUES FROM (100) TO (200) TABLESPACE " + ts2Name);
    }

    LOG.info("Verifying whether tablet replicas were placed correctly at creation time");
    verifyCustomPlacement("geo_partition_test_table_p1", ts1);
    verifyCustomPlacement("geo_partition_test_table_p2", ts2);

    // Execute the ALTER TABLE SET TABLESPACE command
    LOG.info("Moving table to new tablespace" + tablespaceName);
    try (Statement setupStatement = connection.createStatement()) {
      setupStatement.execute("ALTER TABLE geo_partition_test_table_p2 SET TABLESPACE " + ts3Name);
    }

    LOG.info("Waiting for the loadbalancer to become active...");
    assertTrue(miniCluster.getClient().
      waitForLoadBalancerActive(MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    LOG.info("Waiting for the load balancer to become idle...");
    assertTrue(miniCluster.getClient().waitForLoadBalancerIdle(MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    LOG.info("Verifying whether tablet replicas were moved to the new tablespace");
    verifyCustomPlacement("geo_partition_test_table_p2", ts3);

    // Verify that the tablets for the other partition were not moved.
    verifyCustomPlacement("geo_partition_test_table_p1", ts1);

    // Create indexes on the partitions.
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE INDEX geo_partition_test_table_p1_idx " +
        "ON geo_partition_test_table_p1(a) TABLESPACE " + ts1Name);
    }

    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE INDEX geo_partition_test_table_p2_idx " +
        "ON geo_partition_test_table_p2(a) TABLESPACE " + ts2Name);
    }

    // Verify that the indexes were placed correctly.
    verifyCustomPlacement("geo_partition_test_table_p1_idx", ts1);
    verifyCustomPlacement("geo_partition_test_table_p2_idx", ts2);

    // Move one of the indexes to a new tablespace.
    try (Statement setupStatement = connection.createStatement()) {
      setupStatement.execute("ALTER INDEX geo_partition_test_table_p2_idx SET TABLESPACE " +
        ts3Name);
    }

    LOG.info("Waiting for the loadbalancer to become active...");
    assertTrue(miniCluster.getClient().
      waitForLoadBalancerActive(MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    LOG.info("Waiting for the load balancer to become idle...");
    assertTrue(miniCluster.getClient().waitForLoadBalancerIdle(MASTER_LOAD_BALANCER_WAIT_TIME_MS));

    // Verify that the index is placed in the tablespace we moved it to.
    verifyCustomPlacement("geo_partition_test_table_p2_idx", ts3);
  }

  /**
   * Helper function for running DDL statements in a thread.
   *
   * @param conn              Connection to use for executing the statement.
   * @param sql               SQL statement to execute, as a format string with a single %s to be
   *                          replaced with the tablespace name.
   * @param errorsDetected    AtomicBoolean to set if an error is detected.
   * @param barrier           CyclicBarrier to synchronize with other threads.
   * @param numStmtsPerThread Number of statements to execute per thread.
   * @param tablespaces       Array of tablespaces to cycle through.
   */
  public void runDDLThread(Connection conn, String sql, AtomicBoolean errorsDetected,
                           CyclicBarrier barrier, int numStmtsPerThread, Tablespace[] tablespaces)
    throws InterruptedException, BrokenBarrierException {
    try (Statement lstmt = conn.createStatement()) {
      for (int i = 0; i < numStmtsPerThread; ++i) {
        barrier.await();

        final int tablespaceIdx = i % tablespaces.length;
        String sqlStmt = String.format(sql, tablespaces[tablespaceIdx].name);

        try {
          LOG.info("DDL thread: Executing statement: " + sqlStmt);
          lstmt.execute(sqlStmt);

        } catch (Exception e) {
          LOG.error(String.format("Unexpected exception while executing %s: %s", sqlStmt, e.getMessage()));
          errorsDetected.set(true);
        }
      }
    } catch (SQLException e) {
      LOG.info("Infrastructure exception, can be ignored", e);
    }
  }

  /**
   * Generates several tablespaces with varying placement and replication.
   * @return Array of generated tablespaces.
   */
  public Tablespace[] generateTestTablespaces() {
    PlacementBlock block1 = new PlacementBlock("cloud1", "region1", "zone1", 1);
    PlacementBlock block2 = new PlacementBlock("cloud2", "region2", "zone2", 1);
    PlacementBlock block3 = new PlacementBlock("cloud3", "region3", "zone3", 1);

    // Single-node tablespaces
    Tablespace ts1 = new Tablespace("testTsZone1", 1, Collections.singletonList(block1));
    Tablespace ts2 = new Tablespace("testTsZone2", 1, Collections.singletonList(block2));
    Tablespace ts3 = new Tablespace("testTsZone3", 1, Collections.singletonList(block3));

    // Double-node tablespaces
    Tablespace ts12 = new Tablespace("testTsZone12", 2, Arrays.asList(block1, block2));
    Tablespace ts23 = new Tablespace("testTsZone23", 2, Arrays.asList(block2, block3));
    Tablespace ts13 = new Tablespace("testTsZone13", 2, Arrays.asList(block1, block3));

    // Triple-node tablespace
    Tablespace ts123 = new Tablespace("testTsZone123", 3, Arrays.asList(block1, block2, block3));

    return new Tablespace[]{ts1, ts2, ts3, ts12, ts23, ts13, ts123};
  }

  /**
   * Tests ALTER TABLE SET TABLESPACE running concurrently with a DML workload.
   */
  @Test
  public void testAlterTableSetTablespaceConcurrent() throws Exception {
    markClusterNeedsRecreation();
    final YBClient client = miniCluster.getClient();
    int previousBGWait = MiniYBCluster.CATALOG_MANAGER_BG_TASK_WAIT_MS;

    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      // Increase the interval between subsequent runs of bg thread so that
      // it assigns replicas for tablets of both the tables concurrently.
      assertTrue(client.setFlag(hp, "catalog_manager_bg_task_wait_ms", "10000"));
      assertTrue(client.setFlag(hp,
        "TEST_skip_placement_validation_createtable_api", "true", true));
    }
    LOG.info("Increased the delay between successive runs of bg threads.");

    // Create some tablespaces with varying placement and replication.
    Tablespace[] tablespaces = generateTestTablespaces();
    Arrays.stream(tablespaces).forEach(Tablespace::create);

    // Create the table.
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE concurrent_test_tbl " +
        "(k INT PRIMARY KEY, v1 INT DEFAULT 10, v2 INT DEFAULT 20)");
    }

    // Number of DML threads to run concurrently.
    int numDmlThreads = 1;
    // Number of DDL threads to run concurrently.
    int numDdlThreads = 1;
    // Number of total threads.
    int totalThreads = numDmlThreads + numDdlThreads;
    // Number of statements to execute per thread.
    int numStmtsPerThread = 10;

    // Synchronization variables.
    final AtomicBoolean errorsDetected = new AtomicBoolean(false);
    final CyclicBarrier barrier = new CyclicBarrier(totalThreads);
    final Thread[] threads = new Thread[totalThreads];
    final Connection[] connections = new Connection[totalThreads];

    for (int i = 0; i < totalThreads; ++i) {
      ConnectionBuilder b = getConnectionBuilder();
      b.withTServer(totalThreads % miniCluster.getNumTServers());
      connections[i] = b.connect();
    }

    // Enqueue threads that execute ALTER TABLESPACE commands on a table, index, and mat view.
    // These threads cycle through the tablespaces in the tablespaces array round-robin.
    // In total, each thread does numStmtsPerThread ALTER TABLESPACE commands.
    // When these threads are done, we to be in tablespace number
    // (numStmtsPerThread - 1) % tablespaces.length.
    Tablespace expectedFinalTablespace = tablespaces[(numStmtsPerThread - 1) % tablespaces.length];

    // ALTER TABLE SET TABLESPACE thread.
    threads[0] = new Thread(() -> {
      try {
        runDDLThread(connections[0], "ALTER TABLE concurrent_test_tbl SET TABLESPACE %s",
          errorsDetected, barrier, numStmtsPerThread, tablespaces);
      } catch (InterruptedException | BrokenBarrierException e) {
        LOG.info("Infrastructure exception, can be ignored", e);
      }
    });


    // Enqueue the DML threads.
    for (int i = numDdlThreads; i < totalThreads; ++i) {
      final int idx = i;
      threads[i] = new Thread(() -> {
        try (Statement lstmt = connections[idx].createStatement()) {
          for (int item_idx = 0;
               !errorsDetected.get() && item_idx < numStmtsPerThread;
               ++item_idx) {
            barrier.await();
            try {
              lstmt.execute(String.format(
                "INSERT INTO concurrent_test_tbl(k, v1, v2) VALUES(%d, %d, %d)",
                idx * 10000000L + item_idx,
                idx * 10000000L + item_idx + 1,
                idx * 10000000L + item_idx + 2));

              lstmt.execute(String.format("UPDATE concurrent_test_tbl SET v2 = v2 + 1 WHERE v1 = %d",
                item_idx));
            } catch (Exception e) {
              final String msg = e.getMessage();
              if (!(msg.contains("Catalog Version Mismatch") ||
                msg.contains("Restart read required") ||
                msg.contains("schema version mismatch"))) {
                LOG.error("Unexpected exception while executing INSERT/UPDATE", e);
                errorsDetected.set(true);
                return;
              }
            }
          }
        } catch (InterruptedException | BrokenBarrierException | SQLException throwables) {
          LOG.info("Infrastructure exception, can be ignored", throwables);
        } finally {
          barrier.reset();
        }
      });
    }

    // Wait for join.
    Arrays.stream(threads).forEach(Thread::start);

    for (Thread t : threads) {
      t.join();
    }

    // Reset the bg threads delay.
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "catalog_manager_bg_task_wait_ms",
        Integer.toString(previousBGWait)));
      assertTrue(client.setFlag(hp, "TEST_skip_placement_validation_createtable_api",
        "false", true));
    }

    // Verify that no errors were thrown by the worker threads.
    assertFalse(errorsDetected.get());

    // Verify that the table has the right number of rows and is placed in the expected tablespace
    assertEquals(numDmlThreads * numStmtsPerThread, getNumRows("concurrent_test_tbl"));
    verifyCustomPlacement("concurrent_test_tbl", expectedFinalTablespace);
  }

  /**
   * Tests ALTER INDEX SET TABLESPACE running concurrently with a DML workload.
   */
  @Test
  public void testAlterIndexSetTablespaceConcurrent() throws Exception {
    markClusterNeedsRecreation();
    final YBClient client = miniCluster.getClient();
    int previousBGWait = MiniYBCluster.CATALOG_MANAGER_BG_TASK_WAIT_MS;

    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      // Increase the interval between subsequent runs of bg thread so that
      // it assigns replicas for tablets of both the tables concurrently.
      assertTrue(client.setFlag(hp, "catalog_manager_bg_task_wait_ms", "10000"));
      assertTrue(client.setFlag(hp,
        "TEST_skip_placement_validation_createtable_api", "true", true));
    }
    LOG.info("Increased the delay between successive runs of bg threads.");

    // Create some tablespaces with varying placement and replication.
    Tablespace[] tablespaces = generateTestTablespaces();
    Arrays.stream(tablespaces).forEach(Tablespace::create);

    // Create the table + index.
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE concurrent_test_tbl " +
        "(k INT PRIMARY KEY, v1 INT DEFAULT 10, v2 INT DEFAULT 20)");
      stmt.execute("CREATE INDEX concurrent_test_idx ON concurrent_test_tbl(v1)");
    }

    // Number of DML threads to run concurrently.
    int numDmlThreads = 1;
    // Number of DDL threads to run concurrently.
    int numDdlThreads = 1;
    // Number of total threads.
    int totalThreads = numDmlThreads + numDdlThreads;
    // Number of statements to execute per thread.
    int numStmtsPerThread = 10;

    // Synchronization variables.
    final AtomicBoolean errorsDetected = new AtomicBoolean(false);
    final CyclicBarrier barrier = new CyclicBarrier(totalThreads);
    final Thread[] threads = new Thread[totalThreads];
    final Connection[] connections = new Connection[totalThreads];

    for (int i = 0; i < totalThreads; ++i) {
      ConnectionBuilder b = getConnectionBuilder();
      b.withTServer(totalThreads % miniCluster.getNumTServers());
      connections[i] = b.connect();
    }

    // Enqueue threads that execute ALTER TABLESPACE commands on a table, index, and mat view.
    // These threads cycle through the tablespaces in the tablespaces array round-robin.
    // In total, each thread does numStmtsPerThread ALTER TABLESPACE commands.
    // When these threads are done, we to be in tablespace number
    // (numStmtsPerThread - 1) % tablespaces.length.
    Tablespace expectedFinalTablespace = tablespaces[(numStmtsPerThread - 1) % tablespaces.length];

    // ALTER INDEX SET TABLESPACE thread.
    threads[0] = new Thread(() -> {
      try {
        runDDLThread(connections[0], "ALTER INDEX concurrent_test_idx SET TABLESPACE %s",
          errorsDetected, barrier, numStmtsPerThread, tablespaces);
      } catch (InterruptedException | BrokenBarrierException e) {
        LOG.info("Infrastructure exception, can be ignored", e);
      }
    });

    // Enqueue the DML threads.
    for (int i = numDdlThreads; i < totalThreads; ++i) {
      final int idx = i;
      threads[i] = new Thread(() -> {
        try (Statement lstmt = connections[idx].createStatement()) {
          for (int item_idx = 0;
               !errorsDetected.get() && item_idx < numStmtsPerThread;
               ++item_idx) {
            barrier.await();
            try {
              lstmt.execute(String.format(
                "INSERT INTO concurrent_test_tbl(k, v1, v2) VALUES(%d, %d, %d)",
                idx * 10000000L + item_idx,
                idx * 10000000L + item_idx + 1,
                idx * 10000000L + item_idx + 2));

              lstmt.execute(String.format("UPDATE concurrent_test_tbl SET v2 = v2 + 1 WHERE v1 = %d",
                item_idx));
            } catch (Exception e) {
              final String msg = e.getMessage();
              if (!(msg.contains("Catalog Version Mismatch") ||
                msg.contains("Restart read required") ||
                msg.contains("schema version mismatch"))) {
                LOG.error("Unexpected exception while executing INSERT/UPDATE", e);
                errorsDetected.set(true);
                return;
              }
            }
          }
        } catch (InterruptedException | BrokenBarrierException | SQLException throwables) {
          LOG.info("Infrastructure exception, can be ignored", throwables);
        } finally {
          barrier.reset();
        }
      });
    }

    // Run threads and wait for join.
    Arrays.stream(threads).forEach(Thread::start);

    for (Thread t : threads) {
      t.join();
    }

    // Reset the bg threads delay.
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "catalog_manager_bg_task_wait_ms",
        Integer.toString(previousBGWait)));
      assertTrue(client.setFlag(hp, "TEST_skip_placement_validation_createtable_api",
        "false", true));
    }

    // Verify that no errors were thrown by the worker threads.
    assertFalse(errorsDetected.get());

    // Verify that the table has the right number of rows.
    assertEquals(numDmlThreads * numStmtsPerThread, getNumRows("concurrent_test_tbl"));

    // Verify that the index has the right number of rows using an index-only scan hint.
    try (Statement stmt = connection.createStatement()) {
      Row countRow = getSingleRow(stmt,
        "/*+ IndexOnlyScan(concurrent_test_tbl concurrent_test_idx) */ " +
          "SELECT COUNT(v1) FROM concurrent_test_tbl");
      assertEquals(numDmlThreads * numStmtsPerThread, (long) countRow.getLong(0));
    }

    // Verify that the index is placed according to the expected final tablespace.
    verifyCustomPlacement("concurrent_test_idx", expectedFinalTablespace);
    LOG.info("testAlterTableSetTablespaceConcurrent completed successfully");
  }

  /**
   * Tests ALTER MATERIALIZED VIEW SET TABLESPACE running concurrently with a DML workload.
   */
  @Test
  public void testAlterMatViewSetTablespaceConcurrent() throws Exception {
    markClusterNeedsRecreation();
    final YBClient client = miniCluster.getClient();
    int previousBGWait = MiniYBCluster.CATALOG_MANAGER_BG_TASK_WAIT_MS;

    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      // Increase the interval between subsequent runs of bg thread so that
      // it assigns replicas for tablets of both the tables concurrently.
      assertTrue(client.setFlag(hp, "catalog_manager_bg_task_wait_ms", "10000"));
      assertTrue(client.setFlag(hp,
        "TEST_skip_placement_validation_createtable_api", "true", true));
    }
    LOG.info("Increased the delay between successive runs of bg threads.");

    // Create some tablespaces with varying placement and replication.
    Tablespace[] tablespaces = generateTestTablespaces();
    Arrays.stream(tablespaces).forEach(Tablespace::create);

    // Create the table + matview.
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE concurrent_test_tbl " +
        "(k INT PRIMARY KEY, v1 INT DEFAULT 10, v2 INT DEFAULT 20)");
      stmt.execute("CREATE MATERIALIZED VIEW concurrent_test_mv AS " +
        "SELECT COUNT(*) FROM concurrent_test_tbl");
    }

    // Number of DML threads to run concurrently.
    int numDmlThreads = 1;
    // Number of DDL threads to run concurrently.
    int numDdlThreads = 1;
    // Number of total threads.
    int totalThreads = numDmlThreads + numDdlThreads;
    // Number of statements to execute per thread.
    int numStmtsPerThread = 10;

    // Synchronization variables.
    final AtomicBoolean errorsDetected = new AtomicBoolean(false);
    final CyclicBarrier barrier = new CyclicBarrier(totalThreads);
    final Thread[] threads = new Thread[totalThreads];
    final Connection[] connections = new Connection[totalThreads];

    for (int i = 0; i < totalThreads; ++i) {
      ConnectionBuilder b = getConnectionBuilder();
      b.withTServer(totalThreads % miniCluster.getNumTServers());
      connections[i] = b.connect();
    }

    // Enqueue threads that execute ALTER TABLESPACE commands on a table, index, and mat view.
    // These threads cycle through the tablespaces in the tablespaces array round-robin.
    // In total, each thread does numStmtsPerThread ALTER TABLESPACE commands.
    // When these threads are done, we to be in tablespace number
    // (numStmtsPerThread - 1) % tablespaces.length.
    Tablespace expectedFinalTablespace = tablespaces[(numStmtsPerThread - 1) % tablespaces.length];

    // ALTER TABLE SET TABLESPACE thread.
    threads[0] = new Thread(() -> {
      try {
        runDDLThread(connections[0],
          "ALTER MATERIALIZED VIEW concurrent_test_mv SET TABLESPACE %s",
          errorsDetected, barrier, numStmtsPerThread, tablespaces);
      } catch (InterruptedException | BrokenBarrierException e) {
        LOG.info("Infrastructure exception, can be ignored", e);
      }
    });

    // Enqueue the DML threads.
    for (int i = numDdlThreads; i < totalThreads; ++i) {
      final int idx = i;
      threads[i] = new Thread(() -> {
        try (Statement lstmt = connections[idx].createStatement()) {
          for (int item_idx = 0;
               !errorsDetected.get() && item_idx < numStmtsPerThread;
               ++item_idx) {
            barrier.await();
            try {
              lstmt.execute(String.format(
                "INSERT INTO concurrent_test_tbl(k, v1, v2) VALUES(%d, %d, %d)",
                idx * 10000000L + item_idx,
                idx * 10000000L + item_idx + 1,
                idx * 10000000L + item_idx + 2));

              lstmt.execute(String.format("UPDATE concurrent_test_tbl SET v2 = v2 + 1 WHERE v1 = %d",
                item_idx));
            } catch (Exception e) {
              final String msg = e.getMessage();
              if (!(msg.contains("Catalog Version Mismatch") ||
                msg.contains("Restart read required") ||
                msg.contains("schema version mismatch"))) {
                LOG.error("Unexpected exception while executing INSERT/UPDATE", e);
                errorsDetected.set(true);
                return;
              }
            }
          }
        } catch (InterruptedException | BrokenBarrierException | SQLException throwables) {
          LOG.info("Infrastructure exception, can be ignored", throwables);
        } finally {
          barrier.reset();
        }
      });
    }

    // Run threads and wait for join.
    Arrays.stream(threads).forEach(Thread::start);

    for (Thread t : threads) {
      t.join();
    }

    // Reset the bg threads delay.
    for (HostAndPort hp : miniCluster.getMasters().keySet()) {
      assertTrue(client.setFlag(hp, "catalog_manager_bg_task_wait_ms",
        Integer.toString(previousBGWait)));
      assertTrue(client.setFlag(hp, "TEST_skip_placement_validation_createtable_api",
        "false", true));
    }

    // Verify that no errors were thrown by the worker threads.
    assertFalse(errorsDetected.get());

    // Verify that the table has the right number of rows
    assertEquals(numDmlThreads * numStmtsPerThread, getNumRows("concurrent_test_tbl"));

    // Verify that the materialized view has the right contents
    // and is placed in the expected tablespace
    try (Statement stmt = connection.createStatement()) {
      Row row = getSingleRow(stmt, "SELECT * FROM concurrent_test_mv");
      assertEquals(0, (long) row.getLong(0));
    }
    verifyCustomPlacement("concurrent_test_mv", expectedFinalTablespace);

    // Refresh the materialized view and verify its placement
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("REFRESH MATERIALIZED VIEW concurrent_test_mv");
    }
    verifyCustomPlacement("concurrent_test_mv", expectedFinalTablespace);
  }

  /**
   * Class representing a placement block. A placement block is composed of the following:
   * 1. Cloud, region, and zone (e.g. aws, us-west1, us-west1-a).
   * 2. Minimum number of replicas in this block.
   * 3. Optionally, the block's leader preference.
   */
  static class PlacementBlock {
    final String cloud;
    final String region;
    final String zone;
    final int minNumReplicas;
    final Integer leaderPreference;

    PlacementBlock(String cloud, String region, String zone, int minNumReplicas) {
      this(cloud, region, zone, minNumReplicas, null);
    }

    PlacementBlock(String cloud, String region, String zone,
                   int minNumReplicas, Integer leaderPreference) {
      this.cloud = cloud;
      this.region = region;
      this.zone = zone;
      this.minNumReplicas = minNumReplicas;
      this.leaderPreference = leaderPreference;
    }

    public String toJson() {
      if (leaderPreference != null) {
        return String.format(
          "{\"cloud\":\"%s\",\"region\":\"%s\",\"zone\":\"%s\"," +
            "\"min_num_replicas\":%d,\"leader_preference\":%d}",
          cloud, region, zone, minNumReplicas, leaderPreference
        );
      } else {
        return String.format(
          "{\"cloud\":\"%s\",\"region\":\"%s\",\"zone\":\"%s\",\"min_num_replicas\":%d}",
          cloud, region, zone, minNumReplicas
        );
      }
    }

  }

  /**
   * Class representing a tablespace. A tablespace is composed of the following:
   * 1. Name of the tablespace.
   * 2. Number of replicas.
   * 3. A list of placement blocks.
   */
  public static class Tablespace {
    final String name;
    final int numReplicas;
    final List<PlacementBlock> placementBlocks;

    Tablespace(String name, int numReplicas, List<PlacementBlock> placementBlocks) {
      this.name = name;
      this.numReplicas = numReplicas;
      this.placementBlocks = placementBlocks;
    }

    public String toJson() {
      String placementBlocksJson = placementBlocks.stream()
        .map(PlacementBlock::toJson)
        .collect(Collectors.joining(","));

      return String.format("{\"num_replicas\":%d,\"placement_blocks\":[%s]}", numReplicas,
        placementBlocksJson);
    }

    /**
     * Returns the SQL command to create this tablespace.
     */
    private String getCreateCmd() {
      return String.format("CREATE TABLESPACE %s WITH (replica_placement='%s')", name,
        this.toJson());
    }

    /**
     * Executes the command to create this tablespace.
     */
    public void create() {
      try (Statement setupStatement = connection.createStatement()) {
        setupStatement.execute(this.getCreateCmd());
      } catch (SQLException e) {
        LOG.error("Unexpected exception while creating tablespace: ", e);
        fail("Unexpected exception while creating tablespace");
      }
    }
  }
}
