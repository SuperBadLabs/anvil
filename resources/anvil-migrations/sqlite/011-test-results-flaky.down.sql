-- Reverse of 011-test-results-flaky.up.sql — drop the partial index
-- first, then the columns.  SQLite DROP COLUMN landed in 3.35 (2021);
-- the bundled xerial/sqlite-jdbc 3.51 is comfortably newer.

DROP INDEX IF EXISTS idx_anvil_test_results_flaky;
--;;
ALTER TABLE anvil_test_results DROP COLUMN retry_count;
--;;
ALTER TABLE anvil_test_results DROP COLUMN flaky_bool;
--;;
ALTER TABLE anvil_test_results DROP COLUMN attempt_number;
