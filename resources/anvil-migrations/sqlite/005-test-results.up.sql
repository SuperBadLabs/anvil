-- Anvil test_results — per-test-case rows ingested from surefire XML
-- by anvil.compat.junit/scan-build-artifacts (T1.2).
--
-- One row per <testcase> across all suites in the build's surefire
-- output. Suite-level aggregates can be derived by GROUP BY on
-- (job_name, number, test_class); we don't denormalize the suite
-- header because the suite IR isn't useful to the dashboard beyond
-- name + counts, and the counts are trivially summable.
--
-- Test trend queries (the board T1.4 "30-build sparkline of pass
-- rate") go through the (job_name, test_id) index — a single test's
-- result history across builds is a single index scan.
--
-- FK to anvil_builds (job_name, number) — matches the composite PK
-- from migration 002. CASCADE delete so dropping a build wipes its
-- test rows.

CREATE TABLE IF NOT EXISTS anvil_test_results (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  job_name      TEXT NOT NULL,
  build_number  INTEGER NOT NULL,
  test_id       TEXT NOT NULL,        -- classname#name
  test_name     TEXT NOT NULL,
  test_class    TEXT NOT NULL,
  status        TEXT NOT NULL CHECK (status IN ('passed','failed','errored','skipped')),
  duration_ms   INTEGER NOT NULL DEFAULT 0,
  failure_msg   TEXT,
  failure_type  TEXT,
  failure_trace TEXT,
  scanned_at    TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (job_name, build_number) REFERENCES anvil_builds (job_name, number) ON DELETE CASCADE
);
--;;
-- Build-page lookup: "give me every test from build (job, number)"
CREATE INDEX IF NOT EXISTS idx_anvil_test_results_build
  ON anvil_test_results (job_name, build_number);
--;;
-- Trend / sparkline lookup: "give me this test's history across builds"
CREATE INDEX IF NOT EXISTS idx_anvil_test_results_test_id
  ON anvil_test_results (job_name, test_id, build_number DESC);
--;;
-- Per-build summary header. Avoids COUNT-with-CASE-WHEN over the per-
-- case rows on every dashboard render. One row per scanned build;
-- parse_errors > 0 signals the UI to show a "N of M reports parsed"
-- diagnostic.
CREATE TABLE IF NOT EXISTS anvil_test_summaries (
  job_name      TEXT NOT NULL,
  build_number  INTEGER NOT NULL,
  tests         INTEGER NOT NULL DEFAULT 0,
  passed        INTEGER NOT NULL DEFAULT 0,
  failed        INTEGER NOT NULL DEFAULT 0,
  errored       INTEGER NOT NULL DEFAULT 0,
  skipped       INTEGER NOT NULL DEFAULT 0,
  duration_ms   INTEGER NOT NULL DEFAULT 0,
  parse_errors  INTEGER NOT NULL DEFAULT 0,
  scanned_at    TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (job_name, build_number),
  FOREIGN KEY (job_name, build_number) REFERENCES anvil_builds (job_name, number) ON DELETE CASCADE
);
