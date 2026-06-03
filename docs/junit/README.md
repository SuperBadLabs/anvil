# JUnit / surefire test reporting (v0.3 — T1)

Status: **stub** (T0.5 placeholder). Real docs land at T1.7 / T8.5.

## What this feature ships

Builds that produce JUnit-XML reports (the surefire format) get a
per-build test-results dashboard: pass/fail counts, failed-test list
with stack traces, sortable-by-duration, 30-build pass-rate sparkline.

## Feature flag

```clojure
;; anvil.edn
{:anvil.features/junit true}
```

Closed-by-default while T1 is in flight. Routes 404 with a
self-explanatory body until the flag flips.

## Bounding decisions

- **AV3-2**: JUnit XML (surefire-compatible) is the canonical test
  report format at v0.3.0. xunit / cargo-json / jest-json parsers
  defer to v0.3.1.
- **R1**: Parse the common subset of surefire dialects first
  (TestNG, JUnit 4, JUnit 5, Maven Surefire 2.x/3.x); document
  unsupported quirks; ship 4 golden corpus samples.

## Files (when T1 lands)

- `src/anvil/compat/junit/parse_xml.clj` — surefire XML → IR
- `src/anvil/compat/junit/scan_build_artifacts.clj` — post-build glob
- `src/anvil/web/views/test_results.clj` — Hiccup dashboard
- `resources/migrations/*-test-results.sql` — DB schema
- `test/anvil/compat/junit/*` — golden corpus

## SSE event

`:test-completed` on `[:build <job> <n>]` topic. Reserved in T0.4
(`anvil.events.topics/evt-test-completed`).

## Owner

T1 tranche owner.
