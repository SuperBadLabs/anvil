---
title: Bitbucket PR Build-Status Integration
audience: operators, developers
category: feature
purpose: Real-time PR build status from anvil posted to Bitbucket Cloud
lifecycle: shipped
status: v0.5 T3.2
---

# Bitbucket PR Build-Status Integration

Anvil can post build status updates to Bitbucket Cloud pull requests using the
[Bitbucket Commit Status API](https://developer.atlassian.com/cloud/bitbucket/rest/api-group-commit-statuses/).
When enabled, every build fires an `INPROGRESS` status when it starts and a
`SUCCESSFUL` / `FAILED` / `STOPPED` status when it finishes. Bitbucket
surfaces these automatically on the PR's **Builds** section.

## Quick start

### 1. Enable the feature flag

```edn
;; anvil.edn
{:anvil.features/enabled #{:bitbucket-pr}}
```

### 2. Set your token

**OAuth2 Bearer token** (recommended):

```bash
export ANVIL_BITBUCKET_TOKEN=<oauth2-access-token>
```

**App Password** (alternative):

```bash
export ANVIL_BITBUCKET_USER=my-username
export ANVIL_BITBUCKET_APP_PASSWORD=apppassword-here
```

For self-hosted Bitbucket Data Center, set `ANVIL_BITBUCKET_URL`:

```bash
export ANVIL_BITBUCKET_URL=https://bitbucket.internal.example.com
```

### 3. Configure per-job mapping

```edn
;; anvil.edn
{:anvil.bitbucket/jobs
 {"my-pipeline-job" {:workspace  "my-workspace"
                     :repo-slug  "my-repo"
                     :checks-enabled? true}}}
```

### 4. Pass the commit SHA in your build trigger

The subscriber reads `:sha` from the `build-started` / `build-done` bus event
payload. If `:sha` is absent, no status is posted (AV5-6 honesty rule).

## Config reference

| Key | Source | Description |
|---|---|---|
| `ANVIL_BITBUCKET_TOKEN` | env | OAuth2 access token (Bearer) |
| `ANVIL_BITBUCKET_USER` | env | Username for Basic auth (App Password) |
| `ANVIL_BITBUCKET_APP_PASSWORD` | env | App password for Basic auth |
| `ANVIL_BITBUCKET_URL` | env | Base URL (default: `https://api.bitbucket.org`) |
| `:anvil.bitbucket/token` | anvil.edn | Token fallback if env not set |
| `:anvil.bitbucket/base-url` | anvil.edn | Base URL fallback if env not set |
| `:anvil.bitbucket/jobs` | anvil.edn | Map of `job-name → {:workspace :repo-slug :checks-enabled?}` |

Auth precedence: `ANVIL_BITBUCKET_TOKEN` (Bearer) > `ANVIL_BITBUCKET_USER`+`ANVIL_BITBUCKET_APP_PASSWORD` (Basic).
If neither is set the `Authorization` header is omitted entirely (public repo scenario).

## Status mapping

| anvil result | Bitbucket state |
|---|---|
| `:success` | `SUCCESSFUL` |
| `:failure` | `FAILED` |
| `:unstable` | `FAILED` |
| `:aborted` | `STOPPED` |
| `:neutral` | `SUCCESSFUL` |
| anything else | `FAILED` (honest) |

## SSE event

After a final status is posted, the subscriber publishes `:evt-pr-checked`
on the `[:job <name>]` bus topic. The payload includes `:state` and
`:http-status`.

## Gaps and known limitations (honest per AV5-6)

- **SHA must be explicit**: the caller must supply `:sha` in the build trigger;
  anvil has no automatic SCM commit-detection for Bitbucket remotes.
- **One repo per job**: each anvil job maps to exactly one workspace+repo pair.
- **Cloud API only tested**: the self-hosted (`ANVIL_BITBUCKET_URL`) path is
  supported structurally but not integration-tested against a real Data Center
  instance; endpoint paths may differ in some Data Center versions.
- **No webhook parsing**: anvil does not consume Bitbucket webhooks; SHA
  injection is the caller's responsibility.

## Files

| File | Role |
|---|---|
| `src/anvil/integration/bitbucket.clj` | API client + SHA cache |
| `src/anvil/integration/bitbucket_subscriber.clj` | Bus subscriber wiring |
| `test/anvil/integration/bitbucket_test.clj` | API client unit tests |
| `test/anvil/integration/bitbucket_subscriber_test.clj` | Subscriber integration tests |
