# anvil — changelog

## 0.6.0 — Hermetic-Adjacent + Kubernetes + Fidelity (in progress)

### Added — v0.6 T1 (Kubernetes agent runtime)

- **chengis-core 0.4.0 dependency bump** — pulls in the new
  `chengis.engine.backend.k8s/K8sBackend` (per
  [AV6-2](docs/roadmap/v0.6-board.md#locked-decisions-av6-series): k8s
  backend lives in chengis-core, anvil only consumes the protocol).
- **Translator: `agent { kubernetes { yaml '...' } }`** (T1.3) — the
  declarative form. Regex-extracts image / namespace / resource
  limits (`memory`, `cpu`) from the inline yaml without taking a
  clj-yaml dep into the translator path. Falls through to a
  honest-degrade marker (`:k8s-empty-block`) when extraction misses.
- **Translator: `agent { kubernetes { containerTemplate(...) } }`**
  (T1.4) — the structured Jenkins form. Maps `image`, `name`,
  `resourceLimitMemory`, `resourceLimitCpu` into the same IR shape
  declarative emits.
- **Kubeconfig lookup** (T1.5) — chengis-core's K8sBackend resolves
  in order: `:anvil.k8s/kubeconfig-path` (anvil.edn override) →
  `KUBECONFIG` env → `~/.kube/config`. Threaded into every kubectl
  invocation via `KUBECONFIG=` env so the backend doesn't mutate
  the calling process's env.
- **backend-wiring.k8s-agent-spec / backend-for-ctx** — recognizes
  the `:kubernetes` key in `(:active-agent ctx)`, constructs a
  K8sBackend with operator-overridable kubeconfig + the AN7-5b
  per-job resource-limit override (re-uses the existing
  `:docker-resource-limits` key — backend-agnostic).
- **`:k8s-agent` feature flag defaults ON** (per AV6-7 —
  closed-by-default through in-progress; flips on with the
  tranche-closing commit). Operators on hosts without a reachable
  cluster set `{:anvil.features/k8s-agent false}` to opt out;
  k8s shapes then degrade to `:unsupported` honestly.
- **T1.6 receipt**: `docs/k8s/anvil-k8s-runbook.md` — kind setup,
  config knobs, declarative + scripted walkthroughs, resource-limit
  mapping, debugging, and when-to-use-k8s-vs-docker. Replaces the
  T0.3 stub.

### Changed

- `anvil.compat.jenkins.agent` — `:kubernetes`-keyed specs are no
  longer rejected at import. The new `agent-summary` names the
  image; `rejected?` returns `false`; `deferred?` flips per
  feature flag + IR completeness.
- `anvil.compat.jenkins.dispatcher.unhonored-container-agent-shape`
  — k8s is honored when `:k8s-agent` flag is on AND an image is
  extractable. Otherwise emits `:agent/degraded` (AN4-1 classifier
  reads as `:unsupported`).

### Tests

- 7 new tests (24 assertions) in
  `test/anvil/compat/jenkins/k8s_agent_test.clj`.
- Updated `agent_test.clj` + `agent_degraded_test.clj` for the
  new IR shape.
- Full anvil suite: 952 tests / 2779 assertions, 0 failures, 0 errors.

---

## 0.6.0 (other threads — in progress)

Per the [v0.6 execution board](docs/roadmap/v0.6-board.md), in
progress. Two threads:

1. **Runtime expansion** — Kubernetes agent runtime via chengis-core
   0.4 (T1, biggest tranche, unblocks cassandra-real / eclipse-epsilon
   / eclipse-mojarra); Vault + Cloud-KMS secret backend adapters
   (T2, the v0.4-deferred operator ask); multi-stage Dockerfile
   container-as-step (T3, the v0.5-deferred polish); build-overrides
   hot-reload (T4, v0.5.x continuation).
2. **Honesty thread (AN8 series)** — `tools{}`, `parameters{choice}`
   defaults, matrix-declarative-with-tools composition, SCM-checkout-
   before-stage-1 lifecycle. Targets ≥ 8/14 wild-corpus `:success`
   per AV6-5 (aspirational 11-12).

Reserved feature flags + SSE topics shipped by T0; all closed by
default until each tranche merges.

Tracks until v0.6.0 tag; see board for week cadence + locked
decisions (AV6-1..9).

---

## 0.5.0 — Scale + Honesty Release (2026-06-08)

The release the v0.5 board promised: four scale tranches (T1-T4) and
five AN7 honesty tickets, all on master. Wild-corpus `:success` moves
from 1/14 to 5/14 (conservative) with a realistic ceiling of 7/14.

### Scale tranches — SHIPPED

- **T1 — Remote build cache** (`:anvil.features/cache`, `:cache-remote`).
  Content-addressed step-level caching per AV5-2. Cache key =
  `(image-digest, command, env-fingerprint, input-tree-merkle)`.
  Local FS store at `~/.anvil/cache`; optional S3-style remote via
  `:cache-remote` flag. `:cache-hit` / `:cache-miss` SSE events fire
  per step. Cache invariants receipt at `docs/cache/README.md`.

- **T2 — Build cost reporting** (`:anvil.features/cost-reporting`).
  Wall-time x declared host rate per AV5-3. Rates declared in
  `:anvil.cost/host-rates` in `anvil.edn`; cost recorded to
  `anvil_build_costs` table (migration 012); `/cost` dashboard
  shows top-20 by cost + 30-day totals; per-build cost pill.
  Cache savings estimated proportionally (T2.5 honesty: no
  per-step wall-times yet).

- **T3 — GitLab MR + Bitbucket PR commit-status** (`:gitlab-mr`,
  `:bitbucket-pr`). Mirrors v0.4 T3.4 GitHub Checks pattern per
  AV5-4. `anvil.integration.gitlab-subscriber` posts `running` on
  `:build-started` and `success`/`failed`/`canceled` on `:build-done`
  to GitLab Commit Status API. `anvil.integration.bitbucket-subscriber`
  posts `INPROGRESS`/`SUCCESSFUL`/`FAILED`/`STOPPED` to Bitbucket
  Build Status API. Both publish `:evt-mr-checked` / `:evt-pr-checked`
  on the per-job bus topic. Docs at `docs/gitlab/` and `docs/bitbucket/`.

- **T4 — Chengis 0.1 RBAC adapter** (`:anvil.features/multi-tenant`).
  Optional multi-tenant RBAC adapter per AV5-5. `anvil.tenancy.rbac`
  defines the `RbacBackend` protocol; `NoOpBackend` (default, zero
  overhead, single-tenant behavior unchanged) and `ChengisBackend`
  (HTTP to a chengis 0.1 service). Ring middleware `wrap-rbac` /
  `wrap-rbac-route` for per-route permission enforcement. Audit-log
  subscriber writes `:build-started` / `:build-done` / `:job-created`
  / `:credential-added` events to chengis audit log. SSO redirect
  stub (JWT validation defers to chengis 0.1). Docs at
  `docs/chengis-rbac/README.md`. Chengis 0.1 ships from its own
  repo; anvil does NOT bundle it.

### AN7 honesty series — SHIPPED

Per AV5-7, AN7 tickets ride alongside scale and are not gating.
Five of six AN7 tickets landed:

- **AN7-1** — Synthetic shims for apache-maven, apache-activemq,
  apache-zookeeper, eclipse-jdt-core. All four labeled type-B per
  AV5-6. Shim resolution order: overlay wins over real Jenkinsfile.
  Receipt at `docs/jenkins-compat/an7-1-synthetic-shims.md`.

- **AN7-2** — Groovy GString `${X}` interpolation in declarative
  pipeline agent labels, environment blocks, and parameter defaults.
  Scoped strictly to declarative contexts per R5; `sh` step bodies
  are untouched.

- **AN7-3** — `:file` credential type for GPG keyrings and
  certificate bundles. Host path mounted read-only into docker steps
  via `-v HOST_PATH:CONTAINER_PATH:ro`. Honest gap when docker not
  present: credential injected as env var with a WARN.

- **AN7-4** — External `@Library` Git resolver. `@Library('name@ref')`
  coordinates now attempt clone from `:anvil.libs/remotes` in
  `anvil.edn`, cached at `~/.anvil/libs/<name>/<ref>/`. Depth-1 for
  branches/tags; full + `reset --hard` for 40-char SHA refs.
  Falls back to local `ANVIL_LIBRARIES_DIR` lookup. Receipt at
  `docs/jenkins-compat/an7-4-shared-libs.md`.

- **AN7-6** — Verdict-provenance **Type** column on the wild-corpus
  receipt. Type-A = real upstream Jenkinsfile. Type-B = synthetic
  shim ran. Both labeled in every v0.5+ receipt row.

**AN7-5** (docker memory + Surefire JVM tuning for activemq / zookeeper
test-phase OOM) did not ship by v0.5.0 per AV5-7. Tracks into v0.5.x.

### Wild-corpus T6 receipt summary

| Scenario | `:success` count |
|---|---|
| Conservative (no library resolution, no GPG cred) | **5/14** |
| Expected (AN7-4 resolves 1+ library; jkube GPG provisioned) | **6-7/14** |

The 5/14 conservative tally meets the AV5-8 minimum gate. The
aspirational 9-10/14 ceiling was not reached at v0.5.0 — per AV5-8,
this was stated upfront as realistic-ceiling dependent on AN7 tickets
landing with verified artifacts. Receipt at
`docs/jenkins-compat/wild-corpus-honest-receipt.md`.

### Test coverage

910 tests, 0 failures across the full suite.

---

## Unreleased — 0.4.2 / 0.5 fleet-balance polish

Follow-up from the v0.4.1-T6 wild-corpus fleet rerun (2026-06-08), where
a 3-host fleet (HeMan 32c, Mario 12c, Luigi 56c) ran imbalanced:
hardcoded `numExecutors=2` per daemon plus a hand-coded 4/5/5 corpus
split saturated 12-core Mario at load 28 while 56-core Luigi sat at
0.4. Two fixes ship together:

- **Daemon worker pool is now sized to the host.** `queue/default-worker-count`
  returns `max(2, cores/4)`; the boot resolver picks the count from
  (in precedence) `ANVIL_WORKERS` env, `:anvil.queue/workers` in
  `anvil.edn`, or the default. The startup log line tells operators
  which source won, plus the host core count. The Jenkins shim's
  `/jenkins/api/json` `numExecutors` field now mirrors the actual
  pool size instead of always reporting 2 — fleet drivers can use
  it as a shard weight.
- **CPU-weighted shard distribution + heavyweight rotation in
  `scripts/wild-corpus-rerun.bb`.** New `--fleet=URL1,URL2,...`
  mode queries each daemon's `numExecutors`, apportions the corpus
  via Hamilton's largest-remainder method, and rotates the
  heavyweight builds (apache-hbase, apache-cassandra) across hosts
  by `--cycle=N`. Optional per-host weight override
  (`--fleet=URL:weight,...`) for hosts shared with other work.
  `--plan-only` prints the shard plan without dispatching, for
  sanity-checking before a multi-hour rerun. Single-host invocation
  (`ANVIL_URL=…`) unchanged. Runbook updated:
  [docs/jenkins-compat/AN5-RERUN-runbook.md](docs/jenkins-compat/AN5-RERUN-runbook.md).

## 0.4.0 — Leapfrog + Honesty Release (2026-06-07)

The release the v0.4 board promised — though scoped honestly: of the
four leapfrog tranches, **T1 (flaky)** and **T2 (container-as-step)**
shipped. T3 (AI authoring) and T4 (SLSA provenance) reserved their
flags + SSE topics + doc stubs but their implementations defer to
**0.4.1**. The **AN6 honesty series — all six tickets** shipped.
Every wild-corpus build the v0.3.3 receipt named by build has a
code-side answer now.

### Leapfrog tranches — SHIPPED

- **T1 — Flaky-test detection** (`:anvil.features/flaky`).
  Per AV4-3, passed-on-retry analysis is the only definition at
  v0.4.0 (no statistical models). New `anvil.flaky` namespace
  detects tests that failed an earlier attempt and passed a later
  one in the same build; the build-completion hook flags them in
  `anvil_test_results` and publishes the new `:flaky-flagged` SSE
  event. New `/flaky` dashboard (top-20 across instance, 30-build
  window) + per-build widget on the job page. `h-retry` is now a
  real loop emitting `:retry/attempt` per cycle, and `h-junit`
  threads the attempt index into per-attempt rows so the substrate
  exists for real wild-corpus signal.

- **T2 — Container-as-step** (`:anvil.features/container-step`).
  `steps { container('maven:3.9') { sh '...' } }` from
  Jenkinsfile-Pipeline now routes the wrapped step through
  chengis-core's `DockerBackend` via the existing AN5-3 plumbing.
  No new container abstraction — per AV4-2, we reuse what's there.
  Composes with declarative matrix.

### Leapfrog tranches — RESERVED, deferred to 0.4.1

- **T3 — AI authoring** (`:anvil.features/ai-authoring`). Feature
  flag reserved + SSE topic `:ai-suggested` reserved + docs stub
  at `docs/ai-authoring/README.md` naming substrate, planned
  files, and AV4-4 local-first decision. Implementation
  (`anvil init` / `explain` / `optimize` CLI + UI buttons + the
  Anthropic API client) deferred to 0.4.1. The reservation lets
  operators wire dependent automation against the flag/topic
  shapes today even though they 404 / silently produce nothing
  until 0.4.1.

- **T4 — SLSA L3 provenance** (`:anvil.features/provenance`).
  Feature flag reserved + SSE topic `:provenance-attested`
  reserved + docs stub at `docs/provenance/README.md`. Sigstore
  wiring (default Fulcio keyless, offline-key fallback,
  in-toto v1 attestation writer) + `anvil provenance verify`
  CLI deferred to 0.4.1.

### AN6 honesty series — every wild-corpus gap named in 0.3.3 closed

- **AN6-1 (#56)** — `agent { label { label params.X } }` (the
  parameter-driven nested-label shape) now resolves to the
  parameter's `defaultValue` or first listed `choice`. Closes
  apache-activemq's "Maven enforcer because we ran on host 3.8.7"
  honest-but-wrong-environment chain documented in
  `an5-7-activemq-receipt.md`.

- **AN6-2 (#59)** — Nested `stages { … }` blocks inside a stage
  body (apache-cxf's `matrix → stages → stage → stages` chain;
  eclipse-epsilon's `stage('Main') { stages { … } }` grouping)
  now flatten into N sibling stages with the wrapper name prefixed
  and `:agent` / `:environment` / `:post` propagated.

- **AN6-3 (#60)** — `agent { dockerfile { filename '…' } }` is
  honored when `:anvil.features/dockerfile-agent` is on. New
  `anvil.tools.dockerfile` namespace builds the image with a
  deterministic content-hash tag (`anvil-dockerfile:<16-hex>`),
  caches across builds, and upgrades ctx active-agent to the docker
  shape so AN5-3 routing takes over. Closes apache-cassandra; will
  extract to `chengis.tools.dockerfile` when chengis-core 0.4.0
  ships per AV4-8.

- **AN6-4 (#57)** — `mavenBuild()` from
  jenkinsci/pipeline-library is honestly `:unsupported` with a
  receipt at `docs/jenkins-compat/an6-4-mavenbuild-receipt.md`
  + the `sh 'mvn …'` + explicit `junit` step workaround. Per
  the v0.4 board's option (b) — implementing the shared-lib
  step would mean re-implementing ~10 different Jenkins
  integrations that drift from upstream.

- **AN6-5 (#58)** — GPG-subkey credential UX receipt at
  `docs/secrets/gpg-subkey.md` — provision via existing
  `--type string` + the mktemp/trap workaround for Jenkinsfiles
  that expect file paths. v0.4.x adds a real `:file` type.

- **AN6-6 (#57)** — `scripts/wild-corpus-rerun.bb` learns
  `-Jmax-minutes=N` (default 30) for runs that include
  apache-hbase + similar long-runners. `docs/dispatcher/long-builds.md`
  documents the two independent timeout knobs (harness vs daemon)
  + the AN5-1 honest classification: cap-killed builds are
  `:aborted`, NOT `:failure`.

### Infrastructure

- `chengis-core` pinned to **0.3.0** (unchanged from 0.3.3); if
  AN6-3 extracts to `chengis.tools.dockerfile` in a follow-up,
  chengis-core 0.4.0 will ride that release.
- 4 new SSE event topics reserved at T0.4: `:flaky-flagged`,
  `:container-step-started`, `:ai-suggested`, `:provenance-attested`.
- 5 new feature flags, all closed-by-default per AV4-7.
- Migration 011-test-results-flaky adds `attempt_number`,
  `flaky_bool`, `retry_count` columns to `anvil_test_results`.

### Dogfood-driven late polish (between RC and ship)

- **HEAD on `:get`-only routes no longer 405s.** Anvil's reitit
  router defaulted to 405 Method Not Allowed when HEAD hit a route
  declared as `:get`-only. The new `wrap-head-as-get` middleware
  in `anvil.web.routes` upcasts HEAD → GET, lets the GET handler
  run, then strips the body per HTTP semantics. Applies globally
  so every wrap-feature route (and the static handlers) stay
  consistent.  Surfaced when the 0.4.0 dogfood hit `HEAD /flaky`
  and got 405 instead of the expected 404 + Content-Length.
- **apache-cassandra back in the wild-corpus harness** with the
  AN6-3 feature flag note. `scripts/wild-corpus-rerun.bb` re-
  includes the entry and stamps `:requires-flag :dockerfile-agent`
  so operators know what to flip in `anvil.edn` before running.
  apache-maven, eclipse-jkube, and apache-hbase also get `:notes`
  for their AN6 receipts so the per-build expectations travel
  with the harness, not just the receipts.

### Baseline + perf

- v0.4 baseline at `benchmarks/results/v0.4-baseline-2026-06-07.edn`.
- 6 of 7 UI pages under the 50ms p50 budget (slowest under-budget:
  `/jobs` @ 5.8ms, 8.6× headroom).
- One honest data point: `/coverage` p50 = 798ms (15.96× over
  budget). Investigation deferred to T7.2 re-check; not gated by
  v0.4.0 ship.

### Headline numbers (anvil-side)

- ~2,600 LOC across production + tests
- +75 new tests / ~+200 new assertions
- Full suite at the 0.4.0 commit: **642 / 1982** — 0 failures, 0 errors
- 13 PRs cycled through the v0.4 board (PRs #48–#60)

### Honest deferrals tracked for 0.4.1

- T3 (AI authoring) full implementation
- T4 (SLSA provenance) full implementation
- T1.6 — 4-fixture retry-shape browser test (substrate locked
  down by T1.1's analyzer + T1.2 storage tests; etaoin scaffold
  is the only missing piece)
- T2.6 — 3-shape container-step corpus + browser smoke
- T6 — full post-AN6 wild-corpus rerun to verify the receipt
  numbers actually flip (the substrate is there, the verification
  receipt rides 0.4.1 once dogfood instance settles)

## 0.3.3 — The Receipt Release (2026-06-06)

The "0.3.2 made the artifact axis non-zero with a 4-build subset
producing 1,040 jars; 0.3.3 generalizes the receipt across the full
wild-corpus" release. Two translator fixes, one classifier fix, the
new chengis-core 0.3.0 tool-installer matrix, and two diagnostic
receipts — all locked in by a full re-run.

**Headline (verified by AN5-RERUN against master, 2026-06-06)**:

| | v0.3.1 baseline | v0.3.2 (subset) | **v0.3.3 (full)** |
|---|---|---|---|
| Real jar files on disk | 0 | 1,040 | **9,641** |
| Total bytes | 0 | 196 MB | **7.4 GB** |
| Honestly classified | varied | 4 of 4 | **12 of 12** |
| `:success` | 0 | 1 | 1 (apache-camel-quarkus, 7,820 jars) |
| `:failure` (honest exit codes) | 0 | 1 | 6 |
| `:unsupported` (real gap) | varied | 2 | 3 |
| `:neutral` (honest @Library probe) | n/a | n/a | 2 |

Depends on **chengis-core 0.3.0** (the tool-installer matrix —
Temurin + Maven + Gradle + Node + shared helpers).

### AN5 family — Wider corpus, honester gaps

- **AN5-6 (#43)** — `translate-stage` now recognizes
  `matrix { axes {} stages {} }` blocks placed directly inside a
  declarative stage body (no top-level `steps {}` sibling). Before
  AN5-6 these came out as `{:name X :steps []}` and the AN5-1
  classifier reported `:unsupported/:body-skipped`. Apache-camel and
  apache-cxf both hit this shape in the wild corpus. After AN5-6,
  apache-camel reclassifies to `:failure :step-nonzero-exit` — the
  matrix cells actually run and report their honest exit codes (3
  jars / 471 MB on disk before the failure point). apache-cxf still
  body-skipped because its matrix shape is nested deeper than AN5-6
  handles; tracked as v0.4 AN5-6.5.
- **AN5-2 (#44)** — `anvil.compat.jenkins.libraries/load-into-effects!`
  probes every `@Library` coordinate against `ANVIL_LIBRARIES_DIR`
  before the runner dispatches and pushes one `[:library-loaded …]`
  or `[:library-unresolved …]` effect per coordinate into the
  dispatcher's effects atom. The classifier reads these as productive
  (no synth fallback) and maps `:library-unresolved` to
  `library.X-unresolved` in the unsupported-construct rule space,
  parallel to `tool.X-unresolved` and `credential.X-unresolved`.
  hibernate-orm and hibernate-search now classify
  `:neutral :no-effects-recorded` (honest "we tried, nothing was
  there") instead of the synthesized `library.X-unresolved` guess
  from AN5-1.

### Dependency bumps

- `[superbadlabs/chengis-core "0.2.1"]` → `"0.3.0"` to consume the
  new tool-installer matrix. anvil's `tool('jdk_X_latest')` step
  already routes through `chengis.tools/resolve!` (AN4-3); with
  0.3.0 on the classpath operators can register the four installers
  at startup and the resolve call returns real on-disk paths.

### Diagnostic receipts

- **AN5-RERUN (#45)** — Full 12-build wild-corpus re-run receipt
  added at `docs/jenkins-compat/wild-corpus-honest-receipt.md`. The
  9,641-jar / 7.4-GB headline. Per-build breakdown with classification,
  artifacts, and remaining-gap notes.
- **AN5-7 (#46)** — Apache-activemq "MojoExecutionException"
  root-cause: NOT a Maven plugin crash. `maven-enforcer-plugin`
  correctly refusing host Maven 3.8.7 because the parent POM requires
  `[3.9,)`. Build ran on the host instead of in
  `maven:3.9-eclipse-temurin-21` because the
  `agent { label { label params.nodeLabel } }` (parameter-driven
  nested-label) shape translates to `{:label "<dynamic>"}` which
  doesn't match `"ubuntu"` in agents.edn. Full diagnosis at
  `docs/jenkins-compat/an5-7-activemq-receipt.md`. v0.3.3 ship
  behavior stays as-is (honest `:failure :step-nonzero-exit` with a
  recorded `:agent/degraded` effect); v0.4 will handle the
  parameter-driven label shape.

### Upgrade notes

- chengis-core dep bumped 0.2.1 → 0.3.0; consuming operators get the
  new `chengis.tools.{http,archive,checksum,platform,temurin,maven,gradle,node}`
  surface for free.
- Operators wanting real-on-disk `tool('jdk_17_latest')` resolution
  register installers at startup:

  ```clojure
  (require '[chengis.tools :as tools]
           '[chengis.tools.temurin :as temurin]
           '[chengis.tools.maven   :as maven]
           '[chengis.tools.gradle  :as gradle]
           '[chengis.tools.node    :as node])
  (tools/register-installer! (temurin/temurin-installer))
  (tools/register-installer! (maven/maven-installer))
  (tools/register-installer! (gradle/gradle-installer))
  (tools/register-installer! (node/node-installer))
  ```

- `ANVIL_LIBRARIES_DIR` is the new env that AN5-2 probes for
  `@Library` resolution; defaults to `~/.anvil/libraries`. Operators
  with on-disk shared library trees see `:library-loaded` effects;
  those without see `:library-unresolved` (an explicit signal, not a
  guess).

## 0.3.2 — Real Artifacts Release (2026-06-05)

The "0.3.1 told you honestly whether the build worked; 0.3.2 makes
more of them actually work" release. v0.3.1 closed the false-success
hole in the classifier. v0.3.2 keeps the honesty bar and adds the
plumbing that turns honest `:unsupported` / `:failure` results into
honest `:success` results with real jar files on disk — proved
end-to-end by the wild-corpus dirty-dozen hunt:
**1,040 real jar files (196 MB) from apache-camel-quarkus** in 841
seconds. First measurable wild-corpus build with non-zero artifacts.

Depends on **chengis-core 0.2.1** (the `--user $(id -u):$(id -g)`
fix that makes container-produced artifacts host-readable).

### AN5 family — Wire the execution layer end-to-end

- **AN5-3 (#35)** — `anvil.compat.jenkins.backend-wiring` bridges
  the dispatcher's `shell-execute` shape to chengis-core's
  `ExecutionBackend` protocol. `backend-for-ctx` returns LocalShell
  or DockerBackend based on the active agent. Per-step mode as the
  first cut; per-build mode lands in 0.4.
- **AN5-3b (#36)** — `shell-execute`'s docker branch now routes
  through the AN5-3 bridge into chengis-core's `DockerBackend`
  instead of anvil's vendored Docker shim. The LocalShell path is
  unchanged. One protocol, one source of truth for container
  execution.
- **AN5-3c (#37)** — `anvil.agents.registry/merge-defaults`
  surfaces `:docker {:image X}` config when `:executor :docker`,
  so label-based agents (`agent { label 'ubuntu-latest' }`) flow
  through the registry into the docker bridge. Defensive: rejects
  nil/blank `:image`.
- **AN5-3d (#38)** — `resources/anvil/config/wild-corpus-agents.edn`
  maps wild-corpus label conventions to runnable images:
  `ubuntu`/`ubuntu-latest` → `maven:3.9-eclipse-temurin-21`,
  `Hadoop` → `maven:3.9-eclipse-temurin-17`,
  `migration` → `eclipse-temurin:21-jdk`. Plus the AN5-RERUN
  harness (`scripts/wild-corpus-rerun.bb`) with the
  `trigger-build!` bb-script signature fix.
- **AN5-4 (#40)** — `anvil.compat.jenkins.deploy-degrade` —
  feature-flagged `h-sh` rewrites standalone
  `mvn ... deploy ...` calls to `mvn ... package ...` BEFORE
  subprocess spawn. Wild-corpus Jenkinsfiles call
  `mvn clean deploy` expecting Apache's deploy credentials;
  without them, the deploy step crashes with HTTP 401 and no
  jar lands despite all earlier phases succeeding. With
  `:anvil.features/mvn-deploy-degrade true`, the rewrite
  happens, jar lands in `target/`, `archiveArtifacts` picks it
  up. Emits a `[:mvn/deploy-degraded]` effect so the rewrite is
  operator-visible. Token-based shell parsing (not regex) so
  `-Ddeploy=...` and similar do NOT trigger.
- **AN5-5 (#41)** — Lockdown test for `agent none + steps {}`.
  Honest finding: the simple shape already works; the
  `:unsupported` results for apache-camel and apache-cxf come
  from `matrix { ... }` blocks *inside* stage bodies, not from
  `agent none` at top level. That cleanup is AN5-6 in 0.3.3;
  this release locks in the simple shape so it can't regress.

### Bug fixes

- **#39** — `scripts/wild-corpus-rerun.bb`'s `trigger-build!`
  argument list was misaligned, causing the dirty-dozen harness
  to trigger a different job than requested. Surfaced live during
  the hunt; fixed in-band.

### Verified against

- Wild-corpus dirty-dozen hunt (4-build subset, full v0.3.2
  plumbing enabled — `mvn-deploy-degrade` on, `--user` flag on,
  docker label routing through registry):
  - `apache-camel-quarkus` → `:success`, **1,040 real jar files,
    196 MB**, 841 seconds. **First non-zero real-artifact axis
    result in wild-corpus history.**
  - `apache-activemq` → `:failure :step-nonzero-exit`
    (MojoExecutionException — honest failure, root-cause in
    0.3.3 AN5-7).
  - `apache-camel` → `:unsupported` (translator skipped stage
    body — matrix-block-inside-stage; 0.3.3 AN5-6 unblocks).
  - `apache-cxf` → `:unsupported` (same matrix-inside-stage
    shape).

  Full 12-build re-run lands in
  `docs/jenkins-compat/wild-corpus-honest-receipt.md` alongside
  0.3.3 once AN5-6 + AN5-7 land.

### Upgrade notes

- `:dependencies` bumps
  `[superbadlabs/chengis-core "0.2.0"]` → `"0.2.1"` for the
  `--user` ownership fix.
- New optional feature flag `:anvil.features/mvn-deploy-degrade`
  in `anvil.edn`. Closed by default; flip to `true` when running
  Apache-foundation Jenkinsfiles without Apache deploy creds and
  you want jar artifacts instead of 401 crashes.
- New optional config:
  `resources/anvil/config/wild-corpus-agents.edn` is a *worked
  example* for label→image mapping; copy/adapt as
  `anvil-agents.edn` for your own corpus.

## 0.3.1 — Honest Classification Release (2026-06-05)

The "0.3.0 was the parity layer; 0.3.1 is the honesty layer" release.
v0.3.0's headline framing oversold what running the wild-corpus
Jenkinsfiles meant — the matrix walk reported 7 false `:success`
results for builds that produced zero artifacts. This release closes
that gap at the classification + diagnosis layer. Same parity, told
straight.

### AN4 family — Wire chengis-core's honest classifier through anvil

- **AN4-1 (#25)** — Replace anvil's lossy
  `(case status :ok :success :failed :failure :success)` build-result
  classifier with `chengis.engine.result/classify` from chengis-core
  0.2.0. Effects → observation → verdict.
- **AN4-2 (#26)** — Container agents the runner can't honor
  (`docker` / `dockerfile` / `kubernetes`) emit explicit
  `[:agent/degraded]` effects. The classifier reads these as
  `:unsupported`, NOT silent success.
- **AN4-3 (#27)** — `tool('jdk_17_latest')` routes through
  `chengis.tools/resolve!`. Unresolved tools emit `:tool-unresolved`
  effect with the descriptor; the classifier reads as
  `:unsupported-construct`. Handles `CharSequence` + named-arg `Map`
  shapes.
- **AN4-4 (#28)** — `withCredentials([file(credentialsId: 'X', …)])`
  with missing ID emits `:credential-unresolved` effect; classifier
  reads as `:failure` with rule `:credential-unresolved`. No more
  silently-empty `GPG_KEY=""` substitutions.
- **AN4-5 (#29)** — Build page renders new `:neutral` (gray) and
  `:unsupported` (amber) badges. Below the badge: a banner with the
  classifier's `:rule` + `:explain` so operators see WHY a build was
  reclassified without digging through effects.
- **AN4-6 (#30)** — Jenkins API maps `:neutral` and `:unsupported` to
  Jenkins-canonical `NOT_BUILT` for jenkins-cli + GitHub Jenkins
  plugin compatibility; the rule + explain remain in the
  anvil-native response.

### AN5 family — Surface silent failures + lock down the baseline

- **AN5-1 (#31)** — Walk-shape synthesizer in
  `anvil.compat.jenkins.classification`. When the pipeline IR walked
  but no productive effect was recorded, synthesize a diagnostic
  `[:unknown {:name X}]` effect so the build reclassifies from
  vacuous `:neutral` to actionable `:unsupported`. Two cases:
  scripted `@Library` declared but unresolved
  (`library.X-unresolved`), declarative stage body silently skipped
  (`translator.body-skipped`). 8 new tests; 24/54 in
  `classification-test`.
- **AN5-3a (#32)** — End-to-end real-artifact smoke test locks down
  anvil's basic execute path in CI: a minimal Jenkinsfile with
  `agent any` + `sh 'echo … > artifact.txt'` + `archiveArtifacts`
  through the full stack produces a real file on disk + `:archive`
  effect + `:success` classification. 6 new tests; the canary that
  catches future regressions in the simple-IR execute path.
- **AN5-DOC (#33)** — Rewrites
  `docs/jenkins-compat/wild-corpus-honest-receipt.md` (renamed from
  `wild-corpus-an4-receipt.md`) to tell the honest story end-to-end:
  the headline now quotes BOTH the false-success axis (0/15) AND the
  real-artifact axis (still 0/15). The original "0/15 false :SUCCESS
  = victory" framing was scaffolding sold as receipt; this rewrite
  owns that.

### Dogfood receipt

`anvil-self-test` job on the dogfood instance ran anvil's own
`lein test :only anvil.compat.jenkins.classification-test` through
anvil v0.3.0 with master SCM, against this release's source. 24
tests / 54 assertions / 0 failures. Real artifacts archived (
`artifact.txt`, `test-out.txt`). Classification: `:success`. **The
CI eating its own tail, classified honestly.**

### What 0.3.1 is NOT

This release does NOT produce real artifacts for the wild-corpus
matrix. Those still need: AN5-2 (external `@Library` loader), AN5-3
(full container-agent honor via chengis-core's DockerBackend),
CC2-EX3b (concrete Temurin/Maven/Gradle/Node installers). Tracked
on the v0.4 board. See
`docs/jenkins-compat/wild-corpus-honest-receipt.md` for the
per-project breakdown.

### Dependencies

- **`superbadlabs/chengis-core 0.2.0`** (was 0.1.0). The AN4 wiring
  consumes EX1a/b execution-backend, EX2 classifier, EX3a tools
  registry, EX4 credentials, EX5 step framework.

## Unreleased — post-0.3.0 wild-corpus exposure

### Honest amendment to the 0.3.0 release notes

The 0.3.0 release was framed as the "Parity Release." A real-world matrix
run against 15 diverse non-jenkinsci OSS Jenkinsfiles (hibernate-orm,
apache-camel, eclipse-mojarra, apache-hbase, etc.) — recorded in
`docs/jenkins-compat/wild-corpus-receipt.md` — exposed that the parity
delivered was **parser parity, not executor parity**.

Specifically:

- Anvil parses 15/15 real-world Jenkinsfiles without exception, including
  scripted+declarative mixes, k8s YAML heredocs, and 66 KB declaratives.
- Anvil ran zero of them to a real built artifact. The "SUCCESS" results
  in the receipt are walks of the pipeline IR, not builds — most matched
  one of these silent-skip paths:
    - `agent { kubernetes }` / `agent { docker }` / `agent { dockerfile }`
      → body skipped because anvil has no corresponding agent backend
    - `agent none` declaratives without per-stage agents → silently run
      on the controller
    - `tool('jdk_17_latest')` → returns `""` and the user's
      `${tool 'X'}/bin` PATH ends up wrong
    - `withCredentials([…])` → binds the env var to `""` because no
      secret resolves
    - Jenkins-plugin calls (`archiveArtifacts`, `junit`, `slackSend`,
      `recordIssues`, `publishCoverage`, …) → recorded as `[unknown]`
      and treated as no-ops while the build returns green

These are not bugs in v0.3.0 — they are the load-bearing subsystems an
enterprise CI server has and anvil doesn't yet. v0.3.0 was correctly
shipping its parser tier; the headline framing oversold what running
those Jenkinsfiles meant. Operators considering anvil as a Jenkins
drop-in for non-trivial projects should not infer build-equivalence
from the v0.3 receipt.

### What the post-0.3.0 PR (wild-corpus + executor-honesty path) lands

Useful incremental fixes that unblock the cases anvil can plausibly
execute today, and infrastructure the v0.4 executor work will build on:

- **Jenkins env globals** — `JENKINS_URL`, `BUILD_NUMBER`, `BUILD_TAG`,
  `JOB_NAME`, `WORKSPACE`, `BRANCH_NAME` + 16 more — exposed as bare
  identifiers and via the `env.X` Expando; fixes
  `MissingPropertyException` in scripted-eval against blueocean-style
  `if (JENKINS_URL == X) …`.
- **`buildPlugin` / `mavenBuild` shared-lib stubs** — record calls as
  `:jenkins/shared-lib-unresolved` instead of crashing or silently
  passing. Honest "we saw it, we did not run it" diagnostic.
- **Scripted-eval fires on any non-blank source**, not just sources
  with literal `stage()` calls.
- **Per-job SCM auto-checkout** — new ns `anvil.compat.jenkins.scm`
  shells out to git BEFORE the dispatcher runs the first sh step; the
  workspace dir actually contains the source. Without this, `./mvnw`
  exec'd into an empty directory.
- **`params.X` binding** — scripted Pipelines reference build parameters;
  anvil now exposes them via Expando.
- **Top-level helper-fn defs visible to `script {}` blocks** —
  preamble extractor strips the `pipeline {}` block (balanced-brace
  scan, triple-quote heredoc aware) and prepends helpers so
  `def isDeployedBranch() { … }` resolves at script-block compile time.
- **`echo "X " + env.Y` evaluates** — translator extracts the original
  source span (via newly-preserved AST line/column positions) and
  emits a `:jenkins/script` step instead of dumping the Groovy AST's
  `.toString()` into the console.
- **Declarative `script {}` Jenkins config built-ins** — `logRotator`,
  `buildDiscarder`, `disableConcurrentBuilds`, `parameters`, `tool`,
  `withEnv`, +18 more — added to the `runtime.clj` binding set as
  no-ops so `properties([buildDiscarder(logRotator(…))])` patterns
  stop failing on MissingMethodException.
- **`scripts/wild-corpus.bb`** — Babashka harness that reproduces the
  receipt against any anvil instance; reads the latest persisted build
  number so it stays honest across re-runs.

Migrations 008–010 add `scm_type` / `scm_url` / `scm_branch` columns
to `anvil_jobs` so per-job SCM persists.

Test suite: 466 tests / 1517 assertions / 0 failures.

### v0.4 direction — Executor Parity

v0.4 closes the gap from parser-parity to executor-parity. The work
divides cleanly between `chengis-core` (generic CI-engine subsystems,
benefits both anvil and the chengis enterprise product) and `anvil`
(Jenkinsfile-specific mapping over the new engine):

| In chengis-core | In anvil |
|---|---|
| Docker agent backend (per-build container, volumes, env, signals) | `agent { docker { image 'X' } }` → chengis docker-agent API |
| Kubernetes agent backend (real podTemplate apply, container exec) | `agent { kubernetes { yaml … } }` → chengis k8s-agent API |
| Tool installer registry (JDK/Maven/Node/etc. version cache) | `tool('X')` → chengis tool registry |
| Honest build result classes (NEUTRAL/UNSTABLE distinct from SUCCESS) | unsupported-agent / unresolved-step → NEUTRAL |
| Credentials-store binding pipeline | `withCredentials([…])` → real value injection |
| Plugin-step emulation framework + top-20 implementations | Jenkins step-name → chengis plugin-step API |

This is the bulk of v0.4 engineering and it lives in chengis-core. anvil
ships the Jenkinsfile-compat layer; chengis-product ships the SaaS /
multi-tenant / RBAC layer on top of the same engine. The wild-corpus
matrix re-runs after each chengis-core executor subsystem ships,
honestly.

## 0.3.0 — Parity Release (2026-06-03)

The "yes, I'll switch from Jenkins/GHA to anvil" tranche. Seven Tier-1 features close the day-to-day operator surface that v0.2 left open. Closed-by-default behind per-feature flags so upgrade is byte-identical until the operator flips each one.

### Features (each behind `:anvil.features/<flag>` in `anvil.edn`)

- **JUnit / surefire dashboard** (`:junit`) — Maven / Gradle / pytest / cargo nextest XML parsed at build end; build page grows a summary card, collapsible failures with stack traces, sortable-by-duration table, 30-build pass-rate sparkline. Jenkinsfile `junit '…/*.xml'` step now scans+persists+publishes `:test-completed`.
- **Problem matchers** (`:problem-matchers`) — clickable file:line in build logs. Six bundled YAML rules (gcc, rustc, javac, mypy, eslint, msbuild) + native GHA workflow-command parser (`::warning file=…::msg`). Per-build Problems tab with severity-filtered list.
- **PR-check integration** (`:pr-checks`) — webhook receiver at `POST /anvil/webhooks/github` (HMAC-SHA256 verified) → triggers anvil build → publishes status to GitHub's Checks API. PR shows "anvil — success/failure" with link back. PAT auth at v0.3.0; App auth protocol documented for v0.3.1.
- **Declarative matrix** (`:matrix`) — `matrix { axes { axis … } excludes { } stages { } }` parser + cross-product expander + 100-cell cap. 2-axis grid view on the parent build page. Child-build dispatcher fan-out queued for v0.3.1.
- **Scheduled triggers** (`:scheduler`) — full Jenkins cron syntax + 7 aliases (`@hourly`/`@daily`/…) + Jenkins's `H`-spread (SHA-256 hashed per job so load distributes). Job page shows "Next scheduled run". Config-driven (`anvil.edn`); Jenkinsfile `triggers { cron(…) }` parser deferred to v0.3.1.
- **Secrets management** (`:secrets`) — `anvil secrets {add|list|show|delete|rotate-master}` CLI (values read from stdin only, never argv). `/secrets` admin page (IP-gated). Crypto + storage + log masking + `withCredentials` already from TX11D; this release ships the operator UX + rotation flow.
- **mise / asdf tool detection** (`:mise`) — workspace's `.mise.toml` or `.tool-versions` triggers `mise install` (asdf fallback) before the first `sh` step. `anvil setup tools` CLI installs mise from upstream.

### Infrastructure (always-on)

- Auto-redeploy on master: systemd timer + Babashka script + UFW LAN-only recipe under `docs/deploy/`. anvil's own dogfood host runs this — every PR merge hot-swaps into prod within 5 min.
- Bus event topic registry at `anvil.events.topics` — typo-guarded constants for both existing (TX2–TU6) and v0.3-reserved event types.
- Feature-flag mechanism at `anvil.features` — `wrap-feature` middleware 404s disabled routes with a self-explanatory body.
- Migration 005 (`anvil_test_results` + `_summaries`), 006 (`anvil_problems` + `_summaries`), 007 (matrix parent/child columns).

### Test suite

442 tests / 1451 assertions, 0 failures. UI TTFB still under the 50ms p50 TU0.7 budget on every page.

### Locked decisions (`docs/roadmap/v0.3-board.md`)

AV3-1 Tier 1 only; AV3-2 surefire is the canonical test format; AV3-3 adopt GHA's problem-matcher schema; AV3-4 GitHub PR checks first (GitLab/Bitbucket in v0.3.1); AV3-5 matrix declarative-only; AV3-6 file-encrypted at rest; AV3-7 mise primary, asdf fallback; AV3-8 cron expressions only at v0.3.0; AV3-9 (revised) public deploy waits for v0.3.0 — this is the first.

### Carryover to v0.3.x (documented; data hooks in place)

- T2.6 console ANSI overlay for matched problems
- T3 GitHub App auth (JWT → installation tokens)
- T4.4/4.6 matrix child-build dispatcher fan-out + SSE
- T5.4 Jenkinsfile `triggers { cron(…) }` translator
- v0.3.1 secondary parsers: xunit / cargo-json / jest-json
- v0.3.1: GitLab MR + Bitbucket build-status

## 0.2.1 — Post-extraction standalone release (2026-06-03)

First release built from `github.com/SuperBadLabs/anvil` after the monorepo subtree-split. `chengis-core 0.1.0` consumed via local Maven cache (Clojars deferred). Functionally identical to 0.2.0.

## 0.2.0 — anvil UI

Marquee feature: build console live tail. Dashboard + jobs + queue + executors + builds + compare + artifacts views. SSE bus. Mobile-passable. Trigger UX with cookie-scoped recent-values. See `docs/anvil-ui/` for the TU0–TU6 board.

## 0.1.0 — initial public

Jenkinsfile parser + Pipeline DSL runtime, Jenkins REST shim (read-mostly + build trigger), SQLite persistence, `anvil import jenkinsfile` CLI.
