# sigstore / cosign setup

anvil signs and verifies SLSA provenance attestations by **shelling
out to `cosign`** — the canonical sigstore CLI. You need cosign on
PATH wherever anvil runs (the build host that signs, and any host
that runs `anvil provenance verify`).

Per the v0.4 board's risk **R9**: we deliberately don't bundle a
Java sigstore client. It's heavy (~5 MB+ on the uberjar), cosign is
the standard tool everyone in the ecosystem reaches for anyway, and
operators can independently `cosign verify` attestations without
running any anvil code.

## Install cosign

Pick the option that matches your platform.

### macOS (Homebrew)

```sh
brew install cosign
cosign version    # → v3.0.6 or later
```

### Linux (release binary)

```sh
VERSION=v3.0.6
curl -fsSL -o /usr/local/bin/cosign \
  "https://github.com/sigstore/cosign/releases/download/${VERSION}/cosign-linux-amd64"
chmod +x /usr/local/bin/cosign
cosign version
```

### Docker image

```sh
# Run cosign in-container — useful when you don't want it on the host
docker run --rm -v "$PWD":/work -w /work \
  gcr.io/projectsigstore/cosign:v3.0.6 version
```

anvil's shell-out path expects `cosign` on PATH; if you're running
inside a container, mount your cosign binary into a PATH directory or
wrap the anvil JAR in a script that prepends to PATH.

### Verify install

```sh
cosign version | head -3
```

You should see something like:
```
______   ______        _______. ...
GitVersion:    v3.0.6
```

## Configure the Fulcio keyless flow (default)

The default sigstore flow uses **Fulcio** (a CA that issues short-lived
certs based on OIDC identity) + **Rekor** (a transparency log). No
long-lived key to manage; the signature is bound to your OIDC identity
at signing time, and anyone with internet access to Fulcio + Rekor
can verify after the fact.

For anvil's automatic signing (T4.3, PR-2) to use the keyless flow,
your build host needs an OIDC identity token in the
`COSIGN_IDENTITY_TOKEN` env var when cosign runs. The typical sources:

| OIDC source | How to get the token |
|---|---|
| GitHub Actions workflow | `id-token: write` permission → `${{ secrets.GITHUB_TOKEN }}` (cosign auto-detects) |
| GitLab CI | `id_tokens` keyword in `.gitlab-ci.yml` → cosign auto-detects |
| Google Cloud workload identity | `gcloud auth print-identity-token` |
| AWS workload identity | aws-vault or IAM Roles Anywhere → exchange to OIDC |
| Local dev / interactive | `cosign sign-blob` opens a browser to sign in with GitHub/Google/Microsoft |

For **anvil running unattended on an operator-managed VM** without
an OIDC issuer in scope: this isn't a great fit. Use the offline-key
fallback instead — see [`offline-key-fallback.md`](offline-key-fallback.md).

## Test the install end-to-end

Generate a throwaway keypair and sign a dummy file. This proves
cosign works on the host before anvil ever asks it to do anything.

```sh
mkdir /tmp/cosign-test && cd /tmp/cosign-test

# Make a fake artifact
echo "hello provenance" > my-app.jar

# Generate a local keypair (will prompt for a password; leave empty for test)
COSIGN_PASSWORD="" cosign generate-key-pair

# Sign a dummy in-toto statement
cat > statement.json <<'JSON'
{
  "_type": "https://in-toto.io/Statement/v1",
  "subject": [{"name": "my-app.jar", "digest": {"sha256": "REPLACE"}}],
  "predicateType": "https://slsa.dev/provenance/v1",
  "predicate": {"buildDefinition": {}, "runDetails": {}}
}
JSON

# Replace the placeholder with the real sha
sha=$(sha256sum my-app.jar | cut -d' ' -f1)
sed -i "s/REPLACE/$sha/" statement.json

# Sign (cosign v3 new-bundle-format, default)
COSIGN_PASSWORD="" cosign attest-blob --yes \
  --predicate statement.json --type slsaprovenance1 \
  --bundle my-app.jar.intoto.jsonl \
  --key cosign.key \
  my-app.jar

# Verify
cosign verify-blob-attestation \
  --type slsaprovenance1 --check-claims=true \
  --new-bundle-format \
  --bundle my-app.jar.intoto.jsonl \
  --key cosign.pub \
  my-app.jar
# → Verified OK
```

If that round-trip works, anvil's automatic signing will too once
you flip the flag.

## Enable in anvil

```edn
;; anvil.edn
{:anvil.features/provenance true}
```

Restart the daemon. From the next build onward, every
`archiveArtifacts`-matched file gets a sibling `.intoto.jsonl`
written next to it.

The build page surfaces the count + verified status (T4.4, PR-3).
The verification CLI is documented in [`verify-cli.md`](verify-cli.md).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `cosign not found on PATH` from anvil | cosign isn't installed where anvil runs. Install it (see above) and restart anvil. |
| `failed to validate token` | Your OIDC identity token expired / isn't trusted. Re-acquire it or switch to long-lived key. |
| `signature is not valid` on verify | The artifact was modified after signing, OR you're verifying with the wrong public key / identity. |
| Multi-MB transient extra files in `target/` | cosign writes intermediate files during signing. They get cleaned up automatically; if you see leftovers, file an issue. |

## See also

- [`overview.md`](overview.md) — what SLSA L3 provenance means + the in-toto statement shape
- [`offline-key-fallback.md`](offline-key-fallback.md) — R4 fallback for air-gapped operators
- [`verify-cli.md`](verify-cli.md) — `anvil provenance verify` reference
- [Sigstore docs](https://docs.sigstore.dev/) — upstream
- [cosign manpage](https://github.com/sigstore/cosign/blob/main/doc/cosign.md) — every flag
