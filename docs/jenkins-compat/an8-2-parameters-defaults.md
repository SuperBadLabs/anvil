---
title: AN8-2 — parameters{ choice } defaults (stub)
audience: developers, operators
category: jenkins-compat
purpose: Plan + receipt stub for the v0.6 AN8-2 ticket — declarative `parameters { choice(name:'X', choices:[...]) }` default propagation into `params.X` lookups. Populated as AN8-2 lands.
lifecycle: stub
last-verified: 2026-06-08
status: stub
---

# AN8-2 — `parameters{ choice }` defaults

> **Stub.** Placeholder for the AN8-2 receipt.

Per the [AN7-5c receipt](an7-5c-tuning-experiment-receipt.md): the
real apache-activemq Jenkinsfile uses
`parameters { choice(name: 'nodeLabel', choices: ['ubuntu', ...]) }`
and references `params.nodeLabel` inside `agent { label { label
params.nodeLabel } }`. anvil accepts the `parameters` block
syntactically but doesn't propagate the choice defaults into the
`params` namespace — so the label resolution evaluates to nil.

Planned:

- [ ] Translator extracts `choice.choices[0]` as the default value
- [ ] At dispatch time, populate `params.X` from these defaults
      before evaluating downstream expressions
- [ ] Operator can override via build-trigger query params, REST
      payload, or anvil.edn per-job defaults (the existing
      mechanisms continue to win)
- [ ] Mirror Jenkins's choice semantics: `defaultValue` if declared,
      else first choice
