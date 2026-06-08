(ns anvil.cost-test
  "v0.5 T0.5 — host-rate schema reader tests. Pure-fn level; the
   config loader is exercised by anvil.config-test."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.cost :as c]))

(deftest cost-for-zero-rate-returns-zero
  (testing "host with no declared rate → 0 cents regardless of wall-time"
    (let [r (c/cost-for "unknown-host" 60000)]
      (is (= 0 (:cents r)))
      (is (= 0.0 (:rate-per-min r)))
      (is (= :usd (:currency r))))))

(deftest cost-for-wall-ms-multiplies-cleanly
  (testing "1 minute @ $0.06/min = 6 cents (test config-free path via with-redefs)"
    (with-redefs [c/host-rate (constantly {:rate-per-min 0.06 :currency :usd})]
      (let [r (c/cost-for "heman" 60000)]
        (is (= 6 (:cents r))))))
  (testing "30s @ $0.10/min = 5 cents (rounds half-up)"
    (with-redefs [c/host-rate (constantly {:rate-per-min 0.10 :currency :usd})]
      (is (= 5 (:cents (c/cost-for "heman" 30000)))))))

(deftest total-cents-sums
  (testing "summing a sequence of cost maps"
    (is (= 0 (c/total-cents [])))
    (is (= 11 (c/total-cents [{:cents 3} {:cents 8}])))
    (is (= 100 (c/total-cents (repeat 25 {:cents 4}))))))
