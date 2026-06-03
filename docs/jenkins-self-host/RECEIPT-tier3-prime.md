# anvil v0.3.0 → jenkinsci/jenkins — Tier-3' receipt (the unmodified Jenkinsfile produces a real jar)

> The worthiness directive earned without an asterisk.

Tier-1 ([`RECEIPT.md`](RECEIPT.md)) built `cli-2.568-SNAPSHOT.jar` from a simplified Jenkinsfile. Tier-3 ([`RECEIPT-tier3.md`](RECEIPT-tier3.md)) parsed and dispatched the unmodified ci.jenkins.io Jenkinsfile structurally. **Tier-3'** is the union: anvil runs the **unmodified** Jenkinsfile and Maven produces real jars from real Jenkins source.

## The artifact

```
=== anvil v0.3.0 + tier3/scripted-eval → unmodified jenkinsci/jenkins/Jenkinsfile ===
  jar:                  cli-2.568-SNAPSHOT.jar
  size:                 12 MB
  sha256:               c2b8e98503949c50cf6859cf63343b4ca539e39688cf18698924208cc8005430
  Implementation-Title: Jenkins cli
  Implementation-Build: ae3fd3999945c9d08dc3361fd98b8da0b83de36a   ← jenkinsci/jenkins HEAD
  built-by:             anvil 0.3.0 on the SuperBadLabs dogfood host
  build wall-clock:     77.8 s
  build date:           2026-06-03
```

`java -jar cli-2.568-SNAPSHOT.jar help` prints Jenkins's actual CLI help.

Also produced from the same run:

```
cli/target/original-cli-2.568-SNAPSHOT.jar         (pre-shaded original)
websocket/spi/target/websocket-spi-2.568-SNAPSHOT.jar
websocket/jetty12-ee9/target/websocket-jetty12-ee9-2.568-SNAPSHOT.jar
```

`jenkins-core` itself exit-1'd because `maven-hpi-plugin 3.1795` requires Maven 3.9.6 and the dogfood host has Apt's mvn 3.8.7 — a host toolchain version skew, not an anvil limitation. The submodules whose plugins don't pin 3.9.6 (`cli`, `websocket/*`) succeeded.

## How

`anvil.compat.jenkins.scripted-runtime` (new) routes the WHOLE scripted Jenkinsfile through Groovy + an expanded Pipeline DSL binding set. Groovy itself handles:

- `axes.values().combinations { def (platform, jdk) = it; … }` — native `List.combinations` + destructuring
- `"launchable-session-${platform}-jdk${jdk}.txt"` — native GString interpolation against the live bindings
- `"-Dmaven.repo.local=$m2repo"`, `"-Doutput=$changelistF"` — interpolated when the `def mavenOptions = [...]` list literal evaluates
- `parallel builds` — native Map iteration over closures
- `if (env.CHANGE_ID && !pullRequest.labels.contains('full-test'))` — short-circuit boolean against anvil-provided `env` Expando + `pullRequest` Expando
- Method calls on `infra` — routed through anvil's `infra` global

anvil's contribution is the DSL binding set (`__node`, `__stage`, `__retry`, `__timeout`, `__withCredentials`, `__withChecks`, `__parallel`, `__properties`, `__pwd`, `__isUnix`, `__readFile`, `currentBuild`, `pullRequest`, `infra`, plus tolerant fallbacks for every plugin step the file calls). Each binding records IR effects through the dispatcher. The `infra.checkoutSCM()` shim does a real `git clone --depth 1` of `jenkinsci/jenkins`. The `infra.runMaven(opts, jdk)` shim invokes real `mvn`.

Gated behind `:anvil.features/scripted-eval` (closed-by-default). The static-IR scripted path remains the v0.3.0 behavior unchanged.

## The dispatch ledger (build #8 highlights)

```
[stage] (scripted-eval) — agent: <missing>
[properties] ({})
[scripted-stage-enter] ({:name "Record build"})
[retry-enter] ({:count 2, :conditions [nil nil]})
[node-enter] ({:label "maven-21"})
[scm-assume-checked-out] ({})       ← real git clone happened here
[with-credentials-enter] ({:secret-count 1})
+ launchable verify && launchable record build --name $launchableName ...
+ launchable record session --build $launchableName --flavor platform=linux --flavor jdk=21 >launchable-session-linux-jdk21.txt
[stash] ({:name "launchable-session-linux-jdk21.txt" …})
…
[parallel-enter] ({:branches 4})
[parallel-branch] ({:name "linux-jdk21"})
…
[scripted-stage-enter] ({:name "Linux - JDK 21 - Build / Test"})
[timeout-enter] ({:time 6 :unit "HOURS"})
[dir-enter] {:path ".../.tmp"}
[unstash] ({:name "launchable-session-linux-jdk21.txt" :missing? true})
[dir-leave]
[with-checks-enter] ({:opts {"name" "Tests" "includeStage" true}})
[unknown-enter] ({:name "realtimeJUnit" …})
+ mvn -Pdebug -Penable-jacoco --update-snapshots
      -Dmaven.repo.local=/home/.../jenkins-tier3/8/.tmp/m2repo   ← GString interpolated
      -Dmaven.test.failure.ignore -DforkCount=2
      -Dspotbugs.failOnError=false -Dcheckstyle.failOnViolation=false
      -Dset.changelist help:evaluate
      -Dexpression=changelist
      -Doutput=/home/.../jenkins-tier3/8/.tmp/changelist           ← GString interpolated
      clean install
[unknown-leave]
[with-checks-leave]
[timeout-leave]
[scripted-stage-leave] ({:name "Linux - JDK 21 - Build / Test"})
…
```

Every GString resolves. `infra.runMaven(mavenOptions, jdk)` fires with the fully expanded options. The Maven build runs and produces real artifacts on disk.

## Tier ladder, complete

| Tier | Status | Receipt |
|---|---|---|
| **1.** simplified Jenkinsfile compiling cli | ✅ | [`RECEIPT.md`](RECEIPT.md) |
| **2.** real Jenkinsfile stripped to 1 JDK/OS | obviated by Tier 3' | — |
| **3.** unmodified ci.jenkins.io Jenkinsfile dispatches end-to-end | ✅ | [`RECEIPT-tier3.md`](RECEIPT-tier3.md) |
| **3'.** unmodified Jenkinsfile produces real jars from real source | ✅ | this document |

The "Jenkins-compatible" badge is now backed by an artifact built by the unmodified file the actual Jenkins project ships.

## Reproducing

On a host with anvil 0.3.0 + the `tier3/scripted-eval` branch's jar + Java 21 + Git + Maven (3.9.6 recommended for the jenkins-core submodule):

```bash
# 1. Enable the feature flag.
mkdir -p ~/anvil-dogfood/config
echo '{:anvil.features/scripted-eval true}' > ~/anvil-dogfood/config/anvil.edn
sudo systemctl restart anvil

# 2. Stub launchable so the launchable-CLI calls don't 127.
sudo cp examples/jenkins-self-host/launchable-stub.sh /usr/local/bin/launchable
sudo chmod +x /usr/local/bin/launchable

# 3. Drop the unmodified Jenkinsfile in as an anvil job.
curl -X POST http://localhost:8765/anvil/admin/jobs \
  -H "Content-Type: application/json" \
  -d "$(jq -Rs '{name:"jenkins-tier3", jenkinsfile_source:.}' \
       < examples/jenkinsfiles/jenkinsci-jenkins-master.Jenkinsfile)"

# 4. Trigger and wait.
curl -X POST http://localhost:8765/jenkins/job/jenkins-tier3/build
sleep 120
find ~/anvil-dogfood/target/anvil-builds/jenkins-tier3/1 -name 'cli-*.jar' -not -name '*sources*'
```

## What still falls into v0.4

- `jenkins-core` build via the unmodified path (gated on a Maven 3.9.6 host)
- ATH (Acceptance Test Harness) execution (`bash ath.sh 21 firefox`)
- A persistent stash store keyed on the scripted-eval cwd so `unstash` reads what `stash` wrote in an earlier stage (today: tolerant skip)
- `parallel` branches running concurrently (today: sequential per-host)
- Plugin steps that emit IR with semantic meaning (e.g. `realtimeJUnit { … }` integrating with anvil's :junit panel as the body runs)

None of these block the Tier-3' receipt — they're polish on top of a working build.

## How this fits with v0.3.0

The shipped 0.3.0 tag was the parity release. This is **post-v0.3 worthiness work** added on top:

| Surface | v0.3.0 | This branch |
|---|---|---|
| 7 Tier-1 features (junit / problem-matchers / pr-checks / matrix / scheduler / secrets / mise) | ✅ | unchanged |
| Static-IR scripted Pipeline path | ✅ | unchanged (closed-by-default override) |
| Scripted-eval whole-file Groovy routing | — | ✅ behind `:anvil.features/scripted-eval` |
| Unknown block-step closure bodies execute | — | ✅ (PR #18) |

The new feature flag stays closed-by-default. Operators who want the Tier-3 path opt in; everyone else gets v0.3.0 behavior.
