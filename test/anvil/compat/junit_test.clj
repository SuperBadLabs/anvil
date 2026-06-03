(ns anvil.compat.junit-test
  "Tests for the surefire JUnit XML parser (T1.1 / T1.7 partial — the
   golden corpus tests).

   Four fixtures, each a real-world dialect:
     - junit4-surefire-2x.xml — classic Surefire 2.x / JUnit 4
     - junit5-testsuites-wrap.xml — JUnit 5 with <testsuites> wrap
     - testng.xml — TestNG with extra attrs (groups, hostname, package)
     - surefire-3x.xml — Surefire 3.x with <properties> + system-out

   The 'consistent IR' contract from the board T1.7:
     - status one of #{:passed :failed :errored :skipped}
     - test-id = '<classname>#<name>'
     - duration-ms is an integer
     - failure-msg/-type/-trace are nil for :passed/:skipped, non-nil
       for at least one of :failed/:errored (depending on emitter)"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [anvil.compat.junit :as junit]))

(defn- fixture-resource [name]
  (io/resource (str "junit-fixtures/" name)))

(defn- fixture-path [name]
  ;; resources/junit-fixtures lives under test/resources/ — Leiningen
  ;; doesn't put test resources on the resource path by default, so
  ;; we go via the filesystem path.
  (str "test/resources/junit-fixtures/" name))

;; ---------------------------------------------------------------------------
;; Per-dialect smoke
;; ---------------------------------------------------------------------------

(deftest junit4-surefire-2x-parses
  (let [{:keys [suites totals]} (junit/parse-surefire-xml
                                  (fixture-path "junit4-surefire-2x.xml"))
        s (first suites)]
    (is (= 1 (count suites)))
    (is (= "com.example.CalcTest" (:name s)))
    (is (= 5 (:tests s)))
    (is (= 2 (:passed s)))
    (is (= 1 (:failed s)))
    (is (= 1 (:errored s)))
    (is (= 1 (:skipped s)))
    (is (= 5 (:tests totals)))
    (testing "failure case carries message and trace"
      (let [fail (first (filter #(= :failed (:status %)) (:cases s)))]
        (is (= "com.example.CalcTest#dividesByZeroFails" (:test-id fail)))
        (is (= "expected ArithmeticException but got null"
               (:failure-msg fail)))
        (is (= "java.lang.AssertionError" (:failure-type fail)))
        (is (re-find #"CalcTest.java:42" (:failure-trace fail)))))
    (testing "errored case is distinct from failed"
      (let [err (first (filter #(= :errored (:status %)) (:cases s)))]
        (is (= "com.example.CalcTest#overflowAtBoundary" (:test-id err)))
        (is (= "java.lang.NullPointerException" (:failure-type err)))))))

(deftest junit5-with-testsuites-wrap-parses-all-suites
  (let [{:keys [suites totals]} (junit/parse-surefire-xml
                                  (fixture-path "junit5-testsuites-wrap.xml"))]
    (is (= 2 (count suites))
        "the <testsuites> wrapper produces one IR entry per <testsuite> inside")
    (is (= 5 (:tests totals)))
    (is (= 3 (:passed totals)))
    (is (= 1 (:failed totals)))
    (is (= 1 (:skipped totals)))
    (testing "JUnit 5 emits <skipped message=…/> — we pick up the message"
      (let [skipped-case (first (filter #(= :skipped (:status %))
                                        (mapcat :cases suites)))]
        (is (= "not implemented yet" (:failure-msg skipped-case)))))
    (testing "JUnit 5 method names with () suffix survive"
      (is (some #(= "reverses_palindrome()" (:name %))
                (mapcat :cases suites))))))

(deftest testng-extra-attrs-ignored
  (let [{:keys [suites]} (junit/parse-surefire-xml
                           (fixture-path "testng.xml"))
        s (first suites)]
    (is (= "TestNG suite — integration" (:name s)))
    (is (= "ci-worker-3" (:hostname s)))
    (is (= 3 (:tests s)))
    (is (= 2 (:passed s)))
    (is (= 1 (:failed s)))
    (testing "TestNG groups attr is silently dropped"
      (is (not (contains? (first (:cases s)) :groups))))))

(deftest surefire-3x-properties-and-system-out-ignored
  (let [{:keys [suites]} (junit/parse-surefire-xml
                           (fixture-path "surefire-3x.xml"))
        s (first suites)]
    (is (= 6 (:tests s)))
    (is (= 6 (:passed s)))
    (is (= 0 (:failed s)))
    (testing "properties block does not pollute cases"
      (is (= 6 (count (:cases s)))))
    (testing "duration parsed even with all-passing suite"
      (is (= 2234 (:duration-ms s))))))

;; ---------------------------------------------------------------------------
;; Cross-dialect IR consistency (the board's T1.7 'consistent IR' clause)
;; ---------------------------------------------------------------------------

(deftest all-fixtures-produce-consistent-ir-shape
  (testing "every case in every fixture has the same keyset"
    (let [expected-keys #{:test-id :name :class :status :duration-ms
                          :failure-msg :failure-type :failure-trace}]
      (doseq [fx ["junit4-surefire-2x.xml"
                  "junit5-testsuites-wrap.xml"
                  "testng.xml"
                  "surefire-3x.xml"]]
        (let [{:keys [suites]} (junit/parse-surefire-xml (fixture-path fx))]
          (doseq [s suites
                  c (:cases s)]
            (is (= expected-keys (set (keys c)))
                (str fx " case " (:test-id c)
                     " has unexpected keys: " (set (keys c)))))))))
  (testing "every case status is in the agreed set"
    (let [allowed #{:passed :failed :errored :skipped}]
      (doseq [fx ["junit4-surefire-2x.xml"
                  "junit5-testsuites-wrap.xml"
                  "testng.xml"
                  "surefire-3x.xml"]]
        (let [{:keys [suites]} (junit/parse-surefire-xml (fixture-path fx))]
          (doseq [s suites c (:cases s)]
            (is (contains? allowed (:status c))
                (str (:test-id c) " has invalid status " (:status c)))))))))

(deftest passed-and-skipped-cases-have-nil-failure-fields
  (doseq [fx ["junit4-surefire-2x.xml"
              "junit5-testsuites-wrap.xml"
              "testng.xml"
              "surefire-3x.xml"]]
    (let [{:keys [suites]} (junit/parse-surefire-xml (fixture-path fx))]
      (doseq [s suites
              c (:cases s)
              :when (= :passed (:status c))]
        (is (nil? (:failure-msg c))
            (str ":passed case " (:test-id c) " carries failure-msg"))
        (is (nil? (:failure-trace c))
            (str ":passed case " (:test-id c) " carries failure-trace"))))))

(deftest failed-or-errored-cases-have-message-and-trace
  (let [{:keys [suites]} (junit/parse-surefire-xml
                           (fixture-path "junit4-surefire-2x.xml"))]
    (doseq [s suites
            c (:cases s)
            :when (#{:failed :errored} (:status c))]
      (is (some? (:failure-msg c))
          (str (:status c) " case " (:test-id c) " missing failure-msg"))
      (is (some? (:failure-trace c))
          (str (:status c) " case " (:test-id c) " missing failure-trace")))))

;; ---------------------------------------------------------------------------
;; Tree-level (multiple files) parsing — preview of T1.2's consumer
;; ---------------------------------------------------------------------------

(deftest parse-surefire-tree-aggregates-totals
  (let [all (junit/parse-surefire-tree
              [(fixture-path "junit4-surefire-2x.xml")
               (fixture-path "junit5-testsuites-wrap.xml")
               (fixture-path "testng.xml")
               (fixture-path "surefire-3x.xml")])]
    (testing "all four files contribute to totals"
      ;; 5 + 5 + 3 + 6 = 19 cases total
      (is (= 19 (get-in all [:totals :tests]))))
    (testing "passed = junit4 2 + junit5 3 + testng 2 + surefire 6 = 13"
      (is (= 13 (get-in all [:totals :passed]))))
    (testing "failed = junit4 1 + junit5 1 + testng 1 + surefire 0 = 3"
      (is (= 3 (get-in all [:totals :failed]))))
    (testing "no parse errors on the golden corpus"
      (is (empty? (:parse-errors all))))))

;; ---------------------------------------------------------------------------
;; Resilience: malformed input returns an empty result, not a throw
;; ---------------------------------------------------------------------------

(deftest malformed-xml-does-not-throw
  (let [tmp (java.io.File/createTempFile "anvil-junit-test-" ".xml")]
    (try
      (spit tmp "<this is not xml")
      (let [result (junit/parse-surefire-xml tmp)]
        (is (= [] (:suites result)))
        (is (= 0 (get-in result [:totals :tests])))
        (is (some? (:parse-error result))
            "the parse-error field is the producer's hint to the UI"))
      (finally (.delete tmp)))))

(deftest tree-collects-per-file-parse-errors
  (let [tmp (java.io.File/createTempFile "anvil-junit-test-" ".xml")]
    (try
      (spit tmp "<this is not xml")
      (let [all (junit/parse-surefire-tree
                  [(fixture-path "surefire-3x.xml")
                   (str tmp)
                   (fixture-path "junit4-surefire-2x.xml")])]
        (is (= 1 (count (:parse-errors all)))
            "one file failed; the other two contribute")
        (is (= 11 (get-in all [:totals :tests]))
            "surefire 6 + junit4 5 = 11 from the two clean files"))
      (finally (.delete tmp)))))
