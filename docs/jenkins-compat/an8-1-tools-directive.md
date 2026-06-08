---
title: AN8-1 — tools{} directive (stub)
audience: developers, operators
category: jenkins-compat
purpose: Plan + receipt stub for the v0.6 AN8-1 ticket — `tools { maven 'X' jdk 'Y' }` translation + operator-mapped pre-baked docker images. Populated as AN8-1 lands.
lifecycle: stub
last-verified: 2026-06-08
status: stub
---

# AN8-1 — `tools{}` directive

> **Stub.** Placeholder for the AN8-1 receipt. Lands with the
> tranche that ships translator+dispatcher support for
> `tools { maven 'X' jdk 'Y' }`.

Per the [AN7-5c receipt](an7-5c-tuning-experiment-receipt.md), all four
wild-corpus heavies (activemq / zookeeper / jdt-core / one stage of
hbase) use the `tools { maven '...' jdk '...' }` directive. anvil's
current translator stub recognizes the directive but doesn't act on
it, so the docker container is whatever the agent image baked — which
is rarely the JDK version the project wants.

Planned approach (per [v0.6 anti-goal](../roadmap/v0.6-board.md#anti-goals-resist):
no anvil-managed JDK installer):

- [ ] Operator maps tool-version → docker image in `anvil.edn`:
      `:anvil.tools/images {"maven-3.9-jdk-17" "maven:3.9-eclipse-temurin-17"}`
- [ ] Translator parses `tools { maven 'X' jdk 'Y' }` into a synthesized
      docker agent if the outer agent didn't already specify one
- [ ] Without operator mapping, fall back to a sensible default +
      emit `[:tools/unmapped]` effect

Receipt lands when the tranche ships.
