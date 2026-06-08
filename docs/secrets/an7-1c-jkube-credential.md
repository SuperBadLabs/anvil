---
title: AN7-1c — Provisioning the jkube GPG-subkey credential (operator runbook)
audience: operators
category: secrets-runbook
purpose: Step-by-step for unblocking `wild-eclipse-jkube` from `:failure :credential-unresolved` ahead of AN7-3 (`:file` credential UX). Pure operator action; no anvil code change.
lifecycle: live
last-verified: 2026-06-08
status: shipped
---

# AN7-1c — Provisioning the jkube GPG-subkey credential

> **Context.** Eclipse JKube's CI signs release artifacts with a GPG
> subkey loaded from `secret-subkeys.asc`. Without that file present
> as a credential, anvil's classifier honestly reports
> `:failure :credential-unresolved` for the build. AN6-5 documented
> the workaround using anvil's existing `--type string` credential +
> a mktemp/trap shell pattern. This runbook is the up-to-date
> step-by-step for v0.5.
>
> When **AN7-3** ships proper `:file`-type credentials, this runbook
> retires in favor of `anvil secrets add --type file --name jkube-gpg
> --path /path/to/secret-subkeys.asc`.

## Prerequisites

- A GPG keyring exported to `secret-subkeys.asc` (operator-controlled).
  See [`docs/secrets/gpg-subkey.md`](gpg-subkey.md) for the
  `gpg --export-secret-subkeys` recipe.
- anvil daemon running at `:8765` (or wherever your dogfood lives)
- `anvil-cli` available (or `lein run` from `/tmp/anvil` if you build
  from source)

## Provisioning steps

1. **Encode the keyring** as a base64 string for the `--type string`
   credential. (AN7-3 will let you skip this; for now we round-trip
   through base64 to keep the credential value plain-text-printable.)

   ```bash
   base64 -w0 < secret-subkeys.asc > /tmp/secret-subkeys.b64
   ```

2. **Add the credential** to your anvil daemon:

   ```bash
   anvil-cli secrets add \
     --name jkube-gpg-keyring-b64 \
     --type string \
     --value-file /tmp/secret-subkeys.b64
   ```

   Or via the admin REST endpoint:

   ```bash
   curl -X POST http://heman:8765/anvil/admin/secrets \
     -H 'Content-Type: application/json' \
     -d "{\"name\":\"jkube-gpg-keyring-b64\",\"type\":\"string\",\"value\":\"$(cat /tmp/secret-subkeys.b64)\"}"
   ```

3. **Update the jkube job's Jenkinsfile** to materialize the file at
   runtime. The repo's real Jenkinsfile expects the file at
   `~/.gnupg/secret-subkeys.asc`; we restore it via the mktemp/trap
   shell pattern:

   ```groovy
   pipeline {
     agent { label 'ubuntu' }
     environment {
       JKUBE_GPG_B64 = credentials('jkube-gpg-keyring-b64')
     }
     stages {
       stage('install-creds') {
         steps {
           sh '''
             set -e
             trap 'rm -f $HOME/.gnupg/secret-subkeys.asc' EXIT
             mkdir -p $HOME/.gnupg && chmod 700 $HOME/.gnupg
             echo "$JKUBE_GPG_B64" | base64 -d > $HOME/.gnupg/secret-subkeys.asc
             chmod 600 $HOME/.gnupg/secret-subkeys.asc
           '''
         }
       }
       stage('build') {
         steps {
           sh 'mvn -B -DskipTests install'
         }
       }
     }
   }
   ```

   This shim can live alongside the AN7-1 shims at
   `resources/anvil/config/wild-corpus-shims/eclipse-jkube.Jenkinsfile`
   once the credential is provisioned. Without the credential present,
   `credentials('jkube-gpg-keyring-b64')` resolves to nil and anvil
   honestly classifies as `:failure :credential-unresolved` (same as today).

4. **Trigger the wild-corpus rerun** with the jkube shim staged. The
   verdict should flip from `:failure :credential-unresolved` to
   `:success` (or honest `:failure :step-nonzero-exit` if some other
   downstream step fails — that's an honesty win either way).

## Retirement plan

When **AN7-3** ships `:file`-type credentials:

```bash
# Before AN7-3:
anvil-cli secrets add --type string --value-file /tmp/secret-subkeys.b64 ...
# After AN7-3:
anvil-cli secrets add --type file --path /path/to/secret-subkeys.asc \
  --name jkube-gpg-keyring
```

The shim's `base64 -d` boilerplate goes away; the file gets `-v`-mounted
into the docker step directly per AN7-3's design. This runbook gets a
deprecation banner at the top pointing at the new path.

## Verifying the credential is loaded

```bash
$ curl -sS http://heman:8765/anvil/admin/secrets | jq '.[] | .name'
"jkube-gpg-keyring-b64"
```

Anvil never echoes the value back — only the name. If you need to
verify the value matches what you provisioned, decode + diff
client-side from your local copy.

## Security note

The base64-encoded keyring in `:type :string` credentials is stored in
anvil's SQLite database alongside other secrets. It's not encrypted at
rest — anvil's threat model assumes the daemon's filesystem is trusted
(operator-controlled, not exposed to multi-tenant workloads). When
chengis 0.1 ships multi-tenant + audit logging, the credential row
gets per-tenant scope and an access log; until then, treat the
keyring's reach as equivalent to anvil's filesystem reach.
