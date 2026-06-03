# GitHub PR-check integration — App auth (deferred to v0.3.x)

GitHub Apps are the preferred long-term auth path:

- 1-hour scoped installation tokens (vs 90-day PATs)
- 15000 req/hr rate limit (vs 5000 for PATs)
- Per-installation permission scoping
- No user-account coupling

**App auth is not yet implemented at v0.3.0.** Use `pat-setup.md` for now. This doc captures the intended protocol so operators can mint the App ahead of time and so v0.3.x implementation is a code-only change.

## Planned protocol

1. Create a GitHub App (Settings → Developer settings → GitHub Apps → New App).
   - **Webhook URL**: `https://your-anvil-host/anvil/webhooks/github`
   - **Webhook secret**: same value as `ANVIL_GITHUB_WEBHOOK_SECRET`
   - **Permissions**: `Checks: read/write`, `Contents: read`, `Pull requests: read`
   - **Subscribe to events**: `Pull request`, `Push`

2. Generate a private key (`.pem`). Save it to `$ANVIL_CONFIG_DIR/github-app.pem` with `chmod 600`.

3. Install the App on the repos you want checks for. Note the **installation ID** from the post-install URL.

4. Configure anvil:

   ```clojure
   ;; anvil.edn
   {:anvil.github/auth :app
    :anvil.github/app-id              123456
    :anvil.github/app-private-key-path "github-app.pem"
    :anvil.github/installation-ids
    {"owner/repo-1" 78901
     "owner/repo-2" 78902}}
   ```

5. anvil's `anvil.integration.github.auth/installation-token` (forthcoming) will:
   - Sign a JWT (RS256) with the private key, claiming `iss=<app-id>`, `exp=now+10min`.
   - POST `https://api.github.com/app/installations/<id>/access_tokens` with that JWT.
   - Cache the resulting installation token until 5 min before expiry.
   - Refresh transparently when a request comes in within the buffer window.

PAT-based deployments will continue to work after the App path lands — anvil will detect which flavor is configured.

## Tracking issue

When v0.3.x cycles for this feature, it lands behind `:anvil.github/auth :app` while PAT stays the default for backward compat.
