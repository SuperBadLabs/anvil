-- Per-job SCM column: scm_url. Split across 008/009/010 (one ALTER per
-- migration file) because migratus 1.x batches multi-statement files
-- via SQLite's executeBatch, and back-to-back ALTERs on the same table
-- trip the driver's "prepared statement has been finalized" error.
ALTER TABLE anvil_jobs ADD COLUMN scm_url TEXT;
