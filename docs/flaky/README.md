# Flaky-test detection (v0.4 — T1)

Status: **stub** (T0.5 placeholder). Real docs land at T1.6 + T7.5.

## What this feature ships

A test that **failed an earlier attempt but passed a later attempt in
the same build** gets tagged `:flaky? true` in `test_results`. The job
page surfaces a "Flaky tests this build" widget; a global dashboard at
`/flaky` lists the top flaky tests across the instance with a 30-build
pass-rate sparkline.

Per the AV4-3 decision, passed-on-retry is the **only** definition at
v0.4.0 — no statistical models, no time-series anomaly detection.
Statistical-flake layering can come in v0.4.x if demand justifies it.

## Substrate

- T1 JUnit infra from v0.3 (`anvil.compat.junit` + `test_results` table)
- `retry(N) { steps }` IR from TX11A — the loop already exists; we just
  need each attempt to carry its index into `test_results` rows

## Files (planned, not yet written)

- `src/anvil/flaky.clj` — passed-on-retry analysis (T1.1)
- `src/anvil/web/views/flaky.clj` — dashboard + per-job widget (T1.3)
- `resources/migrations/00NN-add-flaky-columns.up.sql` — schema (T1.2)
- `test/anvil/flaky_test.clj` — 4 retry-shape fixtures (T1.6)

## Not in scope

- Per-test "always-flaky-job-wide" aggregation beyond a 30-build window
- Quarantine / auto-skip of flaky tests (out of scope for v0.4)
- Cross-job flake correlation
