---
title: anvil + Kubernetes — operator runbook
audience: operators
category: k8s
purpose: How to bring up a single-node kind cluster, configure anvil to use it as a build-agent backend, and observe a k8s-agent build end-to-end.
lifecycle: live
last-verified: 2026-06-08
status: shipped
---

# anvil + Kubernetes — operator runbook

> Ships with anvil v0.6 (T1 of the v0.6 board). Implements the
> [AV6-2](../roadmap/v0.6-board.md#locked-decisions-av6-series) locked
> decision: the K8s execution backend lives in chengis-core 0.4; anvil
> only consumes the `ExecutionBackend` protocol via the translator and
> backend-wiring layers.

## What you get

- `agent { kubernetes { yaml '...' } }` — Jenkins's declarative
  KubernetesPipeline form. Translator extracts image / namespace /
  resource-limits hints from the inline yaml via regex (no clj-yaml
  dependency pulled into the translator path).
- `agent { kubernetes { containerTemplate(name: '…', image: '…',
  resourceLimitMemory: '…', resourceLimitCpu: '…') } }` — the
  structured form Jenkins also accepts. Maps to the same IR shape.
- Per-step pod lifecycle: each `sh` step runs in its own pod with
  `restartPolicy: Never`. Pods are individually salted; cleanup is
  automatic at end-of-step.
- Resource limits → pod container `resources.limits` (and
  `resources.requests` — set to the same value). `:memory-mb` becomes
  `<N>Mi`; `:cpus` becomes `<N>` cpu cores.
- Operator-overridable kubeconfig via `:anvil.k8s/kubeconfig-path` in
  `anvil.edn`.

## Provisioning a single-node `kind` cluster

A single-node `kind` cluster is enough for the dev loop:

```bash
# kind 0.21+ — older versions ship k8s 1.27 which is fine for v0.6.
go install sigs.k8s.io/kind@latest

# Spin up a single-node cluster named "anvil-dev"
kind create cluster --name anvil-dev

# Verify the apiserver responds + nodes are Ready
kubectl --context kind-anvil-dev get nodes
kubectl --context kind-anvil-dev cluster-info
```

`kind` writes its kubeconfig into `~/.kube/config` under a context
named `kind-anvil-dev`. anvil's K8sBackend resolves the kubeconfig in
this order:

1. `:anvil.k8s/kubeconfig-path` from `anvil.edn` (explicit override)
2. `KUBECONFIG` env var
3. `~/.kube/config`

So with the default `kind` install + no other config, anvil already
sees your cluster.

If you have multiple clusters in `~/.kube/config`, set the current
context before starting anvil (`kubectl config use-context
kind-anvil-dev`) or point anvil at a kubeconfig containing only the
intended cluster:

```edn
;; anvil.edn
{:anvil.k8s/kubeconfig-path "/home/anvil/.kube/anvil-dev-only.yaml"}
```

## Enabling the feature

The `:k8s-agent` feature flag defaults to **on** starting with anvil
v0.6 (per [AV6-7](../roadmap/v0.6-board.md#locked-decisions-av6-series)
— flags gate routes during in-progress, flip to on with the
tranche-closing commit). Operators on hosts without a reachable
cluster set the flag false to keep k8s agent shapes degrading
honestly to `:unsupported`:

```edn
;; anvil.edn — opt out of k8s execution
{:anvil.features/k8s-agent false}
```

With the flag on AND a reachable cluster, anvil routes every
`agent { kubernetes { … } }` stage through chengis-core 0.4's
K8sBackend.

## Declarative walkthrough — `agent { kubernetes { yaml } }`

Drop this Jenkinsfile in the workspace of an anvil job:

```groovy
pipeline {
  agent {
    kubernetes {
      yaml '''
apiVersion: v1
kind: Pod
metadata:
  namespace: default
spec:
  containers:
  - name: jnlp
    image: eclipse-temurin:21
    resources:
      limits:
        memory: 2Gi
        cpu: 1500m
'''
    }
  }
  stages {
    stage('Build') {
      steps {
        sh 'java -version'
        sh 'echo BUILD_NUMBER=$BUILD_NUMBER'
      }
    }
  }
}
```

Trigger a build. The console output shows one pod per `sh` step:

```
[anvil] kubectl apply pod chengis-myjob-1-step-525b7a ns=default image=eclipse-temurin:21 : java -version
openjdk 21.0.x ...
[anvil] kubectl apply pod chengis-myjob-1-step-ee2a21 ns=default image=eclipse-temurin:21 : echo BUILD_NUMBER=$BUILD_NUMBER
BUILD_NUMBER=1
```

`kubectl get pods -n default` shows zero leftover chengis pods after
the build finishes (per-step lifecycle deletes them).

The translator extracts these hints from the yaml:

| YAML path | Extracted |
| --- | --- |
| `spec.containers[0].image` | `:image` (passed to the pod we launch) |
| `metadata.namespace` | `:namespace` (overrides the default) |
| `spec.containers[0].resources.limits.memory` | `:memory-mb` |
| `spec.containers[0].resources.limits.cpu` | `:cpus` |

When extraction fails (multi-container pods, sidecars, exotic yaml
shapes), the dispatcher records `:agent/degraded :runtime-unsupported`
and the AN4-1 classifier reads the build as `:unsupported` rather
than fake-greening it. Operators see this on the build page.

## Scripted walkthrough — `containerTemplate(...)`

The structured form maps to the same K8sBackend through a different
parser branch:

```groovy
pipeline {
  agent {
    kubernetes {
      containerTemplate(
        name: 'main',
        image: 'maven:3.9-eclipse-temurin-21',
        resourceLimitMemory: '4Gi',
        resourceLimitCpu: '2'
      )
    }
  }
  stages {
    stage('Build') {
      steps {
        sh 'mvn -version'
      }
    }
  }
}
```

The translator pulls `image` + `resourceLimit*` into the same IR
shape declarative emits. The K8sBackend receives identical pod-spec
inputs either way.

## Resource limits — mapping

The chengis-core K8sBackend converts the structured `:resource-limits`
map into k8s container resources:

| anvil/chengis-core key | k8s field | Conversion |
| --- | --- | --- |
| `:memory-mb 2048` | `requests.memory` + `limits.memory` | `2048Mi` |
| `:cpus 1.5` | `requests.cpu` + `limits.cpu` | `1.5` |
| `:cpu-shares N` | — | **dropped** (docker-only, no k8s analog) |
| `:pids-max N` | — | **dropped** (no per-pod equivalent in k8s 1.31) |

Both forms set requests = limits — anvil treats the request as a
hard cap, matching what `docker run --memory=` does for the docker
backend.

When operators want different requests vs limits (e.g. burstable
QoS), the v0.6 IR doesn't expose that knob; deferred to v0.7.

## Operator overrides for resource limits

The AN7-5b build-overrides path (originally written for docker
agents) also applies to k8s. Map a per-job override in `anvil.edn`:

```edn
{:anvil.build-overrides
 {"my-heavy-job" {:docker-resource-limits {:memory-mb 8192 :cpus 4}}}}
```

The same `:docker-resource-limits` key (kept for backwards-compat;
the values are backend-agnostic) merges over the agent-IR's limits
at backend construction time. Operator wins on collision. Useful for
tightening (or loosening) limits without editing the upstream
Jenkinsfile.

## Debugging

### Pod stuck Pending

`kubectl describe pod <pod-name>` typically shows the reason in the
`Events` section. The most common causes against a single-node
`kind` cluster:

- **`Insufficient memory` / `Insufficient cpu`**: the pod's
  `resources.limits` exceed the cluster's allocatable. Lower the
  limit in the Jenkinsfile (or via build-overrides), or add more
  resources to the kind cluster.
- **`ImagePullBackOff` / `ErrImagePull`**: the image isn't in the
  kind node's local image cache and the public registry isn't
  reachable. `kind load docker-image <image>` ships a host-side
  image into the cluster.

### Step fails with `kubectl: cluster not reachable`

`kubectl --kubeconfig <path> cluster-info` from the anvil host. If
that fails, anvil's K8sBackend `prepare-workspace` step returns
`:failed` with a clear explain — visible in the build console as
`[anvil] backend prepare-workspace failed: kubernetes cluster not
reachable via kubeconfig <path>`.

### OOMKilled

The pod's exit code is 137 (SIGKILL after the cgroup memory limit
trips). The build classifies as `:failure` per the standard
exit-code rule. Either raise `:memory-mb` in the Jenkinsfile or
investigate the build's actual peak memory via `kubectl top pod
<pod-name>` while a similar build is running.

## When to use k8s vs docker

| Use the docker backend when … | Use the k8s backend when … |
| --- | --- |
| Single-host install; no cluster | Multi-host or burst capacity needed |
| Simpler ops surface | Already running k8s for prod workloads |
| Faster cold-start per step (~100ms) | Operator wants pod-isolation semantics |
| Host-readable artifacts via `--user $(id -u):$(id -g)` bind-mount | Tolerance for higher per-step latency (~2-5s pod-startup) |

The docker backend (chengis-core 0.2 / v0.3.0) and k8s backend
(chengis-core 0.4 / v0.6 T1) honor the same `:resource-limits`
shape, so switching is a config-file edit — no Jenkinsfile change.

## Provenance

- **Locked decision**:
  [AV6-2](../roadmap/v0.6-board.md#locked-decisions-av6-series) — K8s
  backend in chengis-core, not in anvil.
- **Tranche board**:
  [v0.6 T1](../roadmap/v0.6-board.md#t1--kubernetes-agent-runtime--2-wk).
- **chengis-core release**: [0.4.0](https://github.com/SuperBadLabs/chengis-core/releases/tag/v0.4.0).
- **K8sBackend impl**: `chengis.engine.backend.k8s` in chengis-core.
- **anvil translator**: `agent.clj` + `translator.clj`'s
  `translate-agent-block` k8s branch + `backend_wiring.clj`'s
  `k8s-agent-spec` / `backend-for-ctx`.

## Anti-goals (resist)

- **No k8s SDK in anvil.** The translator emits IR; chengis-core
  shells out to `kubectl`. Keep it that way (AV6-2).
- **No multi-cluster orchestration in v0.6.** One kubeconfig, one
  cluster. Multi-cluster lands when there's a wild-corpus build
  that actually needs it.
- **No PVC-backed workspace in v0.6 T1.** First-cut pods use
  emptyDir; cross-step workspace persistence is a follow-up if
  the wild-corpus heavies demand it.
- **No `:per-build` k8s mode in v0.6 T1.** docker-backend's
  `:per-build` (one container reused across steps) doesn't have a
  direct k8s analog — the closest is "one pod with a long-running
  pause container + kubectl exec per step." Deferred until a
  real-world need surfaces.
