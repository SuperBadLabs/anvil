# Secrets — operator guide (v0.3.0)

## Master-key setup

Anvil stores credentials encrypted at rest with AES-256-GCM. The master key resolves in order:

1. `ANVIL_SECRET_KEY` env var (base64 of 32 random bytes)
2. `~/.config/anvil/master.key` (auto-generated 0600 on first use)

**For production**, set `ANVIL_SECRET_KEY` via systemd:

```bash
sudo systemctl edit anvil.service
# add:
#   [Service]
#   Environment="ANVIL_SECRET_KEY=$(openssl rand -base64 32)"
```

**For dev**, anvil generates the key file automatically on first secret add.

## Adding a secret

```bash
echo -n 'my-pat-token' | anvil secrets add docker-hub --type string --description "Docker Hub PAT"
```

Value comes from stdin so it never appears in `ps` output.

## Listing, showing, deleting

```bash
anvil secrets list
anvil secrets show docker-hub      # masked preview only
anvil secrets delete docker-hub
```

## Rotation

```bash
NEW_KEY=$(openssl rand -base64 32)
anvil secrets rotate-master --new-key "$NEW_KEY"
sudo systemctl edit anvil.service
# update Environment="ANVIL_SECRET_KEY=$NEW_KEY"
sudo systemctl restart anvil
```

Decrypts every credential with the current key, swaps in the new key (persisted to `~/.config/anvil/master.key`), re-encrypts each one. Atomic per-credential (a failure mid-rotation leaves the ones already re-encrypted under the new key).

## Using a secret in a Jenkinsfile

```groovy
withCredentials([usernamePassword(credentialsId: 'docker-hub',
                                   usernameVariable: 'USR',
                                   passwordVariable: 'PSW')]) {
  sh 'echo "$PSW" | docker login -u "$USR" --password-stdin'
}
```

Anvil's dispatcher binds the credential's value to the named env vars only for the duration of the `withCredentials` body, and adds the value to the per-build redaction set so any echo of it gets `****` masked in the console (TX11D / T6.5).

## Web admin

`/secrets` lists stored credentials with masked previews only. The page is gated on:

1. The `:anvil.features/secrets` flag (closed-by-default)
2. The request IP being in `:anvil.secrets/admin-ips` in `anvil.edn` (defaults to loopback)

For multi-admin shops, configure trusted IPs explicitly or front anvil with a reverse proxy that adds an auth header (see `docs/reverse-proxy-auth.md`).
