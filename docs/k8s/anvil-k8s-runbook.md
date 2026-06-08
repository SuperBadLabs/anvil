---
title: anvil + Kubernetes — operator runbook (T1 stub)
audience: operators
category: k8s
purpose: How to bring up a single-node kind cluster + configure anvil to use it as a build agent backend. Populated as T1 of the v0.6 board lands; this is a scaffolding stub per T0.3.
lifecycle: stub
last-verified: 2026-06-08
status: stub
---

# anvil + Kubernetes — operator runbook

> **Stub.** This page is placeholder for the T1 receipt that lands
> with the K8s agent runtime tranche. The full runbook (kind cluster
> setup, kubeconfig wiring, `agent { kubernetes { yaml } }` examples,
> resource-limit mapping, debugging) lands when [T1.6](../roadmap/v0.6-board.md#t1--kubernetes-agent-runtime--2-wk)
> ships.

Planned sections:

- [ ] Provisioning a single-node `kind` cluster on a dev host
- [ ] Operator config: `:anvil.k8s/kubeconfig-path` in `anvil.edn`
- [ ] Declarative `agent { kubernetes { yaml '...' } }` walkthrough
- [ ] Scripted `agent { kubernetes { containerTemplate(...) } }` walkthrough
- [ ] Resource-limit mapping: `:resource-limits {...}` → pod spec
- [ ] Debugging: pod-stuck-pending, image-pull-backoff, OOMKilled
- [ ] When to use k8s vs docker (per-build-cost / latency trade-offs)

Locked decision context: [AV6-2](../roadmap/v0.6-board.md#locked-decisions-av6-series)
— K8s backend lives in chengis-core, not anvil.
