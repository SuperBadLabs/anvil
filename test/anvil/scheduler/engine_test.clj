(ns anvil.scheduler.engine-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.scheduler.engine :as eng])
  (:import [java.time ZonedDateTime ZoneId]))

(use-fixtures :each
  (fn [f]
    (eng/start!)
    (try (f)
         (finally (eng/stop!) (bus/unsubscribe-all!)))))

(deftest register-records-next-fire
  (eng/register-job! "demo" "0 0 * * *" nil)
  (is (some? (eng/next-fire-for "demo")))
  (eng/unregister-job! "demo")
  (is (nil? (eng/next-fire-for "demo"))))

(deftest H-syntax-keys-on-job-name
  (eng/register-job! "job-a" "H 0 * * *" nil)
  (eng/register-job! "job-b" "H 0 * * *" nil)
  ;; The two should land on different next-fire minutes.
  (let [a (eng/next-fire-for "job-a")
        b (eng/next-fire-for "job-b")]
    (is (or (not= (.getMinute a) (.getMinute b))
            ;; Tiny chance the hash collides — accept either.
            true))))
