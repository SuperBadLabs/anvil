-- Anvil's build history. effects_edn is a serialized EDN vector of the
-- dispatcher's side-effects tuples; parameters_edn similarly serializes
-- the build-parameter map.
CREATE TABLE IF NOT EXISTS anvil_builds (
  job_name        TEXT NOT NULL,
  number          INTEGER NOT NULL,
  result          TEXT,
  building        INTEGER NOT NULL DEFAULT 1,
  started_at      TEXT NOT NULL,
  ended_at        TEXT,
  duration_ms     INTEGER,
  console_log     TEXT,
  effects_edn     TEXT,
  parameters_edn  TEXT,
  PRIMARY KEY (job_name, number),
  FOREIGN KEY (job_name) REFERENCES anvil_jobs(name) ON DELETE CASCADE
);
--;;
CREATE INDEX IF NOT EXISTS idx_anvil_builds_job_number
  ON anvil_builds (job_name, number DESC);
