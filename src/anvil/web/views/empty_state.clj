(ns anvil.web.views.empty-state
  "Reusable first-run / empty-state callouts (TU6.1).

   The forgotten 20 minutes of any product: what does a user with
   zero jobs see when they open anvil for the first time?

   Each `cta` here is a stand-alone Hiccup block that drops into a
   page where an empty list/table would otherwise live. Tone: warm
   + concrete + 1-step-to-action."
  (:require [clojure.string :as str]))

(defn first-run-cta
  "Big call-to-action shown on the dashboard / jobs list when no jobs
   are registered. Two paths surfaced: import a Jenkinsfile (the
   Trojan-horse v1 path), and the REST shim (for users already
   scripting against Jenkins APIs)."
  []
  [:div.first-run
   [:h3 {:style "margin-top:0;"} "👋 Welcome to anvil"]
   [:p
    "No jobs registered yet. anvil is a Jenkins-compatible CI that "
    "runs your existing Jenkinsfiles through the chengis-core engine, "
    "with sub-millisecond REST and a 95× faster small-build wall-clock."]
   [:p [:strong "Two ways to register your first job:"]]
   [:ol
    [:li
     "Drop a Jenkinsfile into a registered job (CLI lands in TU7+) — "
     "for now, POST to the anvil-native admin endpoint:"
     [:pre.console (str "curl -X POST http://localhost:8765/anvil/admin/jobs \\\n"
                        "     -H 'Content-Type: application/json' \\\n"
                        "     -d '{\"name\": \"my-job\", \"jenkinsfile_source\": \"pipeline { agent any; stages { stage(\\\"s\\\") { steps { sh \\\"echo hi\\\" } } } }\"}'")]]
    [:li
     "Or use the existing "
     [:a {:href "/jenkins/api/json"} [:code "/jenkins/*"]]
     " REST shim from "
     [:code "jenkins-cli"] " or any GitHub plugin (read-mostly + build trigger)."]]
   [:p.muted
    "Once jobs exist: the dashboard ticks live, builds stream "
    "console logs in real time, and you can trigger / retry / kill "
    "from any tab. See "
    [:a {:href "/coverage"} "Coverage"] " for what Jenkinsfile "
    "features anvil supports."]])

(defn no-builds-cta
  "Inside a registered job that has zero builds. Different intent:
   the user already imported, they need the next click."
  [job-name]
  [:div.empty-state-mini
   [:p [:strong "No builds yet."]]
   [:p
    "Trigger one via "
    [:a.btn-trigger {:href (str "/jobs/" job-name "/build-form")
                     :style "text-decoration:none;"} "▶ Build now"]
    " or POST to the Jenkins shim: "
    [:code (str "curl -X POST http://localhost:8765/jenkins/job/" job-name "/build")]]])

(defn empty-queue
  "Shown when the queue is empty. Calmer copy — no work to do, that's fine."
  []
  [:p.muted "Queue empty — nothing waiting to run."])

(defn empty-executors
  "Shown when every executor slot is idle. Same calm tone."
  []
  [:p.muted "All executors idle."])
