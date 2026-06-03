# anvil — changelog

## 0.3.0 — Parity Release (2026-06-03)

The "yes, I'll switch from Jenkins/GHA to anvil" tranche. Seven Tier-1 features close the day-to-day operator surface that v0.2 left open. Closed-by-default behind per-feature flags so upgrade is byte-identical until the operator flips each one.

### Features (each behind `:anvil.features/<flag>` in `anvil.edn`)

- **JUnit / surefire dashboard** (`:junit`) — Maven / Gradle / pytest / cargo nextest XML parsed at build end; build page grows a summary card, collapsible failures with stack traces, sortable-by-duration table, 30-build pass-rate sparkline. Jenkinsfile `junit '…/*.xml'` step now scans+persists+publishes `:test-completed`.
- **Problem matchers** (`:problem-matchers`) — clickable file:line in build logs. Six bundled YAML rules (gcc, rustc, javac, mypy, eslint, msbuild) + native GHA workflow-command parser (`::warning file=…::msg`). Per-build Problems tab with severity-filtered list.
- **PR-check integration** (`:pr-checks`) — webhook receiver at `POST /anvil/webhooks/github` (HMAC-SHA256 verified) → triggers anvil build → publishes status to GitHub's Checks API. PR shows "anvil — success/failure" with link back. PAT auth at v0.3.0; App auth protocol documented for v0.3.1.
- **Declarative matrix** (`:matrix`) — `matrix { axes { axis … } excludes { } stages { } }` parser + cross-product expander + 100-cell cap. 2-axis grid view on the parent build page. Child-build dispatcher fan-out queued for v0.3.1.
- **Scheduled triggers** (`:scheduler`) — full Jenkins cron syntax + 7 aliases (`@hourly`/`@daily`/…) + Jenkins's `H`-spread (SHA-256 hashed per job so load distributes). Job page shows "Next scheduled run". Config-driven (`anvil.edn`); Jenkinsfile `triggers { cron(…) }` parser deferred to v0.3.1.
- **Secrets management** (`:secrets`) — `anvil secrets {add|list|show|delete|rotate-master}` CLI (values read from stdin only, never argv). `/secrets` admin page (IP-gated). Crypto + storage + log masking + `withCredentials` already from TX11D; this release ships the operator UX + rotation flow.
- **mise / asdf tool detection** (`:mise`) — workspace's `.mise.toml` or `.tool-versions` triggers `mise install` (asdf fallback) before the first `sh` step. `anvil setup tools` CLI installs mise from upstream.

### Infrastructure (always-on)

- Auto-redeploy on master: systemd timer + Babashka script + UFW LAN-only recipe under `docs/deploy/`. anvil's own dogfood host runs this — every PR merge hot-swaps into prod within 5 min.
- Bus event topic registry at `anvil.events.topics` — typo-guarded constants for both existing (TX2–TU6) and v0.3-reserved event types.
- Feature-flag mechanism at `anvil.features` — `wrap-feature` middleware 404s disabled routes with a self-explanatory body.
- Migration 005 (`anvil_test_results` + `_summaries`), 006 (`anvil_problems` + `_summaries`), 007 (matrix parent/child columns).

### Test suite

442 tests / 1451 assertions, 0 failures. UI TTFB still under the 50ms p50 TU0.7 budget on every page.

### Locked decisions (`docs/roadmap/v0.3-board.md`)

AV3-1 Tier 1 only; AV3-2 surefire is the canonical test format; AV3-3 adopt GHA's problem-matcher schema; AV3-4 GitHub PR checks first (GitLab/Bitbucket in v0.3.1); AV3-5 matrix declarative-only; AV3-6 file-encrypted at rest; AV3-7 mise primary, asdf fallback; AV3-8 cron expressions only at v0.3.0; AV3-9 (revised) public deploy waits for v0.3.0 — this is the first.

### Carryover to v0.3.x (documented; data hooks in place)

- T2.6 console ANSI overlay for matched problems
- T3 GitHub App auth (JWT → installation tokens)
- T4.4/4.6 matrix child-build dispatcher fan-out + SSE
- T5.4 Jenkinsfile `triggers { cron(…) }` translator
- v0.3.1 secondary parsers: xunit / cargo-json / jest-json
- v0.3.1: GitLab MR + Bitbucket build-status

## 0.2.1 — Post-extraction standalone release (2026-06-03)

First release built from `github.com/SuperBadLabs/anvil` after the monorepo subtree-split. `chengis-core 0.1.0` consumed via local Maven cache (Clojars deferred). Functionally identical to 0.2.0.

## 0.2.0 — anvil UI

Marquee feature: build console live tail. Dashboard + jobs + queue + executors + builds + compare + artifacts views. SSE bus. Mobile-passable. Trigger UX with cookie-scoped recent-values. See `docs/anvil-ui/` for the TU0–TU6 board.

## 0.1.0 — initial public

Jenkinsfile parser + Pipeline DSL runtime, Jenkins REST shim (read-mostly + build trigger), SQLite persistence, `anvil import jenkinsfile` CLI.
