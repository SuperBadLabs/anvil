(ns anvil.compat.jenkins.scm-lifecycle
  "AN8-4 — declarative-pipeline implicit `checkout scm` lifecycle.

   Jenkins's declarative-pipeline contract: between Jenkinsfile parsing
   and stage-1 execution, the workspace is implicitly populated by
   `checkout scm` against the job's registered SCM. Scripted pipelines
   don't get this — operators write the explicit
   `node { checkout scm; ... }` themselves.

   Anvil's runner historically ran `scm/provision!` BEFORE the
   dispatcher when the job had `:scm` configured, but the IR itself
   never reflected the implicit checkout. That works for the per-job
   pre-build path but leaks in two places:

   1. The IR doesn't honestly model what the dispatcher does — making
      classification (AN5-1) and per-stage diagnostics murky.
   2. When the runner's pre-build provision succeeded but the
      workspace got polluted later (parallel stages, manual ops,
      stale state from the test-infra tuning experiment per AN7-5c),
      there's no second-line defense before stage 1 runs.

   This namespace closes both. The `inject-implicit-checkout` walker
   detects declarative shape during translation, and — if there's no
   explicit `checkout` step at the start of stage 1 — prepends a
   synthetic `:jenkins/checkout :implicit? true` step. The dispatcher
   handler (`anvil.compat.jenkins.dispatcher/h-checkout`) recognizes
   the `:implicit?` flag and routes through `scm/provision!` against
   the `:scm` config in the build ctx.

   Gated behind `:anvil.features/scm-checkout-lifecycle` — closed by
   default through the AN8-4 ship, flipped to default-on once the
   receipt lands.

   Anti-fragile guarantees:

   - Skip injection when the IR already has an explicit `checkout`
     step somewhere before stage 1's first non-checkout step.
   - When the step runs, `scm/provision!` itself is idempotent — if
     `.git` already exists and points to the registered URL, the
     refresh path runs (`git fetch + reset + clean`); otherwise a
     shallow clone is performed.
   - When no `:scm` is configured on the job, the implicit step
     records a `:checkout/skipped` effect and returns ok (preserving
     pre-AN8-4 behavior — no `:scm`, no checkout)."
  (:require [anvil.compat.jenkins.ir :as ir]))

(defn declarative?
  "True if `pipeline-ir` parsed as a declarative pipeline.

   Declarative parse output omits the `:scripted-pipeline?` tag in
   :options that the scripted-eval / scripted-stage extraction paths
   write. We use the inverse to identify declarative shape — i.e. a
   well-formed pipeline IR with at least one stage and no
   :scripted-pipeline? tag.

   Returns false for parse-error IR (no `:stages`) and for explicitly
   tagged scripted pipelines (the AN8-4 anti-goal: scripted operators
   write their own checkout)."
  [pipeline-ir]
  (and (ir/pipeline? pipeline-ir)
       (seq (:stages pipeline-ir))
       (not (some :scripted-pipeline? (:options pipeline-ir)))
       (not (some :parse-error (:options pipeline-ir)))))

(defn- explicit-checkout?
  "True if `steps` (vector of step IR nodes) contains an explicit
   `:jenkins/checkout` step. Conservatively shallow: doesn't recurse
   into :body / :branches because Jenkins's implicit checkout fires
   before any scope wrappers run. An explicit `checkout scm` nested
   inside `script { ... }` or `dir('x') { ... }` is rare enough that
   injecting an additional implicit checkout is the safer default
   (idempotent — provision! short-circuits on existing .git)."
  [steps]
  (boolean (some #(= :jenkins/checkout (:type %)) steps)))

(defn- synth-checkout-step
  [scm]
  (cond-> {:type :jenkins/checkout
           :implicit? true
           :ref "$scm"
           :anvil/source :scm-lifecycle}
    scm (assoc :scm scm)))

(defn needs-implicit-checkout?
  "True if `pipeline-ir` is declarative AND stage 1 has no explicit
   `checkout` step at the top level. Pure predicate — caller decides
   whether to inject."
  [pipeline-ir]
  (and (declarative? pipeline-ir)
       (let [stage1 (first (:stages pipeline-ir))]
         (not (explicit-checkout? (:steps stage1 []))))))

(defn inject-implicit-checkout
  "Return `pipeline-ir` with a synthetic `:jenkins/checkout :implicit? true`
   step prepended to stage 1's :steps if (a) the pipeline is
   declarative-shaped, (b) stage 1 doesn't already have an explicit
   checkout, and (c) `flag-enabled?` is truthy.

   `scm` (optional) is the job's SCM config, snapshotted onto the
   synthetic step for the dispatcher's handler. Passed-through value
   should match the shape `anvil.compat.jenkins.scm/provision!`
   expects: `{:type :git :url <str> :branch <str>}`. Even when nil,
   we still inject the step — the handler records a `:checkout/skipped`
   effect, which keeps the IR honest about Jenkins's lifecycle
   contract for declarative pipelines.

   Idempotent: re-running on already-injected IR is a no-op
   (the existing implicit step trips `explicit-checkout?`)."
  [pipeline-ir {:keys [scm flag-enabled?]}]
  (cond
    (not flag-enabled?) pipeline-ir
    (not (needs-implicit-checkout? pipeline-ir)) pipeline-ir
    :else
    (let [stages (:stages pipeline-ir)
          stage1 (first stages)
          stage1' (update stage1 :steps (fn [steps]
                                          (into [(synth-checkout-step scm)]
                                                (or steps []))))]
      (assoc pipeline-ir :stages (into [stage1'] (rest stages))))))
