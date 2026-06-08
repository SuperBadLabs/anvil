---
title: AN7-1c — jkube GPG credential provisioning receipt (2026-06-08)
audience: operators, developers
category: jenkins-compat-receipt
purpose: Honest receipt of the v0.5.0 dogfood provisioning + trigger of the eclipse-jkube `:file`-type GPG credential via AN7-3's CLI path. Documents what works (provisioning, credential resolution) and the v0.5.x follow-ups uncovered (heredoc shim parse, withCredentials wiring to docker mount).
lifecycle: live
last-verified: 2026-06-08
status: shipped
---

# AN7-1c — jkube GPG credential provisioning receipt

> **What this receipt is.** Step-by-step transcript of provisioning the
> JKube GPG subkey credential on the v0.5.0 production dogfood (heman:8765),
> per the AN7-1c operator runbook ([docs/secrets/an7-1c-jkube-credential.md](../secrets/an7-1c-jkube-credential.md))
> using AN7-3's `:file`-type credential CLI ([PR #84](https://github.com/SuperBadLabs/anvil/pull/84)).
> The provisioning succeeds and the `:credential-unresolved` verdict gate
> is cleared; **two downstream bugs** surface and are flagged for v0.5.x.

## What worked ✓

### 1. Test GPG keyring generation

```bash
GNUPGHOME=$(mktemp -d -p /tmp anvil-gpg-XXXX)
gpg --batch --gen-key <(cat <<EOF
%no-protection
Key-Type: RSA
Key-Length: 2048
Subkey-Type: RSA
Subkey-Length: 2048
Name-Real: Anvil JKube Test
Name-Email: anvil-jkube-test@superbadlabs.local
Expire-Date: 0
%commit
EOF
)
gpg --export-secret-subkeys --armor anvil-jkube-test@superbadlabs.local \
  > /home/srikanth/anvil-dogfood/credentials/jkube-secret-subkeys.asc
chmod 600 /home/srikanth/anvil-dogfood/credentials/jkube-secret-subkeys.asc
```

Result: 3,092-byte ASCII-armored secret-subkey file owned by `srikanth:srikanth` (mode 0600).

### 2. Credential provisioned via AN7-3 CLI

```bash
cd /tmp/anvil
lein run -- secrets add jkube-gpg-key \
  --type file \
  --path /home/srikanth/anvil-dogfood/credentials/jkube-secret-subkeys.asc \
  --description "JKube GPG signing subkey (AN7-3)"
```

Output:
```
Added file credential jkube-gpg-key
  host path: /home/srikanth/anvil-dogfood/credentials/jkube-secret-subkeys.asc
  mounted at: /anvil-creds/jkube-gpg-key (read-only) inside docker steps
  env var:  set by `variable:` binding in withCredentials
```

`lein run -- secrets list` confirms:

```
ID                             TYPE                   MASKED       DESCRIPTION
--------------------------------------------------------------------------------
jkube-gpg-key                  file                   ****.asc     JKube GPG signing subkey (AN7-3)
```

### 3. `:credential-unresolved` gate cleared

Before provisioning (jkube builds #1–#4): every triggered build
classified `:failure :credential-unresolved` — anvil couldn't find a
credential matching the shim's `withCredentials([file(credentialsId: 'jkube-gpg-key', ...)])`.

After provisioning (jkube #5 onward): the credential resolves cleanly.
The daemon log shows the classifier moving past credential resolution
into actual shell execution. **The `:credential-unresolved` honest gap
from AN7-3 is closed for this credential.**

### 4. Real GPG import + mount path works (manual verification)

Reproducing the docker exec with the same `-v` mount and image:

```bash
docker run --rm --user $(id -u):$(id -g) \
  -v /home/srikanth/anvil-dogfood/credentials/jkube-secret-subkeys.asc:/anvil-creds/jkube-gpg-key:ro \
  eclipse-temurin:21-jdk \
  bash -c 'gpg --batch --import /anvil-creds/jkube-gpg-key'
```

Output:
```
gpg: key 826BCA3F2EEC391E: secret key imported
gpg: Total number processed: 1
gpg:   secret keys imported: 1
```

The image has `gpg` available, the credential file mounts correctly,
the import works end-to-end. The pieces work in isolation.

## v0.5.x follow-ups (uncovered by this receipt)

### Bug 1: `sh '''...'''` heredoc parse strips multiline body

**Symptom:** jkube build #5 (post-provisioning) classified
`:failure :step-nonzero-exit, last exit: 2` in 1.6s. The daemon log
shows the docker invocation with an **empty command**:

```
docker run --rm on eclipse-temurin:21-jdk :    (← empty after the colon)
```

The shim's `sh '''<multiline>'''` body was stripped to empty before
reaching the docker backend. **Same bug PR #75 hit on the cassandra
shim and worked around by switching to single-line `sh '...'`.**

**Fix shipped in this PR**: jkube shim moves from `sh '''...'''` to
`sh 'gpg ... && gpg ... && mvn ...'` (chained with `&&`). After this
fix, build #6 logs the full docker command correctly.

### Bug 2: `withCredentials([file(...)])` doesn't propagate to docker

**Symptom:** Build #6 (post-heredoc-fix) still classifies
`:failure :step-nonzero-exit, last exit: 2` in 180 ms. The docker run
command logged is correct:

```
docker run --rm on eclipse-temurin:21-jdk : \
  gpg --batch --import "$GPG_KEY_FILE" && gpg --list-secret-keys ... && mvn ...
```

But inside the container:
- `$GPG_KEY_FILE` is empty (env var unbound)
- `/anvil-creds/jkube-gpg-key` doesn't exist (file mount missing)

The credential **resolved** (the classifier verdict is no longer
`:credential-unresolved`), but the dispatcher didn't add the `-v` mount
or `-e` env-var flags to the docker invocation.

**Root cause analysis:**

The dispatcher's `h-with-credentials` function ([src/anvil/compat/jenkins/dispatcher.clj L1072–L1196](../../src/anvil/compat/jenkins/dispatcher.clj))
*does* compute `file-mounts` and `file-env-additions`, and the
backend-wiring path *does* read `(:file-mounts ctx)` to build
`-v <host>:<container>:ro` args ([src/anvil/compat/jenkins/backend_wiring.clj L88](../../src/anvil/compat/jenkins/backend_wiring.clj)).
The unit test [`file_credentials_test.clj`](../../test/anvil/compat/jenkins/file_credentials_test.clj)
exercises `build-docker-args` directly with well-formed `:file-mounts`
input and passes — 7 tests, 20 assertions all green.

The gap is between the **translator** parse of
`withCredentials([file(credentialsId: 'X', variable: 'Y')])` and the
**dispatcher** entry to `h-with-credentials`. The translator emits
`{:credentials [{:kind "file" :raw-args ...}]}` (see
[translator.clj L431–L457](../../src/anvil/compat/jenkins/translator.clj)),
but somewhere downstream `:file-mounts` doesn't end up on the dispatch
ctx that backend-wiring reads.

**Workaround until fixed:** none — the file genuinely isn't reachable
from inside the docker container. Type-B shim can't produce
`:success` for jkube via the env-var form.

**Filed as v0.5.x follow-up** with high priority — affects every
real-world `:file` credential workflow, not just jkube.

## Where the verdict landed

| Build | Verdict | Notes |
|---|---|---|
| #1–#4 | `:failure :credential-unresolved` for `jkube-gpg-key` | pre-provisioning |
| #5 | `:failure :step-nonzero-exit, last exit: 2` in 1.6s | post-provisioning, heredoc bug |
| #6 | `:failure :step-nonzero-exit, last exit: 2` in 1.6s | post-heredoc-fix, withCredentials wiring bug |
| #7 (with hardcoded mount path) | `:failure :step-nonzero-exit, last exit: 2` in 180ms | confirms file isn't mounted at all |

**Net for v0.5.0**: jkube stays type-B `:failure :step-nonzero-exit` —
honest progress (credential gate cleared, real shell ran) but no
`:success` for the wild-corpus tally. The provisioning runbook is
honestly executed; the follow-up bugs are anvil-side, not operator-side.

## What this proves

1. **AN7-3 CLI provisioning works.** `anvil secrets add --type file
   --path …` correctly adds a `:file`-type credential, the daemon
   reads it from the store, and the classifier sees it as resolved.
2. **AN7-3 docker-mount + env-binding wiring has a translator-to-
   dispatcher gap** that the unit tests don't cover. Real-world
   Groovy `withCredentials([file(...)])` doesn't reach the working
   `:file-mounts` code path.
3. **The cassandra heredoc workaround is general** — every shim using
   `sh '''...'''` is broken; switch to single-line `&&`-chained form
   until that translator bug is fixed too.
4. **Production dogfood survives the dogfood-driven discovery** —
   triggering buggy builds against `:8765` didn't take the daemon
   down; `anvil-self-test` stays blue throughout.

## Operator action required for production jkube CI

Until the AN7-3 wiring bug is fixed in v0.5.x:

1. Operators **CAN** provision the credential via the CLI per this
   receipt (it lands in the store).
2. Operators **CANNOT** rely on `withCredentials([file(...)])` to
   mount it inside docker steps. The build will fast-fail with an
   honest `:step-nonzero-exit` once the shell can't find the file.

Recommended path for v0.5.0:

- File the operator-side gap upstream against the project's real
  Jenkinsfile (no anvil fix can deliver type-A jkube until v0.6's
  k8s + full credential wiring lands)
- Accept the type-B shim verdict as honest failure
- Watch for the v0.5.x file-credentials wiring fix in CHANGELOG
