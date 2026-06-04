# Operation Brasstacks — Execution Board

The public commitment to take anvil, chengis-core, and chengis-product from
"parser-parity + aspirational executor" to **industrial-strength enterprise
CI** across all three products.

This document is the master execution board. It supersedes scattered roadmap
notes. Each tranche has an ID, label, predecessor chain, deliverable, and
falsifiable acceptance receipt. Updates land here, not in PR descriptions.

## Why this board exists

The v0.3.0 "Parity Release" framing was wrong. v0.3 delivered **parser
parity** — anvil parses any real-world Jenkinsfile, including 66 KB
declarative+scripted mixes with Kubernetes yaml heredocs. It did not deliver
**executor parity** — when a Jenkinsfile says `agent { docker }`,
`agent { kubernetes }`, `tool('maven_3_9_latest')`, or
`withCredentials([…])`, anvil silently skips the body, returns empty
strings, or records `[unknown]` and reports the build SUCCESS while
having executed zero shell commands.

A wild-corpus matrix against 15 diverse non-jenkinsci OSS Jenkinsfiles
(`docs/jenkins-compat/wild-corpus-receipt.md`) made this concrete:
**15/15 parsed; 0/15 built a real artifact.**

Operation Brasstacks closes that gap, honestly, across the three-product
stack. The CHANGELOG amendment in the same window (post-0.3.0 Unreleased
section) carries the honest framing for users; this board carries the
internal engineering plan.

## The bar — "industrial-strength" defined

A green build is a manifest of what actually ran:

- An agent provisioned with named image/label/version
- Tools resolved to real paths on PATH (`tool('jdk_17_latest')` returns
  a concrete JDK directory, not `""`)
- Workspace cloned at a verified SHA
- Credentials resolved to non-empty values, or the build fails loud
  with the missing credential named
- Every step ran (sh exec'd, plugin emulated, recorded), every one
  with a known classification
- Artifacts on disk, tests persisted, problems indexed, notifications
  delivered
- Exit code is a function of what ran, not what was skipped

A red/neutral/unstable build comes with a manifest naming the failure
(image pull, tool missing, credential unresolved, plugin unsupported,
step exit code), not a stack trace and silence.

**Non-negotiable rules:**

- No `[unknown]` step counts toward SUCCESS
- No skipped agent counts toward SUCCESS
- No empty-string credential counts toward SUCCESS
- No zero-shell-step build counts toward SUCCESS

**Concurrency target**: 100 concurrent builds per controller, p95 step
overhead < 200 ms vs. raw shell, controller restart loses zero
in-flight work.

**Reliability target**: zero silent failures across a 7-day continuous
soak.

**Compliance target** (chengis-product): SOC 2 Type II ready audit trail
— every action, every secret access, every config change with actor +
timestamp + IP.

## Tranche conventions

| Label | Meaning |
|---|---|
| **S** | **Serial** — must complete before its named dependents start |
| **P** | **Parallel** — can run alongside named siblings once the predecessor is met |
| **B** | **Batch** — small items grouped into one ship; partial completion does not count |

Every tranche has:

- **ID** — phase-prefix-suffix (`CC2-EX1a`, `AV4-3`, `CP1-MT`)
- **Label** — S, P, or B
- **Predecessor(s)** — what must complete first
- **Deliverable** — what the code change is
- **Acceptance receipt** — the falsifiable test that proves it landed

## Phase 0 — Reset (Week 1)

Public commitment + capability matrix + framing cleanup. No engine code.

| ID | Label | Pred | Deliverable | Acceptance receipt |
|---|---|---|---|---|
| **BT0-A** | **S** | — | `docs/brasstacks/board.md` (this document) | This file merged on master |
| **BT0-B** | **S** | BT0-A | `docs/brasstacks/capability.md` — honest matrix of supported agent shapes, step names, credential kinds, tool names; CI-gated regeneration | `bb scripts/gen-capability.bb` produces byte-equal output; CI fails if doc drifts from generator |
| **BT0-C** | **S** | BT0-A | README softened — "parses any Jenkinsfile; executes the supported subset" replaces "drop-in Jenkins replacement runs your Jenkinsfile unchanged" | README on master reflects the honest framing |
| **BT0-D** | **S** | BT0-A | v0.3.0 GitHub release notes amended with a pointer to the CHANGELOG Unreleased amendment + capability.md | v0.3.0 release page on GitHub shows the amendment header |

## Phase 1 — chengis-core v0.2: Executor Backends (Weeks 2–15)

The foundation. Every wild-corpus build success has to bottom out in this
work.

| ID | Label | Pred | Deliverable | Acceptance receipt |
|---|---|---|---|---|
| **CC2-EX1a** | **S** | BT0 | `chengis.engine.agent` protocol (workspace lifecycle, env-injection contract, step-exec contract, cancel contract) + in-process local-shell reference impl | Existing anvil tests pass with new protocol indirection; no behavior change |
| **CC2-EX1b** | **S** after EX1a | EX1a | `chengis.engine.agent.docker` — image pull+auth+retry, container-per-build OR container-per-step, workspace bind/volume mount, env+secrets injection, signal propagation, SIGKILL on cancel, cgroup limits (mem/CPU/pids) | hibernate-orm + apache-camel + apache-zookeeper matrix entries build to real Maven artifacts on disk |
| **CC2-EX2** | **P** with EX1b | EX1a | `:result` enum extended (`:neutral`/`:unsupported` added), `:result/explain` returning a manifest, classifier rules (no-shell-step → `:neutral`, agent-unsupported → `:unsupported`, cred-unresolved → `:failure`), UI badge + sparkline + filter | Wild-corpus matrix shows 7 `:neutral` results where it previously showed false `:success` |
| **CC2-EX3** | **P** with EX1b | EX1a | `chengis.tools/resolve!` API + descriptor format + installers for JDK (temurin/zulu/corretto) / Maven / Gradle / Node / Python / Go / Ruby / sbt with per-version cache + mise/asdf delegation | apache-maven's `tool('maven_3_9_latest')` resolves to a real Maven 3.9 binary and is on the build's PATH |
| **CC2-EX5** | **P** with EX1b | EX1a | `chengis.engine.steps` registry + adapter protocol + built-in primitives (artifacts/archive, tests/junit, problems/record, coverage/record, notifications/{slack,email,github-status,github-comment}, delivery/{scp,s3,gcs,http-post}) + honest unsupported-step fallback | apache-zookeeper's `archiveArtifacts` writes real .tar.gz; junit parses + persists surefire XML; recordIssues persists problems |
| **CC2-EX4** | **S** after EX1b | EX1b | `chengis.engine.credentials.bind!` step-wrapper + per-build config-file injection (`~/.m2/settings.xml`, `~/.npmrc`, `~/.pip/pip.conf`, `~/.docker/config.json`) + credential descriptors with file/env/exec rendering | eclipse-jkube `gpg --import "${GPG_KEY}"` reads a real key; camel-quarkus + streampipes get past Apache snapshot 401 when operator has cred configured |
| **CC2-SOAK** | **S** after EX1–EX5 | EX1b/EX2/EX3/EX4/EX5 | 7-day continuous soak at 100 concurrent Docker builds, no leaks, no zombies, zero silent failures, p95 step overhead < 200 ms vs. raw shell | Soak report committed; wild-corpus matrix at 13/15 honest; chengis-core v0.2.0 tag pushed |

**Phase 1 acceptance**: chengis-core v0.2.0 released; wild-corpus 13/15 honest; soak receipt published.

## Phase 2 — anvil v0.4: Honest Executor Parity (Weeks 16–21)

Thin mapping layer over chengis-core v0.2. No new engine code in anvil.

| ID | Label | Pred | Deliverable | Acceptance receipt |
|---|---|---|---|---|
| **AV4-1** | **S** after CC2-EX1b | CC2-EX1b | Jenkinsfile `agent { docker { … } }` → chengis Docker spec; `agent { dockerfile {…} }` → build-then-run; `agent { label 'X' }` → labeled-agent registry; `agent none` enforcement (per-stage agent required, fails translation otherwise) | apache-cassandra builds via dockerfile-agent; apache-cxf builds with per-stage agents |
| **AV4-2** | **S** after CC2-EX3 | CC2-EX3 | Jenkins tool-name → chengis descriptor mapping (well-known: jdk_*_latest, maven_*_latest, gradle_*_latest, nodejs_*) | apache-maven matrix entry builds end-to-end with real Maven 3.9 |
| **AV4-3** | **S** after CC2-EX4 | CC2-EX4 | Jenkins credential kinds → chengis bindings (usernamePassword, sshUserPrivateKey, file, string, certificate, gitUsernamePassword) | eclipse-jkube + camel-quarkus + streampipes build to real deployed artifacts (with operator-configured Apache snapshot creds) |
| **AV4-4** | **B** after CC2-EX5 | CC2-EX5 | Top-20 Jenkins step mappings: archiveArtifacts, junit, recordIssues, publishCoverage, recordCoverage, slackSend, emailext, mail, sshPublisher, gitPush, gitTag, build (downstream), lock, retry, timeout, waitUntil, dir, checkout, scm, plus 1 more | Each step in the batch has a unit test + an acceptance receipt on a matrix project that exercises it |
| **AV4-5** | **P** | BT0 | Capability matrix doc auto-generator — reads step registry + agent impls, emits `docs/capability.md`; CI-gated (regression blocks merge) | `docs/capability.md` byte-equal to `bb gen-capability.bb` output on every PR |
| **AV4-6** | **P** | BT0 | Wild-corpus matrix as CI gate — runs as a chengis-CI job after each PR, regressions block merge, honest receipt as PR comment | A PR that drops hibernate-orm from honest-success is automatically blocked + commented |

**Phase 2 acceptance**: anvil v0.4.0 released; wild-corpus 13/15 honest with `:unsupported` reasons for the k8s 2; capability.md gated.

## Phase 3 — chengis-core v0.3: Kubernetes Backend (Weeks 22–29)

| ID | Label | Pred | Deliverable | Acceptance receipt |
|---|---|---|---|---|
| **CC3-EX6a** | **S** | CC2-SOAK | fabric8 k8s client wired into agent protocol; `chengis.engine.agent.kubernetes` implements protocol; Pod creation with restart-never, container exec for steps, lifecycle (created → ready → exec → completed → cleanup) | eclipse-epsilon matrix entry builds via a real k8s pod against a local kind cluster |
| **CC3-EX6b** | **S** after EX6a | CC3-EX6a | podTemplate YAML parser with sanity checks (resources, security context); multi-container pod support (jnlp + tools containers); per-step container routing | eclipse-mojarra builds; jenkinsci/jenkins's ci.jenkins.io declarative-with-k8s pattern builds end-to-end |
| **CC3-EX6c** | **S** after EX6b | CC3-EX6b | Multi-namespace support; per-tenant K8s namespace allocation; resource quota enforcement; network policy isolation | 3-tenant k8s load test with cross-tenant isolation verified |
| **CC3-SOAK** | **S** | CC3-EX6c | 7-day continuous soak at 100 concurrent k8s builds across 3 namespaces, zero silent failures, no leaked pods | Soak report; wild-corpus 15/15 honest; chengis-core v0.3.0 tag pushed |

**Phase 3 acceptance**: chengis-core v0.3.0; wild-corpus 15/15 honest; k8s soak receipt.

## Phase 4 — chengis-product v0.1: Enterprise Foundation (Weeks 30–41)

| ID | Label | Pred | Deliverable | Acceptance receipt |
|---|---|---|---|---|
| **CP1-MT** | **S** foundational | CC3-SOAK | Org / project / team hierarchy in DB; tenant-scoped row-level queries; per-tenant resource quotas; per-tenant audit scopes | 1000-tenant load test with isolation verified (no cross-tenant data leaks) |
| **CP1-RBAC** | **P** after CP1-MT skeleton | CP1-MT skeleton | Permission enum (view-build, trigger-build, configure-job, manage-credentials, manage-org, admin); role definitions; bindings at org/project/team/job scope; permission-check middleware | Full RBAC permission matrix unit + integration tests green; perm-check audit-logged |
| **CP1-SSO** | **P** after CP1-MT skeleton | CP1-MT skeleton | SAML 2.0 (Okta + Azure AD + OneLogin); OIDC (Google + GitHub + GitLab); JIT provisioning; SCIM for user lifecycle | Live tests against 3 SAML IdPs + 3 OIDC providers; SCIM user lifecycle verified |
| **CP1-AUDIT** | **P** after CP1-MT skeleton | CP1-MT skeleton | Audit event schema (actor + action + target + result + IP + timestamp); immutable append-only store with cryptographic chain (Merkle); SIEM export (Splunk + Datadog) | Audit chain tamper-detection test; SOC 2 control coverage matrix green |
| **CP1-HA** | **S** after CP1-MT/RBAC/SSO/AUDIT | CP1-MT, CP1-RBAC, CP1-SSO, CP1-AUDIT | Active-passive controller; state replication (DB + workspace state); automatic failover < 30 s; in-flight build resumption | Failover test with in-flight builds: zero loss, recovery time < 30 s; reverse-failover verified |
| **CP1-SOAK** | **S** | CP1-HA | 7-day continuous soak under 1000-tenant load + planned failover + chaos injection; zero data loss; zero silent failures | Soak report; chengis-product v0.1.0 tag pushed |

**Phase 4 acceptance**: chengis-product v0.1.0; multi-tenant + RBAC + SSO + audit + HA all green under 7-day soak.

## Phase 5 — Industrial Scale + Compliance (Weeks 42–65)

| ID | Label | Pred | Deliverable | Acceptance receipt |
|---|---|---|---|---|
| **CP2-SAAS** | **S** | CP1-SOAK | Multi-tenant hosting control plane; billing integration (Stripe); customer signup → first-build < 10 min | First-10-customers production cohort; <10 min activation time verified |
| **CP2-COMPLIANCE** | **P** | CP1-SOAK | SOC 2 Type II audit complete; FedRAMP Moderate package ready; HIPAA BAA-ready | External auditor reports; control evidence library |
| **CP2-PREMIUM** | **B** | CC2-EX5 | Premium plugin tier: SonarQube enterprise, Coverity, Veracode, Snyk, ServiceNow approvals, Slack Enterprise Grid | Each integration has working acceptance test against vendor's sandbox |
| **CP2-COST** | **P** | CP1-MT | Per-tenant resource accounting (CPU-hours, storage-GB-hours, network egress); export to billing | Cost dashboard per tenant; reconciliation tests within 1% of cloud bill |
| **CP2-CACHE** | **P** | CC2-SOAK | Artifact cache backend; cross-build dedup; content-addressable storage; monorepo-scale | Monorepo test (10k modules): cache hit rate > 90% on incremental build; storage growth bounded |
| **CP2-SCALE** | **S** | CP1-SOAK | 10,000 builds/day per controller; p95 queue-to-start < 5 s | Scale test sustained 10k/day for 7 days |

**Phase 5 acceptance**: chengis-product v0.2.0 with SaaS GA, compliance packages public, premium tier shipping.

## Wave view (execution timeline)

```
Week:   1   2   3   4   5   6   7   8   9   10  11  12  13  14  15  16  17  18  19  20  21
        ───────────────────────────────────────────────────────────────────────────────────
Wave 1: BT0
Wave 2:     EX1a
Wave 3:         ┌─ EX1b ──────────┐
                ├─ EX2 ────────┐  │
                ├─ EX3 ────────┤  │
                └─ EX5 ────────┘  │
Wave 4:                          └─ EX4 ──────┐
Wave 5:                                       └─ SOAK ──┐
Wave 6:                                                 └─ AV4-1/2/3/4 (parallel) ─┐
                                                          AV4-5/6 (parallel, anytime)
                                                                                   └─ v0.4

Week:   22  23  24  25  26  27  28  29  30 ... 41  42 ... 65
        ──────────────────────────────────────────────────────
Wave 7: EX6a → EX6b → EX6c → SOAK → v0.3
Wave 8:                            CP1-MT → ┌─ RBAC ─┐
                                            ├─ SSO  ─┤ → CP1-HA → SOAK → v0.1
                                            └─ AUDIT┘
Wave 9:                                                      ┌─ SAAS ──┐
                                                             ├─ COMPL  ┤
                                                             ├─ PREMIUM┤ → v0.2
                                                             ├─ COST  ─┤
                                                             ├─ CACHE ─┤
                                                             └─ SCALE ─┘
```

## Critical path

The longest chain of strictly-serial tranches:

```
BT0 → EX1a → EX1b → EX4 → SOAK → AV4-1+2+3 → EX6a → EX6b → EX6c → CP1-MT → CP1-HA → SOAK → CP2-SAAS → CP2-SCALE
```

~13 serial tranches at ~3-5 weeks each ≈ **50-60 weeks critical path**. Matches
the 65-week total. Anything on the critical path that slips moves the v1.0 ship
date 1:1.

**Parallel tranches** (EX2, EX3, EX5, AV4-4/5/6, CP1-RBAC/SSO/AUDIT, CP2-COMPLIANCE/PREMIUM/COST/CACHE) **don't move the v1.0 date** if they slip — they only delay specific receipts.

**Batch tranches** (BT0, AV4-4, CP2-PREMIUM) ship as a unit. Partial completion does not count.

## Non-negotiable gates between phases

Each gate is a public commit, not internal review.

| Gate | Required to enter next phase |
|---|---|
| Phase 0 → 1 | BT0-A through BT0-D merged; chengis-core v0.2 board public; agent protocol RFC reviewed |
| Phase 1 → 2 | chengis-core v0.2.0 tagged; CC2-SOAK report committed; wild-corpus 13/15 honest |
| Phase 2 → 3 | anvil v0.4.0 tagged; capability.md CI-gated; wild-corpus CI gate live |
| Phase 3 → 4 | chengis-core v0.3.0 tagged; CC3-SOAK report; wild-corpus 15/15 honest |
| Phase 4 → 5 | chengis-product v0.1.0 tagged; CP1-SOAK report; 1000-tenant isolation verified |
| Phase 5 → v1.0 | All CP2 tranches green; external SOC 2 Type II report; 7-day 10k-builds/day soak |

## Operating principles (non-negotiable across all phases)

1. **No silent successes.** Every claim is a receipt the operator can reproduce.
2. **Capability matrix lives in the repo, regenerated from code.** What we support is what we ship a manifest for.
3. **Wild-corpus matrix is the gate.** Every PR re-runs it. Regressions block merge.
4. **Receipt-driven PR descriptions.** "X went from Y to Z, here is the dogfood evidence." No more "tests green, N assertions" without naming what behavior shipped.
5. **Soak before claim.** Concurrency / reliability targets demonstrated under 7-day load, not synthetic single-run benchmarks.
6. **Honest CHANGELOG.** "Parity" means executor-parity; "drop-in" means a real install builds real projects.
7. **Public board.** This document is the truth; PR descriptions reference tranche IDs that live here.

## Status (live)

| Phase | Status |
|---|---|
| Phase 0 — Reset | In progress (BT0-A this PR) |
| Phase 1 — chengis-core v0.2 | Not started |
| Phase 2 — anvil v0.4 | Not started |
| Phase 3 — chengis-core v0.3 | Not started |
| Phase 4 — chengis-product v0.1 | Not started |
| Phase 5 — Industrial scale | Not started |
