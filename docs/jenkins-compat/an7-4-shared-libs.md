---
title: AN7-4 — External @Library loader receipt
category: jenkins-compat
ticket: AN7-4
status: shipped
shipped-in: v0.5.0
last-verified: 2026-06-08
---

# AN7-4 — External `@Library` loader

## What ships

AN7-4 adds Git-backed resolution for Jenkins shared libraries declared via
`@Library('name@ref') _` or `library identifier: 'name@ref'` at the top of a
Jenkinsfile.

### What works (v0.5.0)

| Feature | Notes |
|---------|-------|
| `@Library('name') _` (default branch) | Cloned from configured remote |
| `@Library('name@ref') _` (branch, tag) | `--branch ref` shallow clone |
| `@Library('name@<40-char-sha>') _` | Full clone + `reset --hard <sha>` |
| `@Library(['a','b']) _` (multi-library) | One clone per coordinate |
| `vars/*.groovy` step registration | Each `.groovy` file → plugin adapter |
| Cache-hit skip re-clone | Directory presence = cache hit (per R7) |
| `ANVIL_LIBS_DIR` env override | Replaces `~/.anvil/libs` |

### What doesn't work yet (honest gaps)

| Gap | Notes |
|-----|-------|
| `src/org/…/*.groovy` class loading | vars/ only; class-path libraries defer to v0.5.x |
| Auto-update of branch refs | Once cloned, `main` is NOT re-fetched on re-run — operator evicts by deleting `~/.anvil/libs/<name>/` |
| Private repos (SSH/token auth) | Git credentials not yet threaded through; use pre-cloned directories in `ANVIL_LIBS_DIR` |
| `@Library` without configured remote | Falls back to local `ANVIL_LIBRARIES_DIR/<name>/<ref>/` (AN5-2 behavior); if absent → `:library-unresolved` |
| Multi-positional-arg `def call(a,b,c)` | The runtime spreads single-arg; multi-positional-arg defers to v0.5.x |

## Configuration

Add remote URLs under `:anvil.libs/remotes` in `anvil.edn`:

```edn
{:anvil.libs/remotes
 {"pipeline-library"                   {:url "https://github.com/jenkinsci/pipeline-library.git"}
  "hibernate-jenkins-pipeline-helpers" {:url "https://github.com/hibernate/ci-tools.git"}}}
```

Library names are the identifiers in the Jenkinsfile's `@Library('NAME')` annotation.
The `ref` portion of `@Library('NAME@REF')` selects the branch/tag/SHA to clone.
Omitting `@REF` defaults to `main`.

## Cache behavior (R7)

Anvil caches libraries at `~/.anvil/libs/<name>/<ref>/`. On the first resolve,
anvil runs `git clone --depth 1 --branch <ref> <url> <path>`. On subsequent
resolves of the same (name, ref), it checks for a `.git/` directory and skips
the clone — **no auto-update**. This is intentional:

- Builds are reproducible: the same (name, ref) pair always uses the same
  library version you first cloned.
- Auto-updating branch refs (e.g., `main`) would silently change build
  behavior between runs. Operators who want fresh library code delete the
  cache dir (`rm -rf ~/.anvil/libs/<name>/`) and re-run.

**Cache eviction:**

```bash
# Evict a single library (all refs)
rm -rf ~/.anvil/libs/pipeline-library/

# Evict a specific ref
rm -rf ~/.anvil/libs/pipeline-library/main/

# Override cache root via env (e.g. in CI)
export ANVIL_LIBS_DIR=/tmp/anvil-libs-ci
```

## Security

Shared libraries run arbitrary Groovy code inside the build. The
`:anvil.libs/remotes` list is the operator's trust boundary — only configure
remotes you control or explicitly trust. Anvil does not sandbox library
evaluation; a malicious library has the same access as the Jenkinsfile itself.

## Wild-corpus impact

| Corpus entry | Before AN7-4 | After AN7-4 |
|---|---|---|
| wild-apache-maven | `:success` (AN7-1a type-B shim) | `:success` (real `@Library('pipeline-library')` if remote configured; shim otherwise) |
| wild-hibernate-orm | `:unsupported :no-effects-recorded` | `:success` (type-B shim); improves further when remote is configured |
| wild-hibernate-search | `:unsupported :no-effects-recorded` | `:success` (type-B shim); improves further when remote is configured |

Note: the apache-maven shim (`wild-corpus-shims/apache-maven.Jenkinsfile`) can
be retired by the operator once `:anvil.libs/remotes` includes `pipeline-library`.
Until then, the shim continues to produce the same `:success` result — **no regression**.

## Effects emitted

The existing AN5-2 effects are unchanged (AN7-4 adds no new effect types):

- `[:library-loaded {:name N :ref R :registered [step-names]}]` — library cloned
  and all `vars/*.groovy` registered as plugin adapters.
- `[:library-unresolved {:name N :ref R :reason KW :detail STR}]` — resolution
  failed; classifier marks build `:unsupported` with rule `library.N-unresolved`.

New `:reason` values added by AN7-4:

| `:reason` | When |
|---|---|
| `:remote-not-configured` | No entry in `:anvil.libs/remotes`; falls back to local dir |
| `:git-not-available` | `git` not on PATH |
| `:clone-failed` | `git clone` exited non-zero; `:detail` has git's stderr |
