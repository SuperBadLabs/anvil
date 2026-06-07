# anvil

[![version](https://img.shields.io/badge/version-0.4.0-blue)](CHANGELOG.md)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![tests](https://github.com/SuperBadLabs/anvil/actions/workflows/test.yml/badge.svg?branch=master)](https://github.com/SuperBadLabs/anvil/actions/workflows/test.yml)
[![junit](https://img.shields.io/badge/junit-dashboard-brightgreen)](docs/junit/)
[![matchers](https://img.shields.io/badge/problem--matchers-6%20bundled-brightgreen)](docs/problem-matchers/)
[![pr-checks](https://img.shields.io/badge/PR--checks-GitHub-brightgreen)](docs/pr-checks/)
[![matrix](https://img.shields.io/badge/matrix-declarative-brightgreen)](docs/matrix/)
[![scheduler](https://img.shields.io/badge/scheduler-cron%20%2B%20H--spread-brightgreen)](docs/scheduler/)
[![secrets](https://img.shields.io/badge/secrets-AES--256--GCM-brightgreen)](docs/secrets/)
[![mise](https://img.shields.io/badge/mise%2Fasdf-auto-brightgreen)](docs/tools/)
[![flaky](https://img.shields.io/badge/flaky--tests-passed--on--retry-brightgreen)](docs/flaky/)
[![container-step](https://img.shields.io/badge/container--step-DockerBackend-brightgreen)](docs/container-step/)

A free, open-source CI server that **parses any Jenkinsfile** and
**executes the supported subset** on a modern engine. The supported
subset matrix is the source of truth at
[`docs/brasstacks/capability.md`](docs/brasstacks/capability.md).
**Industrial-strength executor parity is a roadmap commitment**, not a
shipped feature — see [Operation Brasstacks](docs/brasstacks/board.md).

> **Where anvil is honest today:** Jenkinsfiles that use `agent any` or
> a labeled-agent that maps to the controller, the core declarative
> vocabulary (sh, dir, parallel, timeout, retry, withEnv, script), and
> v0.3's seven feature-flagged subsystems (junit dashboard,
> problem-matchers, GitHub PR checks, declarative matrix, scheduler,
> secrets, mise/asdf detection).
>
> **Where anvil silently walks past today:**
> `agent { docker/dockerfile/kubernetes }`, `tool('jdk_17_latest')`
> (returns empty string), `withCredentials(…)` (binds to empty string),
> Jenkins plugin-step calls beyond the recorder set, Jenkins-plugin
> class imports. A build that uses these and reports SUCCESS executed
> a structural walk of the pipeline IR — not the build you expected.
> Operation Brasstacks Phase 1–3 closes these gaps; the capability
> matrix tracks the live state per release.

> **Receipt that does work end-to-end:** anvil **v0.3.0** built
> `cli-2.568-SNAPSHOT.jar` (12 MB) from `jenkinsci/jenkins:master`
> source in 32 seconds on the SuperBadLabs dogfood host. The
> jenkinsci/jenkins Jenkinsfile runs under `agent any` + infra-shim
> shared library — the supported subset. See
> [`docs/jenkins-self-host/RECEIPT.md`](docs/jenkins-self-host/RECEIPT.md).

> Jenkins® is a registered trademark of LF Charities Inc.  anvil is not
> affiliated with or endorsed by the Jenkins project.

---

## What anvil is

- **A Jenkinsfile parser + a single-host CI executor for the supported
  subset.** Point `jenkins-cli.jar` at anvil and trigger builds.
  Jenkinsfiles that fit the supported subset (see
  [capability matrix](docs/brasstacks/capability.md)) execute for real;
  Jenkinsfiles that use `agent { docker/kubernetes }`, `tool()`,
  Jenkins-plugin steps beyond the recorder set, or plugin-class imports
  walk past those constructs and the build reports SUCCESS for the
  structural walk. Always verify the build console.
- **Single-node, single-binary, SQLite-backed.** Install in five
  minutes; no Postgres, no agent cluster, no plugin marketplace,
  no controller-SPOF anxiety.
- **Built on `chengis-core`.** The pipeline executor is shared with
  [Chengis](https://chengis.io) — the same engine, exposed through
  a Jenkins-compatible surface. Operation Brasstacks Phases 1 and 3
  land the Docker + Kubernetes agent backends, real tool provisioning,
  credentials binding pipeline, and plugin-step framework in
  chengis-core; anvil v0.4 is the Jenkinsfile-name mapping layer over
  the new engine.

## What anvil is NOT

- Not a multi-tenant CI platform with RBAC, audit, SSO, multi-org —
  that's Chengis (chengis-product), which Operation Brasstacks Phase 4
  delivers
- Not feature-complete vs Jenkins for every plugin in the long tail —
  see [capability matrix](docs/brasstacks/capability.md) for the
  honest set
- Not a drop-in for Jenkinsfiles that depend on Docker / Kubernetes
  agents, `tool()` provisioning, or Jenkins plugin classpath today.
  Phase 1–3 closes this gap; the capability matrix tracks live state
- Not the canonical Jenkins; users seeking the original Jenkins
  experience should use Jenkins directly

See [`../docs/jenkins-compat/divergences.md`](../docs/jenkins-compat/divergences.md)
for the historical divergence list and
[`docs/brasstacks/capability.md`](docs/brasstacks/capability.md) for the
live capability matrix.

---

## Quickstart

### Install + boot

```
$ cd anvil
$ lein run --port 8765
2026-05-29 INFO Starting anvil 0.1.0-rc1
2026-05-29 INFO anvil 0.1.0-rc1 listening on http://0.0.0.0:8765
```

### Import an existing Jenkinsfile

```
$ anvil import jenkinsfile path/to/Jenkinsfile

  Stages:        3  ·  Steps: 12  ·  Script blocks: 0
  Coverage:      100.0%  [████████████████████████████████████████]  [green]
  Step types known:    12
  Step types unknown:  0

  FIXME points (0):

✓ path/to/Jenkinsfile.chengisfile.edn written. Run `anvil build` on it.
```

### Trigger a build via jenkins-cli

```
$ jenkins-cli.jar -s http://localhost:8765/jenkins/ build my-job
```

### Watch the build

```
$ jenkins-cli.jar -s http://localhost:8765/jenkins/ console my-job
```

---

## Architecture

anvil is the OSS surface; `chengis-core` is the engine library
underneath. Both are Apache 2.0 licensed.

```
   ┌─────────────────────┐                         ┌─────────────────────┐
   │  jenkins-cli.jar    │                         │  Your browser       │
   │  / GitHub Plugin    │                         │  (admin UI)         │
   └──────────┬──────────┘                         └──────────┬──────────┘
              │ HTTP / Jenkins-shape JSON                     │
              ▼                                               ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  anvil daemon (`lein run`)                                       │
   │                                                                  │
   │  /jenkins/* shim ─────────► anvil.web.jenkins_api                │
   │                                  │                                │
   │                                  ▼                                │
   │  Jenkinsfile parser ───► anvil.compat.jenkins.{translator,ir}    │
   │                                  │                                │
   │                                  ▼                                │
   │  Pipeline DSL runtime ─► anvil.compat.jenkins.runtime            │
   │  (script {} blocks via embedded Groovy)                          │
   │                                  │                                │
   │                                  ▼                                │
   │  Step dispatcher ─────► anvil.compat.jenkins.dispatcher          │
   │                                  │                                │
   │                                  ▼                                │
   │  Real subprocess execution ─── babashka.process + Docker         │
   │                                                                  │
   │  Persistence ──────────► SQLite via chengis-core                 │
   │                                                                  │
   └──────────────────────────────────┬──────────────────────────────┘
                                      │ chengis.engine.dispatcher
                                      │ StepDispatcher protocol
                                      ▼
                              ┌──────────────────────┐
                              │  chengis-core        │
                              │  - executor          │
                              │  - agent.worker      │
                              │  - plugin protocol   │
                              │  - DB connection +   │
                              │    migration         │
                              └──────────────────────┘
```

---

## What works today

### Parser + import (TX3, TX7)
- **23/23 real-world declarative Jenkinsfiles** parse cleanly
- **19/23 import at 100% known-step coverage**; the rest need 1-2 FIXME edits
- `--explain <step>` gives a migration recipe per unrecognized step

### Runtime (TX4, TX9 phase 1+2)
- **Real subprocess execution** via `babashka.process`
- **Docker agent execution** — `agent { docker { image 'X' } }` wraps in `docker run`
- **Timeout enforcement** — `timeout(time:N, unit:'MINUTES') { … }` kills runaway subprocesses
- **Credential masking end-to-end** — secrets redacted from stored console logs

### Persistence (TX9 phase 3)
- Jobs + builds survive daemon restart
- SQLite at `~/.anvil/anvil-data.db` by default; override with `$ANVIL_DB_PATH`
- Lazy-loads from disk on first read

### Plugin extensibility (TX5 phase 1)
- `anvil.compat.jenkins.plugins/register!` — extension point for community step adapters
- The dispatcher consults the plugin registry before falling through to `:jenkins-unsupported`

### Shared libraries (TX5 phase 2)
- `@Library('foo@ref') _` resolves from a configurable local directory
- Each `vars/*.groovy` becomes a callable Jenkins step

### REST API (TX6)
- `GET /jenkins/api/json` — root Hudson shape with job list
- `GET /jenkins/job/<n>/api/json` + per-build endpoints
- `GET /jenkins/job/<n>/<m>/consoleText` + progressive log
- `POST /build` + `POST /buildWithParameters`
- 501 stubs for `/createItem`, `/config.xml`, `/script` (policy decisions)

---

## What's deferred to anvil 0.2

- Streaming console-log for very long builds
- Async build queue with per-job concurrency limits
- `@Library` class-style libraries (`src/org/foo/Bar.groovy`)
- Real Kubernetes agent provisioning
- Scripted Pipeline (`node { … }` without `pipeline { … }` wrapper) full execution
- Real credential STORE backed by `chengis.db.secret-store`

See [`../docs/jenkins-compat/divergences.md`](../docs/jenkins-compat/divergences.md)
for the complete list with migration recipes for each.

---

## Performance

Architectural-ceiling numbers from a dev-box benchmark run
(`lein with-profile +bench run -m anvil.bench.runner 30`):

| Suite | Result |
|---|---|
| Parser median (per Jenkinsfile in 23-file corpus) | 0.5–180 ms |
| REST shim handlers (in-JVM, no socket) | <0.1 ms median |
| Pipeline orchestration overhead (record-only) | ~500K effects/sec |

For an apples-to-apples comparison against real Jenkins over HTTP,
run `anvil/benchmarks/scripts/jenkins-compare.bb` — it spins up a
Docker Jenkins LTS, fires N requests against each endpoint on both
products, and prints side-by-side latency.

---

## Status

**anvil 0.1.0-rc1.** The TX-program is substantively code-complete;
v1 ships when the four CI / infra follow-ups (jenkins-cli Docker
fixture, coverage gate in CI, streaming console-log, async queue)
land.

- **117 tests / 407 assertions** all green
- **Apache 2.0 licensed**

Track progress in [`../docs/jenkins-compat/`](../docs/jenkins-compat/)
or in the repo's CHANGELOG.

---

## License

Apache License 2.0. See `LICENSE` at the repository root.

This project consumes the Jenkins REST API surface. Jenkins® is a
registered trademark of LF Charities Inc.; this project uses the term
descriptively under nominative fair use and is not affiliated with the
Jenkins project. See `LEGAL-NOTES.md` for the full disclaimer.
