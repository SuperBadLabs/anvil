---
title: Remote Build Cache (T1)
audience: operators, developers
category: feature
purpose: Content-addressed step-level build cache. v0.5 T1.
lifecycle: live
last-verified: 2026-06-08
status: in-progress (T1.1 + T1.2 shipped)
---

# Remote Build Cache

> **v0.5 T1.** Step-level content-addressed cache per AV5-2. Cache key
> binds the four inputs anvil knows about: docker image digest, the
> exact command, the env vars set for the step, and a merkle of the
> relevant workspace inputs. Local FS store with LRU eviction by
> `:created-at`. Optional remote object-store federation lands in T1.5.

## Status

- [x] **T1.1** — `anvil.cache.key/derive` — pure-fn key derivation
- [x] **T1.2** — `anvil.cache.local-store` — atomic-rename CAS, LRU eviction
- [ ] **T1.3** — dispatcher hook (lookup before run, store on success)
- [ ] **T1.4** — invariants receipt
- [ ] **T1.5** — optional remote object-store adapter
- [ ] **T1.6** — browser smoke test

## Cache key

```clojure
(require '[anvil.cache.key :as k])

(k/derive
  {:image-digest "sha256:abc123..."    ; required, non-nil, non-blank
   :command      "mvn install -DskipTests"
   :env          {"JAVA_HOME" "/opt/jdk" "MAVEN_OPTS" "-Xmx2g"}
   :input-tree   [["pom.xml" "sha256:..."]
                  ["src/main/java/Main.java" "sha256:..."]]})
;; => 64-char lowercase hex SHA-256
```

### Key invariants (R1)

- **Image digest is required.** Bare image tags (`maven:3.9-eclipse-temurin-21`)
  are not sufficient — they're mutable. Resolve to digest first or the
  derive call throws `:reason :missing-image-digest`.
- **Env reorder ⇒ same key.** The env map is sorted internally before
  hashing.
- **Removing or adding an env var ⇒ different key.** Different set of
  inputs == different cache identity.
- **Command whitespace IS sensitive.** `"mvn install"` and `"mvn install "`
  hash to different keys. Anvil shows operators exactly what they wrote.
- **Input-tree pair order ⇒ same key.** Pairs are sorted by path.
- **Empty == nil for env and input-tree.** Both hash the same.
- **NUL inside any input field is rejected** — refuses to silently merge
  field boundaries.

### Tests (`anvil.cache.key-test`)

15 deftests covering each invariant + the throw guards.

## Local store

```
~/.anvil/cache/<prefix-2>/<full-key>/
  stdout         ← captured step stdout
  stderr         ← captured step stderr
  exit           ← exit code as ASCII decimal
  meta.edn       ← {:created-at <ms> :wall-ms <n> :image-digest <str> :command <str>}
  artifacts.tgz  ← optional tar.gz of step-produced files
```

The 2-char prefix subdir keeps any single dir under ~10k entries on a
sane filesystem.

### Atomic writes

Every payload file is written to a sibling `.anvil-cache-*.tmp` then
renamed onto its final path. Two concurrent writers for the same key
race to rename; both end up with identical content. Readers see old
payload, new payload, or nothing — never half-written.

### LRU eviction

`evict-to-cap! opts max-bytes`:

1. Enumerate every entry's `:created-at` from meta.edn.
2. Sort ascending (oldest first).
3. Delete entries until `total-bytes ≤ max-bytes`.

Best-effort: a delete race or permission error is logged and skipped.
Callers never block; sweeps run synchronously on `store!` after the
new entry lands.

We chose `:created-at` over `:accessed-at` (atime) to keep reads
IO-free. If atime-based LRU is wanted later, meta.edn gains
`:accessed-at` — no migration needed; sort falls back to created-at.

## Configuration

```edn
;; anvil.edn
{:anvil.features/cache true
 :anvil.cache/store-root "/var/lib/anvil/cache"
 :anvil.cache/max-bytes 10737418240}  ;; 10 GB
```

`:store-root` defaults to `~/.anvil/cache`. `:max-bytes` defaults to
unlimited (no eviction) — set it explicitly in production.

## Roadmap

- **T1.3** — Wire lookup into chengis-core's docker backend dispatch.
  On hit: replay stdout/stderr/exit, untar artifacts. On miss: run,
  then `store!`. Gated by `:anvil.features/cache`.
- **T1.4** — Invariants receipt with a worked example: a `mvn install`
  with same pom but different test files reuses dependency-resolution
  cache but re-runs the test step.
- **T1.5** — Remote object-store adapter via `:anvil.features/cache-remote`.
- **T1.6** — Browser smoke: second build run < 20% of first.
