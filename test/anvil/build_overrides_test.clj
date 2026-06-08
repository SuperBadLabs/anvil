(ns anvil.build-overrides-test
  "AN7-5b — verify the operator-side build override loader. Tests mock
   `anvil.config/load-edn` directly rather than wrangling env vars,
   which keeps the test isolated from the real anvil.edn on disk.

   v0.6 T4 adds hot-reload tests at the bottom — these DO touch the
   filesystem (a tempdir is created + an anvil.edn is written +
   modified) because that's the whole point of the file-watch path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [anvil.config :as config]
            [anvil.build-overrides :as bo])
  (:import [java.io File]))

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

;; ---------------------------------------------------------------------------
;; v0.6 T4 — hot-reload watcher
;; ---------------------------------------------------------------------------
;;
;; These tests touch the filesystem (real tempdir, real anvil.edn file,
;; real WatchService). WatchService events on Linux can take >1s to
;; deliver under load — we poll for `clear-cache!` to be observed
;; rather than asserting on a timer.

(defn- mktmpdir! []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "anvil-bo-watch-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn- rmtree! [^File f]
  (when (and f (.exists f))
    (when (.isDirectory f)
      (doseq [^File c (.listFiles f)] (rmtree! c)))
    (.delete f)))

(defn- wait-for
  "Poll `pred` every 100 ms up to `timeout-ms`. Returns true if it
   becomes truthy in time, false otherwise."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 100) (recur))))))

(deftest start-watcher-noop-when-no-on-disk-anvil-edn
  (bo/stop-watcher!)
  (with-redefs [bo/resolve-anvil-edn-path (constantly nil)]
    (is (false? (bo/start-watcher!))
        "no file to watch → no-op return false")
    (is (false? (bo/watcher-running?))
        "no thread spawned"))
  (bo/stop-watcher!))

(deftest start-watcher-attaches-and-detaches
  (bo/stop-watcher!)
  (let [d (mktmpdir!)
        f (io/file d "anvil.edn")]
    (try
      (spit f (pr-str {:anvil.build-overrides {"a" {:env-extra {"X" "1"}}}}))
      (with-redefs [bo/resolve-anvil-edn-path (constantly f)]
        (is (true? (bo/start-watcher!)) "first start succeeds")
        (is (true? (bo/watcher-running?)))
        (is (false? (bo/start-watcher!)) "second start no-ops"))
      (is (true? (bo/stop-watcher!)) "stop returns true when running")
      (is (false? (bo/watcher-running?)))
      (is (nil? (bo/stop-watcher!)) "stop is idempotent — nil when already stopped")
      (finally
        (bo/stop-watcher!)
        (rmtree! d)))))

(deftest hot-reload-clears-cache-on-anvil-edn-change
  (bo/stop-watcher!)
  (bo/clear-cache!)
  (let [d (mktmpdir!)
        f (io/file d "anvil.edn")
        load-count (atom 0)]
    (try
      (spit f (pr-str {:anvil.build-overrides {"a" {:env-extra {"X" "1"}}}}))
      (with-redefs [bo/resolve-anvil-edn-path (constantly f)
                    config/load-edn (fn
                                      ([_] (swap! load-count inc)
                                       (read-string (slurp f)))
                                      ([_ _] (swap! load-count inc)
                                       (read-string (slurp f))))]
        (bo/start-watcher!)
        ;; Prime the cache.
        (is (= {"X" "1"} (:env-extra (bo/for-job "a"))))
        (is (= 1 @load-count))
        ;; Modify the file. Wait for the watcher to clear the cache.
        (spit f (pr-str {:anvil.build-overrides {"a" {:env-extra {"X" "2"}}}}))
        ;; The watcher should clear the cache; the next `for-job` reloads.
        ;; We can't observe clear-cache! directly; we observe via the next
        ;; for-job seeing the new value AND load-count incrementing.
        (is (wait-for #(let [v (bo/for-job "a")]
                         (= "2" (get (:env-extra v) "X")))
                      8000)
            "after anvil.edn modification, next for-job sees new value")
        (is (>= @load-count 2)
            "load-edn invoked again after the file change"))
      (finally
        (bo/stop-watcher!)
        (rmtree! d)))))
