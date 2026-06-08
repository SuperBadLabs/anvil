---
title: GitLab MR Commit-Status Integration
audience: operators, developers
category: feature
purpose: Real-time MR pipeline status from anvil builds posted to GitLab
lifecycle: shipped
status: v0.5 T3.1
---

# GitLab MR Commit-Status Integration

Anvil can post build status updates to GitLab merge-request commits using the
[GitLab Commit Status API](https://docs.gitlab.com/ee/api/commits.html#post-the-build-status-to-a-commit).
When enabled, every build fires a `running` status when it starts and a
`success` / `failed` / `canceled` status when it finishes. GitLab surfaces
these automatically under the MR's **Pipelines** tab.

## Quick start

### 1. Enable the feature flag

```edn
;; anvil.edn
{:anvil.features/enabled #{:gitlab-mr}}
```

### 2. Set your token

```bash
export ANVIL_GITLAB_TOKEN=glpat-xxxxxxxxxxxxxxxxxxxx
```

The token needs at minimum `write_repository` scope. For self-hosted GitLab
instances you can also set `ANVIL_GITLAB_URL`:

```bash
export ANVIL_GITLAB_URL=https://gitlab.internal.example.com
```

### 3. Configure per-job mapping

```edn
;; anvil.edn
{:anvil.gitlab/jobs
 {"my-pipeline-job" {:project-id "42"
                     :checks-enabled? true}}}
```

`project-id` accepts either the numeric project ID or the URL-encoded path
(`"my-group%2Fmy-repo"`).

### 4. Pass the commit SHA in your build trigger

The subscriber reads `:sha` from the `build-started` / `build-done` bus event
payload. When calling the anvil build trigger endpoint, include:

```json
{ "sha": "<full-commit-sha>" }
```

If `:sha` is absent, no status is posted (anvil never invents data it does
not have — AV5-6 honesty rule).

## Config reference

| Key | Source | Description |
|---|---|---|
| `ANVIL_GITLAB_TOKEN` | env | GitLab personal/project access token |
| `ANVIL_GITLAB_URL` | env | Base URL for self-hosted GitLab (default: `https://gitlab.com`) |
| `:anvil.gitlab/token` | anvil.edn | Token fallback if env not set |
| `:anvil.gitlab/base-url` | anvil.edn | Base URL fallback if env not set |
| `:anvil.gitlab/jobs` | anvil.edn | Map of `job-name → {:project-id STR :checks-enabled? BOOL}` |

`checks-enabled?` defaults to `true` when the job entry is present.

## Status mapping

| anvil result | GitLab state |
|---|---|
| `:success` | `success` |
| `:failure` | `failed` |
| `:unstable` | `failed` |
| `:aborted` | `canceled` |
| `:neutral` | `success` |
| anything else | `failed` (honest) |

## SSE event

After a final status is posted, the subscriber publishes `:evt-mr-checked`
on the `[:job <name>]` bus topic. The payload includes `:state` and
`:http-status` so UI widgets can react without polling.

## Gaps and known limitations (honest per AV5-6)

- **No webhook verification**: anvil does not parse GitLab webhook payloads.
  SHA is caller-supplied via the build trigger parameters.
- **SHA must be explicit**: there is no automatic SCM integration that
  extracts the HEAD sha; the caller (CI glue script, webhook handler) must
  provide it.
- **One project per job**: each anvil job maps to exactly one GitLab project.
  Parameterized multi-repo pipelines are not yet supported.
- **No MR IID**: the status is posted on the commit SHA, not on the MR number.
  GitLab automatically maps it; no MR IID lookup is needed or performed.

## Files

| File | Role |
|---|---|
| `src/anvil/integration/gitlab.clj` | API client + SHA cache |
| `src/anvil/integration/gitlab_subscriber.clj` | Bus subscriber wiring |
| `test/anvil/integration/gitlab_test.clj` | API client unit tests |
| `test/anvil/integration/gitlab_subscriber_test.clj` | Subscriber integration tests |
