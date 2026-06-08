(ns anvil.web.provenance-producer-test
  "v0.4.1 T4.5 — tests for the :provenance-attested SSE producer
   wired into anvil.web.jenkins-api.jobs/complete-build!.

   Hermetic — drives the producer code path by calling complete-build!
   with synthesized effects containing :provenance/attested entries,
   then asserts the per-build bus topic received the right events.
   Same shape as flaky_producer_test.clj."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.features :as features]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as jobs-persist]
            [clojure.java.io :as io]))

(def ^:private tmp-db-path
  (str (System/getProperty "java.io.tmpdir") "/anvil-provenance-producer-test.db"))

(defn- with-fresh-db [t]
  (db/close!)
  (when (.exists (io/file tmp-db-path)) (.delete (io/file tmp-db-path)))
  (db/init! tmp-db-path)
  (jobs/clear!)
  (bus/unsubscribe-all!)
  (try (t)
       (finally
         (jobs/clear!)
         (db/close!)
         (.delete (io/file tmp-db-path))
         (features/set! :provenance false)
         (bus/unsubscribe-all!))))

(use-fixtures :each with-fresh-db)

(defn- register-job! [name]
  (jobs-persist/upsert-job! {:name name :jenkinsfile-source "pipeline { agent any }"})
  (jobs/register-job! {:name name
                       :jenkinsfile-source "pipeline { agent any }"
                       :buildable? true
                       :max-concurrent-builds 1}))

;; ---------------------------------------------------------------------------
;; SSE producer
;; ---------------------------------------------------------------------------

(deftest provenance-attested-events-published-per-effect
  (features/set! :provenance true)
  (register-job! "demo")
  (jobs/record-build-start! "demo" {})
  (let [received (atom [])
        effects [[:archive {:artifacts "target/*.jar"}]
                 [:provenance/attested
                  {:path "/ws/target/a.jar" :sha256 "aaaa"
                   :bundle "/ws/target/a.jar.intoto.jsonl"
                   :cosign-version "v3.0.6"}]
                 [:provenance/attested
                  {:path "/ws/target/b.jar" :sha256 "bbbb"
                   :bundle "/ws/target/b.jar.intoto.jsonl"
                   :cosign-version "v3.0.6"}]]]
    (bus/subscribe! (topics/topic-build "demo" 1)
                    #(swap! received conj %))
    (jobs/record-build-end! "demo" 1
                            {:result :success
                             :effects effects
                             :console-log ""
                             :duration-ms 100})
    (let [prov-evts (filter #(= :provenance-attested (:type %)) @received)]
      (is (= 2 (count prov-evts))
          "one event per :provenance/attested effect")
      (let [first-evt (first prov-evts)]
        (is (= "demo" (:job-name first-evt)))
        (is (= 1 (:build-number first-evt)))
        (is (= "/ws/target/a.jar" (:path first-evt)))
        (is (= "aaaa" (:sha256 first-evt)))
        (is (= "/ws/target/a.jar.intoto.jsonl" (:bundle first-evt)))
        (is (= "v3.0.6" (:cosign-version first-evt)))))))

(deftest provenance-attested-publisher-silent-when-flag-off
  (features/set! :provenance false)
  (register-job! "demo")
  (jobs/record-build-start! "demo" {})
  (let [received (atom [])
        effects [[:provenance/attested
                  {:path "/ws/a.jar" :sha256 "aaaa"
                   :bundle "/ws/a.jar.intoto.jsonl"}]]]
    (bus/subscribe! (topics/topic-build "demo" 1)
                    #(swap! received conj %))
    (jobs/record-build-end! "demo" 1
                            {:result :success :effects effects
                             :console-log "" :duration-ms 100})
    (let [prov-evts (filter #(= :provenance-attested (:type %)) @received)]
      (is (empty? prov-evts)
          "flag off → no :provenance-attested events even if the effect
           is present (defensive: dispatcher itself is also flag-gated)"))))

(deftest provenance-producer-skips-degraded-effects
  (features/set! :provenance true)
  (register-job! "demo")
  (jobs/record-build-start! "demo" {})
  (let [received (atom [])
        effects [[:provenance/attested {:path "/ws/a.jar" :sha256 "aaaa"
                                        :bundle "/ws/a.jar.intoto.jsonl"}]
                 [:provenance/degraded {:reason :cosign-error :path "/ws/b.jar"
                                        :error "boom"}]]]
    (bus/subscribe! (topics/topic-build "demo" 1)
                    #(swap! received conj %))
    (jobs/record-build-end! "demo" 1
                            {:result :success :effects effects
                             :console-log "" :duration-ms 100})
    (let [prov-evts (filter #(= :provenance-attested (:type %)) @received)]
      (is (= 1 (count prov-evts))
          "only :provenance/attested becomes :provenance-attested events;
           :provenance/degraded does NOT publish — false-signal hygiene
           (same posture as T1.4: a failure is not a 'new signed artifact')"))))

(deftest provenance-producer-handles-builds-with-no-archive
  (features/set! :provenance true)
  (register-job! "demo")
  (jobs/record-build-start! "demo" {})
  (let [received (atom [])
        effects [[:sh {:cmd "make"}]
                 [:sh {:cmd "make test"}]]]
    (bus/subscribe! (topics/topic-build "demo" 1)
                    #(swap! received conj %))
    (jobs/record-build-end! "demo" 1
                            {:result :success :effects effects
                             :console-log "" :duration-ms 100})
    (let [prov-evts (filter #(= :provenance-attested (:type %)) @received)]
      (is (empty? prov-evts)
          "no archiveArtifacts in this build → no provenance events"))))

;; ---------------------------------------------------------------------------
;; Build page renders the pill end-to-end (T4.4 + T4.5 integration)
;; ---------------------------------------------------------------------------

(deftest build-page-renders-provenance-pill-when-flag-on
  (features/set! :provenance true)
  (register-job! "demo")
  (jobs/record-build-start! "demo" {})
  (jobs/record-build-end! "demo" 1
                          {:result :success
                           :effects [[:archive {:artifacts "target/*.jar"}]
                                     [:provenance/attested
                                      {:path "/ws/target/a.jar"
                                       :sha256 "aaaa"
                                       :bundle "/ws/target/a.jar.intoto.jsonl"
                                       :cosign-version "v3.0.6"}]]
                           :console-log "" :duration-ms 100})
  (let [build (jobs/find-build "demo" 1)
        build (assoc build :job-name "demo")
        pill (anvil.web.views.provenance-pill/pill build)
        html (str pill)]
    (is (some? pill) "pill rendered when flag on + effects present")
    (is (clojure.string/includes? html "attested"))
    (is (clojure.string/includes? html "1 artifact"))))

(deftest build-page-omits-pill-when-flag-off
  (features/set! :provenance false)
  (register-job! "demo")
  (jobs/record-build-start! "demo" {})
  (jobs/record-build-end! "demo" 1
                          {:result :success
                           :effects [[:provenance/attested
                                      {:path "/ws/a.jar" :sha256 "aaaa"
                                       :bundle "/ws/a.jar.intoto.jsonl"}]]
                           :console-log "" :duration-ms 100})
  (let [build (assoc (jobs/find-build "demo" 1) :job-name "demo")]
    (is (nil? (anvil.web.views.provenance-pill/pill build))
        "flag off → pill is nil regardless of effects content")))
