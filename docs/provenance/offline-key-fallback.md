# Offline / air-gapped fallback — long-lived signing key

The v0.4 board's risk **R4** named this concern explicitly:

> SLSA provenance sigstore flow needs network access to Fulcio + Rekor;
> offline / air-gapped operators block.

The mitigation: anvil's cosign wrapper accepts a long-lived signing
key path (`--key`) for both signing and verification. **The signed
attestation is still in-toto v1 + slsa-provenance/v1 — only the trust
root changes.** Operators trading off the Fulcio transparency
guarantee for the ability to run without internet.

## Trust trade-off — read this first

| Property | Fulcio keyless | Long-lived key |
|---|---|---|
| Requires network at sign-time | Yes (Fulcio + Rekor) | No |
| Requires network at verify-time | Yes (Rekor public log) | No |
| Signing key lifetime | ~10 minutes (cert ephemeral) | As long as operator wants |
| Compromise blast radius | Bounded — attacker can only mis-sign during cert validity | Operator's responsibility — rotate regularly |
| Identity proof | OIDC token (e.g. github.com/owner) | Bare key — verifier trusts whoever holds the public key |
| Public auditability | Yes — every signature in Rekor log | No — verifiers need the public key out-of-band |

If you have ANY of: internet egress, an OIDC issuer (GitHub Actions,
GitLab CI, GCP/AWS workload identity, an internal IdP) — **prefer
Fulcio keyless** (see [`sigstore-setup.md`](sigstore-setup.md)). Only
reach for long-lived keys when keyless is genuinely impossible.

## Generate a keypair

On a trusted workstation:

```sh
# Pick a strong password — needed to UNSIGN later. The pubkey is plain.
cosign generate-key-pair
# → cosign.key  (private; protect this)
# → cosign.pub  (public; distribute to verifiers)
```

The output is `cosign.key` (an encrypted private key) and `cosign.pub`
(the corresponding public key). Move `cosign.key` to a secrets store
your anvil host can read; ship `cosign.pub` to anyone who needs to
verify.

### Key custody recommendations

- **Don't commit `cosign.key` to git.** Even encrypted, it's a key.
- **Don't bake it into a container image.** Same reason.
- **Mount it via secrets manager** (Vault, AWS Secrets Manager,
  sealed-secrets) so rotation doesn't require an image rebuild.
- **Rotate at least annually.** Re-issue with a new keypair, update
  the public key wherever verifiers live, deprecate the old.
- **Use a passworded key.** Cosign supports unpasworded for CI; if
  you choose that, document it explicitly.

## Configure anvil to use the long-lived key

Point cosign at the key via an env var your daemon process can read:

```sh
# Set in your systemd unit / launchd plist / Docker env
COSIGN_KEY_PATH=/etc/anvil/cosign.key
COSIGN_PASSWORD=<from-secrets-manager>
```

Then in `anvil.edn`:

```edn
{:anvil.features/provenance true
 :anvil.provenance/key-path "/etc/anvil/cosign.key"
 :anvil.provenance/key-password-env "COSIGN_PASSWORD"}
```

Anvil's dispatcher hook (T4.3, PR-2) passes `--key` to cosign on
every sign operation when this is set. No network calls to Fulcio
or Rekor.

> **Unpassworded key shortcut.** For an unpassworded key, set
> `COSIGN_PASSWORD=""` in the env — both `generate-key-pair` and
> signing accept the empty string as "no password".

## Distribute the public key

Verifiers need `cosign.pub` to check signatures. Options:

| Distribution | Use when |
|---|---|
| Bake into your CI image | Verifying agent images sign your artifacts |
| Publish on a known-good HTTPS URL | Customers verifying your releases |
| Include in your release notes | One-off downloads |
| Pin to a TUF root | Enterprise / very-high-trust scenarios |

## Verify with the long-lived key

```sh
anvil provenance verify build/my-app-1.2.3.jar \
  --key /path/to/cosign.pub
```

Exit 0 = verified. Exit 4 = signature doesn't match (tampered or
wrong key). Exit 2/3 = setup error (artifact not found, cosign not
installed).

Or directly via cosign:

```sh
cosign verify-blob-attestation \
  --type slsaprovenance1 --check-claims=true \
  --new-bundle-format \
  --bundle build/my-app-1.2.3.jar.intoto.jsonl \
  --key /path/to/cosign.pub \
  build/my-app-1.2.3.jar
```

The output looks the same as the Fulcio keyless flow — the
attestation envelope is identical in shape, only the trust root
differs.

## Key rotation

When you rotate keys:

1. Generate a new keypair (`cosign generate-key-pair`)
2. Distribute the new public key alongside the old
3. Flip anvil to sign with the new key
4. Wait for verifiers to update (give them a grace window)
5. Deprecate the old public key

Anvil doesn't have a built-in "sign with both keys during transition"
flow in 0.4.1. If you need that, sign separately with each key
during the grace window via two anvil instances or a wrapper script.

## What you give up vs Fulcio keyless

- **No public transparency log.** With keyless, every signature lands
  in Rekor and is publicly auditable for the life of the project.
  With long-lived keys, you and your verifiers are the only ones who
  see signatures.
- **No identity binding.** Keyless ties the signature to whoever
  proved the OIDC identity at sign time. Long-lived keys only say
  "someone with this key signed this" — exactly who is operator
  knowledge, not cryptographic fact.
- **More operator burden.** Key rotation, custody, distribution are
  all on you. Fulcio handles cert lifecycle for free.

## See also

- [`overview.md`](overview.md) — SLSA L3 and the in-toto statement shape
- [`sigstore-setup.md`](sigstore-setup.md) — the recommended Fulcio path
- [`verify-cli.md`](verify-cli.md) — `anvil provenance verify` reference
- v0.4 board R4 — the risk this doc closes
