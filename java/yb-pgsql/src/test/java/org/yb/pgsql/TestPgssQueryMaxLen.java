// Copyright (c) YugabyteDB, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied. See the License for the specific language governing permissions and limitations
// under the License.
//

package org.yb.pgsql;

import static org.yb.AssertionWrappers.assertEquals;
import static org.yb.AssertionWrappers.assertFalse;
import static org.yb.AssertionWrappers.assertTrue;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.YBTestRunner;

/**
 * Tests for the pg_stat_statements.yb_query_text_max_length GUC, which caps the
 * length of individual query text entries stored in pg_stat_statements.  This
 * prevents large binary literals (e.g. bytea values embedded directly in SQL)
 * from being retained verbatim when normalization is skipped or bypassed,
 * protecting sensitive data such as PII.
 */
@RunWith(value = YBTestRunner.class)
public class TestPgssQueryMaxLen extends BasePgSQLTest {
  private static final Logger LOG = LoggerFactory.getLogger(TestPgssQueryMaxLen.class);

  /**
   * Verifies that when yb_query_text_max_length is set, queries whose text
   * exceeds the limit are stored with a "..." truncation suffix.
   */
  @Test
  public void testLongQueryTextIsTruncated() throws Exception {
    final int maxLen = 50;

    try (Statement stmt = connection.createStatement()) {
      stmt.execute("SELECT pg_stat_statements_reset()");

      // Configure a small per-entry limit for this session.
      stmt.execute("SET pg_stat_statements.yb_query_text_max_length = " + maxLen);

      // Execute a utility statement (SET) whose raw text exceeds maxLen.
      // Utility statements are stored verbatim (no normalization), so this
      // exercises the length-cap path directly.
      String longValue = IntStream.range(0, 60).mapToObj(i -> "X").collect(Collectors.joining());
      stmt.execute("SET application_name = '" + longValue + "'");

      ResultSet rs = stmt.executeQuery(
          "SELECT query FROM pg_stat_statements WHERE query LIKE 'SET application_name%'");

      assertTrue("Expected a pg_stat_statements row for SET application_name", rs.next());
      String storedQuery = rs.getString("query");

      assertEquals("Stored query length should equal yb_query_text_max_length",
          maxLen, storedQuery.length());
      assertTrue("Stored query should end with '...' truncation marker",
          storedQuery.endsWith("..."));
    }
  }

  /**
   * Verifies that a short query (below the limit) is stored without truncation.
   */
  @Test
  public void testShortQueryTextIsNotTruncated() throws Exception {
    final int maxLen = 200;

    try (Statement stmt = connection.createStatement()) {
      stmt.execute("SELECT pg_stat_statements_reset()");

      stmt.execute("SET pg_stat_statements.yb_query_text_max_length = " + maxLen);

      // A simple DML query; its normalized form ($1 + $2) is well below 200 bytes.
      stmt.execute("SELECT 1 + 2");

      ResultSet rs = stmt.executeQuery(
          "SELECT query FROM pg_stat_statements WHERE query LIKE 'SELECT%+%'");

      assertTrue("Expected a pg_stat_statements row for SELECT 1 + 2", rs.next());
      String storedQuery = rs.getString("query");

      assertFalse("Short query should not end with '...'", storedQuery.endsWith("..."));
      assertTrue("Stored query length should be within the limit",
          storedQuery.length() <= maxLen);
    }
  }

  /**
   * Verifies that a DML query with a large literal is stored as a short
   * normalized form ($1) that is not truncated, even when the original query
   * text was very long.  This is the primary defense: normalization replaces
   * literals before the length check, so correctly normalized queries are
   * always compact.
   */
  @Test
  public void testDmlWithLargeLiteralIsNormalized() throws Exception {
    final int maxLen = 200;

    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE IF NOT EXISTS pgss_blob_test (id SERIAL, data TEXT)");
      stmt.execute("SELECT pg_stat_statements_reset()");

      stmt.execute("SET pg_stat_statements.yb_query_text_max_length = " + maxLen);

      // Build a DML query with a large embedded string literal (> maxLen bytes).
      // After normalization the stored text becomes "INSERT INTO pgss_blob_test
      // (data) VALUES ($1)" which is well within maxLen.
      String largeLiteral = IntStream.range(0, 500)
          .mapToObj(i -> "A")
          .collect(Collectors.joining());
      stmt.execute("INSERT INTO pgss_blob_test (data) VALUES ('" + largeLiteral + "')");

      ResultSet rs = stmt.executeQuery(
          "SELECT query FROM pg_stat_statements "
          + "WHERE query LIKE 'INSERT INTO pgss_blob_test%'");

      assertTrue("Expected a pg_stat_statements row for INSERT", rs.next());
      String storedQuery = rs.getString("query");

      // The normalized query should be compact and not contain the large literal.
      assertFalse("Normalized DML should not be truncated", storedQuery.endsWith("..."));
      assertTrue("Normalized query should be within the length limit",
          storedQuery.length() <= maxLen);
      assertFalse("Normalized query must not contain the large literal",
          storedQuery.contains(largeLiteral.substring(0, 20)));
    }
  }

  /**
   * Verifies that setting yb_query_text_max_length = -1 disables the cap and
   * long query texts are stored in full.
   */
  @Test
  public void testDisabledLimitAllowsLongText() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("SELECT pg_stat_statements_reset()");

      // Disable the per-entry length cap.
      stmt.execute("SET pg_stat_statements.yb_query_text_max_length = -1");

      String longValue = IntStream.range(0, 80).mapToObj(i -> "Y").collect(Collectors.joining());
      stmt.execute("SET application_name = '" + longValue + "'");

      ResultSet rs = stmt.executeQuery(
          "SELECT query FROM pg_stat_statements WHERE query LIKE 'SET application_name%'");

      assertTrue("Expected a pg_stat_statements row", rs.next());
      String storedQuery = rs.getString("query");

      // With no limit, the query should not be truncated.
      assertFalse("Query should not be truncated when limit is disabled",
          storedQuery.endsWith("..."));
      assertTrue("Full query text should be stored",
          storedQuery.contains(longValue));
    }
  }
}
