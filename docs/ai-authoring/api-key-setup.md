# API key setup — `ANTHROPIC_API_KEY`

The AI commands call Anthropic's Messages API directly from your
anvil host. No anvil-hosted middleware; no proxy. Per the v0.4 board
decision **AV4-4**, this is non-negotiable: bundling a hosted AI
dependency would invert anvil's single-team self-host posture.
Operator's key, operator's bill.

## Get a key

1. Sign in at [console.anthropic.com](https://console.anthropic.com/).
2. **Settings → API Keys → Create Key**. Name it (e.g. `anvil-prod`)
   so you can revoke it without affecting other integrations.
3. Copy it. The console shows the key **once**.

The key format is `sk-ant-api03-…`.

## Configure

### For interactive CLI use (single operator)

Add to your shell profile:

```sh
# ~/.bashrc or ~/.zshrc
export ANTHROPIC_API_KEY="sk-ant-api03-..."
```

Reload your shell, then verify:

```sh
echo $ANTHROPIC_API_KEY | head -c 12   # → sk-ant-api03
anvil explain Jenkinsfile               # smoke test
```

### For anvil daemon use (the web UI's "Explain"/"Optimize" buttons)

The daemon reads `ANTHROPIC_API_KEY` from its own process env. How
you set that depends on how you run anvil:

**systemd unit:**
```ini
[Service]
EnvironmentFile=/etc/anvil/env
ExecStart=/usr/local/bin/java -jar /opt/anvil/anvil.jar
```

`/etc/anvil/env`:
```
ANTHROPIC_API_KEY=sk-ant-api03-...
```

Set permissions: `chmod 600 /etc/anvil/env` and `chown anvil:anvil`.

**Docker:**
```sh
docker run -e ANTHROPIC_API_KEY=sk-ant-api03-... superbadlabs/anvil
```

Prefer a secrets manager (Vault, AWS Secrets Manager, sealed-secrets
on k8s) over committing the env file to your config repo.

## Verify

```sh
anvil explain Jenkinsfile
```

You should see streamed output ending in:
```
✓ Done.
  tokens: in=420  out=180
```

### Common errors

| Error | Likely cause |
|---|---|
| `ERROR: ANTHROPIC_API_KEY not set` | The shell or process env doesn't have the variable exported. For systemd, check `systemctl show -p Environment anvil`. |
| `Anthropic API returned HTTP 401` + `hint: API key missing or invalid` | The key is set but rejected — wrong workspace, revoked, or you copied a partial value. |
| `Anthropic API returned HTTP 429` + `hint: Rate limited` | You hit your tier's per-minute quota. Wait + retry, or upgrade tier in Console. |
| `Anthropic API returned HTTP 529` + `hint: API overloaded` | Anthropic-side capacity. Retry with backoff. |

## Cost control

Each CLI call's footer prints token usage on stderr:
```
tokens: in=420  out=180
```

The default model is **`claude-sonnet-4-6`** — for the typical
Jenkinsfile (few hundred lines, few KB), one call runs under 5K
input + 1K output tokens. At Sonnet 4.6 pricing this is well under
a penny per call.

For tighter cost: pass `--model claude-haiku-4-5`. For higher quality
on complex pipelines: `--model claude-opus-4-8`. The
[Anthropic pricing page](https://platform.claude.com/docs/en/pricing)
has the current rates.

## See also

- [`overview.md`](overview.md) — what the commands do
- [`what-gets-sent.md`](what-gets-sent.md) — exact data sent on the wire
- v0.4 board AV4-4 — the local-first decision and rationale
