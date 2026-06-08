---
title: AN8-4 — SCM-checkout-before-stage-1 lifecycle
audience: developers, operators
category: jenkins-compat
purpose: Receipt for the v0.6 AN8-4 ticket — declarative pipelines without an explicit `checkout scm` step now get a synthetic implicit checkout prepended to stage 1, matching Jenkins's declarative lifecycle.
lifecycle: live
last-verified: 2026-06-08
status: shipped
---

# AN8-4 — SCM checkout before stage 1

> **Status: shipped.** Closes the universal fidelity bug from the
> [AN7-5c receipt](an7-5c-tuning-experiment-receipt.md): declarative
> pipelines without an explicit `checkout scm` step ran stage 1 against
> an empty workspace and failed in ~800 ms on `git clean -fxd` exit 128.

## Background

Jenkins's declarative-pipeline lifecycle implicitly runs `checkout scm`
between pipeline parsing and stage 1. Scripted pipelines don't —
operators write the explicit `node { checkout scm; ... }` themselves.

Pre-AN8-4 anvil ran a per-job `scm/provision!` upfront in the runner
when the job had `:scm` configured. That handled the happy path but
left the IR dishonest about Jenkins's declarative contract and
provided no second-line defense between parse and stage 1. Real
upstream Jenkinsfiles (zookeeper, activemq) that assume the implicit
checkout — and don't write `checkout scm` themselves — surface the
gap as a confusing `git clean -fxd: exit 128` red herring in stage 1.

## What landed

- New namespace `anvil.compat.jenkins.scm-lifecycle` with pure-data IR
  walkers:
  - `declarative?` — identifies declarative-shaped pipeline IR (no
    `:scripted-pipeline?` marker on `:options`).
  - `needs-implicit-checkout?` — true when declarative AND stage 1 has
    no explicit `:jenkins/checkout` step.
  - `inject-implicit-checkout` — prepends a synthetic
    `{:type :jenkins/checkout :implicit? true :scm <cfg>}` step to
    stage 1's `:steps`. Idempotent.
- Dispatcher's `h-checkout` now branches on `:implicit?`:
  - implicit + `:scm` config + `:workspace` → call
    `scm/provision!` against the configured URL/branch. Failure
    propagates as `{:status :failed :error :scm-checkout-failed}`.
  - implicit + no `:scm` → record `[:checkout {:implicit? true
    :result :skipped :reason :no-scm-configured}]` and return ok
    (preserves pre-AN8-4 behavior for jobs registered without SCM).
  - explicit (`:implicit?` absent) → unchanged from pre-AN8-4
    (effect-only record).
- Runner wires the rewrite between matrix expansion and dispatch,
  gated behind `:anvil.features/scm-checkout-lifecycle`. Also passes
  `:scm` into the build ctx so the dispatcher handler can find it.

## Anti-fragile guarantees

- **Idempotent w/ pre-build provision** — `scm/provision!` already
  short-circuits to `:refreshed` when `.git` exists. The runner's
  upfront `scm/provision!` call still runs (pre-AN8-4 path); the
  implicit-checkout step hits the refresh path, which is a cheap
  `git fetch + reset + clean`.
- **Idempotent w/ re-injection** — re-running
  `inject-implicit-checkout` on already-rewritten IR is a no-op: the
  synthetic step is itself a `:jenkins/checkout`, so
  `explicit-checkout?` finds it and skips.
- **Explicit checkouts win** — if the Jenkinsfile has `checkout scm`
  or `checkout([...])` as the first step of stage 1, no synthetic
  step is injected.
- **Scripted pipelines untouched** — `declarative?` returns false for
  IR tagged `:scripted-pipeline?`. The R5 contract holds (scripted
  operators write their own checkout).
- **Flag-gated** — closed-by-default through the v0.6.x cycle.
  Operators opt in via `:anvil.features/scm-checkout-lifecycle true`
  in `anvil.edn`. Flag flips to default-on once the wild-corpus
  receipt confirms zookeeper et al. reach stage 2.

## Wild-corpus impact

The AN7-5c receipt documented zookeeper's `:failure
:step-nonzero-exit` in 863 ms. With AN8-4 enabled + `:scm` registered
on the job, the synthetic checkout fires before stage 1's `sh "git
clean -fxd"` and the workspace is populated. Zookeeper still has
secondary blockers (AN8-1 `tools{}` directive, AN8-3 matrix-with-tools
composition) before reaching `:success`, but stage 1 no longer fails
in 863 ms on an empty workspace.

## Tests

- `test/anvil/compat/jenkins/scm_lifecycle_test.clj` — 14 tests
  covering the predicate + injection walker, including the real
  zookeeper Jenkinsfile shape.
- `test/anvil/compat/jenkins/dispatcher_test.clj` — 4 new tests for
  `h-checkout` branching (implicit + scm, implicit no scm, implicit
  failed-provision, explicit unchanged).

Full suite: 961 tests / 2790 assertions / 0 failures.

## Follow-ups (out of scope for AN8-4)

- Flip the flag to default-on once the AN8-1 + AN8-3 fidelity tickets
  ship and the AN7-5c receipt re-runs cleanly against zookeeper.
- Honor checkout's `:spec` payload (custom branch/ref via
  `checkout([$class: 'GitSCM', ...])`) on the explicit path.
  Pre-AN8-4 behavior persists: the runner's per-job `:scm` config is
  the only source of truth.
