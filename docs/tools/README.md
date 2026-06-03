# mise / asdf tool-version detection (v0.3 — T7)

Status: **stub** (T0.5 placeholder). Real docs land at T7.5 / T8.5.

## What this feature ships

A repo with a `.tool-versions` or `.mise.toml` file gets its declared
tool versions provisioned before build steps run. Build logs show
`using mise: nodejs 22.5.1, python 3.12.7, …` as a foldable stage.

## Feature flag

```clojure
;; anvil.edn
{:anvil.features/mise true}
```

## Bounding decisions

- **AV3-7**: `mise` as primary backend; `asdf` detected as fallback.
  Project's `.tool-versions` / `.mise.toml` auto-detected pre-build.
  mise is the 2026 standard (asdf successor with shared file format).
- **R7**: `mise` may not be present on default Ubuntu / macOS.
  `anvil setup tools` CLI installs it via the official one-liner
  if absent; doc the asdf-fallback path.

## Files (when T7 lands)

- `src/anvil/tools/mise.clj` — detect + provision (asdf fallback)
- `src/anvil/cli/setup_tools.clj` — `anvil setup tools` installer
- Dispatcher hook: before first `sh` step, run provisioning
- 3 project sample fixtures: `.tool-versions` only / `.mise.toml`
  only / both
- Fake `mise` shim binary for hermetic test

## SSE event

None — provisioning is a build-prefix phase; status flows through
normal build lifecycle events.

## Owner

T7 tranche owner — smallest tranche (~half week); paired with T5 in
week 6 per the v0.3 cadence.
