# Secrets management (v0.3 — T6)

Status: **stub** (T0.5 placeholder). Real docs land at T6.8 / T8.5.

Three docs are reserved under this directory (named in the v0.3 board T6.8):

- `operator-guide.md` — master-key setup, env-var vs file backend,
  rotation procedure
- `threat-model.md` — what we protect against vs not (esp. NOT
  protecting against a compromised anvil process)

## What this feature ships

`withCredentials([usernamePassword(credentialsId: 'docker-hub',
usernameVariable: 'USER', passwordVariable: 'PSW')])` works on anvil.
Secrets are encrypted at rest, env-injected at build time, masked in
logs (replaced with `***` *before* publishing to the SSE bus, so even
live console viewers see the redaction).

## Feature flag

```clojure
;; anvil.edn
{:anvil.features/secrets true}
```

## Bounding decisions

- **AV3-6**: Secrets file-encrypted at rest (AES-256-GCM via
  `javax.crypto`); master key from env-var or local file. No Vault /
  Cloud-KMS integration in v0.3.0 — those land as plug-ins in v0.3.x.
- **R6**: Ship `anvil secrets rotate-master --new-key …` (one-shot
  decrypt-old + re-encrypt-new). Automated rotation defers to v0.3.x.

## Files (when T6 lands)

- `src/anvil/secrets/crypto.clj` — AES-256-GCM round-trip
- `src/anvil/secrets/store.clj` — encrypted-at-rest persistence
- `src/anvil/cli/secrets.clj` — `anvil secrets {add,list,rotate,delete}`
- `src/anvil/compat/jenkins/with_credentials.clj` — step impl
- Log masking: per-build "redaction set" applied *before* bus publish

## SSE event

`:secret-rotated` on `:global` topic (every UI tab refreshes the
secrets list when master-key rotation completes). Reserved in T0.4
(`anvil.events.topics/evt-secret-rotated`).

## Owner

T6 tranche owner — the heaviest tranche (~1.5 weeks).
