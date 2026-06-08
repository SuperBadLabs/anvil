---
title: AN8-4 — SCM-checkout-before-stage-1 lifecycle (stub)
audience: developers, operators
category: jenkins-compat
purpose: Plan + receipt stub for the v0.6 AN8-4 ticket — implicit `checkout scm` before stage 1 of declarative pipelines, matching Jenkins's declarative lifecycle. Populated as AN8-4 lands.
lifecycle: stub
last-verified: 2026-06-08
status: stub
---

# AN8-4 — SCM checkout before stage 1

> **Stub.** Placeholder for the AN8-4 receipt. This is the universal
> fidelity bug from the [AN7-5c receipt](an7-5c-tuning-experiment-receipt.md):
> declarative pipelines without an explicit `checkout scm` step run
> stage 1 against an empty workspace and fail in ~800 ms on
> `git clean -fxd` / `mvn` / similar.

Jenkins's declarative pipeline lifecycle implicitly runs `checkout scm`
between pipeline parsing and stage 1. Scripted pipelines don't —
operators write the explicit `node { checkout scm; ... }` themselves.
Anvil's dispatcher currently follows the scripted contract for both,
which breaks every real-world declarative Jenkinsfile that doesn't
have an explicit checkout.

Planned:

- [ ] Detect declarative pipeline shape during translation
- [ ] Insert a synthetic `checkout scm` step before stage 1 if not
      already present
- [ ] Honor the registered `:scm {:type :url :branch}` config
- [ ] Gate behind `:scm-checkout-lifecycle` flag for a tranche before
      flipping to default-on
- [ ] Receipt: which wild-corpus builds the fix unblocks
- [ ] Anti-fragile: don't re-checkout if the workspace already has
      a `.git` directory matching the configured SCM url + branch
