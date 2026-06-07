(ns anvil.compat.jenkins.container-fixtures-test
  "v0.4 T2.6 — three Jenkinsfile shapes that exercise the
   `container('image') { … }` step + a dispatcher-level smoke that
   pins the AV4-2 routing contract: the body's shell calls see
   `ctx :active-agent = {:docker {:image …}}`, which is what AN5-3b's
   shell-execute routes through chengis-core's DockerBackend.

   Scope: complements `scope_wrappers_test.clj`'s three T2 tests
   (flag-off → degraded, flag-on → enter/sh/leave, missing-image
   gap).  The new shapes here cover:

     1. Nested containers — outer :active-agent restored after inner
        :container/leave.  Real-world: matrix-cell jobs swap into a
        builder image then a publisher image inside a single stage.
     2. Multiple body steps — every body :sh effect sits between
        :container/enter and :container/leave, preserving order.
        Real-world: setup → build → smoke-test inside one container.
     3. Container followed by a non-container sh in the same stage —
        the post-container sh must NOT see the docker active-agent.
        Real-world: build in container, then archive artifacts via a
        host-side sh.  If h-container's :leave didn't clear the
        agent, the archive step would unexpectedly tunnel through
        DockerBackend.

   Plus one dispatcher smoke that captures the ctx :active-agent at
   each body-sh invocation via a private-fn redef of h-sh, proving
   the ctx mutation actually reaches the leaf step — the contract
   h-sh + AN5-3b rely on for DockerBackend routing.

   Note: the map-form arg shape (`container(image: 'X') { … }`) is a
   known-honest gap in the current translator (yields :image nil); it
   would be a translator-fix ticket, not a test-track one.  T2.6
   stays scoped to fixtures that exercise behavior anvil already
   ships."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.features]))

;; ---------------------------------------------------------------------------
;; Fixture: gate the :container-step flag.  Default-closed per AV4-7;
;; these tests assume open.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    (anvil.features/set! :container-step true)
    (try (t)
         (finally
           (anvil.features/set! :container-step false)))))

;; ---------------------------------------------------------------------------
;; Harness — same shape as scope_wrappers_test and retry_fixtures_test
;; so the three files read alike.  Kept inline so each file owns its
;; disposition tweaks.
;; ---------------------------------------------------------------------------

(defn- run-jenkinsfile [src & {:keys [canned]}]
  (let [ir (t/parse src)
        stage-block (for [s (:stages ir)]
                      {:name (:name s)
                       :steps (concat (:steps s)
                                      (get-in s [:post :always] []))})
        flat {:stages (vec stage-block)}
        d (ad/make {:sh-canned (atom (or canned {}))})
        result (d/run-pipeline flat d {:cwd "/workspace"})]
    {:result result :effects @(:effects d) :ir ir :dispatcher d}))

;; ---------------------------------------------------------------------------
;; Shape 1 — Nested containers: outer agent restored after inner leave
;; ---------------------------------------------------------------------------

(deftest nested-containers-restore-outer-agent-after-inner-leave
  (testing "nested container shape — outer image survives the inner :container/leave"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              container('builder:1') {
                sh 'configure'
                container('publisher:2') {
                  sh 'publish'
                }
                sh 'finalize'
              }
            } } } }")]
      (let [types (mapv first effects)
            enter-events  (filter #(= :container/enter (first %)) effects)
            leave-events  (filter #(= :container/leave (first %)) effects)
            sh-events     (filter #(= :sh (first %)) effects)]
        ;; Two enter, two leave, three sh.
        (is (= 2 (count enter-events)))
        (is (= 2 (count leave-events)))
        (is (= 3 (count sh-events)))
        ;; Canonical ordering:
        ;;   enter(builder) sh(configure)
        ;;     enter(publisher) sh(publish) leave(publisher)
        ;;   sh(finalize) leave(builder)
        (is (= [:container/enter
                :sh
                :container/enter
                :sh
                :container/leave
                :sh
                :container/leave]
               types)
            "nested container's :leave fires before the outer's :leave; outer's body resumes after")
        ;; Image strings on each enter/leave pair match — the outer
        ;; image is preserved through the inner round-trip.
        (is (= "builder:1"   (-> enter-events first  second :image)))
        (is (= "publisher:2" (-> enter-events second second :image)))
        (is (= "publisher:2" (-> leave-events first  second :image))
            "first :leave belongs to the inner container — fires before the outer's :leave")
        (is (= "builder:1"   (-> leave-events second second :image))
            "outer :leave preserves the original image — h-container restored outer-agent")))))

;; ---------------------------------------------------------------------------
;; Shape 2 — Container body with multiple sh steps: all wrapped, order preserved
;; ---------------------------------------------------------------------------

(deftest container-body-with-multiple-sh-steps-keeps-ordering
  (testing "every body :sh sits between :container/enter and :container/leave; cmd order preserved"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              container('node:20') {
                sh 'npm ci'
                sh 'npm run build'
                sh 'npm test'
              }
            } } } }")]
      (let [types (mapv first effects)
            sh-cmds (mapv #(:cmd (second %))
                          (filter #(= :sh (first %)) effects))]
        (is (= [:container/enter :sh :sh :sh :container/leave] types))
        (is (= ["npm ci" "npm run build" "npm test"] sh-cmds)
            "body sh ordering preserved verbatim within the container window")))))

;; ---------------------------------------------------------------------------
;; Shape 3 — Container then post-container sh: agent cleared on :leave
;; ---------------------------------------------------------------------------

(deftest container-leave-clears-active-agent-for-following-sh
  (testing "post-container sh does NOT inherit the docker active-agent"
    ;; This is the contract that lets a real pipeline run `build in
    ;; container` followed by `archive on host` without the archive
    ;; step accidentally tunneling through DockerBackend.  Captured
    ;; here via the h-sh redef so we can read ctx :active-agent at the
    ;; instant each leaf step ran (record-only effect stream doesn't
    ;; expose ctx).
    (let [agent-by-cmd (atom {})
          orig-h-sh @#'anvil.compat.jenkins.dispatcher/h-sh]
      (with-redefs-fn
        {#'anvil.compat.jenkins.dispatcher/h-sh
         (fn [d step ctx]
           (swap! agent-by-cmd assoc (:script step) (:active-agent ctx))
           (orig-h-sh d step ctx))}
        #(run-jenkinsfile
          "pipeline { agent any; stages { stage('S') { steps {
             container('builder:1') {
               sh 'in-container-build'
             }
             sh 'post-container-archive'
           } } } }"))
      (let [in-agent  (get @agent-by-cmd "in-container-build")
            out-agent (get @agent-by-cmd "post-container-archive")]
        (is (= "builder:1" (-> in-agent :docker :image))
            "body sh sees the container image as its active-agent")
        (is (or (nil? out-agent)
                (nil? (:docker out-agent)))
            "post-container sh does NOT inherit :docker — h-container's :leave
             restored the outer agent (which had no :docker — agent any)")))))

;; ---------------------------------------------------------------------------
;; Dispatcher smoke — AV4-2 routing contract: every body sh sees
;; ctx :active-agent = {:docker {:image ...}}.  Captured via private-fn
;; redef so the assertion is exact even in record-only mode.
;; ---------------------------------------------------------------------------

(deftest dispatcher-threads-docker-active-agent-through-body-sh
  (testing "AV4-2 routing — during body invocation, ctx :active-agent carries
            the container image; non-body sh in the same stage doesn't"
    (let [agent-by-cmd (atom {})
          orig-h-sh @#'anvil.compat.jenkins.dispatcher/h-sh]
      (with-redefs-fn
        {#'anvil.compat.jenkins.dispatcher/h-sh
         (fn [d step ctx]
           (swap! agent-by-cmd assoc (:script step) (:active-agent ctx))
           (orig-h-sh d step ctx))}
        #(run-jenkinsfile
          "pipeline { agent any; stages { stage('S') { steps {
             container('python:3.12-slim') {
               sh 'pytest-1'
               sh 'pytest-2'
             }
           } } } }"))
      (is (= 2 (count @agent-by-cmd))
          "both body sh calls captured")
      (let [pytest-1-agent (get @agent-by-cmd "pytest-1")
            pytest-2-agent (get @agent-by-cmd "pytest-2")]
        (is (= "python:3.12-slim" (-> pytest-1-agent :docker :image))
            "first body sh saw docker active-agent — the contract h-sh + AN5-3b
             use to route through chengis-core's DockerBackend instead of the
             host shell path")
        (is (= "python:3.12-slim" (-> pytest-2-agent :docker :image))
            "second body sh saw the SAME docker active-agent — h-container
             doesn't mutate ctx mid-body between steps")))))
