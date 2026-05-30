# anvil — jenkins-cli integration fixture

The end-to-end test that drives the actual `jenkins-cli.jar` binary
against a running anvil. Verifies anvil's REST shim is jenkins-cli
compatible at the binary level — what a real Jenkins user would see
when they point their existing tools at anvil.

## What it tests

The fixture script runs through this sequence:

| # | Step | What it proves |
|---|---|---|
| 1 | `GET /api/health` returns `ready: true` | anvil's daemon is up |
| 2 | `GET /jenkins/api/json` returns `_class: hudson.model.Hudson` | The REST shim is jenkins-cli compatible at the JSON-shape level |
| 3 | `POST /anvil/admin/jobs` registers `cli-demo` | Anvil-native admin works (Jenkins `/createItem` is intentionally 501) |
| 4 | `jenkins-cli list-jobs` shows `cli-demo` | The binary parses our /api/json correctly |
| 5 | `jenkins-cli build cli-demo` enqueues a build | Build-trigger endpoint accepts jenkins-cli's POST flow |
| 6 | Poll until build completes (≤30s) | The async queue + worker pool actually run builds |
| 7 | `jenkins-cli console cli-demo` shows step output | progressiveText is correctly populated by the streaming log |
| 8 | `POST /jenkins/script` returns `501` | The policy decision still holds |
| 9 | `POST /jenkins/createItem` returns `501` | The policy decision still holds |
| 10 | `DELETE /anvil/admin/jobs/cli-demo` succeeds | Cleanup |

## Running locally

```
$ cd anvil/test-integration
$ ./jenkins-cli-fixture.bb
```

Default behavior: spawns `lein run --port 8765`, downloads
`jenkins-cli.jar` to `target/` if absent, runs the sequence, kills the
daemon on exit. Output goes to `target/anvil-fixture.log`.

### Flags

| Flag | Effect |
|---|---|
| `--anvil-url URL` | Use an already-running anvil (default `http://localhost:8765`) |
| `--no-start` | Don't try to launch anvil — assume `--anvil-url` is already up |
| `--skip-build` | Skip the build-and-poll steps (fast smoke check) |
| `--cli-path PATH` | Use a pre-downloaded `jenkins-cli.jar` |
| `--cli-url URL` | Download from a custom URL |
| `--help` | Print usage |

## CI

`.github/workflows/jenkins-cli-integration.yml` runs the fixture on
every push to `main`. The workflow:

1. Sets up Java, Leiningen, Babashka
2. Caches `~/.m2`, `~/.lein` for fast restarts
3. Downloads `jenkins-cli.jar` to a workspace path
4. Starts anvil in the background
5. Waits for `/api/health`
6. Runs this fixture script with `--no-start`
7. On failure, uploads `target/anvil-fixture.log` as an artifact

## Why a separate admin endpoint exists

The Jenkins REST shim at `/jenkins/createItem` is **intentionally 501** —
anvil rejects Jenkins's `config.xml`-as-source-of-truth model. The
anvil-native admin endpoint at `POST /anvil/admin/jobs` is how
operators (and this CI fixture) register jobs.

If a user wants to import an existing Jenkinsfile, the recommended
path is the CLI: `anvil import jenkinsfile <path>` outputs a
Chengisfile. Registering the resulting job with a running daemon is
this admin endpoint's job.

## When this passes

- jenkins-cli compatibility is provable, not just claimed
- the async queue + worker pool dispatch real builds
- streaming console-log + REST shim plumbing is wired correctly
- the 501 policy decisions hold across releases (no accidental drift)

## When this fails

The script prints a `✗` next to the failing step and exits non-zero;
the CI workflow surfaces this with a red check on the PR. The
`anvil-fixture.log` artifact captures everything the daemon emitted
during the run, which is usually enough to debug.
