# Perf-regression baselines

This directory holds frozen EDN snapshots of `anvil/benchmarks/results/latest.edn`
at specific release points. They're the anchor for the perf-regression
gate (`anvil/benchmarks/scripts/perf-regression.bb`).

## What's here

| File | When recorded |
|---|---|
| `v0.1.0.edn` | First v1 release; first commit that ran with TX11A–E wiring |

## When to bump

After an intentional perf change (e.g. swapping a hot algorithm),
overwrite the relevant baseline by re-running the bench and copying
`results/latest.edn` into `baselines/<tag>.edn`:

```
cd anvil
lein with-profile +bench run -m anvil.bench.runner 100
cp benchmarks/results/latest.edn benchmarks/baselines/v0.2.0.edn
git add benchmarks/baselines/v0.2.0.edn
git commit -m "perf: bump baseline to v0.2.0 — N% improvement from <change>"
```

The bump should be a deliberate, reviewed step. The gate is there
precisely to make accidental regressions visible.

## How the gate uses these

```
./anvil/benchmarks/scripts/perf-regression.bb --baseline v0.1.0
```

Runs the bench, diffs every per-file parser/dispatch median + every
per-endpoint API median, classifies:

- **OK**: ratio < 1.5x slower
- **WARN**: 1.5–2.0x slower (yellow, doesn't fail)
- **FAIL**: ≥ 2.0x slower (red, exits 1)

Thresholds are tunable via `--warn-ratio` and `--fail-ratio`.

## Why we keep baselines per-release rather than rolling

Rolling baselines mask slow drift: a 5% regression per week becomes
2.5× over a year, and no individual PR ever trips the gate. Per-
release baselines force the question explicitly — at each release we
bump deliberately or we don't.
