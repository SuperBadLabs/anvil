# PR-checks diagnostic — "why isn't my check appearing?"

A curl-based debug ladder. Walk top to bottom; the first failed check is your fault.

## 1. Is the feature flag on?

```bash
curl -s http://localhost:8765/anvil/webhooks/github -X POST -d '{}'
```

| Response | Diagnosis |
|---|---|
| `503 anvil: :pr-checks feature disabled` | Set `:anvil.features/pr-checks true` in `anvil.edn` and restart anvil. |
| `401 webhook signature mismatch` | Flag is on; you just hit the signature gate. Continue to step 2. |
| `200 {"received":null}` | Flag is on AND no webhook secret is set; anvil is accepting unverified webhooks. Set `ANVIL_GITHUB_WEBHOOK_SECRET` (see `pat-setup.md`). |

## 2. Does anvil reach api.github.com with the PAT?

```bash
curl -s -H "Authorization: Bearer $ANVIL_GITHUB_TOKEN" \
     -H "Accept: application/vnd.github+json" \
     https://api.github.com/repos/<owner>/<repo>/check-runs?check_name=anvil | jq
```

| Result | Diagnosis |
|---|---|
| HTTP 200 with a `check_runs` array | Token is valid for that repo. Continue to step 3. |
| HTTP 401 | Token expired or wrong. Re-generate (`pat-setup.md` step 1). |
| HTTP 404 | Token doesn't have access to that repo. Check the repo selector when you minted the PAT. |
| HTTP 403 | Token is missing `checks:write` scope. Re-mint with the right permissions. |

## 3. Is the bus subscriber running?

```bash
journalctl -u anvil -n 200 | grep -i 'github-subscriber\|create-check-run'
```

Look for "registered bus subscribers" on startup. If absent, the daemon didn't see `:pr-checks` enabled at startup — verify your `anvil.edn` path with `ANVIL_CONFIG_DIR`.

## 4. Did a build actually trigger?

Trigger a build (PR or manual) and:

```bash
journalctl -u anvil -f | grep -i 'build-started\|webhook\|github'
```

You should see (in order):
1. `anvil.github webhook: triggering my-job for PR head <sha>`
2. `Anvil bus: :build-started`
3. `anvil.github: create-check-run!` (no warn after — 201 success)

If you see (1) but not (2), the webhook triggered but anvil couldn't start the build — most likely the job name doesn't match what's in `:anvil.github/jobs` in your config.

If you see (1) and (2) but not (3), check `:anvil.github/jobs.<job>.checks-enabled?` — a missing `true` here silently no-ops the subscriber.

## 5. End-to-end: simulate a webhook locally

```bash
BODY='{"action":"opened","repository":{"full_name":"foo/bar"},"pull_request":{"head":{"sha":"abc123","ref":"feature"}}}'
SIG=sha256=$(echo -n "$BODY" | openssl dgst -sha256 -hmac "$ANVIL_GITHUB_WEBHOOK_SECRET" | sed 's/^.* //')

curl -s -X POST http://localhost:8765/anvil/webhooks/github \
  -H "X-GitHub-Event: pull_request" \
  -H "X-Hub-Signature-256: $SIG" \
  -H "Content-Type: application/json" \
  -d "$BODY"
```

If this returns `200 {"received":"pull_request"}` and you see a build start in the anvil UI, the full path works.
