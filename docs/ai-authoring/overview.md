# AI authoring — overview

**Status:** v0.4.1 (was reserved-in-0.4.0; shipped in 0.4.1)

Three CLI commands that call the Anthropic Messages API directly from
your anvil host to help you write, understand, and improve
Jenkinsfiles.

```
anvil init                    # scaffold a Jenkinsfile from the current repo
anvil explain <Jenkinsfile>   # plain-English description
anvil optimize <Jenkinsfile>  # suggest concrete improvements
```

A "Explain this Jenkinsfile" / "Optimize" button on `/jobs/<j>` will
open a modal that streams the same content via SSE — that ships in
PR-3 of T3 (alongside the SSE producer for `:ai-suggested`).

## When you'd use each command

### `anvil init`

You're standing up a new repo with anvil and want a starting-point
Jenkinsfile that matches your build tool, languages, and existing CI
configs.

```sh
cd my-new-service
anvil init                                  # writes ./Jenkinsfile
anvil init --out ci/Jenkinsfile             # custom output path
anvil init --print > my.Jenkinsfile         # send to stdout
anvil init --force                          # overwrite an existing file
```

The scaffold is intentionally minimal — declarative pipeline,
build → test → package, the right `junit` glob for your build tool,
no plugins, no shared libraries, no agent labels. It's a starting
point, not a finished product.

### `anvil explain <Jenkinsfile>`

You inherited a Jenkinsfile and need to know what it does before
migrating it to anvil.

```sh
anvil explain Jenkinsfile
anvil explain Jenkinsfile --model claude-opus-4-8    # higher-quality at higher cost
anvil explain Jenkinsfile --no-stream                # buffer the full reply
```

Output shape:
- **What this pipeline does** — one sentence
- **Stages** — bulleted list
- **Triggers** — commits / tags / schedule
- **Outputs** — artifacts / reports
- **Gotchas** — plugin deps, credentials, shared libraries

### `anvil optimize <Jenkinsfile>`

You have a working Jenkinsfile and want concrete improvements:
parallelism, caching, container-step, retry blocks.

```sh
anvil optimize Jenkinsfile
```

Output is markdown with up to 3 numbered suggestions, each with a
one-sentence "why" and a unified diff "change". If the file is
already well-optimized, the model says so — it's instructed not to
manufacture suggestions for the sake of having something to say.

## Streaming and exit codes

All three commands stream tokens to stdout by default. Pass
`--no-stream` if you want a single buffered chunk (useful for piping
through filters that don't handle partial output).

Exit codes:
- `0` — success
- `2` — bad usage (missing argv, file not found, API key not set, network)
- `4` — model refused for safety reasons (Claude returned `stop_reason: refusal`)

A footer goes to **stderr** with the stop reason and token usage:
```
✓ Done.
  tokens: in=420  out=180
```
This lets you cleanly redirect stdout to a file (`anvil init --print > Jenkinsfile`)
without the footer landing in the file.

## See also

- [`api-key-setup.md`](api-key-setup.md) — how to get and configure `ANTHROPIC_API_KEY`
- [`what-gets-sent.md`](what-gets-sent.md) — exact data-flow contract (R3 privacy)
- v0.4 board, AV4-4 — the "local-first, never hosted" decision
