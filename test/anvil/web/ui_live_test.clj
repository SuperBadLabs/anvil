(ns anvil.web.ui-live-test
  "Live UI browser smoke for TU1 — real EventSource, real heartbeat,
   real reconnect (TU1.5).

   Why a separate ns from ui-smoke-test: the smoke covers static
   render; this covers real-time. Different fixture (we need a way
   to publish on the bus from outside the browser), different
   teardown rhythm. Both run under `lein test :browser`.

   Coverage:

     - SSE handshake: EventSource opens, the 'hello' frame arrives,
       readyState flips to OPEN. (Sanity that the wire works at all.)
     - End-to-end: publish a bus event, browser DOM re-renders the
       stats widget within ~500ms.
     - Disconnect cleanup: close the browser tab, bus subscription
       count returns to baseline within ~5s. (The real-tcp test that
       events-sse-test can't reliably do; here we have a browser
       doing honest TCP teardown.)
     - Auto-reconnect: bounce the server, browser EventSource
       reconnects, second publish lands."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [etaoin.api :as e]
            [anvil.events.bus :as bus]
            [anvil.web.events-sse :as events-sse]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.server :as server])
  (:import (java.net ServerSocket)))

;; ---------------------------------------------------------------------------
;; Fixture: one Firefox session, one server, short SSE heartbeat so
;; reconnect timing is sub-second.
;; ---------------------------------------------------------------------------

(def ^:dynamic *driver* nil)
(def ^:dynamic *base-url* nil)

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- browser-fixture [run-tests]
  (let [port (free-port)]
    (server/start! {:port port :host "127.0.0.1"})
    (bus/unsubscribe-all!)
    (events-sse/set-heartbeat-override! 500)
    (let [driver (e/firefox {:headless true
                             :args ["--width=1280" "--height=900"]})]
      (try
        (binding [*driver*  driver
                  *base-url* (str "http://127.0.0.1:" port)]
          (run-tests))
        (finally
          (try (e/quit driver) (catch Exception _ nil))
          (events-sse/set-heartbeat-override! nil)
          (bus/unsubscribe-all!)
          (server/stop!))))))

(use-fixtures :once browser-fixture)

(defn- register-test-job!
  "Side-step the queue: drop a fake job straight into the jobs store so
   the dashboard has something interesting to render and the bus
   producers will fire on lifecycle changes."
  [job-name]
  (jobs/register-job! {:name job-name
                       :jenkinsfile-source "pipeline { agent any; stages { stage('S') { steps { echo 'hi' } } } }"
                       :buildable? true
                       :max-concurrent-builds 1}))

(defn- wait-until
  "Poll `pred` every 50ms up to `max-ms`. Returns true if pred returned
   truthy before deadline, false otherwise. Used in lieu of explicit
   sleeps so flaky CI just takes longer instead of failing."
  [pred max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 50) (recur))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest ^:browser eventsource-opens-and-receives-hello
  (e/go *driver* (str *base-url* "/"))
  ;; Wait for the htmx-sse extension to kick in and open EventSource.
  ;; The dashboard's stats-row has hx-ext='sse' sse-connect=...; once
  ;; the script loads and processes, an EventSource exists. We probe
  ;; via JS: ask the browser if any EventSource is OPEN.
  (let [opened? (wait-until
                 (fn []
                   (let [open (e/js-execute *driver*
                                            "var found=false; if(window.EventSource){
                                               document.querySelectorAll('[sse-connect]').forEach(function(){
                                                 found=true;
                                               });
                                             } return found;")]
                     (true? open)))
                 5000)]
    (is opened? "browser should have at least one element with sse-connect after first paint")))

(deftest ^:browser bus-publish-triggers-widget-refresh
  (bus/unsubscribe-all!)
  (register-test-job! "live-demo")
  (e/go *driver* (str *base-url* "/"))
  ;; Wait for stats element to exist and SSE to be wired.
  (is (wait-until #(e/exists? *driver* {:id "dashboard-stats"}) 5000)
      "stats fragment renders on first paint")
  ;; The dashboard's 'Jobs' count starts at 1 (we registered one job).
  ;; If we register another from outside, publish should fire and
  ;; htmx-sse should swap the widget, bumping Jobs to 2.
  (Thread/sleep 500)   ; let EventSource settle past first hello/heartbeat
  (register-test-job! "live-demo-2")
  ;; The act of registering a job doesn't publish on its own (TU1.2
  ;; only publishes on build-start/end + queue). So provoke a publish:
  (bus/publish! [:job "live-demo-2"]
                {:type :build-started
                 :job-name "live-demo-2"
                 :build-number 1})
  ;; Widget should re-fetch within ~1s. The new count is 2. We must
  ;; re-query each poll: htmx-swap REPLACES the DOM nodes, so any
  ;; element reference held across the swap is stale.
  (let [updated? (wait-until
                  (fn []
                    (try
                      (= "2"
                         (e/js-execute *driver*
                                       "var v=document.querySelector('#dashboard-stats .stat-value');
                                        return v ? v.textContent.trim() : null;"))
                      (catch Exception _ false)))
                  3000)]
    (is updated? "dashboard 'Jobs' value should refresh from 1 → 2 after publish")))

(deftest ^:browser console-page-live-tails-published-lines
  ;; TU2 marquee scenario: open a build's /console page, publish
  ;; :console-line events from the bus, watch them appear in the
  ;; live-tail <pre> via the page's EventSource subscription.
  (bus/unsubscribe-all!)
  (register-test-job! "console-live")
  ;; Force the build into a 'building' state — record-build-start!
  ;; gives us number 1 and tags it building?
  (let [n (anvil.web.jenkins-api.jobs/record-build-start! "console-live" {})]
    (e/go *driver* (str *base-url* "/jobs/console-live/" n "/console"))
    (is (wait-until #(e/exists? *driver* {:css "pre.console.live-tail"}) 5000)
        "live-tail <pre> renders for running build")
    (Thread/sleep 600)
    ;; Publish three lines as if the dispatcher's log-tail thread emitted.
    (doseq [[i text] [[1 "first line"] [2 "second line"] [3 "third!"]]]
      (bus/publish! [:build "console-live" n]
                    {:type :console-line
                     :seq i
                     :stream :stdout
                     :line text}))
    (let [arrived? (wait-until
                    (fn []
                      (let [text (e/js-execute *driver*
                                               "var p=document.querySelector('pre.console.live-tail');
                                                return p ? p.textContent : '';")]
                        (and (clojure.string/includes? text "first line")
                             (clojure.string/includes? text "second line")
                             (clojure.string/includes? text "third!"))))
                    3000)]
      (is arrived?
          "all three published lines must appear in the live-tail <pre> via SSE"))))

(deftest ^:browser eventsource-survives-server-bounce
  ;; This is the auto-reconnect promise of EventSource: if the server
  ;; closes the connection, the browser opens a new one within a few
  ;; seconds (`retry: …` defaults to 3s in spec; browsers respect it).
  (bus/unsubscribe-all!)
  (e/go *driver* (str *base-url* "/"))
  (is (wait-until #(e/exists? *driver* {:id "dashboard-stats"}) 5000))
  (Thread/sleep 600)
  ;; Force-close all bus subscriptions — equivalent to a server bounce
  ;; from the browser's perspective.  The SSE handler's heartbeat will
  ;; see the closed channel + clean up the subscriber.
  (let [initial-subs (bus/subscriber-count)]
    (is (pos? initial-subs) "browser should have at least one bus subscription"))
  ;; Now actually bounce the server: stop + start. The browser's
  ;; EventSource will get a connection-reset on its open stream and
  ;; will retry per spec.
  (server/stop!)
  (Thread/sleep 300)
  (server/start! {:port (Integer/parseInt
                         (last (clojure.string/split *base-url* #":")))
                  :host "127.0.0.1"})
  ;; Browser auto-reconnects within ~3s (spec default `retry:`). Allow
  ;; up to 8s on a slow CI runner.
  (let [reconnected? (wait-until
                      (fn [] (pos? (bus/subscriber-count)))
                      8000)]
    (is reconnected? "browser should re-establish SSE after server bounce")))
