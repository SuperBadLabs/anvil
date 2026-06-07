# AI authoring (v0.4 — T3)

Status: **stub** (T0.5 placeholder). Real docs land at T3.7 + T7.5.

## What this feature ships

Three CLI sub-commands powered by the Anthropic API:

- `anvil init` — scaffold a Jenkinsfile / Chengisfile from a repo,
  detecting language, framework, build tool from package files +
  file-extension counts.
- `anvil explain <Jenkinsfile>` — plain-English description of what
  the pipeline does, streamed to stdout.
- `anvil optimize <Jenkinsfile>` — suggest improvements (parallelism,
  container-step, caching, retry-around-flaky).

A "Explain this Jenkinsfile" / "Optimize" button on `/jobs/<j>` opens
a modal that streams the same content via SSE.

## AV4-4: local-first, never hosted

All API calls go directly from the operator's anvil instance to
`api.anthropic.com` using `ANTHROPIC_API_KEY` from env. There is no
hosted anvil service, no proxied calls, no aggregation. The operator's
key, the operator's bill.

This is non-negotiable for the v0.4 ship — bundling a hosted AI
dependency would invert anvil's single-team-self-host posture.

## R3: what gets sent

`anvil explain` / `optimize` send **Jenkinsfile content only**, never
workspace files. `anvil init` sends only the structured `repo-context`
output (file-extension counts + package-file names + their public
metadata, e.g. `package.json` `dependencies`). The full doc at
`docs/ai-authoring/what-gets-sent.md` (T3.7) makes the data flow
explicit so operators can opt in per-job via `:anvil.ai/explain-enabled?`.

## Files (planned, not yet written)

- `src/anvil/ai/client.clj` — Anthropic HTTP client (T3.1)
- `src/anvil/ai/repo_context.clj` — repo scan → structured context (T3.2)
- `src/anvil/cli/ai.clj` — init / explain / optimize sub-commands (T3.3)
- `src/anvil/web/views/ai.clj` — UI buttons + streaming modal (T3.5)
- `test/anvil/ai/client_test.clj` — stub-client tests (T3.6)

## Not in scope

- Hosted anvil-managed model service (AV4-4 — never)
- Auto-applying optimization suggestions (T4.x at earliest; v0.4 only suggests)
- Multi-model routing / cost optimization (v0.4.x)
