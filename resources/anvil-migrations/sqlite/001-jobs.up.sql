-- Anvil's job registry. One row per registered Jenkinsfile-as-a-job.
CREATE TABLE IF NOT EXISTS anvil_jobs (
  name                  TEXT PRIMARY KEY,
  jenkinsfile_source    TEXT NOT NULL,
  buildable             INTEGER NOT NULL DEFAULT 1,
  color                 TEXT NOT NULL DEFAULT 'notbuilt',
  last_build            INTEGER,
  last_successful_build INTEGER,
  last_failed_build     INTEGER,
  created_at            TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at            TEXT NOT NULL DEFAULT (datetime('now'))
);
