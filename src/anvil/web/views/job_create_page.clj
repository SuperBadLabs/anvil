(ns anvil.web.views.job-create-page
  "Manual job-creation form (v0.2.2 — dogfood-driven patch).

   v0.2 TU6.1 introduced empty-state callouts that assumed a Trojan-horse
   onboarding from Jenkins users with existing Jenkinsfiles (`anvil import
   jenkinsfile <path>` CLI or the Jenkins REST shim). That covers the
   migration case but leaves the green-field case — 'I want to write a
   fresh Jenkinsfile for this repo in the UI' — without a path.

   This page closes the gap: a single form that takes a job name + an
   inline Jenkinsfile and routes to the existing
   `anvil.web.jenkins-api.jobs/register-job!` codepath (same one the
   POST `/anvil/admin/jobs` JSON API uses).

       GET  /jobs/new   the form
       POST /jobs/new   create job, redirect to /jobs/<name> on success
                        (re-render with error message on validation fail)

   Discovered 2026-06-03 during anvil-self dogfood — the user couldn't
   add a job through the UI on a fresh install."
  (:require [clojure.string :as str]
            [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.jobs :as jobs]))

;; ---------------------------------------------------------------------------
;; Example Jenkinsfile shown as placeholder
;; ---------------------------------------------------------------------------

(def ^:private example-jenkinsfile
  "pipeline {
  agent any
  stages {
    stage('test') {
      steps {
        sh 'lein test'
      }
    }
  }
}")

;; ---------------------------------------------------------------------------
;; GET — the form
;; ---------------------------------------------------------------------------

(defn get-form
  "Render the new-job form. Optional `error` (string) shows above the
   form. Optional `prev` (map of form fields) repopulates inputs after
   a validation failure."
  ([req] (get-form req {}))
  ([_req {:keys [error prev]}]
   (layout/page
    {:title "Create job" :active :jobs}
    [:h2 "Create a new job"]
    [:p.muted
     "Paste a Jenkinsfile below. The job will be created immediately
      and can be triggered from its page or via the Jenkins REST shim
      (" [:code "POST /jenkins/job/<name>/build"] ")."]

    (when error
      [:div.error-box {:style "background:#fee; border:1px solid #c33; padding:1em; border-radius:4px; margin:1em 0;"}
       [:strong "Could not create job: "] error])

    [:form#new-job-form {:method "post" :action "/jobs/new"
                        :style "max-width:900px;"}

     ;; Name
     [:div.form-row {:style "margin:1em 0;"}
      [:label {:for "name" :style "display:block; font-weight:bold; margin-bottom:0.3em;"}
       "Name"]
      [:input#name
       {:type "text" :name "name" :required true
        :value (get prev "name" "")
        :placeholder "e.g. anvil-self, chengis-core-self, my-project"
        :pattern "[A-Za-z0-9_-]+"
        :title "Letters, digits, underscore, hyphen"
        :style "width:100%; padding:0.5em; font-family:inherit;"}]
      [:p.muted {:style "font-size:0.9em; margin-top:0.3em;"}
       "Used in URLs and CLI. Letters, digits, " [:code "_"] ", " [:code "-"] " only."]]

     ;; Jenkinsfile
     [:div.form-row {:style "margin:1em 0;"}
      [:label {:for "jenkinsfile_source" :style "display:block; font-weight:bold; margin-bottom:0.3em;"}
       "Jenkinsfile"]
      [:textarea#jenkinsfile_source
       {:name "jenkinsfile_source" :required true
        :rows 20
        :placeholder example-jenkinsfile
        :spellcheck "false"
        :style "width:100%; padding:0.5em; font-family:monospace; font-size:0.9em; tab-size:2;"}
       (get prev "jenkinsfile_source" "")]
      [:p.muted {:style "font-size:0.9em; margin-top:0.3em;"}
       "Declarative pipeline syntax. The Jenkinsfile is stored on this
        server; updating means re-submitting via this form (or "
       [:code "PUT /anvil/admin/jobs"] " for scripted flows)."]]

     ;; Settings row
     [:div.form-row {:style "margin:1em 0; display:flex; gap:2em; flex-wrap:wrap;"}
      [:label {:style "display:flex; align-items:center; gap:0.5em;"}
       [:input {:type "checkbox" :name "buildable" :value "true"
                :checked (not (false? (get prev "buildable")))}]
       "Buildable (can be triggered)"]
      [:label {:style "display:flex; align-items:center; gap:0.5em;"}
       "Max concurrent builds:"
       [:input {:type "number" :name "max_concurrent_builds" :min 1 :max 32
                :value (get prev "max_concurrent_builds" "1")
                :style "width:5em; padding:0.3em;"}]]]

     ;; Submit
     [:div.form-row {:style "margin:1.5em 0;"}
      [:button {:type "submit"
                :style "background:#2a6; color:white; padding:0.7em 1.5em; border:0; border-radius:4px; font-weight:bold; cursor:pointer;"}
       "Create job"]
      " "
      [:a {:href "/jobs"
           :style "margin-left:1em; color:#666;"} "Cancel"]]]

    [:hr {:style "margin:2em 0;"}]
    [:p.muted {:style "font-size:0.9em;"}
     "Prefer scripting? "
     [:code "POST /anvil/admin/jobs"]
     " accepts the same payload as JSON. See "
     [:a {:href "https://github.com/SuperBadLabs/anvil"} "anvil docs"]
     " for the schema."])))

;; ---------------------------------------------------------------------------
;; POST — handle submission
;; ---------------------------------------------------------------------------

(defn- form-bool [v]
  (boolean (or (= v "true") (= v "on") (= v "1") (= v true))))

(defn- positive-int [v default]
  (try
    (let [n (Long/parseLong (str v))]
      (if (pos? n) n default))
    (catch Exception _ default)))

(defn submit
  "Handle the form POST. On success, redirect to the new job page.
   On validation failure, re-render the form with an error."
  [req]
  (let [params  (or (:form-params req) (:params req) {})
        nm      (str/trim (or (get params "name") ""))
        src     (or (get params "jenkinsfile_source") "")
        buildable? (form-bool (get params "buildable"))
        max-c   (positive-int (get params "max_concurrent_builds") 1)]
    (cond
      (str/blank? nm)
      {:status 400
       :headers {"Content-Type" "text/html; charset=UTF-8"}
       :body (str (get-form req {:error "Name is required."
                                  :prev params}))}

      (not (re-matches #"[A-Za-z0-9_-]+" nm))
      {:status 400
       :headers {"Content-Type" "text/html; charset=UTF-8"}
       :body (str (get-form req {:error "Name may only contain letters, digits, underscore, and hyphen."
                                  :prev params}))}

      (str/blank? src)
      {:status 400
       :headers {"Content-Type" "text/html; charset=UTF-8"}
       :body (str (get-form req {:error "Jenkinsfile source is required."
                                  :prev params}))}

      :else
      (do
        (jobs/register-job!
         {:name nm
          :jenkinsfile-source src
          :buildable? buildable?
          :max-concurrent-builds max-c})
        {:status 303
         :headers {"Location" (str "/jobs/" nm)}
         :body ""}))))
