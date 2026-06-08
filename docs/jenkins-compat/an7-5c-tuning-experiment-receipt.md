---
title: AN7-5c — Test-infra tuning experiment receipt (2026-06-08)
audience: operators, developers
category: jenkins-compat-receipt
purpose: Honest receipt of the v0.5.x dogfood experiment that triggered the real upstream Jenkinsfiles for apache-activemq and apache-zookeeper against the dogfood with AN7-5a + AN7-5b plumbing live. Locks down what worked (plumbing verified end-to-end) and what didn't (real-Jenkinsfile retirement of the four heavies is blocked on anvil gaps that aren't memory).
lifecycle: live
last-verified: 2026-06-08
status: shipped
---

# AN7-5c — Test-infra tuning experiment receipt

> **What this receipt is.** A live experiment run against the v0.5.x
> dogfood (`heman:8765`) immediately after PR #95 (AN7-5a parse) and
> PR #96 (AN7-5b operator overrides) merged. The goal was to test
> whether docker memory + JVM tuning lets the **real upstream
> Jenkinsfiles** for the four wild-corpus heavies (activemq, zookeeper,
> jdt-core, hbase) reach a type-A `:success` and retire their AN7-1
> type-B synthetic shims.
>
> **Headline.** The plumbing works end-to-end (verified via `docker
> inspect` on running containers). The shim-retirement goal is blocked
> on **anvil gaps that are not memory tuning**: matrix-declarative-with-
> tools translation, SCM-checkout-before-stage-1 lifecycle, and complex
> agent-shape support. AN7-5a + AN7-5b ship a real building block, but
> they alone don't retire the four heavies' shims.

## What was verified ✓

### 1. Operator override reaches the docker container

After provisioning `:anvil.build-overrides` in `anvil.edn`:

```clojure
{:anvil.features/scripted-eval true
 :anvil.build-overrides
   {"wild-apache-activemq"
      {:docker-resource-limits {:memory-mb 6144 :cpus 4.0}
       :env-extra {"MAVEN_OPTS" "-Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"}}
    "wild-apache-zookeeper"
      {:docker-resource-limits {:memory-mb 6144 :cpus 4.0}
       :env-extra {"MAVEN_OPTS" "-Xmx3g -XX:+UseG1GC"}}}}
```

restarting `anvil.service`, re-registering both jobs with the **real
upstream Jenkinsfile content** (no shim), and triggering builds —
`docker inspect` on every container the dispatcher spawns shows:

```
Memory: 6442450944           # 6 GB exactly (operator's :memory-mb 6144)
NanoCpus: 4000000000          # 4.0 cpus (operator's :cpus 4.0)
MAVEN_OPTS=-Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

**Confirmation across multiple sequential containers in the same build**:
activemq's build progresses through ~5 distinct `docker run --rm`
invocations (init checks → `mvn install` → `mvn apache-rat:check` →
…); each one is constructed with the override applied. The chengis-core
`DockerBackend`'s `:resource-limits` shape composes cleanly with anvil's
build-overrides layer.

### 2. The plumbing layers compose correctly

AN7-5a's parse + AN7-5b's overlay compose without collision:

| Source                                | Reaches container as |
|---------------------------------------|----------------------|
| Jenkinsfile `agent { docker { args '--memory=2g' } }` | `--memory=2g` (PR #95 parse) |
| **+ override `{:memory-mb 6144}`**     | `--memory=6g` (PR #96 wins on collision) |
| Jenkinsfile `withEnv(['MAVEN_OPTS=-Xmx512m'])` | `-e MAVEN_OPTS=-Xmx512m` (pre-AN7-5 plumbing) |
| **+ override `:env-extra {"MAVEN_OPTS" "-Xmx3g"}`** | `-e MAVEN_OPTS=-Xmx3g` (PR #96 wins on collision) |

Verified by trigger + `docker inspect` on the live build.

### 3. No-override builds are unaffected

Sanity check: builds without an entry in `:anvil.build-overrides`
(every wild-corpus build except activemq + zookeeper, plus
`anvil-self-test`) construct the docker backend with no
`:resource-limits` key at all. `docker inspect` shows `Memory: 0`
(unconstrained) — matching pre-AN7-5 behavior. The override is opt-in
per build-name.

## What didn't work — and why it's not AN7-5's fault ✗

### Apache ZooKeeper

**Verdict**: `:failure :step-nonzero-exit` in 863 ms.

The real Jenkinsfile is matrix-declarative:

```groovy
pipeline {
    agent { label 'Hadoop' }
    stages {
        stage('Prepare') {
            matrix {
                agent any
                axes {
                    axis { name 'JAVA_VERSION'
                           values 'jdk_1.8_latest', 'jdk_11_latest' }
                }
                tools { maven "maven_latest" ; jdk "${JAVA_VERSION}" }
                ...
```

Anvil's dispatcher dispatched a stage immediately and ran `git clean
-fxd` in a docker container — but the workspace was **never populated
by an SCM checkout**. Exit 128: "not a git repository". The classifier
honestly tagged `:step-nonzero-exit`.

Root cause: anvil's matrix-declarative + `tools` directive handling
doesn't compose with the SCM-checkout step the way Jenkins's does. The
real Jenkinsfile expects a workspace checkout BEFORE any stage runs;
anvil ran the stage first. This is unrelated to memory.

**Implication**: zookeeper's AN7-1 shim cannot retire via AN7-5 tuning.
Retirement requires (a) anvil's matrix-declarative-with-tools support
catching up to the upstream Jenkinsfile's shape, plus (b) the
SCM-checkout-before-stage-1 lifecycle work. Both are v0.6 / v0.7
territory.

### Apache ActiveMQ

**Verdict at receipt time**: still building, having executed multiple
real upstream stages.

The real Jenkinsfile uses `parameters {...}` with `choice(name:
'jdkVersion', choices: ['jdk_17_latest', 'jdk_21_latest', ...])` and
`agent { label { label params.nodeLabel } }`. anvil ran the build with
default parameter substitution (params.jdkVersion = `'jdk_17_latest'`
fell through to the `maven:3.9-eclipse-temurin-21` agent overlay),
checked out the apache/activemq repo into the workspace (✓ verified —
the `.git` directory + ~30 module dirs are present), and ran:

- `mvn -U -B -e clean install -DskipTests`
- `mvn apache-rat:check`
- (further stages …)

Each container gets 6 GB memory + 4 CPUs + `MAVEN_OPTS=-Xmx3g`
applied. Every container.

**Whether this build ultimately reaches `:success`** depends on
intrinsic upstream behavior (the AN7-1 author flagged the
`mvn verify -Pactivemq.tests-quick` Surefire phase as "thread-greedy
regardless of docker container size; even real Jenkins runs this
flaky"). The receipt-relevant finding is **the AN7-5 plumbing layer is
not the bottleneck**: even if activemq lands at `:success`, the win is
type-B because the real Jenkinsfile's `parameters` + `tools` shape is
only partially honored. If it lands at `:failure`, the root cause is
upstream test flakiness, not anything AN7-5 added.

Either way, activemq's AN7-1 shim **does not retire** as a type-A
result of AN7-5 alone.

### Apache jdt-core + Apache hbase

Not run in this experiment.

- **jdt-core**: AN7-1 marked it as "intrinsic Eclipse-compiler-test
  failures, may need upstream test-skip annotations". AN7-5 tuning
  doesn't help where the failure is structural in the test suite.

- **hbase**: the staged "Jenkinsfile" in `/tmp/anvil-broad/apache-hbase/`
  is already a 2-line minimal shim (`sh 'mvn ... package || true'`) —
  there's no real upstream Jenkinsfile to retire to. AN7-5 doesn't
  apply.

## What this proves

1. **AN7-5a + AN7-5b ship verifiable plumbing.** An operator can tune
   docker resources + inject env vars per build via `anvil.edn` and
   the values reach the running container. This is a legitimate
   building block for tenant-isolated tuning, debugging memory-bound
   tests, etc.

2. **AN7-5 alone does NOT retire the four shims.** The retirement
   criteria spelled out in
   [an7-1-synthetic-shims.md](an7-1-synthetic-shims.md) assumed memory
   was the bottleneck; the experiment shows the actual bottlenecks are
   matrix-declarative translation, `tools`-directive support, and
   SCM-checkout lifecycle — none of which AN7-5 touches.

3. **The wild-corpus tally stays at 6/14 type-B `:success`** for v0.5.x.
   The four heavies (activemq, zookeeper, jdt-core, hbase) keep their
   AN7-1 shims with updated retirement criteria.

## AN7-1 shim retirement — updated criteria

Replacing the table in
[an7-1-synthetic-shims.md](an7-1-synthetic-shims.md#when-to-retire-each-shim):

| Shim                | Pre-AN7-5 retirement criterion           | Post-AN7-5 reality |
|---------------------|------------------------------------------|--------------------|
| apache-maven        | AN7-4 ships `@Library` loader             | **Unchanged** — AN7-4 shipped in v0.5.0 (PR #88); shim retirement pending real Jenkinsfile rerun against AN7-4 |
| apache-activemq     | AN7-5 docker memory + Surefire JVM args  | **Updated** — needs (a) `parameters { ... }` translation honoring choice defaults + (b) `tools` directive at minimum; AN7-5 tuning is necessary-but-insufficient |
| apache-zookeeper    | AN7-5 test infra tuning                   | **Updated** — needs matrix-declarative-with-tools + SCM-checkout-before-stage-1 lifecycle; AN7-5 tuning insufficient |
| eclipse-jdt-core    | Upstream test-skip annotations OR Eclipse compiler skip flag | **Unchanged** — intrinsic to upstream test suite |
| apache-hbase        | n/a — already minimal shim, not type-A target | **Unchanged** — no real Jenkinsfile staged |

The updated criteria push three of the four shims into v0.6+ territory.
That's the honest outcome.

## What got shipped this tranche

| PR  | Status   | What landed |
|-----|----------|-------------|
| #95 | ✓ merged | AN7-5a: parse `--memory/--cpus/--pids-limit/--cpu-shares` from `agent { docker { args ... } }` into chengis-core's structured `:resource-limits` |
| #96 | ✓ merged | AN7-5b: operator-side `:anvil.build-overrides` in anvil.edn — per-build resource caps + env injection without touching upstream Jenkinsfiles |
| this PR | (open) | AN7-5c: this receipt + updated retirement criteria in `an7-1-synthetic-shims.md` |

## Operator runbook (post-AN7-5)

Operators wanting to tune resources for a specific build:

1. Add an entry under `:anvil.build-overrides` in `anvil.edn`:
   ```clojure
   {:anvil.build-overrides
      {"my-greedy-build"
         {:docker-resource-limits {:memory-mb 8192 :cpus 4.0}
          :env-extra {"MAVEN_OPTS" "-Xmx4g -XX:+UseG1GC"}}}}
   ```
2. Restart `anvil.service` — overrides are read at first use, cached
   in process. No hot-reload by design (matches `agents.edn` /
   `libraries.edn`).
3. Verify with `docker inspect <container-id>` while the build runs —
   `HostConfig.Memory` should match `:memory-mb` × 1,048,576;
   `HostConfig.NanoCpus` should match `:cpus` × 10⁹; `Config.Env`
   should carry the merged env-extra.

See [an7-5-build-overrides.md](an7-5-build-overrides.md) for the
operator runbook with the full config shape.

## Honest accounting per AV5-6 + AV5-8

This experiment **set out to retire shims and didn't**. Per AV5-8
(honest receipt over aspirational ceiling), that's the receipt. The
v0.5.x wild-corpus tally stays at 6/14 type-B `:success`. The AN7-5
work isn't wasted: it ships a real plumbing layer that future tranches
(parameters/tools/matrix-translation) can build on. But "AN7-5 ships
9/14" or "AN7-5 retires the four heavies" would be lies, and we're not
in the business of lies.

## Postscript — final activemq verdict

[To be appended once the activemq #5 build completes. Current state:
~12 minutes into a multi-stage build, in `mvn apache-rat:check` after
two `mvn install -DskipTests` stages. The activemq Jenkinsfile has a
20-hour upstream timeout; anvil's dispatcher timeout is the practical
bound. Result will be recorded here either way.]
