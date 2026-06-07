# What gets sent — the R3 data-flow contract

The v0.4 board's **risk R3** named this concern explicitly:

> AI authoring leaks proprietary repo code to Anthropic API.

This doc is the honest answer for each command. **Read it before
enabling the daemon-side "Explain"/"Optimize" buttons in any
environment that processes proprietary code.**

## TL;DR

| Command | What's sent | What's NOT sent |
|---|---|---|
| `anvil init` | The repo-context summary (file-extension counts, names of detected build tools, names of CI configs already present) | File contents — not `pom.xml`'s `<dependencies>`, not `package.json`'s scripts, not any source code |
| `anvil explain <path>` | The full content of the file you named | Everything else in the repo |
| `anvil optimize <path>` | The full content of the file you named | Everything else in the repo |

If you wouldn't paste it into a third-party web form, don't run the
command on it.

## `anvil init` — exact payload

The scanner produces a structured map; the `summary-string` helper
flattens it into the prompt. Here's a concrete example.

For a hypothetical Maven repo at `/home/op/myservice`, the prompt
contains (verbatim):

```
Languages:
Java (87 files), XML (3 files), Markdown (2 files)
Build tools:
Maven
Package files:
pom.xml, module-a/pom.xml, module-b/pom.xml
Existing CI:
GitHub Actions
Tool-version files:
.tool-versions
Total files scanned: 92

Primary language: Java
```

Notice what's NOT there:
- **No absolute paths.** `/home/op/myservice` is deliberately stripped.
- **No file contents.** The presence of `pom.xml` is named; its
  `<groupId>`, `<dependencies>`, and so on never leave the host.
- **No directory tree.** Just counts + the package-file paths.
- **No git metadata.** Branch, remotes, commit history — none sent.

You can dry-run-inspect the payload yourself:

```clojure
(require '[anvil.ai.repo-context :as rc])
(println (rc/summary-string (rc/scan ".")))
```

## `anvil explain <Jenkinsfile>` — exact payload

The full file content goes on the wire, plus a short system prompt
instructing Claude to describe it in plain English.

Send shape:
```json
{
  "model": "claude-sonnet-4-6",
  "max_tokens": 4096,
  "system": "You are a senior CI engineer explaining a Jenkinsfile…",
  "messages": [
    {"role": "user", "content": "Explain this Jenkinsfile:\n\n<file contents>"}
  ]
}
```

If your Jenkinsfile contains:
- **Credentials inline.** Don't run `explain` on it. Move secrets
  out via `withCredentials` and an anvil-stored credential first.
- **Internal hostnames / URLs.** Those go on the wire too.
- **Proprietary build logic / scripts.** Same — the file content
  is sent verbatim.

## `anvil optimize <Jenkinsfile>` — exact payload

Same as `explain` — full file content, different system prompt
focused on improvement suggestions.

## What Anthropic does with the data

Per [Anthropic's API terms](https://www.anthropic.com/legal/aup):
> "By default, Anthropic will not train our models on Inputs or
>  Outputs from our API customers."

The Console may retain inputs/outputs for abuse review (typically
30 days). For organizations with stricter data-handling needs,
Anthropic offers a Zero Data Retention amendment — see your AWS /
Console account team.

## Operator-level controls

### Pin to a specific model

```sh
anvil explain Jenkinsfile --model claude-haiku-4-5
```

### Buffer instead of stream

Streaming sends each token as it's generated. Buffering waits for
the complete response. Either way, the same payload is sent — but
some operators want the wire conversation in one TCP message rather
than chunked:

```sh
anvil explain Jenkinsfile --no-stream
```

### Don't run AI on a file at all

Just don't run the command. The feature requires explicit invocation;
nothing runs in the background. The daemon-side "Explain"/"Optimize"
buttons (T3.5, PR-3) will be gated behind the `:anvil.features/ai-authoring`
flag, closed by default per AV4-7.

## Per-job opt-in (planned for T3.5)

When the UI button ships (PR-3 of T3), it will be gated per-job
via `:anvil.ai/explain-enabled?` in the job's config — operators
opt in explicitly per-job, defaulting closed. This lets a single
anvil instance host both public-OSS jobs (button enabled) and
proprietary jobs (button hidden).

## See also

- [`overview.md`](overview.md) — what the commands do
- [`api-key-setup.md`](api-key-setup.md) — `ANTHROPIC_API_KEY` configuration
- v0.4 board R3 — the risk this doc closes
