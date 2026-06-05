# Wild-corpus matrix — honest reading

**Date**: 2026-06-05
**Supersedes**: the original AN4-only framing of this document. The
original framed "0/15 false SUCCESS = victory" as the headline; that
was scaffolding mistaken for receipt. The honest reading is below.

## Two truths, both required

| | Pre-AN4 | Post-AN4 | Post-AN5-1 |
|---|---|---|---|
| **False :SUCCESS** (vacuous green) | 7 / 15 | 0 / 15 | 0 / 15 |
| **Real artifacts produced** | 0 / 15 | 0 / 15 | **still 0 / 15** |

AN4 fixed the lying. It did NOT fix the not-building.

Both numbers matter:

- The first number is the **honesty** axis. anvil v0.3 was reporting
  green for builds that did nothing. AN4 closed that. 0/15 false
  SUCCESS, mechanically — the classifier can no longer return :success
  for a vacuous walk.
- The second number is the **utility** axis. A CI that never reports
  green for the wrong reason but also never produces an artifact is
  honest about being broken, not useful. anvil's wild-corpus utility
  number is still zero.

A receipt that quotes only the first number is a half-truth. This
document quotes both.

## Per-project state

Sorted by reading from current `:result` to "what's actually needed":

| Project | Result | Rule | What's blocking real artifacts |
|---|---|---|---|
| hibernate-orm | `:unsupported` (AN5-1) | `:unsupported-construct` (library.hibernate-jenkins-pipeline-helpers-unresolved) | AN5-2: external @Library loader |
| hibernate-search | `:unsupported` (AN5-1) | (same) | AN5-2 |
| apache-camel | `:unsupported` (AN5-1) | `:unsupported-construct` (translator.body-skipped) | AN5-3: declarative-agent translator + body dispatch |
| apache-zookeeper | `:unsupported` (AN5-1) | (same) | AN5-3 + matrix-under-degraded-label fix |
| apache-cxf | `:unsupported` (AN5-1) | (same) | AN5-3 |
| eclipse-epsilon | `:unsupported` | `:agent-unhonored` (kubernetes) | k8s backend (out of scope this cycle) |
| apache-maven | `:unsupported` | `:unsupported-construct` (step.mavenBuild) | Adapter for maven-plugin-specific step |
| eclipse-jdt-core | `:failure` | `:step-nonzero-exit` | Source not in workspace (SCM stub) + missing toolchain |
| eclipse-jkube | `:failure` | `:credential-unresolved` (secret-subkeys.asc) | Credential store needs `secret-subkeys.asc` |
| apache-camel-quarkus | `:failure` | `:step-nonzero-exit` | Missing toolchain (Maven, JDK) |
| apache-activemq | `:failure` | `:step-nonzero-exit` | Missing toolchain |
| apache-streampipes | `:failure` | `:step-nonzero-exit` | Missing toolchain |
| apache-hbase | `:failure` | `:step-nonzero-exit` | Missing toolchain |
| apache-cassandra | TIMEOUT (harness) | — | `agent { dockerfile }` — harness gives up before classifier runs |
| eclipse-mojarra | TIMEOUT (harness) | — | `agent { kubernetes }` + YAML — same |

## The three silent-failure shapes AN5-1 surfaces

AN5-1 does NOT fix these — it makes them audible. Operators see a
named `:rule` and `:explain` instead of a vacuous `:neutral`. The
actual fixes are AN5-2 and AN5-3.

1. **Scripted @Library unresolved** (hibernate-orm, hibernate-search).
   The Jenkinsfile starts `@Library('hibernate-jenkins-pipeline-helpers') _`
   followed by `import org.hibernate.jenkins.pipeline.helpers.job.JobHelper`.
   anvil v0.3 has no path that loads an external Groovy library from a
   coordinate at runtime, so the Groovy compile-step throws on the
   import and the dispatcher catches it without producing diagnostic
   effects. AN5-1 emits `[:unknown {:name "library.X-unresolved"}]`;
   AN5-2 will replace this with a real loader.

2. **Translator body-skipped** (apache-camel, apache-cxf). Declarative
   pipelines with per-stage `agent { docker { image '…' } }` shapes
   the translator emits but doesn't dispatch into. The stage enter/leave
   markers fire; the step body inside doesn't reach the dispatcher.
   AN5-1 emits `[:unknown {:name "translator.body-skipped"}]`; AN5-3
   will trace the body-dispatch gap and fix it.

3. **Matrix-under-degraded-label** (apache-zookeeper). `agent { label
   'Hadoop' }` at pipeline level degrades to the fallback; the
   `stages { stage { matrix { agent any; axes; … } } }` block under it
   produces zero expanded stages. AN5-1 emits the same body-skipped
   diagnostic; the matrix-expander needs a degraded-label-aware path.

## What anvil's execute path CAN do

Established by [PR #32](https://github.com/SuperBadLabs/anvil/pull/32)
(`an5-3a-smoke-baseline`), CI-gated:

A minimal declarative Jenkinsfile —

```
pipeline {
  agent any
  stages {
    stage('Produce') { steps {
      sh 'echo anvil-smoke-build > artifact.txt'
      sh 'ls -la artifact.txt'
    } }
    stage('Archive') { steps {
      archiveArtifacts artifacts: 'artifact.txt'
    } }
  }
}
```

— routed through the full anvil stack produces:

- `artifact.txt` on disk in the workspace, exact expected content
- `:archive` effect recorded
- 2 × `:sh` effects with `:exit 0` from real subprocesses
- Classification `:success` with rule `:default`

This is the **honest baseline**. Anvil's execute path is not broken.
What's broken is anvil's handling of the shapes the wild-corpus
Jenkinsfiles actually use (external libraries, per-stage container
agents, matrix-under-label, plugin-specific steps).

## What's in the pipeline

Four pieces of real engineering, each multi-PR:

- **AN5-2** — External @Library loader. Fetch + cache + Groovy
  classpath registration. Unblocks hibernate-orm, hibernate-search.
- **AN5-3** — Wire anvil's `h-sh` through `chengis.engine.backend.docker/DockerBackend`
  with workspace lifecycle, cgroup limits, cancel signal. Unblocks
  apache-camel, apache-cxf, apache-zookeeper.
- **CC2-EX3b** — Concrete tool installers (Temurin JDK, Maven, Gradle,
  Node) in the chengis-core registry. Unblocks the 5 `:step-nonzero-exit`
  builds.
- **AN5-RERUN** — Re-run the matrix and count REAL artifacts on disk.
  The receipt that matters.

## The AN4 + AN5 changes that landed

| PR | Title | Mechanism |
|---|---|---|
| #25 | AN4-1 — chengis-core EX2 classifier wired into runner | `effects → observation → classify` replaces lossy `case` fallback |
| #26 | AN4-2 — `:agent/degraded` for unhonored container shapes | docker / dockerfile / kubernetes emit explicit degradation |
| #27 | AN4-3 — `tool()` routes through `chengis.tools/resolve!` | unresolved tools emit `:tool-unresolved` effect |
| #28 | AN4-4 — `:credential-unresolved` for missing creds | unresolved credentials emit explicit effect |
| #29 | AN4-5 — UI banners for `:neutral` / `:unsupported` | operators see the `:rule` + `:explain` |
| #30 | AN4-6 — Jenkins API maps `:neutral`/`:unsupported` → NOT_BUILT | jenkins-cli + GH plugin compat |
| #31 | AN5-1 — Silent-failure walk-shape synthesizer | `[:unknown {…}]` synthesized when IR walked but no work recorded |
| #32 | AN5-3a — Real-artifact baseline smoke test | CI-gated proof that the basic execute path works |

The first six are honesty. The seventh is diagnostic surfacing. The
eighth locks the baseline. None of them produce real artifacts on
disk in the wild-corpus case. That is honest, and it is what's next.

## On framing this receipt

The original version of this document led with "0/15 false :SUCCESS"
and called it victory. The user pushed back: that's a half-truth that
sells scaffolding as a receipt. The honesty work was necessary, but
it isn't the receipt the user asked for. The receipt is artifacts on
disk, and that number is still zero.

This rewrite owns that. The next receipt will lead with the second
number going up.

## Artifacts (raw run data)

- `/tmp/anvil-broad/results.pre-an4.edn` — original 2026-06-03 baseline
- `/tmp/anvil-broad/run.post-an4.log` — full harness output (post-AN4)
- `/tmp/anvil-broad/anvil-server.log` — anvil daemon log with per-build
  `[anvil.classify]` INFO lines
- `/tmp/anvil-broad/classify-summary.txt` — extracted classifier outcomes
