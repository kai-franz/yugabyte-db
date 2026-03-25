--
-- Test that expression pushdown works correctly for AT TIME ZONE with named
-- timezones such as 'UTC'.  Previously, pushing down an expression like
-- EXTRACT(HOUR FROM col AT TIME ZONE 'UTC') would fail with
-- "time zone UTC not recognized" because the tserver's YBGate environment
-- was not initialized with a valid postgres executable path, preventing
-- tzload() from locating the IANA timezone data files.
--

-- For environment-independent output
SET timezone TO 'UTC';

CREATE TABLE tz_pushdown_test(k INT PRIMARY KEY, ts TIMESTAMPTZ);
-- 2026-03-25 02:57:29 UTC (hour=2 in UTC)
INSERT INTO tz_pushdown_test VALUES (1, '2026-03-25 02:57:29+00');
-- 2026-03-25 00:30:00 UTC (hour=0 in UTC)
INSERT INTO tz_pushdown_test VALUES (2, '2026-03-25 00:30:00+00');

-- Verify pushdown is enabled
SET yb_enable_expression_pushdown TO on;

-- AT TIME ZONE with named timezone 'UTC' should work with pushdown enabled.
-- This was broken: "time zone UTC not recognized" when pushdown was on.
-- Should return only row 1 (hour = 2 >= 1).
SELECT k FROM tz_pushdown_test WHERE EXTRACT(HOUR FROM ts AT TIME ZONE 'UTC') >= 1 ORDER BY k;

-- AT TIME ZONE with POSIX-style offset '+00' should also work.
-- Should return only row 1 (hour = 2 >= 1).
SELECT k FROM tz_pushdown_test WHERE EXTRACT(HOUR FROM ts AT TIME ZONE '+00') >= 1 ORDER BY k;

-- Verify results match when pushdown is disabled.
SET yb_enable_expression_pushdown TO off;

SELECT k FROM tz_pushdown_test WHERE EXTRACT(HOUR FROM ts AT TIME ZONE 'UTC') >= 1 ORDER BY k;
SELECT k FROM tz_pushdown_test WHERE EXTRACT(HOUR FROM ts AT TIME ZONE '+00') >= 1 ORDER BY k;

SET yb_enable_expression_pushdown TO on;

DROP TABLE tz_pushdown_test;
