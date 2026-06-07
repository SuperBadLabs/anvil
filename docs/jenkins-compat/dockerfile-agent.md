# `agent { dockerfile }` support (v0.4 AN6-3)

apache-cassandra's Jenkinsfile declares

```groovy
agent {
    dockerfile {
        filename '.build/docker/jenkinsfile.Dockerfile'
    }
}
```

In v0.3.3 this classified as `:unsupported runtime-unsupported` —
anvil correctly refused to silently bypass a container requirement
(the AN4-2 honesty contract). AN6-3 makes the shape **honored** at
v0.4.0, behind a feature flag.

## What `agent { dockerfile … }` means in Jenkins

The repo contains a `Dockerfile` (or a custom-named variant); Jenkins
builds it once, tags it ephemerally, and uses the resulting image for
the stage's shell steps. The build context is the repo workspace, so
`COPY src dst` instructions can reference checked-out files.

## How anvil honors it

When `:anvil.features/dockerfile-agent` is on AND the dispatcher is
in `:execute? true` mode:

1. **Compute a content-hash tag.** SHA-256 over the Dockerfile body
   + SHA-256 of every workspace file the Dockerfile names via
   `COPY` / `ADD`. The tag is `anvil-dockerfile:<16-hex>`.
2. **Cache hit?** `docker images -q <tag>` checks the local image
   store. If the image exists, skip the build — emit
   `[:dockerfile/image-cached {:tag …}]`.
3. **Cache miss?** Invoke `docker build -t <tag> -f <filename> <workspace>`.
   Emit `[:dockerfile/image-built {:tag …}]` with the exit code.
4. **Upgrade ctx :active-agent** from `{:dockerfile {…}}` to
   `{:docker {:image <tag>} :resolved-from-dockerfile <filename>}`.
   The existing AN5-3 `DockerBackend` bridge then routes every
   subsequent `sh` step into the built image, exactly as
   `agent { docker { image '…' } }` does.

## The cache key

Hash inputs:

- **Dockerfile content** (full body, comments included — a comment
  edit busts the cache even though `docker build` would treat it
  as a no-op)
- **Each named `COPY` / `ADD` source** that resolves to a workspace
  file. `COPY --chown=root:root src dst` correctly extracts `src`
  despite the `--chown=` flag (the parser drops anything starting
  with `--`).

What's NOT in the cache key:

- Files outside the workspace
- Files NOT named by any `COPY` / `ADD` (heuristic: the daemon's own
  layer cache covers these; we only care about the broad-stroke
  "did anything important change?" question)
- The git head sha (could be added; the file-hash already changes
  per commit when source files differ, so this would only matter
  for commits that touch only un-COPY'd files)

## First-build cost

A cold cache means a real `docker build` runs — for apache-cassandra
this is a multi-minute setup that pulls the base image + apt-gets the
JDK + Maven. Subsequent builds reuse the tag if neither the Dockerfile
nor its named COPY sources changed.

Combined with **AN6-6** (`--max-minutes` harness cap), an operator
running the wild-corpus rerun with apache-cassandra should bump both:

```bash
bb -Jmax-minutes=90 scripts/wild-corpus-rerun.bb
# and in anvil.edn:
{:anvil.features/dockerfile-agent true}
```

## Honest gaps remaining

- **Multi-stage Dockerfiles** are not specially handled — the daemon
  builds the final target. Per-stage targeting (`--target=builder`)
  is not exposed; v0.4.x territory.
- **Build args** (`docker build --build-arg`) — `agent { dockerfile {
  additionalBuildArgs '...' } }` is honored as `extra-args` passed
  to `build-image!` but the translator doesn't currently extract
  the Jenkinsfile `additionalBuildArgs` field. Receipt-only at
  v0.4.0; v0.4.x adds it.
- **Per-stage `dockerfile`** vs **per-pipeline** — anvil honors
  both shapes the same way; the dispatcher's `h-agent-stage-enter`
  fires per stage regardless.
- **No registry push** — the built image stays local. CI runs that
  want to push to a registry use a separate `sh 'docker push'` step.

## Why this lives in anvil (not chengis-core) at v0.4.0

Per **AV4-8**, AN6-3 was originally framed as a `chengis.tools.dockerfile`
addition warranting a chengis-core 0.4.0 bump. The impl landed in
anvil first under `anvil.tools.dockerfile` to unblock apache-cassandra
and any other dockerfile-agent build without gating on a chengis-core
release cycle. The namespace will extract to
`chengis.tools.dockerfile` when chengis-core 0.4.0 ships; the public
surface is intentionally small (3 entry points: `dockerfile-image-tag`,
`build-image!`, `ensure-image!`) so the extraction is mechanical.

## References

- AN4-2 contract: `docs/jenkins-compat/an4-2-agent-degraded.md` — why
  the v0.3.3 :unsupported classification was the right answer
- AN5-3 routing: `docs/jenkins-compat/an5-3-docker-backend.md` — the
  bridge AN6-3's image-tag plugs into
- chengis-core extraction: tracked for v0.4.x release cycle
- Wild-corpus receipt: `docs/jenkins-compat/wild-corpus-honest-receipt.md`
  — 2026-06-06 entry, apache-cassandra row (currently excluded as
  "harness TIMEOUT"; the next wild-corpus rerun under AN6-3 should
  flip it to a real result)
