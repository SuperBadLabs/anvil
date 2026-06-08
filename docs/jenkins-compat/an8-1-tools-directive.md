---
title: AN8-1 — tools{} directive
audience: developers, operators
category: jenkins-compat
purpose: Receipt for the v0.6 AN8-1 ticket — `tools { maven 'X' jdk 'Y' }` translator parsing and operator-mapped pre-baked docker images.
lifecycle: live
last-verified: 2026-06-08
status: shipped
---

# AN8-1 — `tools{}` directive

## What this ticket ships

Per the [AN7-5c receipt](an7-5c-tuning-experiment-receipt.md), every
wild-corpus heavy that uses `tools { maven 'X' jdk 'Y' }` fast-fails
because anvil's translator stub recognized the block but the
dispatcher ignored it. The container ran on whatever the agent
image baked, which was rarely the JDK version the project wanted.

AN8-1 closes the gap with **operator-curated image mapping** — no
anvil-managed JDK installer (per the [v0.6 anti-goal](../roadmap/v0.6-board.md#anti-goals-resist)).

### Pieces

1. **Translator** (`anvil.compat.jenkins.translator/translate-tools`) — parses
   `tools { … }` at pipeline AND stage levels into structured IR:
   ```clojure
   [{:type :maven :version "maven_3_latest"}
    {:type :jdk   :version "jdk_17_latest"}]
   ```
   The old `[{:raw "<…>"}]` placeholder is gone.

2. **Nested-stages tool propagation** — `expand-nested-stages-stage`
   now propagates a parent stage's `:tools` to children that don't
   declare their own. Mirrors Jenkins's wrapper-stage semantics:
   apache-struts' `JDK 21 { tools { … } stages { stage('Test') } }`
   wants `Test` to inherit the jdk_21 tools spec.

3. **Operator config** (`anvil.tools.images`) — reads
   `:anvil.tools/images` from `anvil.edn` and resolves a tools-spec
   vector to a docker image via a priority-ordered candidate-key
   list. Operator maps however they like:
   ```clojure
   {:anvil.tools/images
      {"maven_3_latest+jdk_17_latest" "maven:3.9-eclipse-temurin-17"
       "jdk_17_latest"                "eclipse-temurin:17-jdk"
       "jdk_1.8_latest"               "maven:3.9-eclipse-temurin-8"
       "*"                            "fallback-default-image"}}
   ```

4. **Dispatcher** (`h-agent-stage-enter`) — when the stage's effective
   tools spec resolves through the operator map, `:active-agent`
   upgrades to `{:docker {:image <mapped>} :resolved-from-tools {…}}`
   and a `:tools/resolved` effect lands in the log. No mapping →
   `:tools/unmapped` effect with every candidate key the operator
   could have chosen + fall-through to the Jenkinsfile-declared
   agent.

5. **Anti-clobber rule** — a Jenkinsfile that explicitly declares
   `agent { docker { image '…' } }` is NEVER overridden by an
   operator tools map. Author choice wins.

6. **Feature flag** — `:anvil.features/tools-directive`,
   closed-by-default. Operators flip it on after they've populated
   `:anvil.tools/images`.

## Candidate-key priority

Given `tools { maven 'M' jdk 'J' }` the resolver tries (in order):

| Priority | Key                          | When to use                           |
|----------|------------------------------|---------------------------------------|
| 1        | `"M+J"`                      | The raw Jenkinsfile surface — easiest |
| 2        | `"J+M"`                      | Canonical sort if you prefer          |
| 3        | `"maven-M+jdk-J"`            | Disambiguates if version strings collide across tools |
| 4        | `"jdk-J+maven-M"`            | Canonical-sort composite              |
| 5        | `"M"`, `"maven-M"`, `"maven"`| Per-tool fallback (maven)             |
| 5        | `"J"`, `"jdk-J"`, `"jdk"`    | Per-tool fallback (jdk)               |
| 6        | `"*"`                        | Wildcard last-resort                  |

The first hit wins; the rest are still in `:tools/unmapped`'s
`:candidate-keys` so the operator sees every key they could have
mapped.

## What gets tested

| Test namespace                                              | Coverage                                                                                       |
|-------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| `anvil.tools.images-test`                                   | Candidate-key generation, deduplication, priority ordering, wildcard fallback, miss-diagnostics |
| `anvil.compat.jenkins.translator-an8-test`                  | Translator extracts `:tools` at pipeline + stage levels; GString / bare-identifier versions    |
| `anvil.compat.jenkins.dispatcher-an8-tools-test`            | End-to-end: wrapped pipeline runs, `:tools/resolved` + `:tools/unmapped` effects, feature-flag gate, anti-clobber, stage-override |
| `anvil.compat.jenkins.corpus-an8-test`                      | Wild-corpus real Jenkinsfiles: apache-ambari, struts, dubbo, hop, zookeeper-PreCommit, analysis-model all surface structured `:tools` IR |

`lein test` — all 985 tests pass.

## Wild-corpus shapes confirmed

| Real Jenkinsfile                                                   | `tools{}` shape                                                       | Translator output                                                                        |
|--------------------------------------------------------------------|-----------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| `apache/ambari` trunk                                              | `maven 'maven_3_latest' jdk 'jdk_17_latest'`                          | `[{:type :maven :version "maven_3_latest"} {:type :jdk :version "jdk_17_latest"}]`       |
| `apache/struts` main (per-JDK stage)                               | `jdk 'jdk_21_latest' maven 'maven_3_latest'` inside `stage('JDK 21')` | per-stage `:tools` propagated through nested `stages{}` to leaf `stage('Test')`          |
| `apache/dubbo` 3.3                                                 | `maven 'Maven 3 (latest)' jdk 'JDK 1.8 (latest)'`                     | structured pair (no `:raw`)                                                              |
| `apache/zookeeper` PreCommit                                       | `maven "maven_latest" jdk "jdk_1.8_latest"`                           | structured pair                                                                          |
| `jenkinsci/analysis-model` declarative                             | `maven 'mvn-default' jdk 'jdk-default'`                               | structured pair                                                                          |
| `apache/hop` daily (stage-level)                                   | `tools { jdk 'jdk_17_latest' }` inside `stage('Code Quality')`        | stage-level `:tools` overrides pipeline-level                                            |

## How an operator unblocks a wild-corpus heavy

1. Trigger the build once with `:tools-directive` flag on.
2. Open the build console; find the `:tools/unmapped` effect.
3. Copy one of the `:candidate-keys` (raw form is the easiest).
4. Add an entry under `:anvil.tools/images` in `anvil.edn`.
5. Restart anvil (or wait for the hot-reload watcher introduced in
   AN7-5b's `anvil.build-overrides`; the tools-images namespace
   currently uses the same lazy-cache shape but doesn't yet
   register a watcher — see "Follow-ups" below).
6. Re-trigger the build; the `:tools/resolved` effect should
   appear and the container should run on the mapped image.

## What this does NOT do

- **No JDK provisioning.** anvil never installs a JDK. The operator
  must pre-bake (or use an existing) docker image that already
  contains the version the Jenkinsfile names.
- **No GString interpolation of version strings.** `tools { jdk
  "${JAVA_VERSION}" }` parses the template text into `:version`
  but doesn't resolve `${JAVA_VERSION}` against the parameter
  defaults. Operators wanting that case can map either the literal
  template (`"${JAVA_VERSION}"`) or use a wildcard. The right fix
  is AN8-3 (matrix-declarative-with-tools), which carries the axis
  values through to the lookup key.
- **No retroactive image swap on running containers.** A stage's
  agent is decided once at stage-enter. Mid-stage tool spec changes
  are out of scope.

## Follow-ups

- **Hot-reload of `:anvil.tools/images`** — current impl reads
  anvil.edn once at first lookup, cached in process. AN7-5b's
  `anvil.build-overrides` ships a `WatchService` watcher; replicate
  for tools-images in a future v0.6.x patch if operators need
  hot-reload.
- **AN8-3 composes with this** — matrix-declarative cells can each
  carry their own `tools{}` block; AN8-3 will need the IR shape this
  ticket lays down.
- **`:tools/unmapped` UX** — the diagnostic is currently effects-log
  only. A future v0.6.x patch could surface it on the build console
  badge so operators see the gap without grepping effects.
