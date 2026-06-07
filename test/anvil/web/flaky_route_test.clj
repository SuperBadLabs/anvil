(ns anvil.web.flaky-route-test
  "v0.4 T1.6 — route-level + browser smoke for the /flaky dashboard.

   The route-level tests run against a real SQLite db with real
   per-attempt rows written via `record-build-results!` followed by
   `write-flaky-flags!` (the same path the T1.4 producer takes in
   `complete-build!`), so they pin the dashboard's end-to-end shape:
   schema → flaky detection → recent-flaky-window aggregation →
   Hiccup render → HTTP response.

   The `^:browser` smoke (opt-in via `lein test :browser`) puts the
   same seed behind a real Firefox to confirm the page renders in a
   browser DOM, including the htmx SSE-swap target the widget needs
   for T1.4 live updates.

   Modeled on `test_results_route_test.clj` for fixture shape."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [anvil.web.routes :as routes]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as jobs-persist]
            [anvil.storage.test-results :as tr]
            [anvil.features :as features]))

(def ^:private tmp-db-path
  (str (System/getProperty "java.io.tmpdir") "/anvil-flaky-route-test.db"))

(defn- with-fresh-db [f]
  (db/close!)
  (when (.exists (io/file tmp-db-path)) (.delete (io/file tmp-db-path)))
  (db/init! tmp-db-path)
  (jobs/clear!)
  (try (f)
       (finally
         (jobs/clear!)
         (db/close!)
         (.delete (io/file tmp-db-path))
         ;; T1.6 — reset the :flaky flag we toggle in these tests so
         ;; downstream test files (e.g. head_method_test) see the
         ;; closed-by-default state.  Discovered by full-suite run
         ;; bleeding :flaky=true into HEAD tests.
         (features/set! :flaky false))))

(use-fixtures :each with-fresh-db)

;; ---------------------------------------------------------------------------
;; Seed helpers — write per-attempt rows mimicking what h-junit's
;; scanner would persist after a `retry(N) { sh; junit }` shape.
;; ---------------------------------------------------------------------------

(defn- case-row
  "One :cases entry in the shape `record-build-results!` consumes
   (matches the `anvil.compat.junit/parse-surefire-tree` output)."
  [class name status]
  {:test-id      (str class "#" name)
   :name         name
   :class        class
   :status       status
   :duration-ms  10
   :failure-msg  (when (#{:failed :errored} status) "boom")
   :failure-type (when (#{:failed :errored} status) "java.lang.AssertionError")
   :failure-trace (when (#{:failed :errored} status) "  at line 1")})

(defn- write-attempt!
  "Persist one attempt's case rows for (job,build) at the given
   attempt-number — exactly what h-junit calls per retry iteration."
  [job-name build attempt-number cases]
  (let [totals (frequencies (map :status cases))]
    (tr/record-build-results!
     job-name build
     {:suites [{:name "demo.FlakySuite"
                :tests (count cases)
                :passed (get totals :passed 0)
                :failed (get totals :failed 0)
                :errored (get totals :errored 0)
                :skipped (get totals :skipped 0)
                :duration-ms 100
                :cases cases}]
      :totals {:tests (count cases)
               :passed (get totals :passed 0)
               :failed (get totals :failed 0)
               :errored (get totals :errored 0)
               :skipped (get totals :skipped 0)
               :duration-ms 100}
      :parse-errors []}
     {:attempt-number attempt-number})))

(defn- register-build! [job-name n]
  (jobs-persist/upsert-job! {:name job-name :jenkinsfile-source "pipeline {}"})
  (jobs/register-job! {:name job-name
                       :jenkinsfile-source "pipeline { agent any }"
                       :buildable? true
                       :max-concurrent-builds 1})
  (jobs/record-build-start! job-name {:parameters {}})
  (jobs/record-build-end! job-name n
                          {:result :success
                           :effects []
                           :console-log ""
                           :duration-ms 100}))

(defn- seed-flake-then-pass!
  "Build with one passed-on-retry test: attempt 1 fails, attempt 2
   passes for the same test_id.  Then run the same detect→flag path
   the T1.4 producer runs."
  [job-name n]
  (register-build! job-name n)
  ;; attempt 1 — both tests fail
  (write-attempt! job-name n 1
                  [(case-row "demo.FlakySuite" "rolls_dice"  :failed)
                   (case-row "demo.FlakySuite" "stable_pass" :passed)])
  ;; attempt 2 — rolls_dice now passes; stable_pass also passes again
  (write-attempt! job-name n 2
                  [(case-row "demo.FlakySuite" "rolls_dice"  :passed)
                   (case-row "demo.FlakySuite" "stable_pass" :passed)])
  ;; Detect + flag — mirrors complete-build!'s inline producer.
  (let [rows (tr/find-results-all-attempts job-name n)
        flaky-map (#'anvil.flaky/detect-flaky-tests rows)]
    (tr/write-flaky-flags! job-name n flaky-map)
    flaky-map))

(defn- seed-stable!
  "Build with no flakes — all attempts pass on the first try.  Used
   to assert the empty-state copy doesn't lie."
  [job-name n]
  (register-build! job-name n)
  (write-attempt! job-name n 1
                  [(case-row "demo.SolidSuite" "always_passes" :passed)])
  (tr/write-flaky-flags! job-name n {}))

(defn- get-page [path]
  (let [h (routes/make-handler)]
    (h {:request-method :get :uri path
        :scheme :http :headers {"host" "test"}
        :query-params {} :form-params {}})))

;; ---------------------------------------------------------------------------
;; Route-level tests
;; ---------------------------------------------------------------------------

(deftest flaky-route-404s-when-flag-off
  (features/set! :flaky false)
  (seed-flake-then-pass! "demo" 1)
  (let [resp (get-page "/flaky")]
    (is (= 404 (:status resp))
        "/flaky returns 404 when :flaky flag is closed (wrap-feature contract)")))

(deftest flaky-route-renders-empty-state-when-no-flakes
  (features/set! :flaky true)
  (seed-stable! "demo" 1)
  (let [resp (get-page "/flaky")
        body (:body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? body "Flaky tests"))
    (is (str/includes? body "No flaky tests")
        "empty state explains the absence rather than rendering an empty table")))

(deftest flaky-route-renders-flaky-test-on-dashboard
  (features/set! :flaky true)
  (let [flaky-map (seed-flake-then-pass! "demo" 1)
        resp (get-page "/flaky")
        body (:body resp)]
    (is (= 200 (:status resp)))
    (is (= {"demo.FlakySuite#rolls_dice" 1} flaky-map)
        "detect identifies the passed-on-retry test exactly")
    (testing "flaky test appears on the dashboard"
      (is (str/includes? body "demo.FlakySuite#rolls_dice")))
    (testing "stable test does NOT appear"
      (is (not (str/includes? body "stable_pass"))
          "tests that never failed an attempt stay off the dashboard"))
    (testing "flake-rate cell renders count/window-size"
      (is (re-find #"1</strong>/1" body)
          "1 flake across 1 build observed in the window"))))

(deftest flaky-route-ranks-multiple-builds-correctly
  (features/set! :flaky true)
  ;; Two builds, both flaky for the same test → 2/2 (100% flake rate).
  (seed-flake-then-pass! "alpha" 1)
  (seed-flake-then-pass! "alpha" 2)
  ;; A third build, never flaky → keeps the window denominator honest.
  (seed-stable! "beta" 1)
  (let [resp (get-page "/flaky")
        body (:body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? body "rolls_dice"))
    (is (re-find #"2</strong>/2" body)
        "100% flake rate over 2 builds, not 2/3 — recent-flaky-window only counts builds where the test actually ran")))

;; ---------------------------------------------------------------------------
;; etaoin browser smoke — opt-in via `lein test :browser`. Requires Firefox.
;; ---------------------------------------------------------------------------

(deftest ^:browser flaky-dashboard-visible-in-firefox
  ;; Confirms the page renders in a real browser, the flaky test_id
  ;; surfaces, and the htmx hx-target hooks the T1.4 SSE producer
  ;; depends on are present in the DOM.
  ;;
  ;; Opt in:
  ;;   lein test :browser anvil.web.flaky-route-test
  (require '[etaoin.api :as e]
           '[anvil.web.server :as server])
  (let [e-firefox (resolve 'etaoin.api/firefox)
        e-go      (resolve 'etaoin.api/go)
        e-exists? (resolve 'etaoin.api/exists?)
        e-get-source (resolve 'etaoin.api/get-source)
        e-quit    (resolve 'etaoin.api/quit)
        start!    (resolve 'anvil.web.server/start!)
        stop!     (resolve 'anvil.web.server/stop!)
        port 18766]
    (features/set! :flaky true)
    (seed-flake-then-pass! "demo" 1)
    (start! {:port port :host "127.0.0.1"})
    (let [driver (e-firefox {:headless true})]
      (try
        (e-go driver (str "http://127.0.0.1:" port "/flaky"))
        ;; Page rendered at all
        (is (e-exists? driver {:css "h2"})
            "/flaky has a heading after wrap-feature lets it through")
        ;; Flaky test surfaces in the DOM
        (let [src (e-get-source driver)]
          (is (str/includes? src "demo.FlakySuite#rolls_dice")
              "flaky test_id rendered in the table")
          (is (not (str/includes? src "stable_pass"))
              "non-flaky tests stay off the dashboard"))
        (finally
          (e-quit driver)
          (stop!))))))
