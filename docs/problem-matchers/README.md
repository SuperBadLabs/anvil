# Problem matchers — clickable file:line in build logs (v0.3 — T2)

Status: **stub** (T0.5 placeholder). Real docs land at T2.7 / T8.5.

## What this feature ships

Errors in build logs become clickable links to file:line in the anvil
console. Matched diagnostics aggregate into a "Problems" tab on the
build page (severity-filtered, source-clickable).

## Feature flag

```clojure
;; anvil.edn
{:anvil.features/problem-matchers true}
```

## Bounding decisions

- **AV3-3**: GitHub Actions problem-matcher format
  (`::warning file=…,line=…::msg`) is the wire format. Don't invent
  a new format — adopt the existing standard.
- **R2**: Ship a core set hand-tuned (gcc, rustc, mypy, eslint,
  javac, msbuild); community contrib via YAML data files, never code.

## Files (when T2 lands)

- `src/anvil/compat/problem_matchers.clj` — YAML rule loader
- `resources/problem-matchers/*.yml` — rule library (6 tools at v0.3.0)
- `src/anvil/web/log_tail.clj` — extension hooks
- `src/anvil/web/views/problems.clj` — Problems-tab view
- `test/anvil/compat/problem_matchers_test.clj` — 8-tool golden corpus

## SSE event

`:problem-found` on `[:build <job> <n>]` topic. Reserved in T0.4
(`anvil.events.topics/evt-problem-found`).

## Owner

T2 tranche owner.
