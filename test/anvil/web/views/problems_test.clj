(ns anvil.web.views.problems-test
  "Tests for the v0.3 T2.4 Problems-tab view."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [anvil.web.views.problems :as v]))

(defn- flatten-strings [hic]
  (cond
    (string? hic) hic
    (nil? hic) ""
    (map? hic) ""
    (sequential? hic) (str/join " " (map flatten-strings hic))
    :else (str hic)))

(deftest panel-returns-nil-when-no-problems-and-summary-all-zero
  (is (nil? (v/panel {:summary {:errors 0 :warnings 0 :notes 0 :infos 0}
                      :problems []})))
  (is (nil? (v/panel {:summary nil :problems []}))))

(deftest summary-pills-renders-each-severity
  (let [text (flatten-strings
              (v/summary-pills {:errors 3 :warnings 5 :notes 2 :infos 0}))]
    (is (re-find #"3 errors" text))
    (is (re-find #"5 warnings" text))
    (is (re-find #"2 notes" text))
    (testing "zero-infos suppresses the infos pill"
      (is (not (re-find #"0 infos" text))))))

(deftest problems-list-orders-errors-before-warnings
  (let [hic (v/problems-list
             [{:source "gcc" :severity :warning :file "a.c" :line 1
               :message "warn-msg" :log-seq 100}
              {:source "gcc" :severity :error :file "b.c" :line 2
               :message "err-msg" :log-seq 200}
              {:source "gcc" :severity :note :file "c.c" :line 3
               :message "note-msg" :log-seq 50}])
        text (flatten-strings hic)
        err-idx (.indexOf text "err-msg")
        warn-idx (.indexOf text "warn-msg")
        note-idx (.indexOf text "note-msg")]
    (is (< -1 err-idx warn-idx note-idx)
        (str "expected error→warning→note order, got "
             [err-idx warn-idx note-idx]))))

(deftest problem-row-renders-file-line-col
  (let [hic (v/problems-list
             [{:source "gcc" :severity :error :file "src/main.c"
               :line 42 :column 7 :message "msg" :log-seq 0}])
        text (flatten-strings hic)]
    (is (re-find #"src/main.c:42:7" text))))

(deftest panel-composes-summary-plus-list
  (let [hic (v/panel
             {:summary {:errors 1 :warnings 1 :notes 0 :infos 0}
              :problems [{:source "gcc" :severity :error :file "a.c"
                          :line 1 :message "boom" :log-seq 1}
                         {:source "gcc" :severity :warning :file "a.c"
                          :line 2 :message "meh" :log-seq 2}]})
        text (flatten-strings hic)]
    (is (= :section.problems (first hic)))
    (is (re-find #"1 errors" text))
    (is (re-find #"1 warnings" text))
    (is (re-find #"boom" text))
    (is (re-find #"meh" text))))
