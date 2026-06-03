(ns anvil.scheduler.cron-parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.scheduler.cron-parser :as cron])
  (:import [java.time ZonedDateTime ZoneId]))

(defn- zdt [s] (ZonedDateTime/parse s))

(deftest aliases-expand
  (let [ir (cron/parse "@daily" "job1")]
    (is (= #{0} (:minutes ir)))
    (is (= #{0} (:hours ir)))))

(deftest standard-cron-wildcards
  (let [ir (cron/parse "*/15 * * * *" "job1")]
    (is (= #{0 15 30 45} (:minutes ir)))
    (is (= 24 (count (:hours ir))))))

(deftest standard-cron-ranges-and-lists
  (let [ir (cron/parse "0 9-17 * * 1-5" "job1")]
    (is (= #{0} (:minutes ir)))
    (is (= #{9 10 11 12 13 14 15 16 17} (:hours ir)))
    (is (= #{1 2 3 4 5} (:dows ir)))))

(deftest list-syntax
  (let [ir (cron/parse "0,15,30,45 0,12 * * *" "job1")]
    (is (= #{0 15 30 45} (:minutes ir)))
    (is (= #{0 12} (:hours ir)))))

(deftest H-syntax-is-stable-per-key
  (let [a (cron/parse "H 0 * * *" "job-a")
        b (cron/parse "H 0 * * *" "job-b")
        a2 (cron/parse "H 0 * * *" "job-a")]
    (is (= a a2) "deterministic per key")
    (is (not= (:minutes a) (:minutes b)) "different jobs spread")
    (is (= 1 (count (:minutes a))))))

(deftest H-step-spreads-evenly
  (let [ir (cron/parse "H/15 * * * *" "job-1")]
    (is (= 4 (count (:minutes ir))) "H/15 = 4 fires/hour")
    (let [sorted (sort (:minutes ir))
          diffs (map - (rest sorted) sorted)]
      (is (every? #(= 15 %) diffs) "spaced exactly 15 minutes apart"))))

(deftest H-with-subrange
  (dotimes [_ 5]
    (let [ir (cron/parse "0 H(0-7) * * *" (str (rand-int 1000)))]
      (is (= 1 (count (:hours ir))))
      (let [h (first (:hours ir))]
        (is (and (>= h 0) (<= h 7)))))))

(deftest next-fire-handles-daily
  (let [ir (cron/parse "@daily" "job1")
        t (zdt "2026-06-03T15:30:00Z")
        next-t (cron/next-fire-after ir t)]
    (is (= 0 (.getHour next-t)))
    (is (= 0 (.getMinute next-t)))
    (is (= 4 (.getDayOfMonth next-t)))))

(deftest next-fire-handles-hourly
  (let [ir (cron/parse "@hourly" "job1")
        t (zdt "2026-06-03T15:30:00Z")
        next-t (cron/next-fire-after ir t)]
    (is (= 16 (.getHour next-t)))
    (is (= 0 (.getMinute next-t)))))

(deftest next-fire-with-15min-step
  (let [ir (cron/parse "*/15 * * * *" "job1")
        t (zdt "2026-06-03T15:07:00Z")
        next-t (cron/next-fire-after ir t)]
    (is (= 15 (.getMinute next-t)))))

(deftest day-of-week-fridays-only
  ;; 2026-06-03 is Wednesday → next Friday is 2026-06-05
  (let [ir (cron/parse "0 0 * * 5" "job1")
        t (zdt "2026-06-03T15:00:00Z")
        next-t (cron/next-fire-after ir t)]
    (is (= 5 (.getDayOfMonth next-t)))))

(deftest sunday-is-0
  (let [ir (cron/parse "0 0 * * 0" "job1")
        ;; 2026-06-03 Wed → next Sunday 2026-06-07
        t (zdt "2026-06-03T15:00:00Z")
        next-t (cron/next-fire-after ir t)]
    (is (= 7 (.getDayOfMonth next-t)))))

(deftest bad-syntax-throws
  (is (thrown? clojure.lang.ExceptionInfo (cron/parse "* * *" "job1"))))
