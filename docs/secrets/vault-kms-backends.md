---
title: SecretBackend protocol — Vault + Cloud-KMS adapters (T2 stub)
audience: operators
category: secrets
purpose: Operator runbook for the v0.6 T2 SecretBackend protocol + Vault + Cloud-KMS reference impls. Populated as T2 lands; this is a scaffolding stub per T0.3.
lifecycle: stub
last-verified: 2026-06-08
status: stub
---

# SecretBackend protocol — Vault + Cloud-KMS adapters

> **Stub.** Placeholder for the T2 receipt. The full runbook lands
> when [T2.5](../roadmap/v0.6-board.md#t2--vault--cloud-kms-secret-backends--1-wk)
> ships.

Planned sections:

- [ ] `anvil.secrets/SecretBackend` protocol — shape, semantics
- [ ] Vault adapter: KV v2 mode; `:anvil.vault/url` + token bootstrap
- [ ] Cloud-KMS adapter: AWS-first; encrypted blob in `anvil.edn`
- [ ] Wiring into `h-with-credentials` + `h-environment`
- [ ] Audit event: `evt-secret-resolved` payload (NEVER carries
      secret value)
- [ ] Migration from AN7-3 local-disk file credentials

Locked decision context: [AV6-3](../roadmap/v0.6-board.md#locked-decisions-av6-series)
— Vault + KMS as operator-pluggable adapters.
