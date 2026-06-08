---
title: File Credentials (AN7-3)
audience: operators
category: feature
purpose: Documents :file-type credential support — host filesystem paths mounted read-only into docker steps.
lifecycle: live
last-verified: 2026-06-08
status: shipped (AN7-3)
---

# File Credentials

> **v0.5 AN7-3.** Jenkins's `file` credential type allows a file on the CI
> host (GPG key, TLS cert, kubeconfig) to be mounted into a build step as an
> environment variable pointing to the file path. This page explains how anvil
> implements it.

---

## Registering a file credential

```bash
anvil secrets add jkube-gpg-key \
  --type file \
  --path /run/secrets/jkube-secret-subkeys.asc \
  --description "JKube GPG signing subkey"
```

The stored value is the **host filesystem path** — anvil never reads the file
contents into the database. This is safer than base64-encoding the file into a
`:string` credential (which was the workaround documented in AN7-1c) because:

- The file never touches the DB row, so a database export doesn't leak it.
- The host operator controls the file's permissions independently.
- Rotation means replacing the file; no DB update required.

### Path validation

`anvil secrets add` validates the path is readable at registration time. If
the path is not readable, the command exits 3 with an explanatory error rather
than silently storing an unusable credential.

---

## Using a file credential in a Jenkinsfile

```groovy
pipeline {
  agent { docker { image 'eclipse-temurin:21-jdk' } }
  stages {
    stage('import-key') {
      steps {
        withCredentials([file(credentialsId: 'jkube-gpg-key', variable: 'GPG_KEY_FILE')]) {
          sh 'gpg --batch --import "$GPG_KEY_FILE"'
        }
      }
    }
  }
}
```

Inside the `withCredentials` block:
- The host file `/run/secrets/jkube-secret-subkeys.asc` is **mounted read-only**
  into the docker container at `/anvil-creds/jkube-gpg-key`.
- The env var `GPG_KEY_FILE` is set to `/anvil-creds/jkube-gpg-key` inside the
  container, so `"$GPG_KEY_FILE"` expands to the correct path.

### Mount behavior

| Property       | Value |
|---|---|
| Host path      | The path stored at registration time |
| Container path | `/anvil-creds/<credential-id>` |
| Mount flags    | `:ro` (read-only) |
| Env var name   | From the `variable:` binding in `withCredentials` |

The `:ro` flag prevents build steps from accidentally writing back to the
operator's credential store.

### Non-docker agents

For non-docker agents (label, any), file credentials are not mounted — the
file path is bound to the env var as-is. The step runs on the host where
anvil is running, so the path is directly accessible. This matches Jenkins's
behavior on non-containerized executors.

---

## Effects emitted

The dispatcher emits a `:file-credential/mounted` effect for each resolved
file credential when entering the `withCredentials` block:

```clojure
[:file-credential/mounted
 {:credential-id "jkube-gpg-key"
  :host-path     "/run/secrets/jkube-secret-subkeys.asc"
  :container-path "/anvil-creds/jkube-gpg-key"
  :var-name       "GPG_KEY_FILE"}]
```

If the credential is not in the store, a standard `:credential-unresolved`
effect is emitted instead (same as for `:string` credentials per AN4-4).

---

## Migrating from the AN7-1c workaround

Before AN7-3, the recommended workaround was to base64-encode the file and
store it as a `:string` credential, then `base64 -d` inside the step. That
approach is retired.

| Before (AN7-1c workaround)                      | After (AN7-3)                     |
|---|---|
| `base64 -w0 < keyring.asc > /tmp/keyring.b64`  | No encoding needed                |
| `anvil secrets add ... --type string`            | `anvil secrets add ... --type file --path /path/to/keyring.asc` |
| `echo "$KEYRING_B64" | base64 -d > /tmp/key`   | `gpg --import "$GPG_KEY_FILE"`    |
| `chmod 600 /tmp/key`                             | `:ro` mount handles permissions    |

The AN7-1c runbook (`docs/secrets/an7-1c-jkube-credential.md`) is now
superseded. Operators on the AN7-1c workaround can:
1. Delete the old base64 credential: `anvil secrets delete jkube-gpg-keyring-b64`
2. Register the file credential: `anvil secrets add jkube-gpg-key --type file --path ...`
3. Update the Jenkinsfile to use `withCredentials([file(...)])` directly.

---

## Known limitations (v0.5)

- **Rootless docker**: `-v` mounts require the host path to be accessible to
  the docker daemon user. On rootless setups, the daemon UID may differ from
  the operator's UID; ensure the file is world-readable or group-readable by
  the daemon user.
- **Remote docker**: If the docker daemon is on a different host (e.g.,
  `DOCKER_HOST=tcp://...`), the mount path is on the **daemon host**, not the
  operator host. The credential must be pre-staged on the daemon host before
  the build runs.
- **Non-docker agents**: File mounts are a no-op; the path is bound directly
  to the env var on the host where anvil runs.
- **Rotation at runtime**: If the host file changes while a build is running,
  the `:ro` mount reflects the file as of when `docker run` started. A new
  build picks up the new file; in-flight builds see the old version.
