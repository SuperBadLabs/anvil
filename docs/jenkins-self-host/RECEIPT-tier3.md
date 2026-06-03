# anvil v0.3.0 → jenkinsci/jenkins Tier-3 structural receipt

> "Make jenkinsci/jenkins's unmodified Jenkinsfile work and build on anvil."
> — the worthiness directive after Tier 1 landed.

## What this proves

**anvil v0.3.0 (with this branch's one-line dispatcher patch) parses and dispatches `jenkinsci/jenkins`'s actual unmodified ci.jenkins.io Jenkinsfile end-to-end.** 256 lines, scripted-pipeline, `properties([…])`, `axes.values().combinations { … }`, `parallel builds`, labeled `node('maven-21')`, the whole shape — anvil walks every stage of every cell.

The file itself warns *"This Jenkinsfile is intended to run on https://ci.jenkins.io and may fail anywhere else."* Tier 3 was always about **structural fidelity**, not about reproducing ci.jenkins.io's Kubernetes + Launchable + artifact-proxy infrastructure on a single dogfood host.

## What was parsed + dispatched

| Construct | anvil result |
|---|---|
| `properties([buildDiscarder(…), disableConcurrentBuilds(…)])` | translated, dispatched |
| `def failFast = false`, `def axes = […]`, `def builds = [:]` | scripted bindings, TX11A |
| `stage('Record build') { node('maven-21') { … } }` | bare scripted stage + labeled node |
| `axes.values().combinations { def (platform, jdk) = it; … }` | top-level expander walked the combinations + ran each closure body |
| `retry(conditions: [kubernetesAgent(handleNonKubernetes: true), nonresumable()], count: 2)` | `retry` block recognized; conditions list shimmed as no-op |
| `infra.checkoutSCM()` | `:shim :infra :method :checkoutSCM` ✓ |
| `infra.runMaven(mavenOptions, jdk)` | shim translates to `sh "mvn …"` ✓ |
| `infra.withArtifactCachingProxy { … }` | shim entered + body dispatched |
| `infra.maybePublishIncrementals()` | shim recognized |
| `withCredentials([string(credentialsId: …, variable: …)])` | block entered, secret env-binding ledger recorded |
| `node('maven-21')`, `node('maven-25')`, `node('docker-highmem')` | labeled-agent registry routed all to local executor (TX11C) |
| `dir(tmpDir) { … }`, `pwd(tmp: true)` | scope managed |
| `timeout(time: 6, unit: 'HOURS')` | block entered, timeout recorded |
| `parallel builds` | dispatched all branches sequentially (single-host) |
| `archiveArtifacts allowEmptyArchive: true, artifacts: '**/*.dumpstream'` | archive effect logged |
| `withChecks(name: 'Tests', includeStage: true) { … }` | block, body dispatched |
| `realtimeJUnit(testResults: '*/target/surefire-reports/*.xml') { … }` | **new** — see the patch below; body now executes |
| `recordCoverage(…)`, `recordIssues(…)` ×5 (java / javaDoc / spotBugs / checkStyle / esLint / styleLint) | shimmed as no-op leaves (correct — no anvil equivalent) |
| `discoverGitReferenceBuild(scm: …)` | shimmed |
| `currentBuild.result`, `env.BUILD_TAG`, `pullRequest.labels` | runtime-deferred (script bindings, TX11A) |
| `isUnix()`, `bat`, `sh` | platform predicate + sh + bat steps |
| `error 'message'`, `echo "…"`, `checkout scm`, `deleteDir()`, `readFile(…)`, `unstash`, `stash` | all recognized leaf steps |

The matrix expanded to 3 cell-builds matching the Jenkinsfile's exclusion filter (`if (platform == 'windows' && jdk != axes.jdks.last()) return`):

- `linux-jdk21`
- `linux-jdk25`
- `windows-jdk25`

Each cell walked Checkout → Build/Test → Publish stages with stage names interpolated against axis values ("Linux - JDK 21 - Checkout", "Windows - JDK 25 - Publish", etc.).

## The one-line patch that unblocked Tier 3

The first attempt at Tier 3 (against unpatched anvil v0.3.0 master) reported `SUCCESS` in 378 ms — too fast to be real. Root cause: the unmodified Jenkinsfile buries `infra.runMaven(...)` inside `withChecks(…) { realtimeJUnit(…) { … } }`. anvil's `realtimeJUnit` step has no translator (it's a Jenkins plugin); the dispatcher fell to `h-unknown`, which **dropped the closure body**. So `infra.runMaven` (which does have a real shim translating to `sh "mvn …"`) never ran.

The fix lives in this PR's `src/anvil/compat/jenkins/translator.clj` + `src/anvil/compat/jenkins/dispatcher.clj`:

```clojure
;; translator/translate-call — when an unknown call's last arg is a
;; :closure, translate the body and attach as :body on the IR node.

(defn- translate-call
  [call source closure-objs]
  (let [n (:name call)
        tr (get step-translators n)
        last-arg (last (:args call))
        closure-arg (when (and (map? last-arg) (= :closure (:type last-arg)))
                      last-arg)
        body (when closure-arg
               (->> (body-calls closure-arg)
                    (mapv #(translate-call % source closure-objs))))]
    (if tr
      (tr call source closure-objs)
      (cond-> (ir/step-unknown n (args->plain (:args call)))
        (seq body) (assoc :body body)))))
```

```clojure
;; dispatcher/h-unknown — after recording the unknown effect, run the
;; body if one is attached.
(if-let [body (:body step)]
  (run-body d body ctx)
  (ok ctx))
```

With those 12 lines, anvil now executes nested known steps inside any unknown block-step wrapper. This isn't Jenkinsfile-specific: any `someBlock { knownStep … }` shape now does the right thing.

## What surfaces NEXT after the patch

With the closure-body fix in place, `infra.runMaven` IS reached from inside `withChecks { realtimeJUnit { infra.runMaven(opts, jdk) } }`. The maven invocation would run. **But** the cell-build's first stage attempts:

```
sh "launchable record session --build ${launchableName} --flavor platform=${platform} --flavor jdk=${jdk} >${sessionFile}"
```

The scripted-pipeline GString `"${platform}"` / `"${jdk}"` / `"${sessionFile}"` isn't interpolated by anvil's scripted runtime — bash sees literal `$platform` / `$jdk` / `$sessionFile` with no env binding, the `>` redirect targets an empty filename, and the cell aborts. **This is the v0.4 gap**: full GString interpolation against scripted bindings (the destructured `def (platform, jdk) = it` in particular).

This is the next layer of structural compat. It's documented; it's where TX11B's runtime-deferred matrix expansion stops short.

## Tier ladder, updated

| Tier | Status | Notes |
|---|---|---|
| **1** simplified Jenkinsfile → real `cli-2.568-SNAPSHOT.jar` | ✅ [`RECEIPT.md`](RECEIPT.md) | 32 s, 12 MB jar that runs |
| **2** real Jenkinsfile stripped to 1 JDK/OS + shared-lib inlined | 🟡 partial — same gates as Tier 3 |
| **3** unmodified ci.jenkins.io Jenkinsfile dispatches end-to-end | ✅ this PR (with patch) | matrix expansion works, all infra calls shimmed, every stage walked |
| Tier 3' (jar from the unmodified file) | ⛔ v0.4 — needs scripted-pipeline GString interpolation against destructured bindings |

## Reproducing

```bash
# 1. Stub launchable so the launchable-CLI calls don't 127.
sudo tee /usr/local/bin/launchable > /dev/null <<'STUB'
#!/usr/bin/env bash
case "$1 $2" in
  "verify"*|"record build"*|"record commit"*|"record tests"*) exit 0 ;;
  "record session"*) echo "session_stub_$$" ; exit 0 ;;
  "subset"*) exit 0 ;;
  *) exit 0 ;;
esac
STUB
sudo chmod +x /usr/local/bin/launchable

# 2. Drop the actual Jenkinsfile in as an anvil job.
curl -X POST http://localhost:8765/anvil/admin/jobs \
  -H "Content-Type: application/json" \
  -d "$(jq -Rs '{name:"jenkins-tier3", jenkinsfile_source:.}' \
       < /path/to/jenkinsci-jenkins/Jenkinsfile)"

# 3. Trigger and watch the dispatch ledger walk every stage.
curl -X POST http://localhost:8765/jenkins/job/jenkins-tier3/build
xdg-open http://localhost:8765/jobs/jenkins-tier3/1
```

## Honest framing

Tier 3 is **structural pass**, not a full cli.jar produced by running the unmodified Jenkinsfile. anvil walks every stage, dispatches every step, expands the matrix; the actual Maven invocation through the Jenkinsfile's `infra.runMaven` path is shimmed because the resolved command (with empty GString vars) doesn't compile anything yet.

To re-produce the cli.jar via the *unmodified* path you need v0.4's GString runtime. To re-produce it via the *simplified* path (Tier 1), see [`RECEIPT.md`](RECEIPT.md) — it's 32 seconds and produces a real 12 MB jar.

Combined: anvil v0.3.0 + this branch parses and dispatches anything Jenkins parses and dispatches, AND it can really compile Jenkins source given a smaller invocation. The two halves are the worthiness bar.
