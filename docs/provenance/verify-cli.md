# `anvil provenance verify` — CLI reference

Verify a SLSA L3 provenance attestation produced by anvil.

```
anvil provenance verify <artifact> [options]
```

The CLI is a thin convenience wrapper around `cosign
verify-blob-attestation` that:
- Locates the sibling `<artifact>.intoto.jsonl` automatically
- Surfaces operator-friendly errors (artifact missing, attestation
  missing, cosign not installed) before invoking cosign
- Translates cosign's exit codes into anvil's documented set

## Quick start

```sh
# Fulcio keyless flow (default) — need cosign on PATH + Rekor/Fulcio reachable
anvil provenance verify build/my-app-1.2.3.jar \
  --certificate-identity "ci@example.com" \
  --certificate-oidc-issuer "https://accounts.google.com"

# Long-lived key flow (offline)
anvil provenance verify build/my-app-1.2.3.jar \
  --key /etc/anvil/cosign.pub
```

## Exit codes

| Code | Meaning |
|---|---|
| `0` | ✓ Verified. The attestation is valid for this artifact. |
| `2` | Setup error: artifact not found, attestation not found, bad argv. Fix the env and re-run. |
| `3` | `cosign` not on PATH. Install cosign — see [`sigstore-setup.md`](sigstore-setup.md). |
| `4` | ✗ Verification failed. Artifact was modified, attestation is for a different artifact, OR signer identity/key doesn't match what you specified. |

Any other non-zero exit indicates an unexpected cosign error — check
stderr for the upstream message.

## Options

| Flag | Default | Description |
|---|---|---|
| `--attestation PATH` | `<artifact>.intoto.jsonl` | Explicit attestation path. Override when the attestation lives somewhere other than next to the artifact. |
| `--key PATH` | (Fulcio keyless) | Long-lived public key for verification (R4 air-gapped flow). When set, no Fulcio/Rekor lookup happens. |
| `--certificate-identity IDENTITY` | (required by cosign for Fulcio) | Expected signer identity — email address (`ci@example.com`) or URI (`https://github.com/org/repo/.github/workflows/release.yml@refs/heads/main`). |
| `--certificate-oidc-issuer ISSUER` | (required by cosign for Fulcio) | Expected OIDC issuer URL — `https://accounts.google.com`, `https://token.actions.githubusercontent.com`, etc. |
| `-h`, `--help` | — | Print usage and exit 0. |

## Examples

### Verify an artifact built by GitHub Actions, signed via Fulcio

```sh
anvil provenance verify dist/my-app-1.2.3.tar.gz \
  --certificate-identity "https://github.com/myorg/myrepo/.github/workflows/release.yml@refs/heads/main" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com"
```

The certificate-identity here is GitHub Actions' identity URI shape;
it pins the workflow + branch that's allowed to sign for this artifact.
A signature from `feature/sketchy-branch` won't match.

### Verify with a long-lived key (operator-managed)

```sh
anvil provenance verify build/my-app.jar --key /etc/anvil/cosign.pub
```

No network calls. cosign reads `cosign.pub`, decrypts the DSSE
envelope, checks the signature, compares the subject digest against
the actual file bytes.

### Verify an attestation stored at a non-default location

```sh
anvil provenance verify build/my-app.jar \
  --attestation /var/attestations/my-app-v1.2.3.intoto.jsonl \
  --key /etc/anvil/cosign.pub
```

### Verify the integrity of a CI-built artifact your customer downloaded

```sh
# Customer side
curl -O https://your-cdn.example.com/my-app-1.2.3.jar
curl -O https://your-cdn.example.com/my-app-1.2.3.jar.intoto.jsonl
anvil provenance verify my-app-1.2.3.jar --key your-published-pubkey.pub
# → ✓ Provenance VERIFIED
```

This is the operator's win: the customer didn't need to trust your
artifact server, didn't need to trust anvil, didn't need to trust
your build pipeline. They needed your public key (distributed
out-of-band, e.g. in your release notes) and cosign.

## What the verify actually checks

`cosign verify-blob-attestation` performs three checks in sequence:

1. **Signature is valid.** The DSSE envelope's signature decrypts
   with the named key (or Fulcio cert chains to a trusted root).
2. **Predicate type matches.** We pin `--type slsaprovenance1` so a
   different attestation type (e.g. SPDX) doesn't accidentally
   verify as SLSA provenance.
3. **Subject digest matches the artifact.** Cosign re-hashes the
   artifact on disk and compares against the `subject.digest.sha256`
   in the attestation. Tampered artifact → exit 4.

What this does NOT check:
- **Whether the build was hermetic.** That's SLSA L4 territory.
- **Whether the source code was malicious.** Provenance proves what
  was built, not whether what was built was safe.
- **Whether the Jenkinsfile was reviewed.** The Jenkinsfile content
  hash is in `externalParameters.jenkinsfileSha256`; verifiers can
  fetch the source and compare hashes themselves, but that's a
  separate workflow.

## Output

### Success (exit 0)

stdout/stderr:
```
✓ Provenance VERIFIED
  cosign: v3.0.6
Verified OK
```

### Tampered or mismatch (exit 4)

stderr:
```
✗ Provenance VERIFICATION FAILED (cosign exit 1)
--- cosign stderr ---
Error: signature is not valid: blob digest mismatch
---
The attestation does not match this artifact.  Likely causes:
  - the artifact was modified after signing
  - the attestation is for a different artifact
  - the verifying party (identity / OIDC issuer) doesn't match the signer
```

## See also

- [`overview.md`](overview.md) — what SLSA L3 provenance is + the statement shape
- [`sigstore-setup.md`](sigstore-setup.md) — installing cosign + Fulcio flow
- [`offline-key-fallback.md`](offline-key-fallback.md) — R4 long-lived key flow
- [cosign verify-blob-attestation reference](https://github.com/sigstore/cosign/blob/main/doc/cosign_verify-blob-attestation.md) — every cosign flag
