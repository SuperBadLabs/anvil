-- Anvil build cost records (T2.1).
--
-- Stores the computed monetary cost for each completed build.
-- Cost = build.duration_ms × host_rate / 60_000
-- (duration_ms in milliseconds, rate in per-minute currency units).
--
-- cache_saved_cents estimates how much cache hits saved vs running
-- every step. Populated by the cost recorder when cache effects are
-- present in the build's effect stream.
CREATE TABLE IF NOT EXISTS anvil_build_costs (
  job_name        TEXT NOT NULL,
  build_number    INTEGER NOT NULL,
  host            TEXT NOT NULL DEFAULT 'localhost',
  duration_ms     INTEGER NOT NULL DEFAULT 0,
  rate_per_min    REAL NOT NULL DEFAULT 0.0,
  currency        TEXT NOT NULL DEFAULT 'usd',
  total_cents     INTEGER NOT NULL DEFAULT 0,
  cache_saved_cents INTEGER NOT NULL DEFAULT 0,
  recorded_at     TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (job_name, build_number),
  FOREIGN KEY (job_name, build_number)
    REFERENCES anvil_builds(job_name, number) ON DELETE CASCADE
);
--;;
CREATE INDEX IF NOT EXISTS idx_anvil_build_costs_recorded_at
  ON anvil_build_costs (recorded_at DESC);
