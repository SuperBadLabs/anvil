-- SQLite doesn't support DROP COLUMN before 3.35; recreate the table.
CREATE TABLE anvil_builds_new (
  job_name TEXT NOT NULL, number INTEGER NOT NULL,
  result TEXT, building INTEGER NOT NULL DEFAULT 1,
  started_at TEXT NOT NULL, ended_at TEXT, duration_ms INTEGER,
  console_log TEXT, effects_edn TEXT, parameters_edn TEXT,
  PRIMARY KEY (job_name, number)
);
--;;
INSERT INTO anvil_builds_new
  SELECT job_name, number, result, building, started_at, ended_at,
         duration_ms, console_log, effects_edn, parameters_edn
  FROM anvil_builds;
--;;
DROP TABLE anvil_builds;
--;;
ALTER TABLE anvil_builds_new RENAME TO anvil_builds;
