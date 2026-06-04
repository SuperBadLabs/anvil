# anvil — changelog

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
