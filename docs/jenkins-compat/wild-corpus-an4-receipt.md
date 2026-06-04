# Wild-corpus matrix: AN4 honest-classification receipt

**Date**: 2026-06-04
**Anvil version**: 0.3.0 with AN4-1 / AN4-2 / AN4-3 / AN4-4 / AN4-5 merged
**chengis-core version**: 0.2.0-SNAPSHOT with CC2-EX1a/b + EX2 + EX3a + EX4 + EX5 merged

## Why this exists

Operation Brasstacks Phase 2 (AN4-1 through AN4-5) replaced anvil's lossy
`(case status :ok :success :failed :failure :success)` build-result
classification with the honest `chengis.engine.result/classify` from
CC2-EX2. This receipt is the empirical proof against the 15-project
wild-corpus matrix that:

- The 7 anvil-v0.3 **false :SUCCESS** classifications are mechanically
  impossible — each reclassifies to a more honest non-`:success` class
- The two new honest classes (`:neutral`, `:unsupported`) actually fire
  in production against real-world Jenkinsfiles, not just in unit tests
- The new `:rule` and `:explain` fields surface the *why* of every
  non-`:success` build to operators

## Headline number

| | Pre-AN4 | Post-AN4 |
|---|---|---|
| **False :SUCCESS** (builds that produced zero real artifacts but reported green) | **7 / 15** | **0 / 15** |

## Per-project reclassification

| Project | Pre-AN4 | Post-AN4 | Rule | Explain |
|---|---|---|---|---|
| hibernate-orm | SUCCESS ❌ | `:neutral` ✅ | `:no-effects-recorded` | no shell steps ran and no effects were recorded — build's IR walked but did nothing |
| hibernate-search | SUCCESS ❌ | `:neutral` ✅ | `:no-effects-recorded` | (same) |
| eclipse-jdt-core | FAILURE | `:failure` ✅ | `:step-nonzero-exit` | 1 shell step(s) exited non-zero — last exit: 1 |
| eclipse-epsilon | SUCCESS ❌ | `:unsupported` ✅ | `:agent-unhonored` | agent shape(s) this executor cannot honor: agent.kubernetes |
| eclipse-jkube | FAILURE | `:failure` ✅ | `:credential-unresolved` | required credential(s) could not be resolved: secret-subkeys.asc |
| apache-camel | SUCCESS ❌ | `:neutral` ✅ | `:no-effects-recorded` | (same) |
| apache-camel-quarkus | FAILURE | `:failure` ✅ | `:step-nonzero-exit` | 1 shell step(s) exited non-zero — last exit: 1 |
| apache-maven | SUCCESS ❌ | `:unsupported` ✅ | `:unsupported-construct` | IR contains construct(s) this executor cannot honor: step.mavenBuild |
| apache-zookeeper | SUCCESS ❌ | `:neutral` ✅ | `:no-effects-recorded` | (same) |
| apache-cxf | SUCCESS ❌ | `:neutral` ✅ | `:no-effects-recorded` | (same) |
| apache-activemq | FAILURE | `:failure` ✅ | `:step-nonzero-exit` | 1 shell step(s) exited non-zero — last exit: 1 |
| apache-streampipes | FAILURE | `:failure` ✅ | `:step-nonzero-exit` | 1 shell step(s) exited non-zero — last exit: 1 |
| apache-cassandra | TIMEOUT | TIMEOUT (harness) | — | harness wait-for ran past 180s before terminal state reached; not a classifier outcome |
| apache-hbase | FAILURE | `:failure` ✅ | `:step-nonzero-exit` | 1 shell step(s) exited non-zero — last exit: 1 |
| eclipse-mojarra | TIMEOUT | TIMEOUT (harness) | — | (same — `agent { kubernetes }` with YAML, harness times out before classifier runs) |

## Distribution

Post-AN4 result classes across the 13 builds that reached terminal state:

| Class | Count | Rule breakdown |
|---|---|---|
| `:neutral` | 5 | 5 × `:no-effects-recorded` |
| `:unsupported` | 2 | 1 × `:agent-unhonored` (kubernetes), 1 × `:unsupported-construct` (mavenBuild plugin step) |
| `:failure` | 6 | 5 × `:step-nonzero-exit`, 1 × `:credential-unresolved` |
| `:success` | 0 | — |

Pre-AN4 distribution across the same 13 builds:

| Class | Count |
|---|---|
| `:success` (false) | 7 |
| `:failure` | 6 |

## The seven false-SUCCESS shapes, mechanically

Each pre-AN4 false `:success` reclassifies through a specific AN4 mechanism:

| Project | Mechanism | Where it fires |
|---|---|---|
| hibernate-orm | AN4-1 classifier — empty walk → `:neutral` | `effects->observation` saw zero `[:sh]` effects |
| hibernate-search | AN4-1 — empty walk → `:neutral` | (same) |
| eclipse-epsilon | AN4-2 — `agent { kubernetes }` → `[:agent/degraded]` effect → `:unsupported` | `unhonored-container-agent-shape` returned `:kubernetes` |
| apache-camel | AN4-1 — empty walk → `:neutral` | (same as hibernate) |
| apache-maven | classifier `:unsupported-construct` rule via existing `[:unknown {:name "mavenBuild"}]` effect | apache-maven's Jenkinsfile uses the maven-plugin-specific `mavenBuild` step which isn't in anvil's adapter registry |
| apache-zookeeper | AN4-1 — empty walk → `:neutral` | (same) |
| apache-cxf | AN4-1 — empty walk → `:neutral` | (same) |

## eclipse-jkube: the new credential-unresolved path

Pre-AN4 eclipse-jkube was `:failure` (one sh exit non-zero), but the
real silent regression was that `gpg --import "${GPG_KEY}"` was running
with `GPG_KEY=""` because the `withCredentials([file(credentialsId:
'secret-subkeys.asc', variable: 'GPG_KEY')])` block bound the credential
to an empty file. AN4-4's `[:credential-unresolved]` effect for the
missing `secret-subkeys.asc` ID now drives the build to `:failure` with
the rule `:credential-unresolved` — the diagnostic operators actually
need to fix the build.

## What this PR ships

In addition to the empirical run, this PR fixes the Jenkins API surface:

- `views.clj`'s `result->jenkins` now handles `:neutral` and
  `:unsupported`, mapping both to Jenkins-canonical `NOT_BUILT` (the
  closest match in the Jenkins enum)

The `:rule` and `:explain` from the classifier remain available through
the anvil-native API and the build-page UI's banner (AN4-5).

## Artifacts

- `/tmp/anvil-broad/results.pre-an4.edn` — original 2026-06-03 baseline
- `/tmp/anvil-broad/run.post-an4.log` — full harness output (post-AN4)
- `/tmp/anvil-broad/anvil-server.log` — anvil daemon log with per-build
  `[anvil.classify]` INFO lines (the source of truth for this receipt)
- `/tmp/anvil-broad/classify-summary.txt` — extracted classifier outcomes
