DROP INDEX IF EXISTS idx_anvil_builds_parent;
--;;
-- SQLite supports DROP COLUMN as of 3.35+; sqlite-jdbc 3.51 bundles
-- a modern SQLite.
ALTER TABLE anvil_builds DROP COLUMN matrix_axes_edn;
--;;
ALTER TABLE anvil_builds DROP COLUMN parent_build_number;
