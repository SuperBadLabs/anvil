(ns anvil.web.views.matrix-test
  "Tests for the v0.3 T4.5 matrix grid Hiccup view."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [anvil.web.views.matrix :as v]))

(defn- flatten-strings [hic]
  (cond
    (string? hic) hic
    (nil? hic) ""
    ;; Include attr values (esp. :href, :title) so URL assertions work.
    (map? hic)
    (str/join " " (map (fn [v] (cond
                                 (string? v) v
                                 (sequential? v) (flatten-strings v)
                                 :else (str v)))
                       (vals hic)))
    (sequential? hic) (str/join " " (map flatten-strings hic))
    :else (str hic)))

(deftest grid-empty-cells-renders-placeholder
  (let [hic (v/grid {:parent {:name "demo" :number 1}
                     :cells []
                     :axes [{:name "JDK" :values ["17" "21"]}]})
        text (flatten-strings hic)]
    (is (re-find #"no cell builds" text))))

(deftest grid-2x2-renders-each-cell
  (let [hic (v/grid
             {:parent {:name "demo" :number 1}
              :axes [{:name "JDK" :values ["17" "21"]}
                     {:name "OS"  :values ["linux" "windows"]}]
              :cells [{:axes {"JDK" "17" "OS" "linux"}   :build-number 10 :result :success}
                      {:axes {"JDK" "17" "OS" "windows"} :build-number 11 :result :failure}
                      {:axes {"JDK" "21" "OS" "linux"}   :build-number 12 :result :success}
                      {:axes {"JDK" "21" "OS" "windows"} :build-number 13 :result :success}]})
        text (flatten-strings hic)]
    (testing "grid header names both axes"
      (is (re-find #"JDK" text))
      (is (re-find #"OS" text)))
    (testing "every result keyword surfaces"
      (is (re-find #"failure" text))
      (is (re-find #"success" text)))
    (testing "each child build links resolve"
      (is (re-find #"/jobs/demo/10" text))
      (is (re-find #"/jobs/demo/13" text)))))

(deftest grid-with-3rd-axis-shows-extra-as-sub-label
  (let [hic (v/grid
             {:parent {:name "demo" :number 1}
              :axes [{:name "JDK" :values ["17" "21"]}
                     {:name "OS"  :values ["linux"]}
                     {:name "MAVEN" :values ["3.8" "3.9"]}]
              :cells [{:axes {"JDK" "17" "OS" "linux" "MAVEN" "3.8"} :build-number 10 :result :success}
                      {:axes {"JDK" "17" "OS" "linux" "MAVEN" "3.9"} :build-number 11 :result :success}
                      {:axes {"JDK" "21" "OS" "linux" "MAVEN" "3.8"} :build-number 12 :result :success}
                      {:axes {"JDK" "21" "OS" "linux" "MAVEN" "3.9"} :build-number 13 :result :failure}]})
        text (flatten-strings hic)]
    (testing "extra axis (MAVEN) shows as a per-cell sub-label"
      (is (re-find #"MAVEN=3.8" text))
      (is (re-find #"MAVEN=3.9" text)))))

(deftest grid-running-cell-rendered-distinctly
  (let [hic (v/grid
             {:parent {:name "demo" :number 1}
              :axes [{:name "JDK" :values ["17"]}]
              :cells [{:axes {"JDK" "17"} :build-number 10 :building? true}]})
        text (flatten-strings hic)]
    (is (re-find #"running" text))))
