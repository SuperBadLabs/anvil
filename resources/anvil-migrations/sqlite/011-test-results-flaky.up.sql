-- Anvil v0.4 T1.2 — flaky-test substrate: per-attempt rows on test_results.
--
-- Adds three columns on anvil_test_results so the same (job, build, test_id)
-- can carry multiple rows, one per retry attempt within a single build.
-- anvil.flaky/detect (T1.1) consumes the per-attempt rows: a test that
-- failed an earlier attempt but passed a later attempt in the same build
-- is flagged :flaky? true.
--
--   attempt_number  — 1-based attempt index within the build.  Default 1
--                     keeps existing rows (and the single-attempt scan
--                     path in record-build-results!) valid.
--   flaky_bool      — set by anvil.flaky/detect (T1.1) after all attempts
--                     for a build are scanned. NULL until the first
--                     detect pass; 0/1 thereafter.  Convention: every
--                     row for a (build, test) carries the same value
--                     once flagged — the analyzer writes back to all
--                     attempts so a single SELECT can answer "is this
--                     test flaky in this build" without GROUP BY.
--   retry_count     — number of retry attempts the test made within
--                     this build.  Always = (max(attempt_number) - 1)
--                     across the (build, test) group.  Denormalized so
--                     the dashboard widget doesn't aggregate per render.
--
-- The existing INDEX idx_anvil_test_results_test_id covers (job, test,
-- build DESC); adding attempt_number to the SELECT doesn't change the
-- access pattern.  A new index covers the per-build flaky query.

ALTER TABLE anvil_test_results
  ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1;
--;;
ALTER TABLE anvil_test_results
  ADD COLUMN flaky_bool INTEGER;
--;;
ALTER TABLE anvil_test_results
  ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
--;;
-- Per-build flaky lookup: "give me every flaky test in this build".
-- Partial index keeps it small — most rows have flaky_bool NULL or 0.
CREATE INDEX IF NOT EXISTS idx_anvil_test_results_flaky
  ON anvil_test_results (job_name, build_number)
  WHERE flaky_bool = 1;
