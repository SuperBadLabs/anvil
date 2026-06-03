# Migrating from Jenkins to anvil v0.3

This guide walks through what changes you can expect for an existing Jenkins/Jenkinsfile shop adopting anvil v0.3.0.

## What runs unchanged

Drop your Jenkinsfile into anvil as-is. The following work without edits:

- `pipeline { agent any … }` and `pipeline { agent { docker { image '…' } } }`
- `stages { stage('…') { steps { sh '…' } } }`
- `script { … }` blocks (the Pipeline DSL runtime)
- `environment { … }` per-stage or top-level
- `parallel { stage … stage … }`
- `parameters { string(…); choice(…) }`
- `post { always { … } success { … } failure { … } }`
- `withCredentials([usernamePassword(credentialsId: 'foo', …)]) { … }` (when secrets registered via `anvil secrets add`)
- `junit '**/surefire-reports/*.xml'` — **v0.3 NEW**: now actually scans + populates the build's test dashboard
- `archiveArtifacts artifacts: '**/build/*.jar'`
- `stash`/`unstash` (single-host workspace mode)
- `retry(n) { … }`, `timeout(time: 30, unit: 'MINUTES') { … }`
- `node('…') { … }` (single-host labels)
- `triggers { cron('@daily') }` — **v0.3 PARTIAL**: the cron schedule is honored when registered via `:anvil.scheduler/jobs` in `anvil.edn`; Jenkinsfile-DSL trigger parsing lands in v0.3.1.
- `matrix { axes { … } excludes { … } stages { … } }` — **v0.3 PARTIAL**: parser + expander + grid view ship now; child-build dispatcher fan-out queued for v0.3.1.

## What's new in v0.3

| Feature | Flag | Surface |
|---|---|---|
| JUnit dashboard | `:junit` | Build page panel (summary + failures + sortable table + 30-build sparkline) |
| Problem matchers | `:problem-matchers` | Per-build "Problems" tab; file:line:col + matcher source; 6 bundled YAML rules + GHA workflow-cmd parser |
| GitHub PR-checks | `:pr-checks` | Webhook receiver + Checks API publisher; pill on build page |
| Declarative matrix | `:matrix` | 2D grid view; 100-cell cap |
| Cron scheduler | `:scheduler` | "Next scheduled run" pill on job page; per-job `H`-spread |
| Secrets CLI + UI | `:secrets` | `anvil secrets {add|list|show|delete|rotate-master}` + `/secrets` admin page |
| mise auto-provision | `:mise` | `.mise.toml` or `.tool-versions` triggers `mise install` pre-build |

All flags **closed by default**. Until you flip a flag, v0.3 behaves identically to v0.2 — your existing Jenkinsfiles run unchanged, new features stay invisible.

## How to enable each feature

Edit `~/anvil-dogfood/config/anvil.edn` (or `$ANVIL_CONFIG_DIR/anvil.edn`):

```clojure
{:anvil.features/junit            true
 :anvil.features/problem-matchers true
 :anvil.features/pr-checks        true
 :anvil.features/matrix           true
 :anvil.features/scheduler        true
 :anvil.features/secrets          true
 :anvil.features/mise             true

 ;; Per-feature config — only the ones you turn on
 :anvil.github/token              "github_pat_..."
 :anvil.github/webhook-secret     "..."
 :anvil.github/jobs
 {"my-job" {:repo "owner/name" :checks-enabled? true}}

 :anvil.scheduler/timezone        "UTC"
 :anvil.scheduler/jobs
 {"nightly-job" "@daily"
  "hourly-job"  "@hourly"
  "spread-job"  "H/15 * * * *"}

 :anvil.secrets/admin-ips         #{"127.0.0.1" "10.0.0.5"}
 :anvil.matrix/max-cells          100}
```

Then `sudo systemctl restart anvil` (or just kill the lein process; anvil reloads flags at startup).

## What's NOT in v0.3 (deferred to v0.4 by AV3-1)

- Flaky-test detection (passed-on-retry analysis)
- SLSA L3 provenance signing
- Remote build cache
- Container-as-step (`steps { container 'maven:…' { sh '…' } }`)
- AI authoring (`anvil init`, `anvil explain`, `anvil optimize`)
- Cost / minute reporting

Plus these v0.3.x cycle items, all documented:

- GitHub App auth (PAT path works now)
- Console-view ANSI overlay for matched problems (Problems tab is the v0.3.0 surface)
- Matrix child-build dispatcher (parser + expander + grid land now)
- Jenkinsfile `triggers { cron(…) }` parser (config-driven path lives in `anvil.edn`)
- Secondary test parsers (xunit / cargo-json / jest-json)
- GitLab MR + Bitbucket build-status

## Rollback

Set every `:anvil.features/*` to `false`, restart. anvil v0.3 behaves identically to v0.2.

## Compatibility notes

- Database migrations 005 (test-results), 006 (problems), and 007 (matrix columns) apply on startup. They're additive — downgrading to v0.2 leaves the new tables/columns in place but unused. No data loss.
- The `X-Anvil-Version` response header now reports `0.3.0` instead of `0.1.0` (a latent bug — the v0.2 builds incorrectly reported `0.1.0`).
