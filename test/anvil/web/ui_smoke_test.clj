(ns anvil.web.ui-smoke-test
  "Headless-browser smoke for the admin UI (TU0.5).

   Doctrine (AU10): etaoin, not Cypress/Playwright. WebDriver over a
   real browser, but no node_modules, no separate test runner — just
   `lein test :browser`.

   This namespace's tests are tagged `^:browser` and therefore EXCLUDED
   from the default `lein test` run. They require:

     - Firefox + geckodriver  (preferred — both are on the dev image)
     - or Chrome + chromedriver

   The smoke covers the daily-5 baseline: the dashboard renders, the
   navigation links work, the vendored htmx script loads. Deeper UI
   coverage (console tail, trigger form) is TU3/TU4 work and will
   accrete in sibling files.

   Run locally:
     lein test :browser
   Or this one ns:
     lein test :browser anvil.web.ui-smoke-test"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [etaoin.api :as e]
            [anvil.web.server :as server])
  (:import (java.net ServerSocket)))

;; ---------------------------------------------------------------------------
;; Fixture: anvil server on a random port + a single headless Firefox session
;; for the whole ns. Each test reconnects to the same browser, so suite
;; wall-clock is dominated by Firefox start (~1s), not by per-test bring-up.
;; ---------------------------------------------------------------------------

(def ^:dynamic *driver* nil)
(def ^:dynamic *base-url* nil)

(defn- free-port
  "Grab a free TCP port. Used for the dev anvil server so multiple test
   runs in parallel don't fight over :8080."
  []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn- browser-fixture
  "Boot anvil on a random port, open a headless Firefox, run tests, tear down."
  [run-tests]
  (let [port (free-port)]
    (server/start! {:port port :host "127.0.0.1"})
    (let [driver (e/firefox {:headless true
                             :args ["--width=1280" "--height=900"]})]
      (try
        (binding [*driver*  driver
                  *base-url* (str "http://127.0.0.1:" port)]
          (run-tests))
        (finally
          (try (e/quit driver) (catch Exception _ nil))
          (server/stop!))))))

(use-fixtures :once browser-fixture)

;; ---------------------------------------------------------------------------
;; Smoke tests
;; ---------------------------------------------------------------------------

(deftest ^:browser dashboard-renders
  (e/go *driver* (str *base-url* "/"))
  (testing "title sets correctly"
    (is (str/includes? (e/get-title *driver*) "anvil")))
  (testing "h1 says anvil"
    (is (= "anvil" (e/get-element-text *driver* {:tag :h1}))))
  (testing "nav has Dashboard + Jobs + Queue + Coverage"
    (let [links (->> (e/query-all *driver* {:tag :a})
                     (map #(e/get-element-text-el *driver* %))
                     (remove str/blank?)
                     set)]
      (doseq [link ["Dashboard" "Jobs" "Queue" "Coverage"]]
        (is (contains? links link)
            (str "nav missing: " link))))))

(deftest ^:browser vendored-htmx-loads
  (e/go *driver* (str *base-url* "/"))
  ;; htmx attaches itself to window.htmx; if the script 404'd or
  ;; failed to parse, window.htmx is undefined.
  ;; etaoin's js-execute returns the value; nil if undefined.
  (let [has-htmx (e/js-execute *driver* "return typeof htmx !== 'undefined';")]
    (is (true? has-htmx)
        "window.htmx is undefined — vendored asset did not load")))

(deftest ^:browser jobs-page-renders
  (e/go *driver* (str *base-url* "/jobs"))
  (testing "h1 still anvil"
    (is (= "anvil" (e/get-element-text *driver* {:tag :h1}))))
  (testing "Jobs nav entry marked active"
    (let [active (e/get-element-text *driver* {:css "nav a.active"})]
      (is (= "Jobs" active)))))

(deftest ^:browser queue-page-renders
  (e/go *driver* (str *base-url* "/queue"))
  (let [active (e/get-element-text *driver* {:css "nav a.active"})]
    (is (= "Queue" active))))
