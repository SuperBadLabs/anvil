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
            [anvil.compat.jenkins.dispatcher :as ad]))

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

(deftest kubernetes-always-unhonored
  (let [d (ad/make {:execute? true})]
    (agent-stage-enter d {:type :kubernetes :raw {:yaml "..."}})
    (let [degraded (degraded-effects d)]
      (is (= 1 (count degraded)))
      (is (= "kubernetes" (get-in (second (first degraded))
                                   [:requested-agent :type]))))))

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

(deftest k8s-stage-classifies-as-unsupported-end-to-end
  (let [d (ad/make {:execute? true})]
    (agent-stage-enter d {:type :kubernetes})
    (d/dispatch d {:type :jenkins/sh :script "make"} {})
    (let [c (classify/classify-build {:status :ok}
                                     @(:effects d) {})]
      (is (= :unsupported (:result c))))))

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
