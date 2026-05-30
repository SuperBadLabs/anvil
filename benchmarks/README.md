# anvil benchmarks — methodology, honest discussion, reproducer

This directory contains TX8's benchmark suite for anvil. The point of
the suite is to back up *or refute* anvil's marketing claim — "drop-in
Jenkins replacement on modern infrastructure" — with reproducible
numbers.

## What's measured here

| Suite | What it measures | Comparable to Jenkins today? |
|---|---|---|
| `anvil.bench.parser` | Jenkinsfile source → Jenkins IR throughput across the 23-file corpus | No comparable Jenkins endpoint exposed |
| `anvil.bench.dispatch` | IR → recorded effects throughput | **No** — see honest disclaimer below |
| `anvil.bench.api` | REST-shim handler response times (in-JVM, no socket) | **Yes** via the `jenkins-compare.bb` reproducer |

## Honest disclaimer — the elephant in the room

**anvil v1's AnvilJenkinsDispatcher records each step as a side-effect
tuple. It does NOT subprocess-execute commands yet.** Real subprocess
execution wires in TX9 when this layer plumbs into
`chengis.engine.executor` + `chengis.agent.worker` (both already in
chengis-core post-TX1 Wave 7).

What that means for these benchmarks:

- The **parser** suite is honest. Anvil parses Jenkinsfile syntax via
  the Groovy AST; this is real, comparable work.
- The **API** suite is honest. Both anvil and Jenkins serve JSON; the
  comparison reflects actual server throughput.
- The **dispatch** suite measures anvil's pipeline-orchestration
  overhead — the work added by anvil's runtime *on top of* real
  subprocess execution. It does NOT include the subprocess execution
  itself. So a literal comparison ("anvil dispatch is 1000× faster
  than Jenkins's pipeline execution") would be misleading. We report
  the number; we explicitly do not claim a perf win on that axis until
  TX9 lands.

When TX9 wires real execution, this README gets updated and the bench
runner adds a `build-execution` suite that compares apples-to-apples.

## Running the benchmarks

### Anvil intrinsic suites (parser, dispatch, API)

```
$ cd anvil
$ lein with-profile +bench run -m anvil.bench.runner [iterations]
```

Default `iterations=30` for parser/dispatch, `5×` for API since they
are much faster. Output is written to `anvil/benchmarks/results/latest.edn`
and printed to stdout.

### REST API comparison vs real Jenkins

```
$ cd anvil
$ lein run --port 8765 &              # start anvil
$ benchmarks/scripts/jenkins-compare.bb --iterations 200
```

The script spins up Jenkins LTS in Docker on :8080, waits for it to
come up, fires `iterations` HTTP requests against each endpoint on
both products, then prints a side-by-side latency comparison. Output
also written to `benchmarks/results/jenkins-compare-<ms>.edn`.

Flags:
  `--iterations N`       requests per endpoint (default 100)
  `--skip-jenkins`       just time anvil
  `--skip-anvil`         just time Jenkins
  `--anvil-url URL`      where anvil is (default http://localhost:8765)
  `--jenkins-url URL`    where Jenkins is (default http://localhost:8080)
  `--jenkins-image IMG`  Jenkins Docker image (default `jenkins/jenkins:lts-jdk21`)

### Stress / regression mode

```
$ lein with-profile +bench run -m anvil.bench.runner 200
```

Higher iteration counts reduce variance. For CI regression tracking,
run with 100+ and parse `latest.edn`.

## Results — current measurements

See `anvil/benchmarks/results/latest.edn` after running. Each result includes
`:run-at`, `:opts`, and the per-suite distribution. A sample run on
the development machine produces:

  *Sample numbers will be recorded in `results/initial-snapshot.edn`
   after the first commit; reproduce them with the above commands.*

## Exit gate (per the task description)

> Measured win ≥3× on at least one realistic pipeline.

**Status:** deferred. The parser and API suites produce real numbers;
the dispatch suite does not represent real execution. A defensible
"3× win" claim requires TX9 wiring in real subprocess execution and
re-running the comparison with build wall-clock as the metric. This
README, and `jenkins-compare.bb`, are the scaffolding waiting for that
moment.

What we can claim today:

- Anvil parses + walks a real Jenkinsfile in `<median-parser-ms>`
  milliseconds on the dev machine.
- Anvil's REST shim responds to `/api/json` in `<median-api-ms>`
  milliseconds (in-JVM measurement).
- The full dispatcher walk for a 60-line declarative Jenkinsfile
  including agent + post-action handling takes `<median-dispatch-ms>`
  milliseconds (NOT including real subprocess execution).
- **Anvil parses + dispatches `jenkinsci/jenkins`'s own Jenkinsfile**
  (256 lines, fully scripted Pipeline with `stage()` calls nested
  inside `axes.values().combinations { ... }`, `node()`, `retry()`,
  `withCredentials()`, etc.). Parse ~170 ms median; dispatch < 1 ms
  median; 4 scripted stages enumerated and 25 effects recorded. Run
  it with `lein with-profile +bench run -m anvil.bench.jenkins-self`.

These are credible architectural-ceiling numbers — they tell a reader
how fast anvil's machinery COULD be once real execution is wired in.
They are not yet the receipts the marketing claim needs.
