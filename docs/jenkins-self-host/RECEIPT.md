# anvil v0.3.0 → jenkinsci/jenkins — Tier-1 self-host receipt

> The README claim, finally backed by a checked-in artifact.

## What this proves

**anvil v0.3.0 compiled the Jenkins CLI from `jenkinsci/jenkins:master` source on its own.** Same dogfood host that runs `anvil-self`, `chengis-core-self`, and `chengis-self`. Same uberjar. The "Jenkins-compatible" claim has its first earned mark.

## The artifact

| | |
|---|---|
| Jar | `cli-2.568-SNAPSHOT.jar` |
| Size | 12 MB |
| SHA-256 | `c2b8e98503949c50cf6859cf63343b4ca539e39688cf18698924208cc8005430` |
| Main-Class | `hudson.cli.CLI` |
| Built from | `jenkinsci/jenkins` master @ `ae3fd39` (2026-06-02) |
| Built by | anvil 0.3.0 on the SuperBadLabs dogfood host |
| Build wall-clock | 32 s (warm-ish Maven local cache) |
| Build date | 2026-06-03 |

Smoke test:

```
$ java -jar cli-2.568-SNAPSHOT.jar help
Neither -s nor the JENKINS_URL env var is specified.
Jenkins CLI
Usage: java -jar jenkins-cli.jar [-s URL] command [opts...] args...
Options:
 -s URL              : the server URL (defaults to the JENKINS_URL env var)
 -webSocket          : connect using WebSocket …
 -http               : use a pair of HTTP(S) connections rather than WebSocket
 -ssh                : use SSH protocol rather than WebSocket …
 …
```

That's Jenkins's actual CLI binary printing its actual help text. From a jar anvil built.

## How

The Jenkinsfile is checked in at [`../../examples/jenkinsfiles/jenkins-self-tier1.Jenkinsfile`](../../examples/jenkinsfiles/jenkins-self-tier1.Jenkinsfile). Three stages:

1. `git clone --depth 1 --branch master jenkinsci/jenkins`
2. `mvn -B install -pl cli -am -DskipTests -Dmaven.javadoc.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip -Denforcer.skip=true`
3. Print the receipt

The Jenkinsfile is **declarative**, uses only `agent any` + `sh` (no `withCredentials`, no matrix, no `@Library`, no scripted-pipeline tricks). That's deliberate — Tier 1 is "smallest viable Jenkinsfile that compiles Jenkins."

## The three-tier ladder

Per the v0.3 worthiness bar:

| Tier | What | Status |
|---|---|---|
| **1. Simplified Jenkinsfile compiling Jenkins core/cli** | This document | ✅ |
| **2. Real Jenkinsfile, stripped to one JDK/OS + shared-lib inlined** | Needs declarative-matrix dispatch (v0.3.1 T4.4) + a real (non-shimmed) `@Library` resolver | 🟡 v0.3.x |
| **3. `jenkinsci/jenkins`'s own unmodified Jenkinsfile** | Needs scripted-pipeline `combinations()` runtime (TX11B groundwork), node-label matrix, real shared-lib infra calls | ⛔ v0.4 |

Each tier is a step toward saying "Jenkins-compatible" without an asterisk. Tier 1 takes us from marketing to receipt.

## Reproducing

On a host with anvil 0.3.0 + `mvn` + `git` on PATH:

```bash
curl -X POST http://localhost:8765/anvil/admin/jobs \
  -H "Content-Type: application/json" \
  -d "$(jq -Rs '{name:"jenkins-self", jenkinsfile_source:.}' \
       < examples/jenkinsfiles/jenkins-self-tier1.Jenkinsfile)"

curl -X POST http://localhost:8765/jenkins/job/jenkins-self/build
```

Then watch the build at `http://localhost:8765/jobs/jenkins-self/1`.

## Known issues filed against v0.3.x

While the jar built successfully, the Tier-1 run surfaced these dogfood findings:

1. **Streaming log capture races build-end.** The `consoleText` endpoint returned only the first ~30 lines of Maven output before the build was marked done. The actual jar built fine, but the user-visible console is incomplete. Either log-tail's `drain-grace-ms` is too short for long `sh` steps, or the build-end short-circuits the flush. File against v0.3.1 polish.

2. **`junit` panel didn't auto-detect Surefire output.** The Jenkinsfile didn't include an explicit `junit` step, so this is expected — but if a future Tier-2 Jenkinsfile includes one and the `:junit` feature flag is on, it should populate the dashboard automatically. Validation pending.

## After this

- Tier 2 is the next worthiness step. It needs T4.4 (matrix child-build dispatcher) plus a real `@Library` resolver. Both queued for the v0.3.x cycle.
- The README receipt claim now points at this file instead of a dead `../docs/jenkins-self-host/RECEIPT.md` link.
