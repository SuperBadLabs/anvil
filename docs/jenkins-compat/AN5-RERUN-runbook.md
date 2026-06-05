# AN5-RERUN — Wild-corpus runbook

After AN5-3 / AN5-3b / AN5-3c shipped, the wild-corpus dirty-dozen is
**one config and one script away** from a real-artifact rerun. This
runbook walks operators through the procedure.

## Prerequisites

- An anvil v0.3.1+ daemon running locally (Docker socket reachable)
- The 13 wild-corpus Jenkinsfiles available under `$WILD_CORPUS_ROOT`
  (default: `/tmp/anvil-broad`) — these are pre-downloaded with their
  `Jenkinsfile` at the repo root
- Babashka (`bb`) for the harness script

## Step 1 — Load the wild-corpus agents overlay

The AN5-3d overlay
[`resources/anvil/config/wild-corpus-agents.edn`](../../resources/anvil/config/wild-corpus-agents.edn)
maps the labels the wild-corpus uses (`ubuntu`, `ubuntu-latest`,
`Hadoop`, `migration`) to suitable Maven + Eclipse Temurin docker
images.

Two ways to load it:

```bash
# Option A — point ANVIL_CONFIG_DIR at it
ANVIL_CONFIG_DIR=resources/anvil/config \
  bash -c 'cp resources/anvil/config/wild-corpus-agents.edn $ANVIL_CONFIG_DIR/agents.edn && lein run --port 8765'

# Option B — copy + restart your daemon
cp resources/anvil/config/wild-corpus-agents.edn ~/.anvil/agents.edn
# (then restart anvil)
```

Verify the registry loaded by hitting `/jenkins/api/json` and checking
that triggering a job with `agent { label 'ubuntu' }` doesn't emit
`:agent/degraded` in the build's effects.

## Step 2 — Pre-pull the docker images

Optional but strongly recommended — the first build per image
otherwise blocks while `docker pull` runs (can take 5+ min for fresh
maven images).

```bash
docker pull maven:3.9-eclipse-temurin-21
docker pull maven:3.9-eclipse-temurin-17
docker pull eclipse-temurin:21-jdk
```

## Step 3 — Run the harness

```bash
ANVIL_URL=http://localhost:8765 \
  WILD_CORPUS_ROOT=/tmp/anvil-broad \
  bb scripts/wild-corpus-rerun.bb
```

The script:

1. Registers each of the 12 buildable wild-corpus Jenkinsfiles as a
   job (prefixed `wild-`)
2. Triggers all 12 builds via `POST /jenkins/job/wild-<name>/build`
3. Polls every 30s; gives up after 30 minutes per build
4. Tallies classification (`:success` / `:failure` / `:unsupported`)
   and counts artifact files on disk under
   `target/anvil-builds/wild-<name>/<n>/`
5. Writes `/tmp/wild-corpus-rerun.md` with the per-build table

### Test a single build first

```bash
bb scripts/wild-corpus-rerun.bb --subset=1
```

That runs only `apache-activemq` — useful for sanity-checking the
agents.edn load before committing to a 12-build run.

## Step 4 — Read the receipt

`/tmp/wild-corpus-rerun.md` has a markdown table of every build with:

- The classifier verdict (post-AN4-1 honest classifier)
- The classifier `:rule` and `:explain` (post-AN5-1 synthesizer for
  silent failures)
- File count + largest file size on disk

This is the receipt the [`wild-corpus-honest-receipt.md`](wild-corpus-honest-receipt.md)
"real artifacts" axis quoted as zero across all of v0.3.x. AN5-RERUN
moves it.

## Known limitations

- **hibernate-orm / hibernate-search**: still blocked on AN5-2
  (external `@Library` loader). Their labels (`Worker&&Containers`)
  also use Jenkins label expressions which anvil v0.3.x doesn't yet
  parse.
- **apache-camel**: uses `label "${PLATFORM}"` (Groovy interpolation
  at runtime). AN5-3d doesn't try to pre-resolve.
- **apache-cassandra, eclipse-mojarra**: out of scope (k8s /
  dockerfile agents — v0.4 board).
- **Network access**: maven builds need to talk to Maven Central +
  often plugin-specific repos. Builds will fail honestly if your
  Docker network can't reach the public internet.
- **Build duration**: even successful maven builds for these projects
  take 5–20 minutes apiece. Plan for the run to take 1–4 hours total.

## Expected outcome

Realistic forecast on the first proper run, based on the AN5-3 chain
landing:

| Class | Build set | Count |
|---|---|---|
| `:success` (real artifact) | apache-camel-quarkus, apache-activemq, apache-streampipes, eclipse-jdt-core, apache-cxf (best-case) | 3–6 |
| `:failure` (real build that crashed, e.g. missing plugin or test failure) | apache-maven, apache-hbase, eclipse-jkube | 2–4 |
| `:unsupported` (honest "anvil can't honor") | hibernate-orm, hibernate-search, apache-zookeeper, eclipse-epsilon | 3–5 |

The first number — anything > 0 — is the moment the wild-corpus real-
artifact axis moves off zero. The honest receipt update follows.
