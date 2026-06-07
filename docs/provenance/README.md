# SLSA L3 provenance (v0.4 — T4)

Status: **stub** (T0.5 placeholder). Real docs land at T4.7 + T7.5.

## What this feature ships

Each artifact produced by a build gets a sigstore-signed in-toto v1
provenance attestation written beside it as
`<artifact>.intoto.jsonl`. The build page surfaces a "Provenance: ✅
attested (N artifacts)" pill linking to the per-artifact attestation
list with download links.

```
target/my-app-1.2.3.jar
target/my-app-1.2.3.jar.intoto.jsonl   ← sigstore-signed
```

The attestation names: build's git SHA, builder identity
(`anvil@<instance-id>`), source materials, invocation parameters from
job config, and a content-addressed `subject` (`sha256:<digest>`).

`anvil provenance verify <artifact>` reads the sibling `.intoto.jsonl`
and verifies offline against Fulcio's CT log or the configured
long-lived key.

## AV4-5: sigstore + Fulcio keyless by default

- **Online path (default).** Fulcio's OIDC-keyless flow — uses the
  configured identity provider's OIDC token to mint an ephemeral
  signing cert. Operators don't manage long-lived signing keys.
- **Offline path (R4 fallback).** `:anvil.provenance/offline-key <path>`
  points at a long-lived ed25519 key. Trust trade-off documented at
  `docs/provenance/offline-key-fallback.md` (T4.7).

## R9: footprint budget

If the sigstore cosign Java client adds > 5 MB to the anvil uberjar,
fall back to shelling out to the host `cosign` binary. The binary
dependency is documented as a soft requirement for the offline path.

## Files (planned, not yet written)

- `src/anvil/provenance/statement.clj` — in-toto v1 builder (T4.1)
- `src/anvil/provenance/sign.clj` — sigstore wrapper, online + offline (T4.2)
- `src/anvil/cli/provenance.clj` — `verify` sub-command (T4.6)
- `docs/provenance/sigstore-setup.md` — OIDC config + Fulcio trust (T4.7)
- `docs/provenance/offline-key-fallback.md` — long-lived key trust model (T4.7)
- `test/anvil/provenance/round_trip_test.clj` — sign → verify (T4.7)

## Not in scope (v0.6 Hermetic)

- SLSA L4 hermetic build environment
- Witness / Rekor transparency log custom mirrors
- Cross-build attestation chains (one build attests one artifact set)
