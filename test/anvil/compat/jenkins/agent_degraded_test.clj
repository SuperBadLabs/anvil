(ns anvil.compat.jenkins.agent-degraded-test
  "AN4-2: unhonored container agent shapes must emit :agent/degraded so
   the AN4-1 classifier reclassifies the build as :unsupported instead
   of silently SUCCEEDing.

   Honor table this test locks down:

     mode             | docker        | dockerfile    | kubernetes
     -----------------+---------------+---------------+-------------
     :execute? true   | honored       | UNHONORED     | UNHONORED
     :execute? false  | UNHONORED     | UNHONORED     | UNHONORED

   Honored agents emit NO :agent/degraded effect. Unhonored ones emit
   one whose :requested-agent.type the classifier reads as the shape
   that was bypassed."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.classification :as classify]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.tools.dockerfile]
            [anvil.features]))

(defn- agent-stage-enter [dispatcher agent-spec]
  (d/dispatch dispatcher
              {:type :jenkins/agent-stage-enter
               :stage "Build"
               :agent agent-spec}
              {}))

(defn- degraded-effects [dispatcher]
  (filter #(= :agent/degraded (first %)) @(:effects dispatcher)))

;; ---------------------------------------------------------------------------
;; Unhonored shapes — always emit :agent/degraded
;; ---------------------------------------------------------------------------

(deftest dockerfile-always-unhonored
  (testing ":execute? true — dockerfile has no runtime in anvil v0.3"
    (let [d (ad/make {:execute? true})]
      (agent-stage-enter d {:dockerfile {:filename "Dockerfile.ci"}})
      (let [degraded (degraded-effects d)]
        (is (= 1 (count degraded)))
        (is (= "dockerfile" (get-in (second (first degraded))
                                     [:requested-agent :type]))))))
  (testing ":execute? false — same"
    (let [d (ad/make {:execute? false})]
      (agent-stage-enter d {:dockerfile {:filename "Dockerfile"}})
      (is (= 1 (count (degraded-effects d)))))))

(deftest kubernetes-unhonored-when-flag-off
  ;; v0.6 T1: kubernetes is honored when :k8s-agent flag is on AND
  ;; the parsed IR carries an :image. With the flag off, it degrades.
  (anvil.features/set! :k8s-agent false)
  (try
    (let [d (ad/make {:execute? true})]
      (agent-stage-enter d {:kubernetes {:image "busybox:1.36"
                                         :raw-form :yaml}})
      (let [degraded (degraded-effects d)]
        (is (= 1 (count degraded)))
        (is (= "kubernetes" (get-in (second (first degraded))
                                    [:requested-agent :type])))))
    (finally
      (anvil.features/set! :k8s-agent true))))

(deftest kubernetes-unhonored-when-no-image
  ;; Even with the flag on, an empty `kubernetes { }` body (no
  ;; image extractable from yaml or containerTemplate) degrades.
  (anvil.features/set! :k8s-agent true)
  (let [d (ad/make {:execute? true})]
    (agent-stage-enter d {:kubernetes {:raw-form :unknown}})
    (let [degraded (degraded-effects d)]
      (is (= 1 (count degraded)))
      (is (= "kubernetes" (get-in (second (first degraded))
                                  [:requested-agent :type]))))))

(deftest kubernetes-honored-when-flag-on-and-image-extractable
  ;; v0.6 T1.1: with the :k8s-agent flag on AND the IR carrying an image,
  ;; the dispatcher honors the spec and emits NO :agent/degraded. The
  ;; backend-wiring constructs a K8sBackend at execute-via-backend time.
  (anvil.features/set! :k8s-agent true)
  (let [d (ad/make {:execute? true})]
    (agent-stage-enter d {:kubernetes {:image "busybox:1.36"
                                       :raw-form :yaml}})
    (is (empty? (degraded-effects d))
        "k8s with :k8s-agent flag on + image must NOT emit :agent/degraded")))

(deftest docker-unhonored-in-record-only-mode
  (testing "In record-only mode (:execute? false) docker agents are
            bypassed and must be reported as such"
    (let [d (ad/make {:execute? false})]
      (agent-stage-enter d {:docker {:image "maven:3.9"}})
      (let [degraded (degraded-effects d)]
        (is (= 1 (count degraded)))
        (is (= "docker" (get-in (second (first degraded))
                                 [:requested-agent :type])))))))

;; ---------------------------------------------------------------------------
;; Honored shapes — no :agent/degraded
;; ---------------------------------------------------------------------------

(deftest docker-honored-in-execute-mode
  (testing "In execute mode docker agents run via build-docker-args,
            so no :agent/degraded effect"
    (let [d (ad/make {:execute? true})]
      (agent-stage-enter d {:docker {:image "maven:3.9"}})
      (is (empty? (degraded-effects d))))))

(deftest any-agent-never-unhonored
  (let [d (ad/make {:execute? true})]
    (agent-stage-enter d {:type :any})
    (is (empty? (degraded-effects d)))))

(deftest none-agent-never-unhonored
  (let [d (ad/make {:execute? true})]
    (agent-stage-enter d {:type :none})
    (is (empty? (degraded-effects d)))))

;; ---------------------------------------------------------------------------
;; Composition with AN4-1 classifier
;; ---------------------------------------------------------------------------

(deftest dockerfile-stage-classifies-as-unsupported-end-to-end
  (testing "A pipeline whose only stage is agent { dockerfile … } must
            classify as :unsupported, NOT :neutral or :success"
    (let [d (ad/make {:execute? true})]
      (agent-stage-enter d {:dockerfile {:filename "Dockerfile"}})
      (d/dispatch d {:type :jenkins/sh :script "make"} {})
      (let [c (classify/classify-build {:status :ok}
                                       @(:effects d) {})]
        (is (= :unsupported (:result c)))
        (is (= :agent-unhonored (:rule c)))))))

(deftest k8s-stage-classifies-as-unsupported-when-flag-off
  ;; v0.6 T1: kubernetes still classifies as :unsupported when the
  ;; :k8s-agent flag is off (or the IR doesn't carry an image). The
  ;; honesty contract is preserved — the build doesn't fake-green
  ;; just because we parsed the agent block.
  (anvil.features/set! :k8s-agent false)
  (try
    (let [d (ad/make {:execute? true})]
      (agent-stage-enter d {:kubernetes {:image "busybox:1.36"
                                         :raw-form :yaml}})
      (d/dispatch d {:type :jenkins/sh :script "make"} {})
      (let [c (classify/classify-build {:status :ok}
                                       @(:effects d) {})]
        (is (= :unsupported (:result c)))))
    (finally
      (anvil.features/set! :k8s-agent true))))

(deftest record-only-docker-stage-classifies-as-unsupported-even-with-recorded-sh
  ;; Record-only mode: docker is unhonored, so the recorded :sh effect
  ;; doesn't redeem the build — :unsupported still wins on rule
  ;; precedence (unsupported-construct precedes step-nonzero-exit /
  ;; default-success). The PR-review notes a previous version of this
  ;; test named it "execute-mode" while the body used :execute? false;
  ;; this is the renamed honest test, paired with its inverse below.
  (let [d (ad/make {:execute? false})]
    (agent-stage-enter d {:docker {:image "x"}})
    (swap! (:effects d) conj
           [:sh {:cmd "make" :cwd "/workspace" :exit 0
                 :streamed? false :stdout-bytes 0 :stderr-bytes 0}])
    (let [c (classify/classify-build {:status :ok} @(:effects d) {})]
      (is (= :unsupported (:result c))
          "record-only docker stage must be honest about silent skip"))))

(deftest execute-mode-docker-stage-with-recorded-sh-classifies-as-success
  ;; The honest execute-mode inverse: docker IS honored, no
  ;; :agent/degraded is emitted, the recorded :sh drives :success.
  (let [d (ad/make {:execute? true})]
    (agent-stage-enter d {:docker {:image "x"}})
    (swap! (:effects d) conj
           [:sh {:cmd "make" :cwd "/workspace" :exit 0
                 :streamed? false :stdout-bytes 0 :stderr-bytes 0}])
    (is (empty? (degraded-effects d))
        ":execute? true docker agent must NOT emit :agent/degraded")
    (let [c (classify/classify-build {:status :ok} @(:effects d) {})]
      (is (= :success (:result c))))))

;; ---------------------------------------------------------------------------
;; v0.4 AN6-3 — dockerfile-agent honored when feature flag is on
;; ---------------------------------------------------------------------------

(deftest dockerfile-honored-when-an6-3-flag-on
  (testing "with :anvil.features/dockerfile-agent ON + :execute? true,
            the agent-stage-enter handler builds (or caches) the image
            via anvil.tools.dockerfile and upgrades ctx :active-agent
            to a docker shape — no :agent/degraded emitted"
    ;; Force flag on for this test.
    ((requiring-resolve 'anvil.features/set!) :dockerfile-agent true)
    (try
      (with-redefs
        [;; Stub ensure-image! to return a deterministic 'cached' tag
         ;; without invoking the docker daemon.
         anvil.tools.dockerfile/ensure-image!
         (fn [_workspace _filename _opts]
           {:tag "anvil-dockerfile:0123456789abcdef"
            :exit 0 :cached? true})]
        (let [d (ad/make {:execute? true})
              ;; Use the workspace-bearing ctx the build pipeline supplies.
              r (chengis.engine.dispatcher/dispatch
                 d
                 {:type :jenkins/agent-stage-enter
                  :stage "Build"
                  :agent {:dockerfile {:filename "Dockerfile.ci"}}}
                 {:workspace "/tmp/anvil-dockerfile-test-ws"})]
          (testing "no :agent/degraded for the dockerfile shape"
            (is (empty? (filter #(= "dockerfile"
                                    (get-in (second %) [:requested-agent :type]))
                                (degraded-effects d)))))
          (testing "a :dockerfile/image-cached or image-built effect was logged"
            (let [df-effects (filter #(#{:dockerfile/image-cached
                                         :dockerfile/image-built} (first %))
                                     @(:effects d))]
              (is (= 1 (count df-effects)))
              (is (= "anvil-dockerfile:0123456789abcdef"
                     (-> df-effects first second :tag)))))
          (testing "ctx :active-agent upgraded to docker shape — AN5-3 will route h-sh"
            (is (= "anvil-dockerfile:0123456789abcdef"
                   (get-in (:ctx r) [:active-agent :docker :image]))))))
      (finally
        ((requiring-resolve 'anvil.features/set!) :dockerfile-agent false)))))

;; ---------------------------------------------------------------------------
;; v0.6 T3 — multi-stage dockerfile-agent (`--target`)
;; ---------------------------------------------------------------------------

(deftest dockerfile-multistage-target-forwarded-to-ensure-image
  (testing "with both :dockerfile-agent + :dockerfile-multistage ON, the
            dockerfile spec's :target is forwarded to ensure-image! opts"
    ((requiring-resolve 'anvil.features/set!) :dockerfile-agent true)
    ((requiring-resolve 'anvil.features/set!) :dockerfile-multistage true)
    (try
      (let [captured-opts (atom nil)]
        (with-redefs
          [anvil.tools.dockerfile/ensure-image!
           (fn [_ws _filename opts]
             (reset! captured-opts opts)
             {:tag "anvil-dockerfile:cafebabefeedface"
              :exit 0 :cached? false :duration-ms 1234 :target (:target opts)})]
          (let [d (ad/make {:execute? true})]
            (chengis.engine.dispatcher/dispatch
             d
             {:type :jenkins/agent-stage-enter
              :stage "Build"
              :agent {:dockerfile {:filename "Dockerfile"
                                   :target "prod"
                                   :dir "docker-build"}}}
             {:workspace "/tmp/anvil-multistage-ws"})
            (is (= "prod" (:target @captured-opts))
                "ensure-image! must receive the --target from the IR")
            (is (= "docker-build" (:dir @captured-opts))
                "ensure-image! must receive :dir for the build context")
            (is (true? (:execute? @captured-opts))))))
      (finally
        ((requiring-resolve 'anvil.features/set!) :dockerfile-agent false)
        ((requiring-resolve 'anvil.features/set!) :dockerfile-multistage false)))))

(deftest dockerfile-multistage-flag-off-drops-target
  (testing "with :dockerfile-multistage OFF, the dispatcher does NOT forward
            :target — preserves the v0.4 AN6-3 single-stage path"
    ((requiring-resolve 'anvil.features/set!) :dockerfile-agent true)
    ((requiring-resolve 'anvil.features/set!) :dockerfile-multistage false)
    (try
      (let [captured-opts (atom nil)]
        (with-redefs
          [anvil.tools.dockerfile/ensure-image!
           (fn [_ws _filename opts]
             (reset! captured-opts opts)
             {:tag "anvil-dockerfile:cafebabefeedface" :exit 0 :cached? true})]
          (let [d (ad/make {:execute? true})]
            (chengis.engine.dispatcher/dispatch
             d
             {:type :jenkins/agent-stage-enter
              :stage "Build"
              :agent {:dockerfile {:filename "Dockerfile" :target "prod"}}}
             {:workspace "/tmp/anvil-multistage-off-ws"})
            (is (nil? (:target @captured-opts))
                "multistage flag OFF → no --target forwarding"))))
      (finally
        ((requiring-resolve 'anvil.features/set!) :dockerfile-agent false)))))

(deftest dockerfile-built-event-published
  (testing "an honored dockerfile build emits :dockerfile-built on the per-build topic,
            with :cache-hit? reflecting the ensure-image! :cached? bool"
    ((requiring-resolve 'anvil.features/set!) :dockerfile-agent true)
    ((requiring-resolve 'anvil.features/set!) :dockerfile-multistage true)
    (try
      (let [bus       (requiring-resolve 'anvil.events.bus/subscribe!)
            unsub-all (requiring-resolve 'anvil.events.bus/unsubscribe-all!)
            topic-fn  (requiring-resolve 'anvil.events.topics/topic-build)
            received  (atom [])]
        (unsub-all)
        (bus (topic-fn "ci-build" 42) (fn [e] (swap! received conj e)))
        (with-redefs
          [anvil.tools.dockerfile/ensure-image!
           (fn [_ws _filename opts]
             {:tag (str "anvil-dockerfile:abc-" (or (:target opts) "<none>"))
              :exit 0 :cached? false :duration-ms 250
              :target (:target opts)})]
          (let [d (ad/make {:execute? true})]
            (chengis.engine.dispatcher/dispatch
             d
             {:type :jenkins/agent-stage-enter
              :stage "Build"
              :agent {:dockerfile {:filename "Dockerfile" :target "prod"}}}
             {:workspace "/tmp/anvil-multistage-evt-ws"
              :job-name "ci-build"
              :build-number 42})))
        (let [df-events (filter #(= :dockerfile-built (:type %)) @received)]
          (is (= 1 (count df-events))
              "exactly one :dockerfile-built event on the per-build topic")
          (let [e (first df-events)]
            (is (= "ci-build" (:job-name e)))
            (is (= 42 (:build-number e)))
            (is (= "Dockerfile" (:dockerfile-path e)))
            (is (= "prod" (:target e)))
            (is (= "anvil-dockerfile:abc-prod" (:image-tag e)))
            (is (false? (:cache-hit? e))
                ":cached? false → :cache-hit? false")
            (is (integer? (:duration-ms e)))))
        (unsub-all))
      (finally
        ((requiring-resolve 'anvil.features/set!) :dockerfile-agent false)
        ((requiring-resolve 'anvil.features/set!) :dockerfile-multistage false)))))

(deftest dockerfile-built-event-cache-hit-flag
  (testing "ensure-image! cached? true → emitted event carries :cache-hit? true"
    ((requiring-resolve 'anvil.features/set!) :dockerfile-agent true)
    ((requiring-resolve 'anvil.features/set!) :dockerfile-multistage true)
    (try
      (let [bus       (requiring-resolve 'anvil.events.bus/subscribe!)
            unsub-all (requiring-resolve 'anvil.events.bus/unsubscribe-all!)
            topic-fn  (requiring-resolve 'anvil.events.topics/topic-build)
            received  (atom [])]
        (unsub-all)
        (bus (topic-fn "ci-build" 7) (fn [e] (swap! received conj e)))
        (with-redefs
          [anvil.tools.dockerfile/ensure-image!
           (fn [_ws _filename _opts]
             {:tag "anvil-dockerfile:cached-hit" :exit 0 :cached? true
              :duration-ms 1})]
          (let [d (ad/make {:execute? true})]
            (chengis.engine.dispatcher/dispatch
             d
             {:type :jenkins/agent-stage-enter
              :stage "Build"
              :agent {:dockerfile {:filename "Dockerfile" :target "builder"}}}
             {:workspace "/tmp/anvil-multistage-hit-ws"
              :job-name "ci-build"
              :build-number 7})))
        (let [e (first (filter #(= :dockerfile-built (:type %)) @received))]
          (is (some? e))
          (is (true? (:cache-hit? e)))
          (is (= "builder" (:target e))))
        (unsub-all))
      (finally
        ((requiring-resolve 'anvil.features/set!) :dockerfile-agent false)
        ((requiring-resolve 'anvil.features/set!) :dockerfile-multistage false)))))
