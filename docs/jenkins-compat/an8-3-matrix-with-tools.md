---
title: AN8-3 — matrix-declarative-with-tools composition
audience: developers
category: jenkins-compat
purpose: Receipt for the v0.6 AN8-3 ticket — matrix blocks declaring their own tools{} per axis compose with outer pipeline tools{}, with ${AXIS} interpolation per cell before AN8-1's image lookup.
lifecycle: stable
last-verified: 2026-06-08
status: shipped
---

# AN8-3 — matrix-declarative with `tools{}`

> **Status: shipped.** Composes with [AN8-1](an8-1-tools-directive.md) under the
> same `:anvil.features/tools-directive` flag. No new flag.

## The Jenkinsfile shape this unlocks

Per the [AN7-5c receipt](an7-5c-tuning-experiment-receipt.md), zookeeper's
real Jenkinsfile is:

```groovy
pipeline {
  agent { label 'Hadoop' }
  stages {
    stage('Prepare') {
      matrix {
        agent any
        axes {
          axis {
            name 'JAVA_VERSION'
            values 'jdk_1.8_latest', 'jdk_11_latest'
          }
        }
        tools {
          maven "maven_latest"
          jdk   "${JAVA_VERSION}"
        }
        stages {
          stage('BuildAndTest') {
            steps { sh "mvn verify" }
          }
        }
      }
    }
  }
}
```

The `tools{}` block lives **inside** the matrix block — sibling of
`axes`/`stages`. The `jdk "${JAVA_VERSION}"` selector varies per cell:
the JDK-1.8 cell should map to a `jdk-1.8` docker image, the JDK-11
cell to a `jdk-11` image.

Before AN8-3 the matrix expander dropped the inner `tools{}` block on
the floor and the cells fell back to the pipeline's default agent. The
classifier read this as `:body-skipped` honestly, but the build never
actually exercised the JDK matrix.

## What ships

### 1. Translator IR extension

`anvil.compat.jenkins.matrix-declarative/parse-matrix-call` now accepts
an optional `tools-parser` callback (the translator passes its own
`translate-tools`). The matrix IR carries:

```clojure
{:type :jenkins/matrix
 :axes [{:name "JAVA_VERSION" :values ["jdk_1.8_latest" "jdk_11_latest"]}]
 :tools [{:type :maven :version "maven_latest"}
         {:type :jdk   :version "$JAVA_VERSION"}]  ; raw GString form
 :stages [...]}
```

The version `"$JAVA_VERSION"` is what Groovy's `GStringExpression.getText()`
hands back for `"${JAVA_VERSION}"` — the translator surfaces it
verbatim so the operator's mapping (and the diagnostic
`:tools/unmapped` payload) reflect what the author actually wrote.

### 2. Per-cell composition (translator)

`expand-matrix-stage` in `translator.clj` now:

- Surfaces each cell's `:matrix-axes` so the dispatcher can interpolate
  per cell at dispatch time.
- Composes `parent-stage :tools ⊕ matrix-level :tools` by `:type`, with
  matrix-level winning on collision (the more-specific declaration).

### 3. Pipeline-level base (agent wrapping)

`anvil.compat.jenkins.agent/wrap-pipeline-with-agent-events` now layers
pipeline-level `:tools` as the BASE under cell/stage tools. With a
pipeline `tools { gradle 'G' }` and a matrix `tools { jdk '${V}' }`,
each cell's synthetic `:jenkins/agent-stage-enter` step carries
`[gradle G, jdk $V]` — the un-overridden pipeline tools survive.

### 4. Dispatch-time `${AXIS}` interpolation

`anvil.tools.images/interpolate-tools` substitutes `${VAR}` /
`$VAR` references in each tool's `:version` against the cell's axis
map. The dispatcher (`h-agent-stage-enter`) calls this before the
AN8-1 candidate-key construction, so the operator's mapping sees a
fully-resolved string:

```
maven_latest+jdk_1.8_latest  →  maven:3.9-eclipse-temurin-8
maven_latest+jdk_11_latest   →  maven:3.9-eclipse-temurin-11
```

A new diagnostic effect surfaces the substitution chain:

```clojure
[:tools/axis-interpolated
 {:stage "Prepare [JAVA_VERSION=jdk_1.8_latest]"
  :referenced-axes ["JAVA_VERSION"]
  :substitutions   {"JAVA_VERSION" "jdk_1.8_latest"}
  :tools-before    [{:type :maven :version "maven_latest"}
                    {:type :jdk   :version "$JAVA_VERSION"}]
  :tools-after     [{:type :maven :version "maven_latest"}
                    {:type :jdk   :version "jdk_1.8_latest"
                     :version-template "$JAVA_VERSION"}]}]
```

## Composition rule, restated

For a stage that's a matrix-expanded cell, the effective tools at
`:jenkins/agent-stage-enter` are:

```
pipeline.tools  ⊕  parent-stage.tools  ⊕  matrix.tools
   ↑ base              ↑                       ↑ wins on :type collision
```

Each later layer overrides on `:type` collision (matching Jenkins's
declarative precedence: more-specific wins). The cell's axis map is
applied to interpolate `${VAR}` references in any tool's `:version`
before image lookup.

## Tests

- `anvil.tools.images-an8-3-test` — 8 unit tests pinning
  `interpolate-tools` (braced/bare `$VAR`, word-boundary,
  replacement-string quoting, no-axes/no-vars short-circuits, multi-axis
  substitution).
- `anvil.compat.jenkins.dispatcher-an8-3-test` — 8 integration tests
  including an end-to-end run against the real
  `test/resources/jenkins-corpus/apache__zookeeper__master__Jenkinsfile.Jenkinsfile`
  (parses to 2 cells; both resolve through operator mappings;
  `:tools/axis-interpolated` fires per cell).

## Feature flag

Gated behind `:anvil.features/tools-directive` — the same flag AN8-1
uses. With it off, all `:tools/*` effects (resolved / unmapped /
axis-interpolated) are silent; the legacy AN5-6 matrix-cell expansion
behavior (steps run on the parent stage's agent) is unchanged.

## Related receipts

- [AN8-1](an8-1-tools-directive.md) — base `tools{}` → operator-mapped image
- [AN8-2 (also in PR #107)](an8-2-parameters-defaults.md) — `parameters{}` `${X}` interpolation
- AN5-6 — declarative matrix inside a stage (translator-only; lives in `translator.clj` `expand-matrix-stage`)
- [AN7-5c](an7-5c-tuning-experiment-receipt.md) — original zookeeper failure mode
