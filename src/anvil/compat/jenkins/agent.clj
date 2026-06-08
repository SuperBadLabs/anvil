(ns anvil.compat.jenkins.agent
  "Agent model — record which Jenkins agent each stage 'runs on' so the
   dispatcher and observers can route execution to the right worker.

   Anvil v1 records the selection but doesn't yet ship subprocess
   dispatch onto a fleet of workers — that lands when this layer plumbs
   into chengis.engine.executor + chengis.agent.worker (TX9). Until then
   every step records its target agent in the side-effects log, which is
   already enough for the migration UX, the corpus regression test, and
   for `anvil import --explain agent`.

   Agent specs come from the translator (TX3) and have these shapes:

     {:type :any}                       — agent any
     {:type :none}                      — agent none
     {:label STRING}                    — agent { label '...' }
     {:type :node-label :label STRING}  — agent { node { label '...' } }
     {:docker {:image STRING :args STRING?}}
     {:dockerfile {:filename STRING :dir STRING?
                   :args STRING? :target STRING?}}  ; v0.6 T3 multistage
     {:type :kubernetes :raw ...}       — out of scope; rejection below

   Resolution policy:
     - Stage-level :agent overrides pipeline-level :agent for that stage.
     - agent none at the pipeline level requires every stage to declare
       its own agent; we emit a :agent/missing warning otherwise (the
       runtime still runs but records the gap).
     - kubernetes agents are rejected with an actionable message — TX5
       phase 2 work."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Spec analysis
;; ---------------------------------------------------------------------------

(defn agent-spec
  "Return the effective agent for a stage:
     - stage's own :agent if present
     - else pipeline-level :agent
     - else nil (Jenkins parse-error in real Jenkins; we record :missing)."
  [pipeline-agent stage-agent]
  (or stage-agent pipeline-agent))

(defn agent-summary
  "Compact human-readable label for an agent spec. Used in side-effects
   logging and the migration UX. Never throws — unknown shapes get a
   structured fallback.

   Order matters: more-specific :type checks come before the bare-:label
   check, because node-label specs carry both."
  [spec]
  (cond
    (nil? spec)                  "<missing>"
    (= :any  (:type spec))       "any"
    (= :none (:type spec))       "none"
    (= :node-label (:type spec)) (str "node-label:" (:label spec))
    (= :kubernetes (:type spec)) "kubernetes (UNSUPPORTED)"
    (:label spec)                (str "label:" (:label spec))
    (:docker spec)               (str "docker:" (-> spec :docker :image))
    (:dockerfile spec)           (let [df (:dockerfile spec)
                                       base (str "dockerfile:" (:filename df))]
                                   (cond-> base
                                     (:target df) (str " --target=" (:target df))))
    :else                        (str "unknown:" (pr-str spec))))

(defn deferred?
  "True iff the spec is one we know about but defer real execution for —
   the dispatcher should still log it but the migration UX should flag
   that this stage won't ACTUALLY run on the requested agent in v1."
  [spec]
  (boolean
   (or (= :kubernetes (:type spec))
       (and (= :none (:type spec))))))

(defn rejected?
  "True iff the spec is one we refuse to import. Currently:
     - :kubernetes (until anvil plumbs into a k8s provisioner — TX9)
   The importer surfaces a clear migration message; the dispatcher
   records the rejection rather than executing."
  [spec]
  (= :kubernetes (:type spec)))

(defn rejection-reason
  "Human-readable rejection text for the importer / migration UX."
  [spec]
  (cond
    (= :kubernetes (:type spec))
    "Kubernetes agent declarations are not yet supported. anvil's
     Kubernetes provisioner (chengis-core has the substrate) wires up
     in TX9; until then, replace this stage's agent block with a
     `label` referring to a worker that has the required tools."

    :else
    nil))

;; ---------------------------------------------------------------------------
;; Effects emitted by the dispatcher when a stage begins
;; ---------------------------------------------------------------------------

(defn stage-enter-event
  "Build the [:agent/stage-enter ...] effect tuple for a stage.

   The tuple records:
     - stage name
     - effective agent spec
     - human-readable summary
     - rejection (when applicable)
     - deferred? flag (so observers can show 'pending TX9' badges)"
  [stage-name effective-agent]
  [:agent/stage-enter
   (cond-> {:stage  stage-name
            :agent  effective-agent
            :summary (agent-summary effective-agent)}
     (rejected? effective-agent)
     (assoc :rejected? true :reason (rejection-reason effective-agent))

     (deferred? effective-agent)
     (assoc :deferred? true))])

(defn stage-leave-event
  [stage-name effective-agent]
  [:agent/stage-leave
   {:stage stage-name
    :summary (agent-summary effective-agent)}])

;; ---------------------------------------------------------------------------
;; Pipeline IR transform
;; ---------------------------------------------------------------------------

(defn wrap-pipeline-with-agent-events
  "Transform a Jenkins pipeline IR so that each stage's :steps is bracketed
   with synthetic agent-enter / agent-leave steps. The dispatcher handles
   these like any other step (emitting effects) but uses them to record
   which agent each stage targeted.

   The reference orchestrator (chengis.engine.dispatcher/run-pipeline) is
   agent-unaware — this transform pushes the agent dispatch into the
   step sequence so the orchestrator's stages-→-steps loop carries it.

   When this layer is integrated into chengis.engine.executor (TX9), the
   real agent dispatcher reads :agent off the stage directly rather than
   needing these synthetic markers; the markers become observability
   sugar at that point."
  [pipeline-ir]
  (let [top-agent (:agent pipeline-ir)
        top-tools (:tools pipeline-ir)]
    (update pipeline-ir :stages
            (fn [stages]
              (mapv (fn [stage]
                      (let [eff (agent-spec top-agent (:agent stage))
                            ;; AN8-1: stage-level :tools overrides
                            ;; pipeline-level for this stage. The dispatcher
                            ;; reads :tools off the synthetic enter step to
                            ;; resolve the docker image via
                            ;; :anvil.tools/images in anvil.edn.
                            ;;
                            ;; AN8-3: matrix-expanded cells carry their
                            ;; effective :tools (parent-stage ⊕ matrix-level
                            ;; tools, already composed in
                            ;; expand-matrix-stage). Pipeline-level
                            ;; `top-tools` provides the BASE — merged
                            ;; here by :type so cell/stage declarations
                            ;; (more-specific) win over pipeline ones on
                            ;; collision but un-overridden pipeline tools
                            ;; survive. Without this merge, a pipeline
                            ;; `tools { gradle 'X' }` combined with a
                            ;; matrix `tools { jdk \"${V}\" }` would drop
                            ;; gradle from cells — losing the composition
                            ;; rule's outer-as-base half.
                            stage-tools (:tools stage)
                            eff-tools (cond
                                        (empty? stage-tools)
                                        top-tools

                                        (empty? top-tools)
                                        stage-tools

                                        :else
                                        (let [by-type (reduce (fn [m t]
                                                                (assoc m (:type t) t))
                                                              {}
                                                              (concat top-tools stage-tools))]
                                          (vec (vals by-type))))
                            ;; AN8-3: matrix-expanded cells carry :matrix-axes
                            ;; (the cell's axis-name→value map). Surfacing it
                            ;; on the synthetic stage-enter step lets the
                            ;; dispatcher interpolate `${JAVA_VERSION}` in
                            ;; tool versions before the AN8-1 image lookup.
                            axes (:matrix-axes stage)
                            stage-name (:name stage)
                            enter (cond-> {:type :jenkins/agent-stage-enter
                                           :stage stage-name
                                           :agent eff}
                                    (seq eff-tools) (assoc :tools eff-tools)
                                    (seq axes) (assoc :matrix-axes axes))
                            leave {:type :jenkins/agent-stage-leave
                                   :stage stage-name
                                   :agent eff}]
                        (update stage :steps
                                (fn [steps]
                                  (vec (concat
                                        [enter]
                                        (or steps [])
                                        [leave]))))))
                    stages)))))
