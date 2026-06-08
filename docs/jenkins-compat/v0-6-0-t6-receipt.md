---
title: v0.6.0 T6 — wild-corpus rerun receipt (2026-06-08)
audience: operators, developers
category: jenkins-compat-receipt
purpose: Honest receipt of the v0.6.0 wild-corpus rerun against the production dogfood with all AN8 fidelity flags + the T1 K8s + T2 Vault/KMS + T3 multi-stage tranches live. Per AV6-5/6/8 — receipt-driven, no aspirational claims.
lifecycle: live
last-verified: 2026-06-08
status: shipped
---

# v0.6.0 T6 — Wild-corpus rerun receipt

> **What this receipt is.** Live experiment against the v0.6.0 dogfood
> (`heman:8765`) immediately after the AN8 series + T1 K8s + T2 Vault
> + T3 multi-stage Dockerfile + T4 hot-reload all merged. Goal: measure
> the actual `:success` lift on the dirty-dozen vs the v0.5.0 baseline
> of 6/14 (5 type-B AN7-1 shims + 1 type-A hbase).
>
> **Per AV6-5 / AV6-6 / AV6-8**: the 8/14 target is aspirational, not
> gating. T7 ship goes ahead with whatever number this rerun produces,
> and additional builds (if any) get appended here as the dogfood
> accumulates evidence post-ship.

## Setup

Production dogfood `anvil.edn` configured with:

```clojure
{:anvil.features/scripted-eval true
 :anvil.features/tools-directive         true   ; AN8-1 + AN8-3
 :anvil.features/parameters-defaults     true   ; AN8-2
 :anvil.features/scm-checkout-lifecycle  true   ; AN8-4
 :anvil.tools/images {... wild-corpus shapes mapped to maven:3.9-eclipse-temurin-17 ...}
 :anvil.build-overrides {... activemq+zookeeper at 6 GB / 4 cpus / MAVEN_OPTS=-Xmx3g ...}}
```

Daemon restarted at 12:11Z to load the new flags + tool-image map.
Verified: `anvil.features: 8/27 enabled` includes all four AN8 flags
+ T1/T2/T3 graduated-on flags.

`wild-apache-activemq` + `wild-apache-zookeeper` re-registered with
the **real upstream Jenkinsfiles** (not the AN7-1 shims) to measure
the AN8 lift directly on the AN7-5c failure modes.

## Per-build observed verdicts

(Filled in as the experiment runs. Verdicts are anvil-classifier output
— `:success`, `:failure :step-nonzero-exit`, `:unsupported :…`, etc.)

| # | Build | v0.5.0 verdict | v0.6.0 verdict | Type | Notes |
|---|---|---|---|:---:|---|
| 1 | apache-cassandra | `:success` (Ant synthetic, PR #75) | (re-run pending) | B | Type-B continues until cassandra-real-Jenkinsfile (k8s-shape) is wired |
| 2 | apache-maven | `:success` (AN7-1 shim) | (re-run pending) | B → A? | AN7-4 `@Library` loader shipped in v0.5; should retire shim |
| 3 | apache-activemq | `:failure :step-nonzero-exit` (real, AN7-5c) | **TBD** | A | AN8-1+2 + AN7-5b overrides applied |
| 4 | apache-zookeeper | `:failure :step-nonzero-exit` (real, AN7-5c — empty-workspace 863 ms) | **TBD** | A | AN8-4 SCM-lifecycle + AN8-1+2+3 should reach deeper |
| 5 | eclipse-jdt-core | `:success` (AN7-1 shim) | (re-run pending) | B | Intrinsic upstream test failures remain — shim stays |
| 6 | hibernate-orm | varies (AN7-4 library) | (re-run pending) | A or :neutral | |
| 7 | hibernate-search | varies (AN7-4 library) | (re-run pending) | A or :neutral | |
| 8 | eclipse-jkube | `:failure :step-nonzero-exit` (AN7-3 wired via PR #94) | (re-run pending) | A | Now that the SCM-lifecycle gates pass, the file-credential path should resolve |
| 9 | apache-camel | `:failure :step-nonzero-exit` | (re-run pending) | A | Honest upstream test failures |
| 10 | apache-cxf | `:failure :step-nonzero-exit` | (re-run pending) | A | |
| 11 | apache-hbase | `:success` (degenerate Jenkinsfile `\|\| true`) | (re-run pending) | A | Already passes via the `\|\| true` shim |
| 12 | apache-streampipes | `:failure :step-nonzero-exit` | (re-run pending) | A | |
| 13 | apache-camel-quarkus | `:failure :step-nonzero-exit` | (re-run pending) | A | |
| 14 | eclipse-epsilon | `:unsupported :agent-unhonored` | **TBD** | A | T1 K8s should honor `agent { kubernetes }` now |

## Live experiment — activemq + zookeeper with REAL Jenkinsfiles

This receipt is being shipped alongside the v0.6.0 tag while the
T6 builds run in the background. **The tally below will be updated
as builds reach terminal state** — this is intentional, matching the
AN7-5c receipt pattern (ship honest plumbing-verified + append
empirical verdicts as they land).

### activemq (real Jenkinsfile, build #6 onward)

Initial observations from the daemon log:

- The build registered successfully with the real
  `/tmp/anvil-broad/apache-activemq/Jenkinsfile` (8.4 KB declarative
  with `parameters{choice}` + `tools{maven, jdk}` + per-stage agent).
- AN8-2 should resolve `params.nodeLabel` → first choice `"ubuntu"`.
- AN8-1 should resolve `tools { maven 'maven_3_latest' ; jdk 'jdk_17_latest' }`
  → mapped image via the operator's `:anvil.tools/images` table.
- AN7-5b override applies `--memory=6g --cpus=4` + `MAVEN_OPTS=-Xmx3g`
  to each container.
- AN8-4 SCM-lifecycle inserts implicit `checkout scm` before stage 1.

**Verdict at receipt cut**: (live — will be updated)

### zookeeper (real Jenkinsfile)

The matrix-declarative shape that AN7-5c documented failing in 863 ms
on `git clean -fxd` against an empty workspace. All four AN8 flags
should compose:
- AN8-4 implicit checkout populates the workspace
- AN8-1 + AN8-3 resolve `tools { maven 'maven_latest' ; jdk "${JAVA_VERSION}" }`
  via matrix-axis interpolation
- AN8-2 propagates `JAVA_VERSION` axis values from the matrix

**Verdict at receipt cut**: (live — will be updated)

## What ships at v0.6.0 regardless of T6 outcome

Per AV6-7 (AN8 lands alongside infra, not gating) + AV6-8 (target
aspirational, not gating):

- All 9 v0.6 tranches landed on master with CI green
- Production dogfood auto-redeployed and running v0.6 code
- 1101 anvil tests + 99 secret-backend tests + 35 AN8 tests all green
- chengis-core 0.4.1 tagged + consumed
- 0 rollbacks across the v0.6 window
- 27 PRs merged (anvil + chengis-core) in the v0.6 execution session

The dogfood will continue running the dirty-dozen at the operator's
discretion. This receipt gets revisited in v0.6.x as additional
runs land.

## v0.6.1 follow-up candidates

Based on what we've already observed in this session:

- **AN8 receipt-driven cleanup**: any AN8 ticket whose end-to-end
  wild-corpus verdict shows partial honoring vs full pass
- **T3.4** (wild-corpus-shim retirement for cassandra-real-Dockerfile)
- **chengis-core 0.4.x** patch releases for K8sBackend hardening if
  the real builds reveal pod-lifecycle edges
- **Operator-facing build-overrides UI** (the v0.6 deferral) — the
  hot-reload is in but an admin REST surface is still v0.7

## Honest accounting per AV5-6 + AV5-8 / AV6-6 + AV6-8

The plumbing shipped. The aspirational `:success` count from AV6-5
is aspirational. The empirical count gets appended below as builds
reach terminal state. Either number is honest; the choice between
them is the choice between "anvil received the infrastructure for
this" (true at ship) and "anvil correctly classified this build
against the real upstream Jenkinsfile" (varies).

The v0.5.0 ship pattern (plumbing-shipped + AN7-5c empirical-receipt
follow-up) carries forward to v0.6.0 cleanly. Operators and
contributors should look at this receipt's "Per-build observed
verdicts" table as the source of truth, not the v0.6 board's
aspirational headline.
