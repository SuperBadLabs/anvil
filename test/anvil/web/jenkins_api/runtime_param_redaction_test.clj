(ns anvil.web.jenkins-api.runtime-param-redaction-test
  "v0.6.2 security — the [:parameters/defaults-applied] effect must
   NOT carry runtime-param VALUES into the effects vector. That vector
   is persisted to the build's DB row and rendered into /consoleText,
   so a leaked runtime param (a REST trigger of `{\"PASSWORD\":
   \"hunter2\"}`) lands on disk + log.

   Translator-defaults and operator-defaults are public Jenkinsfile /
   anvil.edn config — those values stay readable in the effect. Only
   runtime-param values flip to a `<provided>` / `<empty>` indicator;
   their names remain so operators can still audit which params were
   supplied.

   The dispatcher's `:secrets` masker covers values that flowed
   through `withCredentials` — trigger-time params bypass that masker,
   so the redaction has to happen at the effect-emission site."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.web.jenkins-api.runner :as runner]
            [anvil.features :as features]))

(use-fixtures :each
  (fn [f]
    (jobs/clear!)
    (queue/clear!)
    (try (f)
         (finally
           (queue/clear!)
           (jobs/clear!)))))

;; ---------------------------------------------------------------------------
;; Pure-fn unit tests for the redaction helpers
;; ---------------------------------------------------------------------------

(deftest redact-runtime-values-replaces-every-value
  (testing "non-empty string → <provided>"
    (is (= {"PASSWORD" "<provided>"}
           (runner/redact-runtime-values {"PASSWORD" "hunter2"}))))
  (testing "blank / nil → <empty>"
    (is (= {"X" "<empty>" "Y" "<empty>"}
           (runner/redact-runtime-values {"X" "" "Y" nil}))))
  (testing "names preserved across types of values"
    (let [out (runner/redact-runtime-values
               {"S" "secret" "N" 42 "B" true "M" {:inner "nope"}})]
      (is (= #{"S" "N" "B" "M"} (set (keys out))))
      (is (every? #{"<provided>" "<empty>"} (vals out))
          "every value reduces to one of two redaction tokens")))
  (testing "nil and non-map input → {}"
    (is (= {} (runner/redact-runtime-values nil)))
    (is (= {} (runner/redact-runtime-values {})))))

(deftest redact-runtime-values-never-emits-secret-literal
  (let [out (runner/redact-runtime-values
             {"PASSWORD" "hunter2"
              "API_TOKEN" "tok_abcdef"
              "PUBLIC" "ok-to-see"})  ; redaction is uniform: even non-secret runtime values redact
        rendered (pr-str out)]
    (is (not (re-find #"hunter2" rendered))
        "secret value MUST NOT appear in the redacted map's printed form")
    (is (not (re-find #"tok_abcdef" rendered)))
    (is (not (re-find #"ok-to-see" rendered))
        "even non-secret-looking values redact — we can't classify intent at this layer")
    (is (re-find #"PASSWORD" rendered)
        "name DOES appear so operators can audit which params were supplied")
    (is (re-find #"API_TOKEN" rendered))))

(deftest redact-effective-runtime-keys-only-redacts-runtime-keys
  (testing "key from translator-defaults retains its value"
    (is (= {"X" "translator-value"}
           (runner/redact-effective-runtime-keys
            {"X" "translator-value"}
            {}))
        "with no runtime keys, every effective value is public-shaped"))
  (testing "key from runtime is redacted; translator key stays"
    (is (= {"PASSWORD" "<provided>" "BUILD_MODE" "release"}
           (runner/redact-effective-runtime-keys
            {"PASSWORD" "hunter2" "BUILD_MODE" "release"}
            {"PASSWORD" "hunter2"}))))
  (testing "runtime key whose effective value is blank → <empty>"
    (is (= {"X" "<empty>"}
           (runner/redact-effective-runtime-keys
            {"X" ""}
            {"X" ""}))))
  (testing "nil inputs → {}"
    (is (= {} (runner/redact-effective-runtime-keys nil nil)))
    (is (= {} (runner/redact-effective-runtime-keys nil {"X" "y"})))))

;; ---------------------------------------------------------------------------
;; End-to-end — drive run-build! with a runtime PASSWORD and verify the
;; [:parameters/defaults-applied] effect does NOT carry the secret.
;; ---------------------------------------------------------------------------

(def ^:private with-password-jenkinsfile
  "pipeline {
     agent any
     parameters {
       string(name: 'BUILD_MODE', defaultValue: 'debug')
     }
     stages {
       stage('Noop') {
         steps {
           sh 'true'
         }
       }
     }
   }")

(defn- params-defaults-applied-effect
  "Pluck the single [:parameters/defaults-applied <payload>] effect
   from the build's effects vector. Returns the payload map (or nil
   if no such effect was emitted — i.e. the feature flag was off)."
  [build-result]
  ;; The effects vector is persisted on the job row; re-read from there.
  (let [job-name (or (:job-name build-result)
                     ;; Fallback for the smoke shape used by run-build!
                     ;; which returns :build-number but no :job-name.
                     "redact-smoke")
        n (:build-number build-result)
        build (jobs/find-build job-name n)
        effects (:effects build)]
    (some (fn [[tag payload]]
            (when (= :parameters/defaults-applied tag) payload))
          effects)))

(deftest run-build-redacts-runtime-param-values-in-effect
  (testing "runtime PASSWORD value does NOT appear in the effects vector"
    (with-redefs [features/enabled? (fn [k]
                                      (or (= k :parameters-defaults)
                                          false))]
      (jobs/register-job!
       {:name "redact-smoke"
        :jenkinsfile-source with-password-jenkinsfile})
      (let [result (runner/run-build!
                    "redact-smoke"
                    {:execute? true
                     :parameters {"PASSWORD" "hunter2"
                                  "BUILD_MODE" "release"}})
            payload (params-defaults-applied-effect
                     (assoc result :job-name "redact-smoke"))
            rendered (pr-str payload)]
        (is (some? payload)
            "effect [:parameters/defaults-applied …] must be present when feature on")
        ;; --- INVARIANT: runtime VALUE never appears anywhere in the payload ---
        (is (not (re-find #"hunter2" rendered))
            "secret runtime-param value MUST NOT appear in the effect payload")
        ;; --- The runtime NAME is preserved (operators can audit) ---
        (is (re-find #"PASSWORD" rendered)
            "runtime-param NAME is preserved so operators can audit which params were supplied")
        ;; --- Spot-check the structured shape ---
        (let [runtime (:runtime-params payload)]
          (is (= "<provided>" (get runtime "PASSWORD"))
              "PASSWORD value redacted to <provided>")
          (is (= "<provided>" (get runtime "BUILD_MODE"))
              "even non-secret runtime values redact uniformly"))
        ;; --- :effective also redacts runtime keys ---
        (let [eff (:effective payload)]
          (is (= "<provided>" (get eff "PASSWORD"))
              ":effective merges runtime + defaults; runtime-keyed values redact")
          (is (= "<provided>" (get eff "BUILD_MODE"))
              "BUILD_MODE was overridden by runtime → redacts"))))))
