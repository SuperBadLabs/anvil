---
title: T3 — multi-stage Dockerfile container-as-step (stub)
audience: developers, operators
category: container-step
purpose: Plan + receipt stub for the v0.6 T3 tranche — `agent { dockerfile { args '--target ...' } }` multi-stage build support via chengis-core's docker backend. Populated as T3 lands.
lifecycle: stub
last-verified: 2026-06-08
status: stub
---

# T3 — multi-stage Dockerfile container-as-step

> **Stub.** Placeholder for the T3 receipt. Lands with the tranche
> that ships multi-stage support for the existing
> [dockerfile-agent](dockerfile-agent.md) (AN6-3 from v0.4) extended
> for `--target` selection.

Why: cassandra-real-Jenkinsfile uses a multi-stage Dockerfile agent.
Without `--target` support, anvil builds all stages and runs the last
one, which is often not the build stage cassandra-real wants.

Planned sections:

- [ ] Translator support for `agent { dockerfile { filename '...'
      dir '...' args '--target prod' } }`
- [ ] chengis-core docker-backend honors `:dockerfile-build` step type
- [ ] Image cache key includes `(Dockerfile-content, COPY/ADD-sources,
      --target)`
- [ ] BuildKit cache federation (in-process, not cross-host —
      cross-host is v0.7)
- [ ] Wiring with wild-corpus-shim resolution so cassandra-real
      bypasses the PR #75 Ant synthetic
- [ ] Receipt: did the multi-stage path retire any shims?

Depends on no other v0.6 tranches.
