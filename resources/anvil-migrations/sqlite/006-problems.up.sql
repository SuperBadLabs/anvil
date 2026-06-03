-- Anvil problems — per-diagnostic rows emitted by the log-tail problem
-- matcher (T2.2). One row per matched diagnostic.
--
-- Indexed on (job_name, build_number, severity) for the Problems-tab
-- view which renders severity-filtered lists. Cascade delete on builds.

CREATE TABLE IF NOT EXISTS anvil_problems (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  job_name      TEXT NOT NULL,
  build_number  INTEGER NOT NULL,
  log_seq       INTEGER NOT NULL,        -- line sequence in the build's log
  source        TEXT NOT NULL,           -- matcher owner (gcc/javac/::workflow/...)
  severity      TEXT NOT NULL CHECK (severity IN ('error','warning','note','info')),
  file_path     TEXT,
  line_no       INTEGER,
  column_no     INTEGER,
  message       TEXT NOT NULL,
  found_at      TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (job_name, build_number) REFERENCES anvil_builds (job_name, number) ON DELETE CASCADE
);
--;;
CREATE INDEX IF NOT EXISTS idx_anvil_problems_build
  ON anvil_problems (job_name, build_number, severity);
--;;
-- Per-build summary (denormalized counts so the Problems-tab pill row
-- renders without aggregating per page load).
CREATE TABLE IF NOT EXISTS anvil_problem_summaries (
  job_name      TEXT NOT NULL,
  build_number  INTEGER NOT NULL,
  errors        INTEGER NOT NULL DEFAULT 0,
  warnings      INTEGER NOT NULL DEFAULT 0,
  notes         INTEGER NOT NULL DEFAULT 0,
  infos         INTEGER NOT NULL DEFAULT 0,
  updated_at    TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (job_name, build_number),
  FOREIGN KEY (job_name, build_number) REFERENCES anvil_builds (job_name, number) ON DELETE CASCADE
);
