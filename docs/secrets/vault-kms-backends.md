---
title: SecretBackend protocol — Vault + Cloud-KMS adapters (T2 receipt)
audience: operators
category: secrets
purpose: Operator runbook for the v0.6 T2 SecretBackend protocol + Vault + Cloud-KMS reference impls. Locked decision context — AV6-3.
lifecycle: live
last-verified: 2026-06-08
status: shipped
---

# SecretBackend protocol — Vault + Cloud-KMS adapters

> v0.6 T2 ships in PR #100. Two new reference adapters + the
> `anvil.secrets/SecretBackend` protocol that generalises the v0.3
> local-disk credential store. AV6-3 locked: anvil ships the protocol
> + two impls; operators can write their own. The default in-anvil
> store stays the fallback.

## The protocol

```clojure
(defprotocol SecretBackend
  (resolve! [this id]
    "Returns {:value <decrypted-str> :type <kw>} or nil.")
  (list-ids [this]
    "Returns a seq of credentialId strings — NEVER values."))
```

Two methods. Tagged with `:anvil.secrets/kind` metadata so audit
events identify the backend that served a lookup. Implementations
MUST be thread-safe.

## Backend registry

`anvil.secrets/active-backend` returns the currently registered
backend. `anvil.secrets/register-backend!` replaces it. At startup
`anvil.secrets/install-default-backend!` registers the local-disk
store (`LocalDiskBackend`, kind `:local`). Vault / KMS constructors
install themselves over top when their feature flag is on.

Operators who write their own backend register it from a custom
startup hook. The protocol surface is small on purpose.

## Vault adapter — `anvil.secrets.vault`

Mode: KV v2 (most common). Operators on KV v1 can override.

### Operator configuration (`anvil.edn`)

```clojure
{:anvil.features/vault-backend  true
 :anvil.vault/url               "https://vault.example.com"
 :anvil.vault/token-path        "/var/run/secrets/vault-token"
 :anvil.vault/kv-mount          "secret"      ; default
 :anvil.vault/kv-path-prefix    "anvil/"      ; default ""
 :anvil.vault/kv-version        2             ; default 2
 :anvil.vault/timeout-s         5}            ; default 5
```

### Token bootstrap (AV6-3 — minimal, operator-shaped)

The Vault token is read from `:anvil.vault/token-path` at adapter
construction AND on every `resolve!` call (re-read so a sidecar that
rotates the file is honoured without a restart). anvil does NOT
implement Vault auth — that's the operator's territory. Two common
sidecar patterns:

**AppRole (most production deployments)**

```bash
# vault-sidecar.sh — runs as init container / systemd one-shot
ROLE_ID=$(cat /etc/anvil/vault-role-id)
SECRET_ID=$(cat /etc/anvil/vault-secret-id)
curl -fsSL -X POST "$VAULT_ADDR/v1/auth/approle/login" \
  -d "{\"role_id\":\"$ROLE_ID\",\"secret_id\":\"$SECRET_ID\"}" \
  | jq -r .auth.client_token > /var/run/secrets/vault-token
```

**Kubernetes service-account**

```bash
JWT=$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
curl -fsSL -X POST "$VAULT_ADDR/v1/auth/kubernetes/login" \
  -d "{\"role\":\"anvil\",\"jwt\":\"$JWT\"}" \
  | jq -r .auth.client_token > /var/run/secrets/vault-token
```

**Dev mode (do NOT use in production)**

```bash
echo "$VAULT_DEV_ROOT_TOKEN" > /var/run/secrets/vault-token
```

The token file should be `0600` and owned by the anvil process user.

### Storing secrets in Vault

```bash
# String secret (the default credential type)
vault kv put secret/anvil/gh-token value=ghp_1234567890

# usernamePassword credential — same path, type field disambiguates
vault kv put secret/anvil/docker-creds \
  value="myuser:mypassword" \
  type="username-password"
```

The adapter reads the `value` field as the secret string and the
optional `type` field as the credential type (`string` /
`username-password` / `file`). When `type` is missing it defaults
to `:string`.

### Errors

- `404` → `resolve!` returns nil; the dispatcher emits
  `:credential-unresolved` as it does for any missing id.
- non-2xx other → `resolve!` returns nil + logs at WARN. A Vault
  outage shouldn't crash the dispatcher; the credential just appears
  unresolved that build.
- transport / parse error → same as non-2xx.

## Cloud-KMS adapter — `anvil.secrets.kms`

Unlike Vault, the secret VALUE doesn't live in KMS — KMS only holds
the encryption key. The base64-encoded **encrypted blob** lives in
`anvil.edn` (which can live in Git). KMS decrypts it at resolve time.

### Operator configuration

```clojure
{:anvil.features/cloud-kms-backend true
 :anvil.kms/provider :aws            ; :aws | :gcp | :azure
 :anvil.kms/region   "us-east-1"
 :anvil.kms/blobs    {"gh-token"
                       {:ciphertext-b64 "AQICAH..."
                        :type :string}
                      "docker-creds"
                       {:ciphertext-b64 "AQICAH..."
                        :type :username-password}}}
```

### Encrypting the blob

```bash
# AWS: produce the ciphertext-b64 with the AWS CLI
aws kms encrypt \
  --key-id arn:aws:kms:us-east-1:123:key/abc-... \
  --plaintext "ghp_1234567890" \
  --query CiphertextBlob \
  --output text
# → paste that base64 string into :anvil.kms/blobs in anvil.edn
```

The `CiphertextBlob` is already base64'd; paste it verbatim.

### AWS SDK weight — operator opt-in

AV6-3 anti-goal: an operator with only Vault should not pay the
KMS-jar weight. anvil's `project.clj` does NOT depend on the AWS SDK.
Operators who flip `:cloud-kms-backend true` add it themselves:

```clojure
;; In your fork's project.clj
:dependencies [[com.cognitect.aws/api "0.8.711"]
               [com.cognitect.aws/kms "857.2.1908.0"]
               ;; ... rest of anvil deps
               ]
```

If the SDK isn't on the classpath at `install!` time, the adapter
fails loudly with a clear "add these two deps" message — the
previously-installed backend stays active so the dispatcher keeps
working.

### Provider matrix

- **AWS** — first-class. Decrypt via `cognitect.aws-api`'s `:Decrypt`
  op against KMS.
- **GCP** — stub. `make-backend` logs a WARN; `resolve!` returns nil
  for every id. Operators who need GCP either write their own
  backend or wait for v0.6.x.
- **Azure** — stub. Same posture as GCP.

## Wiring into the dispatcher

`h-with-credentials` (anvil.compat.jenkins.dispatcher) routes its
lookup through `anvil.secrets/active-backend`'s `resolve!`. The v0.3
code that called `anvil.storage.credentials/lookup` directly is now a
fallback path for if the secrets ns somehow fails to load.

On every successful resolution, the dispatcher publishes a
`:secret-resolved` event on the per-build topic. The event payload:

```clojure
{:type           :secret-resolved
 :job-name       "my-job"
 :build-number   42
 :credential-id  "gh-token"
 :backend        :vault              ; or :kms, :local
 :latency-ms     14}
```

### Secret-leak invariant

The `:secret-resolved` event **NEVER** contains the secret value.
Three defences:

1. The publish call composes the payload from a fixed set of keys
   (`:credential-id` / `:backend` / `:latency-ms`) — `:value` is not
   in the constructor at all.
2. `anvil.secrets/assert-no-value-leak!` checks the payload before
   handing it to the bus; if a future refactor accidentally adds
   `:value`, this throws.
3. The wiring test (`anvil.secrets.dispatcher-wiring-test`) asserts
   that the literal secret string never appears in any field of the
   emitted event.

The decrypted value lives ONLY in the `{:value …}` map that
`resolve!` hands back to `h-with-credentials`. From there the
dispatcher's existing AN4-4 path scopes it to the build's env and
adds it to the masker so it doesn't leak into console output.

## Migration from AN7-3 local-disk credentials

No migration needed. The local-disk store (PR #84) remains the
default backend. When a Vault/KMS adapter is installed it takes
priority; if it returns nil (id not in Vault) the dispatcher emits
`:credential-unresolved` as it would for any missing id.

If you want a fallback chain (try Vault, then local), write a small
composite backend:

```clojure
(defrecord FallbackBackend [primary secondary]
  anvil.secrets/SecretBackend
  (resolve! [_ id]
    (or (anvil.secrets/resolve! primary id)
        (anvil.secrets/resolve! secondary id)))
  (list-ids [_]
    (distinct (concat (anvil.secrets/list-ids primary)
                      (anvil.secrets/list-ids secondary)))))
```

Per AV6-3, that's exactly the kind of operator-owned backend the
protocol exists to support.

## Locked decision context

[AV6-3](../roadmap/v0.6-board.md#locked-decisions-av6-series) — Vault
+ KMS as operator-pluggable adapters.
