# anvil — changelog

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
