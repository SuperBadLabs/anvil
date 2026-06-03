# PR-check integration (GitHub status API) (v0.3 — T3)

Status: **stub** (T0.5 placeholder). Real docs land at T3.7 / T8.5.

Three docs are reserved under this directory (named in the v0.3 board T3.7):

- `github-app-setup.md` — GitHub App credentials path (preferred)
- `pat-setup.md` — Personal Access Token fallback
- `diagnostic.md` — curl-based "is my anvil → GitHub round-trip working?" checks

## What this feature ships

anvil reports build status back to GitHub PRs. A PR view on github.com
shows anvil's check with green/red status, build duration, and a link
to the anvil build page. Re-running from the PR updates status live.

## Feature flag

```clojure
;; anvil.edn
{:anvil.features/pr-checks true}
```

## Bounding decisions

- **AV3-4**: GitHub status API for PR checks at v0.3.0. GitLab MR +
  Bitbucket build status deferred to v0.3.1.
- **R3**: Document both GitHub App AND PAT paths; ship a curl
  diagnostic page so misconfigured auth is debuggable without source.

## Files (when T3 lands)

- `src/anvil/integration/github/auth.clj` — App + PAT
- `src/anvil/integration/github/checks_api.clj` — POST/PATCH /check-runs
- `src/anvil/web/webhooks_github.clj` — POST /anvil/webhooks/github
- Per-job config: `:anvil.github/checks-enabled?`, `:anvil.github/repo`,
  `:anvil.github/installation-id`

## SSE event

`:checks-updated` on `[:job <name>]` topic. Reserved in T0.4
(`anvil.events.topics/evt-checks-updated`).

## Owner

T3 tranche owner.
