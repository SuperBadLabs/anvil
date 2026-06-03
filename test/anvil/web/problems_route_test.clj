(ns anvil.web.problems-route-test
  "Route-level smoke for the v0.3 T2.4 Problems panel + the T2.2 log-tail
   wiring (end-to-end through the bus → DB → ring view path).

   The dedicated etaoin browser test for the panel is covered by T8.3's
   page-tour refresh; route-level here keeps the default suite hermetic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [anvil.web.routes :as routes]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as jobs-persist]
            [anvil.storage.problems :as p-store]
            [anvil.features :as features]))

(def ^:private tmp-db-path
  (str (System/getProperty "java.io.tmpdir") "/anvil-problems-route-test.db"))

(defn- with-fresh-db [f]
  (db/close!)
  (when (.exists (io/file tmp-db-path)) (.delete (io/file tmp-db-path)))
  (db/init! tmp-db-path)
  (jobs/clear!)
  (try (f)
       (finally
         (jobs/clear!)
         (db/close!)
         (.delete (io/file tmp-db-path)))))

(use-fixtures :each with-fresh-db)

(defn- seed-build [job-name n]
  (jobs-persist/upsert-job! {:name job-name :jenkinsfile-source "pipeline {}"})
  (jobs/register-job! {:name job-name
                       :jenkinsfile-source "pipeline { agent any }"
                       :buildable? true
                       :max-concurrent-builds 1})
  (jobs/record-build-start! job-name {:parameters {}})
  (jobs/record-build-end! job-name n
                          {:result :success :effects []
                           :console-log "" :duration-ms 50}))

(defn- get-page [path]
  (let [h (routes/make-handler)]
    (h {:request-method :get :uri path})))

(deftest build-page-renders-problems-tab-when-flag-on-and-rows-exist
  (features/set! :problem-matchers true)
  (seed-build "demo" 1)
  (p-store/record-problem! "demo" 1 100
                           {:source "gcc" :severity :error
                            :file "src/main.c" :line 42 :column 7
                            :message "expected ';' before '}'"})
  (p-store/record-problem! "demo" 1 105
                           {:source "::workflow" :severity :warning
                            :file "lib/x.rs" :line 12 :column 3
                            :message "unused variable: `y`"})
  (let [resp (get-page "/jobs/demo/1")
        body (:body resp)]
    (is (= 200 (:status resp)))
    (testing "section anchor + summary pills"
      (is (str/includes? body "problems"))
      (is (str/includes? body "1 errors"))
      (is (str/includes? body "1 warnings")))
    (testing "per-problem rows include file:line:col"
      (is (str/includes? body "src/main.c:42:7"))
      (is (str/includes? body "lib/x.rs:12:3")))
    (testing "matcher source rendered alongside"
      (is (str/includes? body "gcc"))
      (is (str/includes? body "::workflow")))))

(deftest build-page-omits-problems-panel-when-flag-off
  (features/set! :problem-matchers false)
  (seed-build "demo" 1)
  (p-store/record-problem! "demo" 1 100
                           {:source "gcc" :severity :error
                            :file "x.c" :line 1 :message "boom"})
  (let [body (:body (get-page "/jobs/demo/1"))]
    (is (not (str/includes? body "errors")))
    (is (not (str/includes? body "expected")))))

(deftest log-tail-emit-line-matches-problems-and-persists
  (features/set! :problem-matchers true)
  (seed-build "demo" 1)
  ;; Reach into the private emit-line! to confirm the full flow:
  ;; bus publish AND DB persist for a matched line on the [:build job n] topic.
  (let [emit (var-get
              (or (resolve 'anvil.web.log-tail/emit-line!)
                  (ns-resolve 'anvil.web.log-tail 'emit-line!)))]
    (emit [:build "demo" 1] 7 :stdout
          "src/main.c:42:15: error: expected ';' before '}' token")
    (let [probs (p-store/find-problems "demo" 1)]
      (is (= 1 (count probs)))
      (is (= "gcc" (:source (first probs))))
      (is (= 42 (:line (first probs))))
      (is (= 7 (:log-seq (first probs)))))))

(deftest log-tail-does-not-persist-when-flag-off
  (features/set! :problem-matchers false)
  (seed-build "demo" 1)
  (let [emit (var-get
              (or (resolve 'anvil.web.log-tail/emit-line!)
                  (ns-resolve 'anvil.web.log-tail 'emit-line!)))]
    (emit [:build "demo" 1] 7 :stdout
          "src/main.c:42:15: error: expected ';' before '}' token")
    (is (empty? (p-store/find-problems "demo" 1)))))
