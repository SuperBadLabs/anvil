# anvil v0.3 — capability matrix

The honest inventory of what anvil v0.3 actually does — agent shapes,
step names, credential kinds, tool names — with the silent-skip paths
named explicitly so operators evaluating anvil for real-world
Jenkinsfiles know exactly what executes and what walks.

This document supersedes any "drop-in Jenkins replacement" framing in
older copy. Per Operation Brasstacks
([`docs/brasstacks/board.md`](board.md)) the bar for v1.0 is
**industrial-strength executor parity**, not parser parity. Until the
Phase 1–3 executor work lands, this matrix is the truth.

If a Jenkinsfile fits inside the **Supported** column it executes for
real. If it lands in **Degraded** or **Unsupported**, anvil walks past
it and the build's SUCCESS does not mean an artifact was produced —
read the build console and check whether shell commands actually ran.

## Status legend

| Status | Meaning |
|---|---|
| ✅ **Supported** | The feature executes end-to-end. Side effects land on disk / DB / network as they would on Jenkins. |
| ⚠️ **Degraded** | Anvil recognizes the syntax, runs a partial subset, and records the gap in the build console. Build may report SUCCESS for the structural walk even though the intended side effect did not happen. |
| ❌ **Unsupported** | Anvil parses the syntax but does not execute it. Body is silently skipped. Build reports SUCCESS unless something else failed — **this is a wild-corpus matrix false-positive class**, fixed in Phase 1. |
| 🪨 **Parser-only** | Anvil parses the syntax cleanly; execution semantics not implemented. Step is recorded as `[unknown]` in the console. |

---

## Agent shapes

How `agent { … }` blocks resolve to execution.

| Shape | Status | Notes |
|---|---|---|
| `agent any` | ✅ | Runs every step in the controller's local shell. Same workspace, same shell, same credentials as the anvil process. |
| `agent { label 'X' }` | ⚠️ | If `agents.edn` has an entry for label `X`, runs there; otherwise fallback-to-controller is logged as `[degraded]` and the body runs locally. Real Jenkins would refuse and queue. |
| `agent { node { label 'X' } }` | ⚠️ | Same as `label`. |
| `agent { docker { image 'X' [args '...'] } }` | ❌ | **Body silently skipped.** No container provisioned. Stage records the agent line and returns SUCCESS without executing any step inside it. **Phase 1 EX1b fixes this.** |
| `agent { dockerfile { filename 'X' [dir 'Y'] } }` | ❌ | **Body silently skipped.** No Dockerfile build, no container. **Phase 1 EX1b fixes this.** |
| `agent { kubernetes { yaml '…' } }` | ❌ | **Body silently skipped.** Stage records `agent: kubernetes (UNSUPPORTED)` but the build still returns SUCCESS for the walk. **Phase 3 EX6 fixes this.** |
| `agent none` (declarative pipeline-level) | ❌ | Anvil does not enforce per-stage agent requirement. Stages without an explicit `agent { … }` silently run on the controller. **Phase 2 AV4-1 fixes this — translation-time error.** |

**Implication.** Anvil v0.3 runs Jenkinsfiles whose entire pipeline uses
`agent any` or a labeled-agent that maps to the controller. Any
Jenkinsfile that ships per-stage Docker / Kubernetes / Dockerfile agents
walks past those bodies and reports SUCCESS without building anything.

The wild-corpus matrix
([`docs/jenkins-compat/wild-corpus-receipt.md`](../jenkins-compat/wild-corpus-receipt.md))
shows 7 of the 15 projects fell into this trap.

---

## Step names — declarative IR path

The static IR translator (`anvil.compat.jenkins.translator`) recognizes
these step names in declarative `steps { … }` blocks. Anything not in
this table that appears in a Jenkinsfile falls through to
`:jenkins/unknown` and is recorded as `[unknown]` in the console without
executing.

### Real execution (sh / built-ins)

| Step | Status | What it does |
|---|---|---|
| `sh` | ✅ | Forks `/bin/sh -c <script>` on the agent (= the controller). Streams stdout/stderr into the build's log file. Honors `returnStatus`, `returnStdout`, `label`. |
| `bat` | ⚠️ | Translated to `:jenkins/bat`. Anvil's executor is Unix-only; `bat` script bodies execute via `/bin/sh` for now. |
| `echo` | ✅ | Writes to the build console. Post-PR #20: evaluates `"X " + env.Y` against the live binding instead of dumping the Groovy AST. |
| `dir(path) { … }` | ✅ | Scopes inner steps to a cwd. Honors absolute + relative paths. |
| `deleteDir` / `cleanWs` | ✅ | Recursively deletes the workspace. |
| `writeFile` | ✅ | Writes a file under the workspace. |
| `readFile` | ✅ | Reads a file from the workspace, returns content as a String. |
| `sleep` | ✅ | Honors `time` + `unit`. |
| `error 'msg'` | ✅ | Aborts the build with the given message. |

### Scope wrappers

| Step | Status | What it does |
|---|---|---|
| `timeout(time: N, unit: 'M') { … }` | ✅ | Aborts the body if it exceeds the timeout. |
| `retry(N) { … }` | ✅ | Retries the body up to N times on failure. |
| `parallel(branch1: { … }, branch2: { … })` | ✅ | Runs branches concurrently; build fails if any branch fails. |
| `withEnv(["K=V", …]) { … }` | ✅ | Injects env vars into the body's step ctx. |
| `withCredentials([…]) { … }` | ⚠️ | Recognized, recorded. Resolves the binding to the **empty string** because no secret resolves from the store at v0.3. Body runs with `${X}` expanding to `""`. **Phase 1 EX4 + Phase 2 AV4-3 fixes this.** |
| `withChecks(name: 'X') { … }` | 🪨 | Wrapper recorded; body runs as no-op enter/leave markers. |
| `withMaven(…) { … }` | 🪨 | Wrapper recorded; body runs. No real Maven settings injection. |
| `node(label) { … }` (scripted) | ⚠️ | Same as declarative `agent { label }` — fallback-to-controller if label not in `agents.edn`. |
| `script { … }` | ✅ | Body compiles + runs through Groovy with anvil's DSL bindings. Top-level Jenkinsfile fn defs visible (post-PR #20 preamble fix). |

### Build-config built-ins

These are no-op closures so `properties([buildDiscarder(logRotator(…))])`
doesn't crash. They have **no effect** — anvil does not enforce build
retention, concurrency, triggers, or parameter declarations from the
Jenkinsfile.

| Step | Status | What it does |
|---|---|---|
| `properties([…])` | 🪨 | Recorded; no enforcement. |
| `buildDiscarder` | 🪨 | No-op; build history is not pruned per the Jenkinsfile's policy. |
| `logRotator` | 🪨 | No-op. |
| `disableConcurrentBuilds` | 🪨 | No-op; concurrent builds are not blocked. |
| `disableResume` | 🪨 | No-op. |
| `skipDefaultCheckout` | 🪨 | No-op. |
| `skipStagesAfterUnstable` | 🪨 | No-op. |
| `durabilityHint` | 🪨 | No-op. |
| `timestamps` | 🪨 | No-op. |
| `ansiColor` | 🪨 | No-op (anvil's console already ANSI-aware). |
| `pipelineTriggers([…])` | 🪨 | No-op; cron/triggers from Jenkinsfile not honored. v0.3 has its own scheduler via `anvil.edn`. |
| `cron`, `pollSCM`, `githubPush` (inside triggers) | 🪨 | No-op. |
| `parameters([…])` | 🪨 | No-op; build parameters are read from anvil's job config, not the Jenkinsfile. |
| `booleanParam`, `choice`, `string`, `text`, `password`, `credentials`, `file` (inside parameters) | 🪨 | No-op. |
| `tool('X')` | ❌ | Returns the empty string. `${tool 'maven_3_latest'}/bin` silently becomes `/bin`. **Phase 1 EX3 + Phase 2 AV4-2 fixes this.** |

### Plugin-step recorders

The dispatcher's `plugin-step-types` set recognizes these names and
records the call as a leaf side-effect (logged to the console as
`[record-issues]`, `[slack-send]`, …). **No actual plugin behavior
runs** — no Slack message is sent, no test results are persisted, no
HTML is published.

| Step | Status | What it does |
|---|---|---|
| `archiveArtifacts` | ⚠️ | Translated to `:jenkins/archive-artifacts`. Recorded in the build's effects list. **Artifacts are not copied to a persistent location.** Build's "Artifacts" tab is empty. |
| `junit '…/*.xml'` | ⚠️ | Translated to `:jenkins/junit`. If the v0.3 `:junit` feature flag is on, scans + persists. If off (default), recorded but not persisted. |
| `recordIssues` | 🪨 | Recorded as `:jenkins/record-issues`. No problem matching, no Problems tab population from this step. |
| `slackSend` | 🪨 | Recorded; no message sent. |
| `milestone` | 🪨 | Recorded; no enforcement. |
| `withSonarqubeEnv` | 🪨 | Recorded; no Sonar integration. |
| `publishCoverage` | 🪨 | Recorded; no coverage persisted. |
| `publishHTML` | 🪨 | Recorded; no HTML published. |
| `lock` | 🪨 | Recorded; no actual mutex / queue lock. |
| `sshAgent`, `sshPublisher` | 🪨 | Recorded; no SSH actions. |
| `nexusArtifactUploader` | 🪨 | Recorded; no upload. |
| `waitForQualityGate` | 🪨 | Recorded; passes through immediately. |
| `addFailedStage` | 🪨 | Recorded. |
| `sendNotifications`, `sendErrorNotification`, `sendSuccessNotification`, `notifySlack` | 🪨 | Recorded; nothing sent. |
| `discoverGitReferenceBuild` | 🪨 | Recorded. |
| `pipelineHelpers` | 🪨 | Recorded. |
| `reportPortal` | 🪨 | Recorded. |

**Implication.** A Jenkinsfile that ships `archiveArtifacts`,
`junit`, `slackSend`, `recordIssues`, `publishCoverage` for its
post-build effects has those calls **silently no-op'd** in v0.3. Phase
1 EX5 (chengis-core step framework) + Phase 2 AV4-4 (top-20 mappings)
implement the real behavior.

---

## Step names — scripted-eval path

The Groovy scripted-eval runtime (`anvil.compat.jenkins.scripted-runtime`)
binds a wider set of Jenkins step names when the
`:anvil.features/scripted-eval` flag is on. Anything not in this set
goes through `methodMissing` → throws `MissingMethodException` →
records `[scripted-exception]` and fails the build.

| Step | Status |
|---|---|
| `node`, `stage`, `dir`, `parallel`, `retry`, `timeout`, `withCredentials`, `withChecks` | Same as declarative-IR-path counterparts |
| `stash`, `unstash` | ✅ (tolerant — missing stash does not abort) |
| `checkout` | ✅ (no-op pass-through) |
| `properties`, `pwd`, `isUnix`, `readFile`, `writeFile` | ✅ |
| `buildDiscarder`, `logRotator`, `kubernetesAgent`, `nonresumable`, `agent` | 🪨 (no-op closures) |
| `realtimeJUnit`, `lock` | 🪨 (tolerant block-step closures) |
| `currentBuild`, `pullRequest`, `infra`, `params`, `scm`, plus 22 Jenkins env globals (`JENKINS_URL`, `BUILD_NUMBER`, …) | ✅ exposed as bindings |
| `buildPlugin`, `buildPluginWithGradle`, `mavenBuild`, `gradleBuild`, `nodejs`, `buildPython`, `buildDockerImage` | ⚠️ recorded as `:jenkins/shared-lib-unresolved`; **call is NOT executed**, just logged |
| `discoverGitReferenceBuild`, `recordCoverage`, `recordIssues`, `java`, `javaDoc`, `spotBugs`, `checkStyle`, `esLint`, `styleLint`, `launchable` | 🪨 (no-op closures) |
| `usernamePassword`, `string`, `file` (credential descriptors) | 🪨 |
| `infra.checkoutSCM`, `infra.runMaven`, `infra.runWithMaven`, `infra.withArtifactCachingProxy`, `infra.maybePublishIncrementals` | ✅ (infra shim shells out to git + mvn for the jenkinsci/jenkins self-host receipt) |

---

## Credentials

| Concern | Status | Notes |
|---|---|---|
| Credential store (encrypted at rest, AES-256-GCM) | ✅ | v0.3 T6 shipped. CLI: `anvil secrets {add,list,rotate,delete}`. |
| Log masking | ✅ | Per-build redaction set works. |
| `withCredentials([…]) { … }` binding | ❌ | Recognized; binds the env var to the **empty string** because no secret resolves. **Phase 1 EX4 fixes this.** |
| Per-build `~/.m2/settings.xml` from configured Maven server credentials | ❌ | Not implemented. Maven jobs targeting authenticated repos fail with `401 Unauthorized`. **Phase 1 EX4 fixes this.** |
| Per-build `~/.npmrc`, `~/.pip/pip.conf`, `~/.docker/config.json` | ❌ | Not implemented. |
| Credential kinds supported by `withCredentials` mapping | ❌ | None resolve to real values today. Phase 2 AV4-3 maps Jenkins's `usernamePassword`, `sshUserPrivateKey`, `file`, `string`, `certificate`, `gitUsernamePassword`. |

---

## Tools

| Concern | Status | Notes |
|---|---|---|
| `tool('jdk_17_latest')`, `tool('maven_3_9_latest')`, etc. | ❌ | Returns `""`. `${tool 'maven_3_latest'}/bin` resolves to `/bin`. **Phase 1 EX3 fixes this.** |
| `mise install` / `asdf install` (project-declared) | ⚠️ | v0.3 T7 shipped detection + provision. Works for projects that have `.mise.toml` or `.tool-versions`; not wired to `tool()` for Jenkins-name resolution. |
| Per-version JDK installation (temurin/zulu/corretto) | ❌ | Phase 1 EX3. |
| Per-version Maven, Gradle, Node, Python, Go, Ruby, sbt | ❌ | Phase 1 EX3. |

---

## Plugin imports

Jenkinsfile `import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper`
and similar Jenkins plugin class imports fail compilation because
anvil's Groovy classpath does not ship Jenkins plugin classes. apache-hbase
in the wild-corpus matrix hits this:

```
[script-failed] ("startup failed:
JenkinsDSLScript.groovy: 18: unable to resolve class org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper")
```

| Concern | Status | Notes |
|---|---|---|
| Jenkins plugin class imports (`org.jenkinsci.plugins.*`) | ❌ | Build fails at compile time. Phase 1 EX5 may ship a stub-classes library; alternatively the v0.4 translator strips known-safe plugin imports. |

---

## Wild-corpus matrix outcome under v0.3

Honest tally against
[`docs/jenkins-compat/wild-corpus-receipt.md`](../jenkins-compat/wild-corpus-receipt.md):

- **15/15 parsed** without exception (this includes 66 KB declaratives,
  scripted+declarative mixes, k8s yaml heredocs, top-level fn defs).
- **0/15 produced a real built artifact.**
- **7/15 reported SUCCESS** but the SUCCESS was a structural walk:
  - `agent { kubernetes/docker/dockerfile/none }` silently skipped the
    body
  - `tool()` returned empty string, the `${tool 'X'}/bin` PATH was wrong
  - `withCredentials` bound to empty string, no real secret resolved
  - Plugin-step calls recorded `[unknown]` / leaf-effect and the build
    returned green
- **6/15 reported FAILURE**, of which:
  - 4 actually ran `mvn` / `./mvnw` and failed on real-world toolchain
    issues (Maven version, Apache snapshot 401, Tycho extension, Maven
    deploy auth) — these are the failure modes a fresh Jenkins agent
    would also see, not anvil bugs
  - 2 hit anvil-side gaps: empty GPG secret (no real credential
    resolved) and Jenkins plugin class import (no plugin classpath)
- **2/15 TIMEOUT** at the harness's 180 s cap — real mvn work still
  in flight, not anvil hangs

After Phase 1 (chengis-core v0.2 executor backends + anvil v0.4
mappings) the projected matrix is **13/15 honest builds** with the
2 k8s entries marked `:unsupported` until Phase 3 ships the k8s
backend.

---

## How to regenerate this document

Capability state changes every time anvil's step set or agent set
moves. Phase 0 BT0-B (this doc) is hand-authored from the v0.3 code as
of merge-commit `9bf181b` (BT0-A). Phase 2 AV4-5 ships an auto-generator
(`scripts/gen-capability.bb`) that reads the step registry + agent
implementations and emits this document; CI gates that the committed
file matches the generator output.

Until AV4-5 lands, this document is hand-maintained. PRs that change
step or agent behavior MUST update this document in the same commit, or
CI fails.

---

## Reading guide for operators

- **Considering anvil v0.3 for a Jenkinsfile that uses `agent any` + a
  shell pipeline + simple plugin steps**: should work. Read the build
  console after the first run; if you see `[unknown]` or no `+ sh`
  lines for the steps you care about, that step did not execute.
- **Considering anvil v0.3 for a Jenkinsfile that uses
  `agent { docker }` / `agent { kubernetes }` / `tool()` /
  `withCredentials` for production builds**: not yet. Phase 1–3 of
  Operation Brasstacks closes those gaps. Track progress at
  [`docs/brasstacks/board.md`](board.md).
- **Operating an existing anvil v0.3 install**: assume `[unknown]` and
  silent-skip paths are present. Audit each job's console manually
  before promoting builds to release artifacts.
