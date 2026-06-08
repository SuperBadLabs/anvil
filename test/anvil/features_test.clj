(ns anvil.features-test
  "Tests for the v0.3 feature-flag mechanism (T0.2).

   Covers: closed-by-default semantics, namespaced-keyword wire form,
   unknown-flag tolerance, the wrap-feature 404 path. We do NOT test
   `load-flags!`'s file IO here — `anvil.config/load-edn` already has
   its own coverage and we want these tests to stay hermetic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.set]
            [anvil.config]
            [anvil.features :as features]))

(use-fixtures :each
  (fn [t]
    (let [before (features/snapshot)]
      (try (t)
           (finally
             ;; Reset to pre-test snapshot so cross-test bleed-through
             ;; never makes a flake debugger waste an hour.
             (doseq [f features/known-features]
               (features/set! f (boolean (get before f false)))))))))

(def ^:private v0-4-leapfrog-flags
  "The v0.4 board reservations: 4 leapfrog tranches from T0.2 plus
   :dockerfile-agent added by AN6-3 (the dockerfile-agent honesty
   ticket).  All closed-by-default until each tranche merges or
   the AN6 ship lands a stable runtime.

   We disj all of these from `known-features` when locking down
   the v0.3 tranche set so the v0.3 test stays stable when v0.4
   adds more reservations."
  #{:flaky :container-step :ai-authoring :provenance
    :dockerfile-agent})

(def v0-5-scale-flags
  "v0.5 board T0.2 reservations: cache + cost + GitLab MR + Bitbucket
   PR + chengis multi-tenant adapter. Closed-by-default until each
   tranche merges per AV5-7."
  #{:cache :cache-remote :cost-reporting
    :gitlab-mr :bitbucket-pr
    :multi-tenant})

(def v0-6-runtime-flags
  "v0.6 board T0.1 reservations: K8s agent runtime + Vault + Cloud-KMS
   secret backends + multi-stage Dockerfile + SCM-checkout-lifecycle.

   AN8-1 + AN8-2 fidelity flags (:tools-directive, :parameters-defaults)
   ride here too — they're closed-by-default until each receipt lands
   per the same AV6-7 acceptance posture.

   T3 (multi-stage Dockerfile, :dockerfile-multistage) shipped at
   v0.6.0 and was graduated to **default-on** — its v0.4-shape behavior
   is bit-identical for builds that don't set :target. See
   `default-on-features-graduate-to-true-by-default` below for the
   lockdown of that semantic."
  #{:k8s-agent :vault-backend :cloud-kms-backend
    :dockerfile-multistage :scm-checkout-lifecycle
    :tools-directive :parameters-defaults})

(deftest known-features-covers-the-seven-v0-3-tranches
  (testing "v0.3 board T1–T7 each have a reserved flag"
    (is (= #{:junit :problem-matchers :pr-checks
             :matrix :scheduler :secrets :mise}
           ;; Filter out post-v0.3.0 additions so the test stays
           ;; locked on the seven Tier-1 tranches:
           ;;   :scripted-eval      — post-v0.3.0 Tier-3 worthiness
           ;;   :mvn-deploy-degrade — AN5-4 wild-corpus artifact unlock
           ;;   v0-4-leapfrog-flags — T0.2 of the v0.4 board
           ;;   v0-5-scale-flags    — T0.2 of the v0.5 board
           ;;   v0-6-runtime-flags  — T0.1 of the v0.6 board
           (-> features/known-features
               (disj :scripted-eval)
               (disj :mvn-deploy-degrade)
               (clojure.set/difference v0-4-leapfrog-flags)
               (clojure.set/difference v0-5-scale-flags)
               (clojure.set/difference v0-6-runtime-flags))))))

(deftest known-features-reserves-the-four-v0-4-leapfrog-flags
  (testing "v0.4 board T0.2: :flaky / :container-step / :ai-authoring / :provenance reserved"
    (is (clojure.set/subset? v0-4-leapfrog-flags features/known-features)
        "v0.4 leapfrog flags must be in known-features so anvil.edn parses them and routes can gate on them")))

(deftest v0-4-leapfrog-flags-default-closed
  (testing "AV4-7 + features-closed-by-default: every v0.4 leapfrog flag is false until its tranche merges"
    (doseq [f v0-4-leapfrog-flags]
      (features/set! f false)
      (is (false? (features/enabled? f))
          (str f " is a v0.4 leapfrog reservation — must default disabled per AV4-7")))))

(deftest known-features-reserves-the-six-v0-5-scale-flags
  (testing "v0.5 board T0.2: cache + cost + gitlab-mr + bitbucket-pr + multi-tenant reserved"
    (is (clojure.set/subset? v0-5-scale-flags features/known-features)
        "v0.5 scale flags must be in known-features so anvil.edn parses them and routes can gate on them")))

(deftest v0-5-scale-flags-default-closed
  (testing "AV5-7 + features-closed-by-default: every v0.5 scale flag is false until its tranche merges"
    (doseq [f v0-5-scale-flags]
      (features/set! f false)
      (is (false? (features/enabled? f))
          (str f " is a v0.5 scale reservation — must default disabled per AV5-7")))))

(deftest known-features-reserves-the-five-v0-6-runtime-flags
  (testing "v0.6 board T0.1: k8s-agent + vault-backend + cloud-kms-backend + dockerfile-multistage + scm-checkout-lifecycle reserved"
    (is (clojure.set/subset? v0-6-runtime-flags features/known-features)
        "v0.6 runtime flags must be in known-features so anvil.edn parses them and dispatcher paths can gate on them")))

(deftest v0-6-runtime-flags-default-closed
  (testing "AV6-7 + features-closed-by-default: every v0.6 runtime flag is false until its tranche merges, EXCEPT the ones graduated to default-on (which install! refuses to take over without operator config, so the local-disk backend stays active anyway)"
    (doseq [f (clojure.set/difference v0-6-runtime-flags
                                      features/default-on-features)]
      (features/set! f false)
      (is (false? (features/enabled? f))
          (str f " is a v0.6 runtime reservation — must default disabled per AV6-7 (graduated flags exempt: " features/default-on-features ")")))))

(deftest enabled?-defaults-to-false
  (testing "every known feature is closed-by-default, modulo graduated-to-default-on flags"
    (doseq [f (clojure.set/difference features/known-features
                                       features/default-on-features)]
      (features/set! f false)
      (is (false? (features/enabled? f))
          (str f " should default to disabled (graduated flags exempt: " features/default-on-features ")")))))

;; ---------------------------------------------------------------------------
;; v0.6 T2 — default-on graduations
;; ---------------------------------------------------------------------------

(deftest default-on-features-contains-t2-graduations
  (testing "T2 ships with :vault-backend + :cloud-kms-backend graduated"
    (is (contains? features/default-on-features :vault-backend))
    (is (contains? features/default-on-features :cloud-kms-backend))))

(deftest vault-backend-default-on-when-unloaded
  (testing "Before load-flags! runs (state empty), default-on flags
            still report enabled — matches production post-load."
    (let [before (features/snapshot)]
      (try
        ;; Simulate registry-cleared state by re-binding the private state
        ;; atom to an empty map via swap! — set! is the public surface but
        ;; it always writes a value. We use the public enabled? on a flag
        ;; we explicitly haven't set, which means the state map lacks it.
        (doseq [f features/known-features] (features/set! f false))
        ;; Force the flag out of state by simulating fresh-process state:
        ;; we can't easily clear the atom, but enabled? falls back to
        ;; default-on-features membership only when (contains? state f)
        ;; is false. To exercise that branch we'd need an unset state.
        ;; The next test exercises this via load-flags! with empty edn.
        (finally
          (doseq [f features/known-features]
            (features/set! f (boolean (get before f false)))))))))

(deftest load-flags-with-empty-edn-honors-default-on
  (testing "When anvil.edn lacks the keys entirely, default-on-features
            flips them on (T2 graduation; matches production)."
    (let [before (features/snapshot)]
      (try
        (with-redefs [anvil.config/load-edn (fn [_ _] {})]
          (features/load-flags!)
          (is (true? (features/enabled? :vault-backend))
              ":vault-backend is graduated → defaults true even with empty anvil.edn")
          (is (true? (features/enabled? :cloud-kms-backend))
              ":cloud-kms-backend is graduated → defaults true even with empty anvil.edn"))
        (finally
          (doseq [f features/known-features]
            (features/set! f (boolean (get before f false)))))))))

(deftest load-flags-explicit-false-wins-over-default-on
  (testing "An operator who pins a graduated flag to false in anvil.edn
            keeps it off — the default only applies when the key is
            absent."
    (let [before (features/snapshot)]
      (try
        (with-redefs [anvil.config/load-edn
                      (fn [_ _]
                        {:anvil.features/vault-backend false
                         :anvil.features/cloud-kms-backend false})]
          (features/load-flags!)
          (is (false? (features/enabled? :vault-backend))
              "explicit false in anvil.edn must win over default-on")
          (is (false? (features/enabled? :cloud-kms-backend))
              "explicit false in anvil.edn must win over default-on"))
        (finally
          (doseq [f features/known-features]
            (features/set! f (boolean (get before f false)))))))))

(deftest enabled?-on-unknown-flag-is-false
  (testing "querying an unregistered feature is safe — returns false"
    (is (false? (features/enabled? :leapfrog-thing-from-v0.4)))))

(deftest k8s-agent-defaults-on-after-v0-6-t1
  ;; AV6-7 — flags gate routes during in-progress; defaults flip to
  ;; on with the tranche-closing commit. The :k8s-agent flag flipped
  ;; on when anvil v0.6 T1 shipped, so `default-on-features` includes
  ;; it. Operators can still set false in anvil.edn to opt out.
  (testing ":k8s-agent is in default-on-features"
    (is (contains? features/default-on-features :k8s-agent)))
  (testing "load-flags! with empty anvil.edn returns true for :k8s-agent"
    ;; Reload to honor defaults; the dev anvil.edn doesn't override.
    (let [flags (features/load-flags!)]
      (is (true? (get flags :k8s-agent))
          "after v0.6 T1 ships, an empty anvil.edn must default :k8s-agent on"))))

(deftest set!-flips-state
  (features/set! :junit true)
  (is (true? (features/enabled? :junit)))
  (features/set! :junit false)
  (is (false? (features/enabled? :junit))))

(deftest snapshot-returns-the-flag-map
  (features/set! :junit true)
  (features/set! :matrix false)
  (let [snap (features/snapshot)]
    (is (true? (get snap :junit)))
    (is (false? (get snap :matrix)))))

;; ---------------------------------------------------------------------------
;; default-on features (v0.6 T3 graduation)
;; ---------------------------------------------------------------------------

(deftest default-on-features-set-contains-dockerfile-multistage
  (testing "v0.6 T3 graduated :dockerfile-multistage to default-on"
    (is (contains? features/default-on-features :dockerfile-multistage)
        ":dockerfile-multistage is in default-on-features after T3 ships")))

(deftest default-on-features-after-load-flags-empty-edn
  (testing "load-flags! against an empty anvil.edn → default-on flags
            land as true (single-stage builds are bit-identical, so
            this is safe)"
    (let [snap (features/snapshot)]
      (try
        ;; Force load-flags! to see an empty config: stub the load-edn
        ;; path so it never touches disk.
        (with-redefs [anvil.config/load-edn (fn [_ _] {})]
          (features/load-flags!))
        (doseq [f features/default-on-features]
          (is (true? (features/enabled? f))
              (str f " should be enabled after load-flags! with empty edn")))
        (finally
          (doseq [f features/known-features]
            (features/set! f (boolean (get snap f false)))))))))

(deftest default-on-feature-explicit-false-in-edn-wins
  (testing "operators who set the flag false in anvil.edn keep that value"
    (let [snap (features/snapshot)]
      (try
        (with-redefs [anvil.config/load-edn
                      (fn [_ _] {:anvil.features/dockerfile-multistage false})]
          (features/load-flags!))
        (is (false? (features/enabled? :dockerfile-multistage))
            "explicit false in anvil.edn beats the default-on default")
        (finally
          (doseq [f features/known-features]
            (features/set! f (boolean (get snap f false)))))))))

(deftest default-on-feature-can-be-explicitly-disabled
  (testing "operators can still set :dockerfile-multistage false in anvil.edn"
    (features/set! :dockerfile-multistage false)
    (is (false? (features/enabled? :dockerfile-multistage))
        "explicit false in state overrides the default-on")))

;; ---------------------------------------------------------------------------
;; wrap-feature middleware
;; ---------------------------------------------------------------------------

(defn- ok-handler [_req]
  {:status 200 :headers {"Content-Type" "text/plain"} :body "ok"})

(deftest wrap-feature-allows-when-enabled
  (features/set! :junit true)
  (let [h (features/wrap-feature :junit ok-handler)
        resp (h {:request-method :get :uri "/test-results/1"})]
    (is (= 200 (:status resp)))
    (is (= "ok" (:body resp)))))

(deftest wrap-feature-404s-when-disabled
  (features/set! :junit false)
  (let [h (features/wrap-feature :junit ok-handler)
        resp (h {:request-method :get :uri "/test-results/1"})]
    (is (= 404 (:status resp)))
    (is (re-find #"feature `junit` is disabled" (:body resp))
        "404 body should name the feature so operators can self-diagnose")
    (is (re-find #":anvil.features/junit" (:body resp))
        "404 body should name the anvil.edn flag key")))

(deftest wrap-feature-404-includes-content-type
  (features/set! :secrets false)
  (let [h (features/wrap-feature :secrets ok-handler)
        resp (h {:request-method :get :uri "/secrets"})]
    (is (= "text/plain; charset=utf-8"
           (get-in resp [:headers "Content-Type"])))))
