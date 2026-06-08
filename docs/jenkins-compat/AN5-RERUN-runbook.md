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

### Fleet mode (v0.4.2 — CPU-weighted shard distribution)

When more than one daemon is online, pass `--fleet=URL1,URL2,...` to
distribute the corpus proportionally to each daemon's `numExecutors`.
Apache-hbase and apache-cassandra carry `:heavyweight? true` and
rotate across hosts each cycle so the same box never absorbs every
long-runner.

```bash
# Query each daemon for its worker count (numExecutors), then
# apportion 14 builds proportionally.
bb scripts/wild-corpus-rerun.bb \
   --fleet=http://heman:8765,http://mario:8765,http://luigi:8765 \
   --cycle=0 \
   --max-minutes=90

# Explicit per-host weight override (host shared with other work).
# `@` (not `:`) is the weight separator so URLs with embedded colons
# — IPv6 literals, basic-auth userinfo — parse unambiguously.
bb scripts/wild-corpus-rerun.bb \
   --fleet=http://heman:8765@4,http://mario:8765@2,http://luigi:8765@12 \
   --cycle=1

# Dry-run the plan before committing to a multi-hour run:
bb scripts/wild-corpus-rerun.bb --fleet=... --plan-only
```

Bump `--cycle` by 1 each rerun. With 3 hosts and 2 heavyweights, three
cycles cover all rotation positions; over time each box is heavyweight-
free in one cycle out of three.

This replaces the v0.4.1-T6 hand-coded 4/5/5 split that put 5 jobs
(including two concurrent maven builds) on 12-core Mario — load avg
28 — while 56-core Luigi sat at 0.4 with zero containers. The fleet
plan is sanity-printed at startup and reproduced in the markdown
receipt's "Per-host load" table.

#### Prereq: bump worker count on multi-core daemons

The fleet driver reads each daemon's `numExecutors` (the value the
daemon reports at `/jenkins/api/json`) as its weight. Before v0.4.2
that number was hardcoded to 2 regardless of host capacity; now it
reflects the actual worker pool size, picked at boot via (highest
precedence first):

1. `ANVIL_WORKERS=N` env var
2. `:anvil.queue/workers N` in `~/.anvil/anvil.edn` (or wherever
   `ANVIL_CONFIG_DIR` points)
3. `max(2, cores/4)` default

```edn
;; anvil.edn — give a 56-core host 14 workers
{:anvil.queue/workers 14}
```

The daemon logs the source at startup:

```
anvil queue: starting 14 worker(s) [source=config, cores=56]
```

If you don't set anything, the default lands you at sensible numbers
(12c→3, 32c→8, 56c→14) — but always overshoot if your hosts share the
box with other work, since each maven build pulls 8–12 threads of its
own JVM thread pool on top.

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
