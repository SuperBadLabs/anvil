# SLSA L3 provenance — overview

**Status:** v0.4.1 (was reserved-in-0.4.0; foundation shipped in PR-1 of T4)

Each artifact emitted by an anvil build gets a sigstore-signed
in-toto v1 provenance attestation written beside it as
`<artifact>.intoto.jsonl`. Anyone (with `cosign`) can verify that:

1. The artifact was produced by anvil (named builder identity)
2. From a specific source commit (named git URL + sha)
3. With a specific Jenkinsfile (named by content hash)
4. At a specific time (named by start/end ISO-8601)
5. Without being tampered with after the fact

That's the SLSA Level 3 contract.

## What ships in v0.4.1

| Component | What it does | Where |
|---|---|---|
| `anvil.provenance.statement` | Builds in-toto v1 + slsa-provenance/v1 statements from build records (pure data, no I/O) | T4.1 |
| `anvil.provenance.cosign` | Shells out to `cosign attest-blob` and `cosign verify-blob-attestation` | T4.2 |
| `anvil provenance verify <artifact>` | CLI verifier — exit 0 = verified, exit 4 = tampered/mismatch | T4.6 |
| Dispatcher post-build hook | Signs each `archiveArtifacts`-matched file automatically | T4.3 (PR-2) |
| Build page pill | "Provenance: ✅ attested (3 artifacts)" with download links | T4.4 (PR-3) |
| SSE `:provenance-attested` topic | Live UI swap as each artifact signs | T4.5 (PR-3) |

## Decisions locked

- **AV4-5:** sigstore/cosign with Fulcio ephemeral keys. No proprietary
  signing infra; no anvil-hosted signing service.
- **R9:** anvil shells out to host `cosign` rather than bundling a
  Java sigstore client. Operators install cosign (single static
  binary, ~50 MB) and anvil orchestrates it.
- **R4:** Fulcio keyless flow is the default; long-lived key fallback
  (`--key PATH`) is documented for air-gapped / offline operators.
- **AV4-7:** Closed by default. Feature flag `:anvil.features/provenance`
  starts off; nothing signs unless an operator opts in.

## Statement shape

The in-toto v1 envelope anvil generates conforms to
[github.com/in-toto/attestation v1](https://github.com/in-toto/attestation):

```json
{
  "_type": "https://in-toto.io/Statement/v1",
  "subject": [
    {"name": "my-app-1.2.3.jar",
     "digest": {"sha256": "..."}}
  ],
  "predicateType": "https://slsa.dev/provenance/v1",
  "predicate": {
    "buildDefinition": {
      "buildType": "https://anvil.superbadlabs.dev/buildtype/jenkins-pipeline/v1",
      "externalParameters": {
        "jobName": "demo",
        "buildNumber": 42,
        "jenkinsfileSha256": "..."
      },
      "resolvedDependencies": [
        {"uri": "git+https://github.com/example/repo.git@deadbeef...",
         "digest": {"sha1": "deadbeef..."}}
      ]
    },
    "runDetails": {
      "builder": {"id": "https://anvil.superbadlabs.dev/builder/0.4.1"},
      "metadata": {
        "invocationId": "demo#42",
        "startedOn": "2026-06-07T22:00:00Z",
        "finishedOn": "2026-06-07T22:00:42Z"
      }
    }
  }
}
```

cosign wraps this in a DSSE envelope, signs the envelope with the
operator's chosen key (Fulcio cert or long-lived), and writes the
combined bundle as `.intoto.jsonl`.

## Use cases

- **Supply-chain audits.** Auditors can verify *every* anvil-built
  artifact against the original source and Jenkinsfile, without
  trusting anvil itself.
- **Reproducible builds.** The attestation names the inputs
  (Jenkinsfile hash + git sha); a re-runner with the same inputs
  on the same anvil version produces the same artifact bytes.
- **Tamper detection in transit.** A binary downloaded from your
  artifact store still has its provenance — anyone can `cosign verify`
  before installing.
- **SLSA-graded customer demands.** Customers asking "is your build
  pipeline SLSA L3?" can verify themselves without an audit firm.

## See also

- [`sigstore-setup.md`](sigstore-setup.md) — installing cosign +
  configuring the Fulcio keyless flow
- [`offline-key-fallback.md`](offline-key-fallback.md) — the R4 path
  for air-gapped operators
- [`verify-cli.md`](verify-cli.md) — `anvil provenance verify`
  command reference
- v0.4 board AV4-5 + R4 + R9 — the decisions behind every choice here
