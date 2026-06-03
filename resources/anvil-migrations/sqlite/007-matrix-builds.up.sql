-- v0.3 T4.3 — matrix-build parent/child relationship + per-cell axes.
-- A matrix build splits into N child cell-builds. The parent build
-- holds no commands of its own; it's a holder for the cell rollup.
--
-- parent_build_number is NULL for a normal (non-matrix) build, AND
-- for the matrix-parent itself. For a matrix child, it points at the
-- parent's number within the same job.
-- matrix_axes_edn is a serialized {axis-name → value} map for the
-- cell; NULL for non-matrix builds.

ALTER TABLE anvil_builds ADD COLUMN parent_build_number INTEGER;
--;;
ALTER TABLE anvil_builds ADD COLUMN matrix_axes_edn TEXT;
--;;
-- Lookup: \"give me every child of this parent build\"
CREATE INDEX IF NOT EXISTS idx_anvil_builds_parent
  ON anvil_builds (job_name, parent_build_number);
