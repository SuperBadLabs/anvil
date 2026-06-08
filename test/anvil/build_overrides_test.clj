(ns anvil.build-overrides-test
  "AN7-5b — verify the operator-side build override loader. Tests mock
   `anvil.config/load-edn` directly rather than wrangling env vars,
   which keeps the test isolated from the real anvil.edn on disk."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.config :as config]
            [anvil.build-overrides :as bo]))

(defn- with-anvil-edn [edn-map f]
  ;; Clear the cache so the next `for-job` call re-reads via load-edn.
  ;; with-redefs intercepts the load-edn call to return our test map.
  (bo/clear-cache!)
  (with-redefs [config/load-edn (fn ([_] edn-map) ([_ _] edn-map))]
    (try (f) (finally (bo/clear-cache!)))))

(use-fixtures :each
  (fn [t]
    ;; Belt-and-suspenders: clear cache before each test so test order
    ;; doesn't leak.
    (bo/clear-cache!)
    (t)
    (bo/clear-cache!)))

(deftest empty-map-when-no-overrides-key
  (with-anvil-edn {}
    (fn []
      (is (= {} (bo/all)))
      (is (nil? (bo/for-job "anything"))))))

(deftest empty-map-when-other-keys-only
  (with-anvil-edn {:other :stuff}
    (fn []
      (is (= {} (bo/all)))
      (is (nil? (bo/for-job "wild-apache-activemq"))))))

(deftest override-for-job-returns-full-shape
  (with-anvil-edn
    {:anvil.build-overrides
     {"wild-apache-activemq"
      {:docker-resource-limits {:memory-mb 4096 :cpus 2.0}
       :env-extra {"MAVEN_OPTS" "-Xmx2g"}}}}
    (fn []
      (let [o (bo/for-job "wild-apache-activemq")]
        (is (= {:memory-mb 4096 :cpus 2.0} (:docker-resource-limits o)))
        (is (= {"MAVEN_OPTS" "-Xmx2g"} (:env-extra o)))))))

(deftest override-absent-returns-nil
  (with-anvil-edn
    {:anvil.build-overrides
     {"wild-apache-activemq" {:env-extra {"X" "Y"}}}}
    (fn []
      (is (nil? (bo/for-job "unmatched-build"))))))

(deftest partial-shape-allowed
  (testing "env-extra-only — no resource-limits key"
    (with-anvil-edn
      {:anvil.build-overrides
       {"env-only" {:env-extra {"FOO" "bar"}}}}
      (fn []
        (let [o (bo/for-job "env-only")]
          (is (nil? (:docker-resource-limits o)))
          (is (= {"FOO" "bar"} (:env-extra o)))))))
  (testing "resource-limits-only — no env-extra key"
    (with-anvil-edn
      {:anvil.build-overrides
       {"limits-only" {:docker-resource-limits {:memory-mb 2048}}}}
      (fn []
        (let [o (bo/for-job "limits-only")]
          (is (= {:memory-mb 2048} (:docker-resource-limits o)))
          (is (nil? (:env-extra o))))))))

(deftest nil-job-name-returns-nil
  (with-anvil-edn
    {:anvil.build-overrides {"x" {:env-extra {}}}}
    (fn []
      (is (nil? (bo/for-job nil))))))

(deftest cache-survives-multiple-reads
  (let [call-count (atom 0)]
    (bo/clear-cache!)
    (with-redefs [config/load-edn (fn ([_] (swap! call-count inc)
                                       {:anvil.build-overrides
                                        {"a" {:env-extra {"K" "V"}}}})
                                  ([_ _] (swap! call-count inc)
                                         {:anvil.build-overrides
                                          {"a" {:env-extra {"K" "V"}}}}))]
      (is (= {"K" "V"} (:env-extra (bo/for-job "a"))))
      (is (= {"K" "V"} (:env-extra (bo/for-job "a"))))
      (is (= {"K" "V"} (:env-extra (bo/for-job "a"))))
      (is (= 1 @call-count)
          "load-edn called only once across multiple for-job reads"))
    (bo/clear-cache!)))

(deftest clear-cache-forces-reload
  (let [call-count (atom 0)]
    (bo/clear-cache!)
    (with-redefs [config/load-edn (fn ([_] (swap! call-count inc) {})
                                  ([_ _] (swap! call-count inc) {}))]
      (bo/all)
      (bo/all)
      (is (= 1 @call-count))
      (bo/clear-cache!)
      (bo/all)
      (is (= 2 @call-count)
          "clear-cache! forces a fresh load-edn call"))
    (bo/clear-cache!)))
