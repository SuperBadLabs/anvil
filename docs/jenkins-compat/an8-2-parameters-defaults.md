---
title: AN8-2 — parameters{ choice } defaults
audience: developers, operators
category: jenkins-compat
purpose: Receipt for the v0.6 AN8-2 ticket — declarative `parameters { choice(name:'X', choices:[...]) }` default propagation into `params.X` lookups.
lifecycle: live
last-verified: 2026-06-08
status: shipped
---

# AN8-2 — `parameters{ choice }` defaults

## What this ticket ships

Per the [AN7-5c receipt](an7-5c-tuning-experiment-receipt.md): the
real apache-activemq Jenkinsfile uses
`parameters { choice(name: 'nodeLabel', choices: ['ubuntu', ...]) }`
and references `params.nodeLabel` inside `agent { label { label
params.nodeLabel } }`. The AN6-1 translator already parses the
`parameters{}` block into structured IR — but `params.X` is read at
runtime from `ctx :parameters`, which the runner only seeded with
the trigger payload. Pure-declarative builds with no payload got
`params.X == nil` and the label resolution fell through to dynamic.

AN8-2 closes the gap with a **three-layer parameter merge** at
build-start time.

### Pieces

1. **IR helper** (`anvil.compat.jenkins.ir/default-parameters`) —
   given a parsed pipeline IR, returns a `{name → default-string}`
   map mirroring Jenkins's declarative-parameters semantics:
   - `string(defaultValue: 'X')` → `"X"`
   - `booleanParam(defaultValue: true)` → `"true"`
   - `choice(defaultValue: 'X', choices: [...])` → `"X"`
   - `choice(choices: ['a', 'b'])` → `"a"` (first choice)
   - Parameters with no resolvable default are omitted.

2. **Operator config** (`anvil.parameters-defaults`) — reads
   `:anvil.parameters/defaults` from `anvil.edn` for per-job
   operator overrides:
   ```clojure
   {:anvil.parameters/defaults
      {"wild-apache-activemq"
         {"nodeLabel"  "ubuntu"
          "jdkVersion" "jdk_17_latest"}}}
   ```

3. **Runner** (`anvil.web.jenkins-api.runner/run-build!`) — at
   build-start the runner now:
   1. Pre-parses the Jenkinsfile to extract translator defaults.
   2. Reads operator defaults from anvil.edn.
   3. Merges per the resolution order below.
   4. Threads the merged map into `ctx :parameters` (which the
      scripted-runtime exposes as `params.X`).
   5. Emits a `:parameters/defaults-applied` effect with all three
      layers so operators can audit what flowed in from where.

4. **Resolution order** (later layers win on key conflict):
   ```
   translator-defaults  <  operator-defaults  <  runtime-params
   ```
   - The Jenkinsfile's static defaults are the floor.
   - The operator can pin a per-job choice in anvil.edn.
   - The REST trigger payload / build-form / webhook still wins.

5. **Feature flag** — `:anvil.features/parameters-defaults`,
   closed-by-default. With the flag off, only runtime params seed
   ctx (preserving v0.5.x behavior). The pre-parse only runs when
   the flag is on, so the perf cost is opt-in.

## What gets tested

| Test namespace                                        | Coverage                                                                                            |
|-------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `anvil.parameters-defaults-test`                      | Three-layer merge, nil tolerance, per-job lookup, anvil.edn cache                                   |
| `anvil.compat.jenkins.translator-an8-test`            | `ir/default-parameters` extracts first-choice / explicit `defaultValue` / multi-param shapes        |
| `anvil.compat.jenkins.corpus-an8-test`                | Wild-corpus regression: apache-camel's `PLATFORM_FILTER`/`JDK_FILTER` → first-choice defaults; apache-cassandra-driver datastax `ADHOC_BUILD_TYPE` → "BUILD"; apache-camel-deploy `CLEAN` → "true" |

`lein test` — all 985 tests pass.

## Wild-corpus shapes confirmed

| Real Jenkinsfile                                    | `parameters{}` shape                                                                       | Defaults extracted                                |
|-----------------------------------------------------|--------------------------------------------------------------------------------------------|---------------------------------------------------|
| `apache/camel` main                                 | `choice(name: 'PLATFORM_FILTER', choices: ['all', 'ppc64le', 's390x', 'ubuntu-avx'])` + `choice(name: 'JDK_FILTER', …)` | `{"PLATFORM_FILTER" "all" "JDK_FILTER" "all"}`     |
| `apache/camel` deploy                               | `booleanParam(name: 'CLEAN', defaultValue: true, …)`                                       | `{"CLEAN" "true"}`                                |
| `apache/cassandra-java-driver` datastax             | `choice(name: 'ADHOC_BUILD_TYPE', choices: ['BUILD', 'BUILD-AND-EXECUTE-TESTS'])`          | `{"ADHOC_BUILD_TYPE" "BUILD"}`                    |

## Why the runner pre-parses

The runner already parsed the Jenkinsfile inside `run-build!` once;
AN8-2 needs the IR earlier so it can seed parameter defaults BEFORE
the env vars are built (env vars include parameters via
`:extra-env` for `${BUILD_PARAM}`-style scripted access). The
implementation reuses the pre-parse result, so total parse cost
stays at one pass per build. When the pre-parse throws (malformed
Jenkinsfile, etc.), AN8-2 falls back to no translator defaults and
the second parse (with its own error path) takes over — preserving
v0.5.x error reporting unchanged.

## How an operator pins a parameter default

1. Identify which parameter the Jenkinsfile expects (e.g.
   `params.nodeLabel`).
2. Add to anvil.edn under `:anvil.parameters/defaults
   <job-name> <param-name>`.
3. Restart anvil (lazy-cached, same shape as `:anvil.build-overrides`).
4. Re-trigger; the `:parameters/defaults-applied` effect will show
   the three layers and the effective value.

Example anvil.edn:
```clojure
{:anvil.features/parameters-defaults true
 :anvil.parameters/defaults
   {"wild-apache-activemq"
      {"nodeLabel"  "ubuntu"
       "jdkVersion" "jdk_17_latest"}}}
```

## What this does NOT do

- **No mid-build mutation of `params.X`.** The merge happens once,
  at build-start. Setting `params.X = 'newval'` inside a stage
  script is still a no-op in real Jenkins too — `params` is a
  read-only Map.
- **No retroactive coercion.** Boolean / numeric Jenkins params
  arrive as their string form (Jenkins itself uses String for
  `params.X`). Scripts that want a typed value should `Boolean.parseBoolean(params.X)`.
- **No matrix-axis seeding.** AN8-3 (matrix-declarative-with-tools)
  is the right home for "when a matrix axis has values ['17','21'],
  seed `params.JAVA_VERSION` per cell". This ticket only handles
  the static `parameters{}` block.

## Follow-ups

- **Build-trigger UI display** of the resolution layers — the
  effect carries them but the /build page doesn't yet render them
  side-by-side. v0.6.x UX patch.
- **Hot-reload** of `:anvil.parameters/defaults` — same lazy-cache
  shape as `:anvil.tools/images` (AN8-1) and `:anvil.build-overrides`
  (AN7-5b). A future patch can extend AN7-5b's `WatchService` to
  these too.
- **Composition with AN7-2 GString resolution** — `agent { label
  "${PLATFORM}" }` already resolves through the translator's
  `:parameters` IR statically (AN7-2). The runtime path through
  ctx is independent; both should agree, but if they diverge a
  future test should pin the contract.
