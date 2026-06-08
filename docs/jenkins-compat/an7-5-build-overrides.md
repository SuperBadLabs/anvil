---
title: AN7-5b — operator-side build overrides (resource limits + env)
audience: operators
category: jenkins-compat
purpose: How to tune docker memory / CPU / pids-max and inject extra env vars into specific builds without modifying the upstream Jenkinsfile.
lifecycle: live
last-verified: 2026-06-08
status: shipped
---

# AN7-5b — operator-side build overrides

> **What this is.** A per-build overlay in `anvil.edn` that injects docker
> resource limits and extra env vars at execute time. Lets operators tune
> resource-hungry builds (the wild-corpus heavies are the motivating case)
> without patching the project's upstream Jenkinsfile.
>
> **Why this exists.** AN7-5a (PR #95) added a parser for
> `agent { docker { args '--memory=4g' } }` so a Jenkinsfile author who
> wants to declare resource caps can. But none of the wild-corpus heavies
> (activemq, zookeeper, jdt-core, hbase) declare resource tuning in their
> Jenkinsfiles — they all use `agent { label 'ubuntu' }`. Without a
> non-Jenkinsfile path to inject tuning, the AN7-1 shims couldn't retire.
> AN7-5b is that path.

## Config shape

In `anvil.edn`:

```clojure
{:anvil.build-overrides
   {"wild-apache-activemq"
      {:docker-resource-limits {:memory-mb 4096 :cpus 2.0}
       :env-extra {"MAVEN_OPTS" "-Xmx2g -XX:+UseG1GC"}}

    "wild-apache-zookeeper"
      {:docker-resource-limits {:memory-mb 4096}
       :env-extra {"MAVEN_OPTS" "-Xmx2g"}}}}
```

The map key is the **build name** (the same one used in `lein run --
build <name>` and visible in the Jenkins API). Either inner key may be
absent — a build can request env-only overrides without resource caps,
or vice versa.

### `:docker-resource-limits`

Passes through to chengis-core's [`DockerBackend`][docker-backend]
structured `:resource-limits` map. All four keys are optional:

| Key            | Type   | Becomes                  |
|----------------|--------|--------------------------|
| `:memory-mb`   | LONG   | `--memory=<N>m`          |
| `:cpus`        | DOUBLE | `--cpus=<N>`             |
| `:pids-max`    | LONG   | `--pids-limit=<N>`       |
| `:cpu-shares`  | LONG   | `--cpu-shares=<N>`       |

If the Jenkinsfile also declares `agent { docker { args '...' } }`,
the AN7-5a parser extracts those flags first; the operator override is
then merged on top, so **operator wins on collision** (intentional —
the override is the explicit operator action).

### `:env-extra`

A string-keyed map merged into the build's environment before the
container starts. Override wins on collision with whatever env the
Jenkinsfile already set (via `withEnv`, etc.). The merged env reaches
the container via chengis-core's `-e KEY=VAL` flag emission.

## Operator workflow

1. Edit `anvil.edn` (or place an `anvil.edn` under `$ANVIL_CONFIG_DIR`)
   with a `:anvil.build-overrides` map.
2. **v0.6 T4** — Save the file. The daemon's filesystem watcher detects
   the change and clears the override cache automatically; the next
   build sees the new shape without restart.
   *Note*: hot-reload only works when `anvil.edn` is on disk (the
   `$ANVIL_CONFIG_DIR` or `./config/` paths). When using the
   classpath-bundled default, restart the daemon to pick up changes.
3. Re-trigger the affected builds. The daemon log shows
   `anvil.build-overrides: anvil.edn changed (ENTRY_MODIFY) — clearing
   cache for hot-reload` and the new merged values reach the docker
   invocation.

### v0.5.x → v0.6 hot-reload migration

Before v0.6 T4, this section read "restart the daemon — overrides are
read once at first use, no hot-reload by design." That contract changed
in v0.6 T4 — file-watch via `java.nio.file.WatchService` runs on a
daemon thread started at boot. Operators on v0.5.x still need restart.
Operators on v0.6+ can edit-and-save.

If you want the old behavior (e.g. running anvil in an immutable
container), the watcher silently no-ops when no on-disk `anvil.edn`
exists, so the restart-to-reload contract holds for the classpath
case.

## Verification

After restart, verify the override is loaded:

```bash
# The daemon doesn't currently expose a /api/build-overrides endpoint
# (could be a v0.6 nice-to-have). For now, trigger a build and inspect
# the dispatcher log for the docker invocation.

curl -X POST http://localhost:8765/job/wild-apache-activemq/build
journalctl -u anvil -n 200 | grep -E "docker run|MAVEN_OPTS"
```

You should see `--memory=4096m` (or whatever you configured) in the
docker invocation, and `-e MAVEN_OPTS=...` in the env flags.

## What this does NOT do

- Does NOT modify the Jenkinsfile or the build's source repo.
- Does NOT support hot-reload — operator restarts after edits.
- Does NOT expose a REST or web UI for managing overrides (future).
- Does NOT validate values against host capacity — an operator who
  requests 64g on a 16g host gets a docker error at run time, the same
  way a hand-edited `args '--memory=64g'` would.
- Does NOT (yet) support wildcard / regex build-name matching. Each
  entry is an exact-string match against `:job-name`.

## Why this approach (and what was rejected)

- **Per-build config in anvil.edn** (this PR): operator-controlled,
  single config file, no Jenkinsfile changes, no new admin endpoints.
  Picked.
- **Global `:anvil.docker/default-resource-limits`**: would force the
  same limits on every build. Rejected — too coarse for a multi-tenant
  fleet that runs heterogeneous workloads.
- **Patch the upstream Jenkinsfiles**: outside anvil's scope — these
  are project-owned files.
- **Pass-through `:extra-args` at chengis-core**: chengis-core's
  `DockerBackend` doesn't honor `:extra-args`. Even if it did, that's
  a cross-repo change. The structured `:resource-limits` shape chengis
  already supports composes cleanly.

## Tests + invariants

11 tests in [`build_overrides_test.clj`](../../test/anvil/build_overrides_test.clj)
lock down:
- empty map when no `:anvil.build-overrides` key
- correct shape return for matched job-name
- nil for unmatched job-name
- partial-shape (env-extra-only or limits-only) handled
- nil job-name input handled
- cache survives multiple reads (load-edn called once)
- `clear-cache!` forces a reload

5 wiring tests in [`backend_wiring_test.clj`](../../test/anvil/compat/jenkins/backend_wiring_test.clj):
- override applied to docker backend at construction
- override augments Jenkinsfile-parsed limits
- override wins on key collision
- no override for unmatched job-name
- env-extra merged into ctx (with override winning on collision)

[docker-backend]: ../../src/anvil/compat/jenkins/backend_wiring.clj
