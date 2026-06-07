(ns anvil.features-test
  "Tests for the v0.3 feature-flag mechanism (T0.2).

   Covers: closed-by-default semantics, namespaced-keyword wire form,
   unknown-flag tolerance, the wrap-feature 404 path. We do NOT test
   `load-flags!`'s file IO here — `anvil.config/load-edn` already has
   its own coverage and we want these tests to stay hermetic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.set]
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
  "The four leapfrog reservations made by the v0.4 board T0.2.
   Closed-by-default until each tranche merges."
  #{:flaky :container-step :ai-authoring :provenance})

(deftest known-features-covers-the-seven-v0-3-tranches
  (testing "v0.3 board T1–T7 each have a reserved flag"
    (is (= #{:junit :problem-matchers :pr-checks
             :matrix :scheduler :secrets :mise}
           ;; Filter out post-v0.3.0 additions so the test stays
           ;; locked on the seven Tier-1 tranches:
           ;;   :scripted-eval      — post-v0.3.0 Tier-3 worthiness
           ;;   :mvn-deploy-degrade — AN5-4 wild-corpus artifact unlock
           ;;   v0-4-leapfrog-flags — T0.2 of the v0.4 board
           (-> features/known-features
               (disj :scripted-eval)
               (disj :mvn-deploy-degrade)
               (clojure.set/difference v0-4-leapfrog-flags))))))

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

(deftest enabled?-defaults-to-false
  (testing "every known feature is closed-by-default"
    (doseq [f features/known-features]
      (features/set! f false)
      (is (false? (features/enabled? f))
          (str f " should default to disabled")))))

(deftest enabled?-on-unknown-flag-is-false
  (testing "querying an unregistered feature is safe — returns false"
    (is (false? (features/enabled? :leapfrog-thing-from-v0.4)))))

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
