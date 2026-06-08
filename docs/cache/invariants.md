---
title: Cache Invariants Receipt (T1.4)
audience: operators, developers
category: engineering-receipt
purpose: Documents what changes invalidate the step-level cache, what doesn't,
  and why. Includes a worked example demonstrating the pom-same/tests-changed
  scenario. Per AV5-2 / R1 (wrong key collisions corrupt downstream builds).
lifecycle: live
last-verified: 2026-06-08
status: shipped (T1.4)
---

# Cache Invariants Receipt

> **v0.5 T1.4.** This document is the honest-accounting receipt for the
> step-level cache per AV5-2 and R1. It answers: when does a step hit the
> cache? When must it miss? What are the known limitations of T1.3's first
> cut? Where is work still needed?

---

## The cache key

A step's cache key is the SHA-256 of four ordered fields, NUL-separated:

```
sha256(
  image-digest  NUL
  command       NUL
  sorted-env    NUL
  input-tree    NUL
)
```

Implemented in `anvil.cache.key/derive`. Pure function — no IO, no globals.
Tests: `anvil.cache.key-test` (15 invariants).

### Field definitions

| Field | What it captures | Notes |
|---|---|---|
| `image-digest` | Docker image content digest (`sha256:…`) | Required; blank/nil throws. In T1.3, resolved via `docker inspect --format={{.Id}}`; falls back to raw tag string if docker unavailable. |
| `command` | Literal shell command string | Whitespace-sensitive — NOT normalized. `"mvn install"` ≠ `"mvn install "`. |
| `sorted-env` | The step's env map, sorted by key name | Keys are `(keyword/string → name)` coerced; env var removal/addition changes the key; env reorder does NOT. |
| `input-tree` | `[[path sha256-hex] …]` pairs, sorted by path | Caller decides what's relevant. **T1.3: always `[]` (empty).** See T1.3 limitation below. |

---

## Invariant table

| Scenario | Cache result | Why |
|---|---|---|
| Identical image, command, env, input-tree | **HIT** | All four fields match. |
| Same image, same command, env vars reordered | **HIT** | Env is sorted before hashing. |
| Same image, command changed by one character | **MISS** | Command is exact-match. |
| Same image, one env var value changed | **MISS** | Sorted env string changes. |
| Same image, one env var added | **MISS** | Sorted env string changes. |
| Same image, one env var removed | **MISS** | Sorted env string changes. |
| Same image tag, but docker pulled a new layer | **HIT (wrong! — known limitation)** | If using raw tag (no `docker inspect`), the key doesn't know about the updated layers. See §T1.3 limitation. |
| New docker image digest (tag repinned or `docker pull` forced fresh) | **MISS** | `docker inspect` returns the new `.Id`. |
| `withCredentials` block active | **bypass** | Cache is skipped entirely for steps running with secret env bindings — cached stdout could leak secrets across operator boundaries. |
| Step exited non-zero | **not stored** | Only exit 0 results are cached. A failed step's output is not a valid cached result. |
| Two builds with different job names | **HIT** (cache shared) | The key doesn't include job name — the step cache is per-step-identity, not per-build. This is intentional: `mvn install` on the same pom across different jobs shares the dep resolution cache. |

---

## Worked example: pom-same / test-files-changed

This example shows the cache correctness claim from the T1.4 spec: when a
`pom.xml` hasn't changed but test files have, the dependency-resolution step
hits the cache and the test step misses.

### Setup

A Jenkinsfile with two sequential stages:

```groovy
pipeline {
  agent { docker { image 'maven:3.9-eclipse-temurin-21' } }
  stages {
    stage('Deps') {
      steps {
        sh 'mvn dependency:resolve -q'   // ① downloads deps to ~/.m2
      }
    }
    stage('Test') {
      steps {
        sh 'mvn test'                    // ② compiles src + runs tests
      }
    }
  }
}
```

### Build 1 — cold cache (all misses)

The cache store is empty. Both steps run:

```
cache: MISS key=f3a72c91… (mvn dependency:resolve -q)
  → subprocess: docker run maven:3.9… sh -c "mvn dependency:resolve -q"
  → exit 0; store! key=f3a72c91…

cache: MISS key=9d4b1f08… (mvn test)
  → subprocess: docker run maven:3.9… sh -c "mvn test"
  → exit 0; store! key=9d4b1f08…
```

Both keys stored. Wall time: ~4 min.

### Commit: add a new test file

Developer adds `src/test/java/NewFeatureTest.java`. The pom.xml is
unchanged, so the dependency set is unchanged.

### Build 2 — warm cache (Deps hits, Test misses)

**Why Deps hits:**

| Field | Build 1 | Build 2 | Same? |
|---|---|---|---|
| image-digest | sha256:ab… | sha256:ab… (same tag, not repinned) | ✓ |
| command | `mvn dependency:resolve -q` | `mvn dependency:resolve -q` | ✓ |
| env | `{JAVA_HOME=/opt/jdk21}` | `{JAVA_HOME=/opt/jdk21}` | ✓ |
| input-tree | `[]` (T1.3 empty) | `[]` (T1.3 empty) | ✓ |

All four match → **HIT**. `mvn dependency:resolve -q` replays from the store.
Saved: ~3 min of network I/O.

**Why Test misses (… in theory, but not in T1.3):**

The test step's command `mvn test` is the same. The env is the same. The
image is the same. Under T1.3 the input-tree is always `[]`, so the new
`NewFeatureTest.java` file does NOT change the key.

**T1.3 is over-caching here.** The test step would incorrectly hit the cache
and replay the old stdout, skipping the new test entirely.

This is the known T1.3 limitation documented below.

---

## T1.3 limitation: empty input-tree

**What:** T1.3 always passes `input-tree=[]` to `cache.key/derive`. The key
doesn't capture workspace file changes.

**Impact:** steps that read workspace files (test steps, compile steps) may
hit the cache even when their inputs changed. The cached result is from an
older build and may not reflect new source files.

**What's NOT impacted:**

- Dependency resolution steps (`mvn dependency:resolve`, `gradle
  dependencies`, `npm install --prefer-offline`) that only read `pom.xml`
  or `package.json` from a mounted workspace path — if those files haven't
  changed AND the cache records the right env, a hit is correct.
- Any step whose correctness depends only on image + command + env (e.g.,
  `apt-get install -y curl` in a base-image bake step).

**Mitigation in T1.3:** anvil only caches steps inside docker agent blocks
by default. The feature flag is closed-by-default (`:anvil.features/cache
false`). Operators who enable it should understand the input-tree limitation
and restrict caching to steps they know are workspace-read-free.

**Fix in T1.5:** `input-tree` will be populated from a configurable glob
of workspace files (e.g., `pom.xml`, `**/*.gradle`, `requirements.txt`)
read at step dispatch time. Steps declare their relevant inputs; the merkle
of those files becomes part of the key.

---

## Cache entry lifecycle

```
store!  → writes stdout, stderr, exit, meta.edn atomically
          → triggers evict-to-cap! if :max-bytes configured

lookup  → reads exit + meta.edn; returns nil on any read error
          (error-swallowing — see anvil.cache.lookup/fetch)

eviction → sorted by :created-at ascending; oldest removed first
           → a delete that fails (race, permissions) is logged + skipped
           → eviction runs synchronously on store! (never on lookup)
```

---

## Fleet verification (batch)

The board calls for a sanity batch run across the fleet. Because T1.3's
input-tree is empty, the useful invariant to test across the fleet is:

**Same image + command + env on all three hosts → same cache key → same HIT/MISS behavior.**

Procedure (can run when fleet daemons at :8767 are up):

```bash
# 1. Clear the cache on heman
ssh heman 'rm -rf ~/.anvil/cache && mkdir ~/.anvil/cache'

# 2. Run a build with :cache enabled (e.g., the anvil-self-test job).
#    First run → all MISS; store on heman.
# ...

# 3. Run same build again on heman.
#    Second run → all HIT.  Wall time < 20% of first.
# ...

# 4. Run on mario (same image tag, no local cache).
#    First run on mario → all MISS (expected: different host, cold cache).
#    This is the T1.3 behavior: cache is LOCAL FS, not shared.
#    T1.5 (remote object store) makes the cache fleet-shared.
```

The fleet-shared cache (step-level, not build-level) requires T1.5 and is
the point where the "cache invariants across hosts" batch test becomes
meaningful.

---

## Honest gaps summary

| Gap | Impact | Fix |
|---|---|---|
| `input-tree=[]` (T1.3) | Overcaches workspace-reading steps | T1.5: caller-configurable glob → merkle |
| `docker inspect` falls back to tag string when docker unavailable | Cache hits across different underlying images with same tag | Use digest everywhere; T1.5 will enforce this at the lookup entrypoint |
| Cache is local-FS (not fleet-shared) | Each host has its own cold cache | T1.5: S3-style remote object-store adapter behind `:cache-remote` flag |
| No cache invalidation API | Operators cannot invalidate per-key | `anvil cache bust <key-prefix>` command planned for T1.5.x |
