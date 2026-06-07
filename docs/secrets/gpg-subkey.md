# GPG subkey credentials (v0.4 AN6-5)

eclipse-jkube's Jenkinsfile references a credential named
`secret-subkeys.asc` — a GPG keyring file used for signing release
artifacts. v0.3.3 honestly classified the build as
`:failure :credential-unresolved` because the credential wasn't
provisioned. AN6-5 closes the operator-facing gap: how to provision
this kind of secret on anvil.

## What `secret-subkeys.asc` is

A GPG ASCII-armored keyring containing the project's signing subkeys:

```
-----BEGIN PGP PRIVATE KEY BLOCK-----

…base64 payload…

-----END PGP PRIVATE KEY BLOCK-----
```

The Jenkins job uses it to `gpg --import` the keyring into a
build-scoped keyring directory, then runs `mvn -B clean deploy`
with Maven's `gpg-maven-plugin` configured to sign with one of the
subkeys.

## How to provision it on anvil

**TL;DR**: use the existing `--type string` credential and pass the
content of the .asc file via stdin. The
`anvil.secrets/credential-unresolved` warning disappears, the
withCredentials wrapper binds the value to the env var the
Jenkinsfile names, and the build's `gpg --import "$env_var"` reads
it.

```bash
# 1. Add the keyring as a string credential.
cat secret-subkeys.asc | anvil secrets add secret-subkeys.asc --type string \
  --description "eclipse-jkube release signing subkeys"

# 2. Reference it from the Jenkinsfile the way eclipse-jkube does:
#    withCredentials([file(credentialsId: 'secret-subkeys.asc',
#                          variable: 'SUBKEYS_FILE')]) { … }
#
#    At v0.4.0 anvil treats `string` and `file` interchangeably for
#    the env-injection path: the variable is bound to the literal
#    secret value as a string.  Jenkinsfiles that expect
#    `${SUBKEYS_FILE}` to be a file *path* (not the contents) need
#    the workaround below.
```

## When the env-var-must-be-a-path Jenkinsfile shape applies

If the Jenkinsfile does:

```groovy
withCredentials([file(credentialsId: 'subkeys', variable: 'KP')]) {
    sh 'gpg --import "$KP"'   // expects $KP to be a path
}
```

then anvil v0.4.0's string-binding won't satisfy it — `$KP` is the
contents, not a path. Workaround at v0.4.0:

```groovy
withCredentials([string(credentialsId: 'subkeys', variable: 'KP_BODY')]) {
    sh '''
        # Materialize the body to a temp file the build scope owns.
        KP="$(mktemp -p "$WORKSPACE" subkeys.XXXXXX.asc)"
        printf '%s' "$KP_BODY" > "$KP"
        trap 'rm -f "$KP"' EXIT
        gpg --import "$KP"
    '''
}
```

The `trap … EXIT` line makes sure the materialized file goes away when
the wrapped block exits, matching the Jenkins-original lifecycle.

## v0.4.x — proper `:file` type

A v0.4.x ticket will add a real `:file` credential type that:

1. Stores the value AES-encrypted (existing T6 path)
2. On withCredentials bind, writes the decrypted body to a workspace
   temp file with `chmod 600`
3. Binds the env-var to the file's *path*
4. Cleans up automatically on wrapper exit

That removes the workaround above. Until then, the string + trap
pattern lands the build's intent without the operator silently losing
signal.

## Honest classification at v0.4.0

A Jenkinsfile that references a credential ID anvil doesn't have
classifies the build as `:failure :credential-unresolved` per AN4-4.
That's the right answer — synthesizing an empty value would let the
`gpg --import` silently succeed against an empty keyring, the `mvn`
deploy would silently sign nothing, and the operator would only
discover the gap when the release artifact failed signature
verification downstream.

The `:credential-unresolved` effect surfaces in the build console
and the per-build classification — the workaround docs in this file
are the operator's recovery path.

## References

- AN4-4 receipt:
  `docs/jenkins-compat/an4-4-credential-unresolved.md` — the
  withCredentials honesty contract that drives this UX
- v0.3 T6 secrets ship: `docs/secrets/operator-guide.md`
- Wild-corpus receipt:
  `docs/jenkins-compat/wild-corpus-honest-receipt.md` — 2026-06-06
  entry, `eclipse-jkube` row
