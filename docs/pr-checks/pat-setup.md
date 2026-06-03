# GitHub PR-check integration — PAT setup (v0.3.0 default)

Anvil's v0.3.0 GitHub PR-check integration uses a Personal Access Token (PAT) for the simplest possible auth flow. App auth (JWT → installation token) lands in v0.3.x — see `github-app-setup.md` for the planned protocol.

## 1. Generate the PAT

GitHub → **Settings → Developer settings → Personal access tokens → Fine-grained tokens** → "Generate new token."

| Setting | Value |
|---|---|
| Resource owner | the org or user that owns the repo |
| Repository access | only the repos anvil will post checks to |
| **Repository permissions** | **Checks: Read and write**, **Contents: Read** (for webhook payloads), **Pull requests: Read** |
| Expiration | 90 days (rotate per your security posture) |

Copy the `github_pat_...` token. **GitHub shows it only once.**

## 2. Configure anvil

Two options. Pick one.

### a) Environment variable (recommended for production)

```bash
sudo systemctl edit anvil.service
# add:
#   [Service]
#   Environment="ANVIL_GITHUB_TOKEN=github_pat_..."
#   Environment="ANVIL_GITHUB_WEBHOOK_SECRET=randomly-generated-string"
sudo systemctl restart anvil
```

### b) anvil.edn (for dev hosts where you don't want to touch systemd)

```clojure
;; ~/anvil-dogfood/config/anvil.edn  (or $ANVIL_CONFIG_DIR/anvil.edn)
{:anvil.features/pr-checks       true
 :anvil.github/token             "github_pat_..."
 :anvil.github/webhook-secret    "randomly-generated-string"
 :anvil.github/jobs
 {"my-job" {:repo "owner/name" :checks-enabled? true}}}
```

The mapping in `:anvil.github/jobs` is what tells anvil "post checks for the `my-job` job's builds against `owner/name`."

## 3. Configure the webhook

In the GitHub repo → **Settings → Webhooks → Add webhook**:

| Field | Value |
|---|---|
| Payload URL | `https://your-anvil-host/anvil/webhooks/github` |
| Content type | `application/json` |
| Secret | the same value you used for `ANVIL_GITHUB_WEBHOOK_SECRET` |
| Events | Pull request, Push |

## 4. Verify

Open a PR. Within ~1s you should see a check named **anvil** in the PR's Checks section, "In progress." When the build completes, the check updates to success/failure with a link back to the anvil build page.

If the check never appears, run the diagnostic in `diagnostic.md`.
