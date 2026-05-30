(ns anvil.web.views.build-form
  "TU4.1+4.2+4.3+4.4+4.5 — \"Build with parameters\" done right.

   /jobs/<name>/build-form  GET   the rendered form
                            POST  the submission
                            POST  ?action=copy-url  → curl shortlink
                            POST  ?validate=<field> → inline error region

   Convention notes:
     - All form state is on the server; we send each field's CURRENT
       error region as an htmx fragment, so the server's sub-ms
       responses give it the feel of client-side validation.
     - Recent-values memory lives in a per-job cookie
       (anvil_recent_<job-name>=base64-edn). No DB.
     - 'Trigger N times' fans the same params across N enqueue! calls."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [hiccup2.core :as h]
            [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.web.params-parse :as params])
  (:import (java.util Base64)))

;; ---------------------------------------------------------------------------
;; Cookie-scoped recent-values memory (TU4.3)
;; ---------------------------------------------------------------------------

(def ^:private cookie-prefix "anvil_recent_")
(def ^:private recent-cap 5)

(defn- cookie-name [job-name]
  (str cookie-prefix
       (-> job-name
           (str/replace #"[^A-Za-z0-9_-]" "_"))))

(defn- b64-enc [^bytes b] (.encodeToString (Base64/getUrlEncoder) b))
(defn- b64-dec [^String s] (.decode (Base64/getUrlDecoder) s))

(defn- read-recent
  "Read the recent-values map for a job out of the request's cookies.
   {field-name [val1 val2 …]}, newest first."
  [req job-name]
  (let [raw (get-in req [:cookies (cookie-name job-name) :value])]
    (or (try
          (when raw
            (let [decoded (String. (b64-dec raw) "UTF-8")]
              (edn/read-string decoded)))
          (catch Exception _ nil))
        {})))

(defn- bump-recent
  "Return the new {field [vals …]} map for `submitted-params` merged
   into `current` with a cap of recent-cap per field."
  [current submitted-params]
  (reduce-kv
   (fn [acc k v]
     (let [prev (get acc k [])
           v (if (string? v) v (str v))]
       (if (str/blank? v)
         acc
         (assoc acc k (->> (cons v (remove #{v} prev))
                           (take recent-cap)
                           vec)))))
   (or current {})
   submitted-params))

(defn- set-recent-cookie
  "Build a Set-Cookie header value for `m`. Path is the job page so
   the cookie is scoped (and small)."
  [job-name m]
  (let [edn-str (pr-str m)
        encoded (b64-enc (.getBytes edn-str "UTF-8"))]
    {(cookie-name job-name)
     {:value encoded
      :path (str "/jobs/" job-name)
      :max-age (* 60 60 24 90)   ; 90 days
      :http-only true
      :same-site :lax}}))

;; ---------------------------------------------------------------------------
;; Hiccup helpers
;; ---------------------------------------------------------------------------

(defn- field-error-id [name] (str "err-" name))
(defn- field-row-id   [name] (str "row-" name))

(defn- recent-datalist [field-name vals]
  (when (seq vals)
    (let [lid (str "recent-" field-name)]
      [:datalist {:id lid}
       (for [v vals] [:option {:value v}])])))

(defn- field-row
  "Render one labelled input row matching the parameter's :kind. The
   `recent` map is the cookie-stored recent-values cache; the field's
   recent list becomes the input's datalist."
  [{:keys [kind name description default choices]} {:keys [recent value error]}]
  (let [recent-vals (get recent name [])
        v (if (some? value) value default)
        datalist-id (str "recent-" name)]
    [:div.form-row {:id (field-row-id name)}
     [:label {:for name}
      [:span.field-name name]
      [:span.field-kind " · " (clojure.core/name kind)]
      (when (seq description)
        [:span.field-desc " — " description])]
     (case kind
       :string   [:input {:type "text"
                          :id name :name name
                          :value (or v "")
                          :list (when (seq recent-vals) datalist-id)
                          :hx-post (str "?validate=" name)
                          :hx-trigger "blur"
                          :hx-target (str "#" (field-error-id name))
                          :hx-swap "outerHTML"}]
       :password [:input {:type "password"
                          :id name :name name
                          :value (or v "")
                          :autocomplete "new-password"}]
       :boolean  [:input {:type "checkbox"
                          :id name :name name
                          :value "true"
                          :checked (boolean (if (some? value) value default))}]
       :choice   [:select {:id name :name name}
                  (for [c choices]
                    [:option {:value c :selected (= c v)} c])]
       :file     [:input {:type "file" :id name :name name}])
     (recent-datalist name recent-vals)
     [:div.field-error {:id (field-error-id name)} (or error "")]]))

;; ---------------------------------------------------------------------------
;; Validation (TU4.2)
;; ---------------------------------------------------------------------------

(defn- validate-param
  "Return nil if `submitted-value` (string from the form) is OK for
   `param`, otherwise an error-string."
  [param submitted-value]
  (let [v submitted-value]
    (case (:kind param)
      :string
      nil                                          ; v1: every string ok

      :password
      nil

      :choice
      (when (and (some? v) (seq (:choices param))
                 (not (contains? (set (:choices param)) v)))
        (str "must be one of: " (str/join ", " (:choices param))))

      :boolean
      (when (and (some? v)
                 (not (contains? #{"true" "false" "on" "off" "" nil} v)))
        "must be true / false / on / off")

      :file
      nil)))

(defn- validate-all
  "Return {field-name error-string} for every invalid field. Empty
   when the submission is good."
  [param-defs submitted-form]
  (reduce
   (fn [acc {:keys [name] :as pd}]
     (if-let [err (validate-param pd (get submitted-form name))]
       (assoc acc name err)
       acc))
   {} param-defs))

;; ---------------------------------------------------------------------------
;; Form GET
;; ---------------------------------------------------------------------------

(defn render-form
  [job-name pds {:keys [recent values errors]}]
  (layout/page
   {:title (str "Build " job-name) :active :jobs}
   [:h2 "Build " job-name]
   [:p.muted
    [:a {:href (str "/jobs/" job-name)} (str "← " job-name)]]
   (if (empty? pds)
     [:div
      [:p.muted "This job has no parameters block. Just trigger it:"]
      [:form {:method "POST"
              :action (str "/jobs/" job-name "/build-form")}
       [:button.btn-trigger {:type "submit"} "▶ Build"]]]
     [:form#build-form
      {:method "POST"
       :action (str "/jobs/" job-name "/build-form")
       :hx-boost "true"}
      (for [pd pds]
        (field-row pd {:recent recent
                       :value (get values (:name pd))
                       :error (get errors (:name pd))}))
      [:div.form-row.trigger-extras
       [:label
        [:input {:type "checkbox" :id "trigger-n" :name "trigger-n-enabled" :value "true"}]
        " Run this build "
        [:input {:type "number" :id "trigger-n-count" :name "trigger-n-count"
                 :min "1" :max "100" :value "1" :style "width:5em;"}]
        " times (for flake-hunting)"]]
      [:div.form-row
       [:button.btn-trigger {:type "submit"} "▶ Build now"]
       " "
       [:button {:type "button"
                 :class "btn-secondary"
                 :hx-post (str "/jobs/" job-name "/build-form?action=copy-url")
                 :hx-target "#shortlink-out"
                 :hx-swap "outerHTML"
                 :hx-include "#build-form"}
        "🔗 Copy POST URL"]]
      [:div#shortlink-out]])))

(defn get-form [req]
  (let [job-name (get-in req [:path-params :name])
        job (jobs/find-job job-name)]
    (if-not job
      (layout/page
       {:title "Job not found" :active :jobs}
       [:h2 "Job " [:code job-name] " not found"])
      (let [pds (params/extract (:jenkinsfile-source job))
            recent (read-recent req job-name)]
        (render-form job-name pds {:recent recent})))))

;; ---------------------------------------------------------------------------
;; Form POST (submission) — TU4.1 + 4.5
;; ---------------------------------------------------------------------------

(defn- coerce-bool [^String v]
  (boolean (#{"true" "on"} v)))

(defn- coerce-submission
  "Map raw form params to the typed values the queue wants."
  [param-defs form-params]
  (into {}
        (for [{:keys [kind name default] :as _pd} param-defs
              :let [raw (get form-params name)]]
          [name
           (case kind
             :boolean (coerce-bool raw)
             ;; Strings / passwords / choices / files: keep as-is, fall
             ;; back to default if missing. File uploads aren't actually
             ;; transferred to the runner at v1 — the field name is
             ;; carried for compat with the parameters() block.
             (or raw default ""))])))

(defn submit [req]
  (let [job-name (get-in req [:path-params :name])
        job (jobs/find-job job-name)
        form-params (or (:form-params req) {})
        action (get-in req [:query-params "action"])
        validate-field (get-in req [:query-params "validate"])]
    (cond
      (nil? job)
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (str "no such job: " job-name)}

      ;; --- inline field validation (TU4.2) -------------------------
      validate-field
      (let [pds (params/extract (:jenkinsfile-source job))
            pd (first (filter #(= validate-field (:name %)) pds))
            err (when pd (validate-param pd (get form-params validate-field)))]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (str (h/html
                     [:div.field-error {:id (field-error-id validate-field)
                                        :class (when err "error")}
                      (or err "")]))})

      ;; --- copy POST URL (TU4.4) -----------------------------------
      (= action "copy-url")
      (let [pds (params/extract (:jenkinsfile-source job))
            coerced (coerce-submission pds form-params)
            host (or (get-in req [:headers "host"]) "localhost:8080")
            scheme (name (or (:scheme req) :http))
            base (str scheme "://" host "/jenkins/job/" job-name "/buildWithParameters")
            qs (->> coerced
                    (map (fn [[k v]]
                           (str (java.net.URLEncoder/encode k "UTF-8")
                                "="
                                (java.net.URLEncoder/encode (str v) "UTF-8"))))
                    (str/join "&"))
            full (str base "?" qs)
            curl-line (str "curl -X POST '" full "'")]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (str (h/html
                     [:div#shortlink-out.shortlink
                      [:label "POST URL:"]
                      [:textarea {:rows 3 :readonly true :spellcheck "false"
                                  :onclick "this.select()"}
                       curl-line]
                      [:p.muted "Tip: " [:code "curl -X POST '…'"]
                       " from any chatbot / dashboard."]]))})

      ;; --- actual submission (TU4.1 + TU4.5) -----------------------
      :else
      (let [pds (params/extract (:jenkinsfile-source job))
            errors (validate-all pds form-params)
            n-enabled? (= "true" (get form-params "trigger-n-enabled"))
            n (max 1 (min 100 (try (Integer/parseInt
                                    (or (get form-params "trigger-n-count") "1"))
                                   (catch Exception _ 1))))
            n (if n-enabled? n 1)]
        (if (seq errors)
          ;; Re-render the form with errors highlighted.
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (render-form job-name pds
                              {:recent (read-recent req job-name)
                               :values form-params
                               :errors errors})}
          ;; Good submission: enqueue N times, bump recents, redirect.
          (let [coerced (coerce-submission pds form-params)
                qids (doall
                      (for [_ (range n)]
                        (:queue-id
                         (queue/enqueue! job-name {:parameters coerced}))))
                new-recent (bump-recent (read-recent req job-name) coerced)]
            {:status 303
             :headers {"Location" (str "/jobs/" job-name)
                       "X-Anvil-Queue-Ids" (str/join "," qids)}
             :cookies (set-recent-cookie job-name new-recent)
             :body (str "queued " n " build(s): "
                        (str/join ", " qids))}))))))
