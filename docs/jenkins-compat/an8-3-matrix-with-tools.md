---
title: AN8-3 — matrix-declarative-with-tools composition (stub)
audience: developers
category: jenkins-compat
purpose: Plan + receipt stub for the v0.6 AN8-3 ticket — matrix block declaring its own agent / tools per axis composes with outer pipeline tools{}. Populated as AN8-3 lands.
lifecycle: stub
last-verified: 2026-06-08
status: stub
---

# AN8-3 — matrix-declarative with `tools{}`

> **Stub.** Placeholder for the AN8-3 receipt.

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
          axis { name 'JAVA_VERSION' values 'jdk_1.8_latest', 'jdk_11_latest' }
        }
        tools { maven "maven_latest" ; jdk "${JAVA_VERSION}" }
        ...
```

This combines AN8-1 (`tools{}`) with axis-driven tool selection inside
a matrix block. Anvil's matrix support exists (v0.3 T4) but doesn't
compose with `tools{}` against per-axis variables.

Planned:

- [ ] Translator IR extension: matrix entries carry per-axis `tools{}`
- [ ] Dispatcher: resolve `${JAVA_VERSION}` against the active axis
      cell before tool-mapping
- [ ] Composition rules: per-axis tools win on collision; outer
      pipeline tools provide the base
- [ ] Tests against zookeeper's real Jenkinsfile (matrix axis × tools{})

Depends on AN8-1 + (likely) AN8-2 for `${X}` axis-variable
interpolation inside tool selectors.
