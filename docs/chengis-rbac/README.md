---
title: Chengis 0.1 RBAC Adapter
audience: operators, developers
category: feature
purpose: Optional multi-tenant RBAC + audit log via chengis 0.1
lifecycle: shipped
status: v0.5 T4
---

# Chengis 0.1 RBAC Adapter

Anvil v0.5 ships an optional multi-tenant RBAC adapter that connects to a
[chengis 0.1](https://github.com/SuperBadLabs/chengis) service for permission
checks and audit logging. When the adapter is disabled (the default), anvil
behaves as a single-tenant system exactly as before -- no code paths change.

Per **AV5-5**: chengis 0.1 ships from its own repository. Anvil does not
bundle chengis; the adapter talks to a running chengis HTTP service.

## Quick start

### 1. Start a chengis 0.1 service

Follow the chengis 0.1 setup guide to deploy a chengis instance and create
your first tenant.

### 2. Enable the feature flag

```edn
;; anvil.edn
{:anvil.features/multi-tenant true}
```

### 3. Configure the service URL and token

```edn
;; anvil.edn
{:anvil.tenancy/service-url "http://chengis:8090"
 :anvil.tenancy/token-env   "CHENGIS_TOKEN"
 :anvil.tenancy/timeout-ms  2000}
```

```bash
export CHENGIS_TOKEN=<your-chengis-api-token>
```

Or set the URL directly via env:

```bash
export ANVIL_CHENGIS_URL=http://chengis:8090
```

### 4. Configure SSO (optional, for browser flows)

```edn
{:anvil.tenancy/sso-url    "http://chengis:8090/sso/login"
 :anvil.tenancy/cookie-key "anvil-session"}
```

When `sso-url` is set, unauthenticated browser requests redirect to the
chengis SSO login page with a `return_to` param.

## How it works

### Permission model

Anvil uses four permission verbs:

| Verb | Meaning |
|---|---|
| `:read` | View jobs, builds, queue, cost data |
| `:write` | Create/update jobs, trigger builds |
| `:build` | Trigger a build (narrower than :write) |
| `:admin` | Manage credentials, scheduler, feature flags |

Requests that do not carry a recognized identity get `principal = anonymous`.
By default (no RBAC configured), anonymous is allowed everything.

### RBAC check flow

1. `anvil.tenancy.middleware/wrap-rbac` extracts `tenant-id` and `principal`
   from the request (headers or upstream auth).
2. Calls `anvil.tenancy.rbac/check!` on the active backend.
3. On allow: injects `:anvil/tenant-id` and `:anvil/principal` into the
   request map and delegates to the route handler.
4. On deny: returns HTTP 403 with the reason string from chengis.
5. Always calls `record-audit!` (fire-and-forget, never blocks the request).

### Identity extraction order

**Tenant:**
1. `:anvil/tenant-id` key (set by upstream middleware)
2. `X-Anvil-Tenant` header
3. `"default"` (single-tenant compat)

**Principal:**
1. `:anvil/principal` key (set by upstream auth)
2. `Authorization: Bearer <token>` -- token used as principal directly (v0.5 stub)
3. `X-Anvil-Principal` header (service-to-service)
4. `"anonymous"`

### Audit log

The `anvil.tenancy.audit-subscriber` subscribes to these bus events and
writes audit records to chengis:

| Event | Action | Resource |
|---|---|---|
| `:build-started` | `:build` | `/jobs/<name>` |
| `:build-done` | `:build` | `/jobs/<name>` |
| `:job-created` | `:write` | `/jobs` |
| `:credential-added` | `:admin` | `/credentials` |

## Config reference

| Key | Source | Description |
|---|---|---|
| `ANVIL_CHENGIS_URL` | env | Chengis service URL (overrides anvil.edn) |
| `CHENGIS_TOKEN` | env | API token (name configurable via `:anvil.tenancy/token-env`) |
| `:anvil.tenancy/service-url` | anvil.edn | Chengis service base URL |
| `:anvil.tenancy/token-env` | anvil.edn | Env var name for the token (default: `CHENGIS_TOKEN`) |
| `:anvil.tenancy/timeout-ms` | anvil.edn | HTTP call timeout (default: 2000 ms) |
| `:anvil.tenancy/sso-url` | anvil.edn | Chengis SSO login URL for browser redirect |
| `:anvil.tenancy/cookie-key` | anvil.edn | Session cookie name (default: `anvil-session`) |

## Gaps and known limitations (honest per AV5-6)

- **v0.5 stub: Bearer token = principal**. The Bearer token in the
  `Authorization` header is used as the principal string directly. Full
  JWT validation and token-to-user resolution defer to chengis 0.1's
  token introspection API.
- **No server-side session persistence**. The SSO cookie is read as-is;
  session expiry and revocation defer to chengis 0.1.
- **No fine-grained resource ACLs at v0.5**. The permission check passes
  the request URI as the resource. Chengis 0.1 operators can configure
  policies against these resource paths.
- **wrap-rbac not applied globally by default**. Operators must opt in
  per-route via `wrap-rbac-route` or apply the global middleware. T4.2
  provides the primitives; wiring all routes is a follow-up.
- **T4.1 (chengis 0.1 repo + jar)** is out of scope for this PR.

## Files

| File | Role |
|---|---|
| `src/anvil/tenancy/rbac.clj` | Protocol, NoOpBackend, ChengisBackend, registry |
| `src/anvil/tenancy/middleware.clj` | Ring middleware (wrap-rbac, wrap-rbac-route) |
| `src/anvil/tenancy/audit_subscriber.clj` | Bus subscriber for audit events |
| `src/anvil/tenancy/sso.clj` | SSO redirect middleware + session validation stub |
| `test/anvil/tenancy/rbac_test.clj` | RBAC protocol + backend tests |
| `test/anvil/tenancy/middleware_test.clj` | Middleware tests |
| `test/anvil/tenancy/audit_subscriber_test.clj` | Audit subscriber tests |
