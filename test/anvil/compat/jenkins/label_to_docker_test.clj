(ns anvil.compat.jenkins.label-to-docker-test
  "AN5-3c — Lock down the label→docker bridge.

   The wild-corpus dirty-dozen uses `agent { label '...' }` (NOT
   `agent { docker { ... } }`). For those Jenkinsfiles to actually
   run in containers, anvil needs:

     agents.edn:  {\"ubuntu\" {:executor :docker :image \"X\"}}
           ↓
     resolve-label  → {:executor :docker :docker {:image \"X\"}}
           ↓
     h-agent-stage-enter sets ctx :active-agent {:docker {:image \"X\"}}
           ↓
     AN5-3b's backend-wiring sees :docker in :active-agent
           ↓
     chengis-core DockerBackend runs sh inside the container

   This test locks the bridge."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.agents.registry :as reg]))

;; ---------------------------------------------------------------------------
;; Registry resolution — :executor :docker carries :docker config
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (reg/reset-cache!)
    (try (f) (finally (reg/reset-cache!)))))

(deftest registry-local-executor-has-no-docker-config
  (testing ":executor :local entries don't get a :docker key"
    (let [resolved (reg/resolve-label "linux")]
      (is (= :local (:executor resolved)))
      (is (nil? (:docker resolved))
          ":local executor must NOT carry docker config"))))

(deftest registry-docker-executor-surfaces-image-from-flat-key
  (testing ":executor :docker with top-level :image → :docker {:image X}"
    ;; This shape: {:executor :docker :image "X"} — the flat-key style
    ;; the documentation pattern suggests.
    (with-redefs [reg/registry
                  (constantly
                   {:default {:executor :local :env {} :cwd "/tmp"}
                    :labels {"ubuntu" {:executor :docker
                                       :image "eclipse-temurin:21"}}})]
      (let [resolved (reg/resolve-label "ubuntu")]
        (is (= :docker (:executor resolved)))
        (is (= "eclipse-temurin:21" (-> resolved :docker :image)))))))

(deftest registry-docker-executor-surfaces-image-from-nested-key
  (testing ":executor :docker with nested :docker {:image X} → :docker {:image X}"
    (with-redefs [reg/registry
                  (constantly
                   {:default {:executor :local :env {} :cwd "/tmp"}
                    :labels {"ubuntu" {:executor :docker
                                       :docker {:image "eclipse-temurin:21"
                                                :args "--network host"}}}})]
      (let [resolved (reg/resolve-label "ubuntu")]
        (is (= :docker (:executor resolved)))
        (is (= "eclipse-temurin:21" (-> resolved :docker :image)))
        (is (= "--network host" (-> resolved :docker :args)))))))

(deftest registry-docker-executor-without-image-stays-vacuous
  (testing ":executor :docker without an :image key → no :docker config emitted"
    ;; Malformed entry — operator error. We don't synthesize a fake image;
    ;; the dispatcher falls through to the legacy path.
    (with-redefs [reg/registry
                  (constantly
                   {:default {:executor :local :env {} :cwd "/tmp"}
                    :labels {"broken" {:executor :docker}}})]
      (let [resolved (reg/resolve-label "broken")]
        (is (= :docker (:executor resolved))
            "executor field still surfaces, so downstream can warn")
        (is (nil? (:docker resolved))
            "no :docker config synthesized — downstream falls through")))))

;; ---------------------------------------------------------------------------
;; Default agents.edn keeps current behavior (no docker entries yet)
;; ---------------------------------------------------------------------------

(deftest default-agents-edn-has-no-docker-entries
  (testing "default registry uses :local for all labels — AN5-3c is the wire,
            not the populated registry. Adding docker labels is an operator
            concern (or a follow-up PR that ships a known-good set)."
    ;; Defensive: if a future PR adds docker entries, this test points at
    ;; the right place to also update the operator docs.
    (doseq [label (reg/known-labels)]
      (let [r (reg/resolve-label label)]
        (when (= :docker (:executor r))
          (is false (str "label " (pr-str label) " resolves to :docker — "
                         "remember to update docs/operator/agents.md")))))))