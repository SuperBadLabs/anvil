# Container-as-step (v0.4 — T2)

Status: **stub** (T0.5 placeholder). Real docs land at T2.7 + T7.5.

## What this feature ships

A Jenkinsfile / Chengisfile step of the form

```groovy
steps {
    container('maven:3.9-eclipse-temurin-21') {
        sh 'mvn -B clean package'
    }
}
```

runs the wrapped `sh` inside the named image via chengis-core's
`DockerBackend`. The same plumbing AN5-3 wired for `agent { docker }`
is reused here per **AV4-2** — no new container abstraction.

Composes with declarative `matrix`: each cell may declare a different
image (JDK17 cells run on `maven:3.9`, JDK21 cells run on
`maven:3.9-eclipse-temurin-21`).

## Substrate

- chengis-core ≥ 0.3.0 `DockerBackend` (the AN5-3 bridge)
- chengis-core ≥ 0.3.0 installer matrix (Temurin / Maven / Gradle / Node)
- v0.3 declarative matrix (T4 of the v0.3 board) — cells are normal
  builds with per-cell IR, so per-cell containers are a natural fit

## Files (planned, not yet written)

- `src/anvil/compat/jenkins/translator.clj` — parse `container 'image' {…}` (T2.1)
- `src/anvil/compat/jenkins/dispatcher.clj` — route `:container/wrap` IR
  through DockerBackend (T2.2)
- `docs/container-step/env.md` — env propagation list (T2.3)
- `docs/container-step/composes-with-matrix.md` — per-cell image pattern (T2.4)
- `test/anvil/compat/jenkins/container_step_test.clj` — 3 shapes (T2.6)

## Not in scope (v0.5+)

- Custom build context / multi-stage Dockerfile-as-step
- Cross-step image sharing (each `container` block re-spins from cache)
- Container resource limits (cpu/memory pinning) — host defaults stand
