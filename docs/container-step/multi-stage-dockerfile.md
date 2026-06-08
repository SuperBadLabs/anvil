---
title: T3 — multi-stage Dockerfile container-as-step (receipt)
audience: developers, operators
category: container-step
purpose: Receipt for the v0.6 T3 tranche — `agent { dockerfile { args '--target ...' } }` multi-stage Dockerfile support. Extends the v0.4 AN6-3 dockerfile-agent path with `--target` forwarding + cache-key extension + a `:dockerfile-built` SSE event.
lifecycle: shipped
last-verified: 2026-06-08
status: shipped
---

# T3 — multi-stage Dockerfile container-as-step

> **Shipped.** Behind `:anvil.features/dockerfile-multistage`,
> default-on at v0.6.0.

## Why

apache-cassandra's wild-corpus Jenkinsfile declares a multi-stage
Dockerfile agent:

```groovy
agent {
    dockerfile {
        filename '.build/docker/jenkinsfile.Dockerfile'
        dir      '.'
        args     '--target build'
    }
}
```

Without `--target` support, the v0.4 AN6-3 path builds the *last*
stage of the Dockerfile and runs the build's `sh` steps in that
image — which, for a multi-stage cassandra build file, is the wrong
stage (usually the final runtime stage rather than the build-tooling
stage the Jenkinsfile wants). T3 closes the gap.

## What changed in T3

### 1. Translator (`anvil.compat.jenkins.translator`)

The `dockerfile-call` arm of `parse-agent-block` now extracts three
additional fields:

```clojure
{:dockerfile {:filename "Dockerfile"
              :dir      "docker-build"          ; T3 — build context subdir
              :args     "--target prod"         ; T3 — raw arg string
              :target   "prod"}}                ; T3 — parsed from args
```

`args` is scanned for `--target X` *and* `--target=X` (both Jenkins-pipeline
spellings); the captured stage name is lifted into `:target`. The
Jenkins `additionalBuildArgs '...'` alias parses identically — same
regex, two field names. Any other tokens in `args` (e.g. `--platform
linux/amd64`) ride through verbatim under `:args` for future hooks.

### 2. Image-tag cache key (`anvil.tools.dockerfile/dockerfile-image-tag`)

The hash inputs grew from `(Dockerfile-content, COPY/ADD-sources)`
to `(Dockerfile-content, COPY/ADD-sources, --target)`. New arity:

```clojure
(dockerfile-image-tag workspace filename)
;; → AN6-3 v0.4 behavior unchanged: no target, workspace = build context.

(dockerfile-image-tag workspace filename {:target "prod" :dir "build-ctx"})
;; → v0.6 T3 entry point. Tag folds the target string, and both the
;;   Dockerfile path and COPY/ADD sources resolve relative to
;;   <workspace>/<dir>.
```

**Invariant**: same `(content, sources, target)` triple → identical
tag → `docker images -q <tag>` cache hit → no rebuild. Changing
**any** of the three busts the cache. Locked down by
`tag-changes-when-target-changes` + `tag-stable-for-same-target` in
`test/anvil/tools/dockerfile_test.clj`.

Multi-stage Dockerfiles routinely use `COPY --from=<earlier-stage>
/abs/path /dst` to plumb artifacts between stages. The COPY-source
hash skips absolute paths now (they live in a previous image layer,
not on disk), so the workspace-file-hash never trips on a
`/usr/local/bin/app`-style source. Locked down by the
`multistage-dockerfile` fixture in the same test file.

### 3. Build invocation (`anvil.tools.dockerfile/build-image!`)

When `:target` is supplied, the argv becomes:

```bash
docker build -t anvil-dockerfile:<hash> -f Dockerfile --target prod .
```

The build runs in `<workspace>/<dir>` rather than `<workspace>` when
`:dir` is set. Both opts default to nil → the AN6-3 single-stage
argv is byte-for-byte unchanged.

### 4. Dispatcher (`anvil.compat.jenkins.dispatcher`)

The `h-agent-stage-enter` dockerfile branch is gated by **two**
flags now:

| `:dockerfile-agent` | `:dockerfile-multistage` | Behavior                                                       |
| ------------------- | ------------------------ | -------------------------------------------------------------- |
| off                 | (either)                 | `:agent/degraded :runtime-unsupported` (unchanged from AN4-2)  |
| on                  | off                      | v0.4 AN6-3: build *without* `--target`, no `:dir` honored      |
| on                  | on                       | v0.6 T3: forward `:target` and `:dir` to `ensure-image!`       |

The second flag was chosen so flipping it on **never** changes the
build output for single-stage Dockerfiles (`:target` is nil → identical
argv → identical tag). The single-stage AN6-3 cache key is preserved
bit-for-bit: when `:target` is nil, the target-part of the hash is
the empty string.

### 5. `:dockerfile-built` SSE event

On every honored build (cache hit OR miss), the dispatcher publishes:

```clojure
{:type :dockerfile-built
 :job-name <str>
 :build-number <int>
 :dockerfile-path <str>
 :target <str>?            ; only when --target was supplied
 :image-tag "anvil-dockerfile:<16-hex>"
 :cache-hit? <bool>
 :duration-ms <int>}
```

Published to `[:build <job> <n>]`. The bus topic + event-type were
reserved by T0 scaffolding (PR #99) — T3 is the producer.
`publish-dockerfile-built-event!` mirrors `publish-cache-event!` from
v0.5 T1: try/catch around the bus deref, no-ops cleanly when ctx
lacks `:job-name` / `:build-number` (record-only / unit-test paths).

The `:cache-hit?` field is the literal `ensure-image! :cached?` —
true when `docker images -q <tag>` found the tag locally, false on
fresh build. Operators rendering the per-build SSE timeline get a
visible Dockerfile-rebuild marker.

## What did NOT change

- The v0.4 AN6-3 single-stage path. Same Dockerfile, no `args`
  block → identical IR shape, identical image tag, identical
  `docker build` argv. All 12 v0.4 dockerfile-agent tests still
  pass unchanged.
- The bridge to `anvil.tools.dockerfile`. The dispatcher still
  calls `ensure-image!` via `requiring-resolve`; only the opts map
  grew (target, dir).
- The chengis-core docker backend wiring (AN5-3). Once the
  multistage `ensure-image!` returns a tag, the existing
  `{:docker {:image <tag>}}` upgrade routes every subsequent `sh`
  step through `chengis.docker.DockerBackend` exactly as before.

## Anti-goals (deferred to v0.7+)

- **BuildKit cross-host cache federation.** Per AV6-4 this is v0.7
  territory; T3 does NOT attempt to push/pull a remote BuildKit
  cache. Local `docker images -q` is the only cache lookup.
- **Per-stage `dockerfile { … }` blocks** with conditionally
  different targets per declarative `stage`. The translator parses
  per-stage-agent already; T3 doesn't add anything new for that
  shape — each stage gets its own tag because each carries its own
  target.
- **`--build-arg` propagation.** `args '--build-arg FOO=bar'` is
  captured under `:args` but not yet forwarded to `docker build`
  — opening that surface means hashing the build-args too, and
  multi-arg parsing across `args` *and* `additionalBuildArgs` is
  enough complexity to defer to v0.6.x. The receipt's v0.4 honest
  gap on this line remains open.

## Test coverage added

- `test/anvil/tools/dockerfile_test.clj` — 8 new tests:
  - `tag-changes-when-target-changes`
  - `tag-stable-for-same-target`
  - `tag-honors-dir-subdirectory`
  - `tag-changes-when-dir-copy-source-changes`
  - `ensure-image-multistage-record-only-passes-target`
  - `ensure-image-target-cache-hit-skips-build`
  - `ensure-image-different-target-misses-cache`
  - `ensure-image-records-duration-ms`
- `test/anvil/compat/jenkins/translator_test.clj` — 2 new tests:
  - `dockerfile-agent-v0-4-shape-test` (regression — base shape)
  - `dockerfile-agent-v0-6-t3-multistage-test` (4 sub-cases:
    `--target X`, `--target=X`, args-without-target, `additionalBuildArgs`)
- `test/anvil/compat/jenkins/agent_degraded_test.clj` — 4 new tests:
  - `dockerfile-multistage-target-forwarded-to-ensure-image`
  - `dockerfile-multistage-flag-off-drops-target`
  - `dockerfile-built-event-published`
  - `dockerfile-built-event-cache-hit-flag`

Full suite: **957 → 971 tests, 0 failures, 0 errors.**

## Flag flip — default-on at v0.6.0

`:anvil.features/dockerfile-multistage` defaults to true in
`resources/anvil/anvil.edn` for v0.6.0. Operators who want the v0.4
single-stage behavior back can set it to `false`. The flag remains
a known feature so future v0.6.x changes can re-key it if needed.

## Receipt: what shims did this retire?

- The "wild-corpus apache-cassandra agent shape" comment in
  `docs/jenkins-compat/dockerfile-agent.md` (line 84-86) — "Multi-stage
  Dockerfiles are not specially handled" — now obsolete; that line
  is updated alongside this receipt.
- The PR #75 `cassandra-shim` synthetic Ant step (referenced from
  the wild-corpus receipt) is not retired here yet — wiring T3 into
  the wild-corpus-shim resolution is a v0.6.x follow-up (board item
  T3.4 in `docs/roadmap/v0.6-board.md`). The current T3 closes
  T3.1-T3.3 and T3.5 of the board.

## References

- v0.4 AN6-3 receipt: `docs/jenkins-compat/dockerfile-agent.md` —
  base single-stage path
- T0 scaffolding (PR #99): reserved the flag + the SSE event
- v0.6 board: `docs/roadmap/v0.6-board.md` — T3 tranche, lines 175-194
- AV6-4 anti-goal: cross-host BuildKit federation is v0.7+
