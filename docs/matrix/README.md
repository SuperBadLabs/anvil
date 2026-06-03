# Declarative matrix builds (v0.3 — T4)

Status: **stub** (T0.5 placeholder). Real docs land at T4.8 / T8.5.

## What this feature ships

A Jenkinsfile that declares a `matrix { axes { … } stages { … } }`
block builds N parallel child-builds cross-producting all axes, then
displays them as a per-cell status grid on a single build page.

## Feature flag

```clojure
;; anvil.edn
{:anvil.features/matrix true}
```

## Bounding decisions

- **AV3-5**: Matrix is declarative-only in v0.3.0. Dynamic matrix
  from Groovy scripts needs the scripted-pipeline runtime — separate
  scope (likely v0.4 alongside the Jenkins-self build effort).
- **R4**: Cap matrix size at 100 cells by default; configurable via
  `:anvil.matrix/max-cells`. Fail fast above with a clear error.

## Files (when T4 lands)

- `src/anvil/compat/jenkins/matrix_parser.clj` — parser extension
- `src/anvil/compat/jenkins/matrix_expander.clj` — cross-product +
  exclusion expansion at build-trigger time
- `src/anvil/web/views/matrix.clj` — grid view
- DB migration: `builds` table gets `parent_build_id` + `matrix_axes`
- 3 Jenkinsfile sample fixtures (2-axis simple, 3-axis w/ excludes,
  1-axis edge case)

## SSE event

Existing `:build-done` flows up — no new topic required, but the
parent matrix-grid widget subscribes per-child and lights cells live.

## Owner

T4 tranche owner.
