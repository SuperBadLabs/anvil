(ns anvil.web.ai-handler-test
  "v0.4.1 T3.4 + T3.5 + T3.6 — route-level tests for the AI Explain/
   Optimize handlers.  Hermetic — stubs anvil.ai.client/messages so
   no real Anthropic call happens in CI.

   The browser test (anvil.web.ai-handler-browser-test) is opt-in
   via `lein test :browser` and skipped by default."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [anvil.web.routes :as routes]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as jobs-persist]
            [anvil.features :as features]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.ai.client :as ai]))

(def ^:private tmp-db-path
  (str (System/getProperty "java.io.tmpdir") "/anvil-ai-handler-test.db"))

(defn- with-fresh-db [f]
  (db/close!)
  (when (.exists (io/file tmp-db-path)) (.delete (io/file tmp-db-path)))
  (db/init! tmp-db-path)
  (jobs/clear!)
  (bus/unsubscribe-all!)
  (try (f)
       (finally
         (jobs/clear!)
         (db/close!)
         (.delete (io/file tmp-db-path))
         (features/set! :ai-authoring false)
         (bus/unsubscribe-all!))))

(use-fixtures :each with-fresh-db)

(defn- register-job!
  "Plant a job in the store with the given Jenkinsfile source.  Skips
   the SQLite upsert for nil sources (NOT NULL constraint) — the
   in-memory atom is enough for find-job to return the job, which is
   what the handler reads."
  [name jenkinsfile-src]
  (when jenkinsfile-src
    (jobs-persist/upsert-job! {:name name :jenkinsfile-source jenkinsfile-src}))
  (jobs/register-job! {:name name
                       :jenkinsfile-source jenkinsfile-src
                       :buildable? true
                       :max-concurrent-builds 1}))

(defn- post
  "POST to a route via the same handler chain the daemon uses."
  ([path] (post path {}))
  ([path body]
   ((routes/make-handler)
    {:request-method :post :uri path
     :scheme :http :headers {"host" "test"}
     :query-params {} :form-params body})))

;; ---------------------------------------------------------------------------
;; Feature-flag gate
;; ---------------------------------------------------------------------------

(deftest explain-404s-when-flag-off
  (features/set! :ai-authoring false)
  (register-job! "demo" "pipeline { agent any }")
  (let [resp (post "/jobs/demo/ai/explain")]
    (is (= 404 (:status resp))
        "wrap-feature contract: closed flag → 404, not 500 or partial render")))

(deftest optimize-404s-when-flag-off
  (features/set! :ai-authoring false)
  (register-job! "demo" "pipeline { agent any }")
  (let [resp (post "/jobs/demo/ai/optimize")]
    (is (= 404 (:status resp)))))

;; ---------------------------------------------------------------------------
;; Missing-job + missing-jenkinsfile error fragments
;; ---------------------------------------------------------------------------

(deftest explain-job-not-found-renders-modal-error
  (features/set! :ai-authoring true)
  (let [resp (post "/jobs/does-not-exist/ai/explain")
        body (:body resp)]
    (is (= 200 (:status resp))
        "200 with an error fragment — modal stays open, operator sees the problem")
    (is (str/includes? body "not found"))
    (is (str/includes? body "does-not-exist"))))

(deftest blank-jenkinsfile-treated-as-missing-on-explain
  ;; In production a registered job always has a non-nil source (DB
  ;; NOT NULL + register-job!'s string? assertion).  The only
  ;; user-visible "no jenkinsfile" case is whitespace-only — covered
  ;; here.  Handler treats either nil or blank as missing.
  (features/set! :ai-authoring true)
  (register-job! "blank" "   \n   ")
  (let [resp (post "/jobs/blank/ai/explain")
        body (:body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? body "no jenkinsfile-source"))))

(deftest blank-jenkinsfile-treated-as-missing-on-optimize
  (features/set! :ai-authoring true)
  (register-job! "blank2" "")
  (let [resp (post "/jobs/blank2/ai/optimize")
        body (:body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? body "no jenkinsfile-source")
        "operator-actionable error: tells you to trigger a build first")))

;; ---------------------------------------------------------------------------
;; Happy path — stubbed AI call → response fragment
;; ---------------------------------------------------------------------------

(deftest explain-happy-path-renders-response-fragment
  (features/set! :ai-authoring true)
  (register-job! "demo" "pipeline { agent any; stages { stage('Build') { steps { sh 'make' } } } }")
  (with-redefs [ai/messages (fn [opts]
                              ;; Verify the handler built a sensible request.
                              (is (str/includes? (-> opts :system) "explaining a Jenkinsfile")
                                  "explain system prompt forwarded")
                              (is (= 1 (count (:messages opts))))
                              (is (str/includes? (-> opts :messages first :content)
                                                 "Explain this Jenkinsfile")
                                  "user message carries the prompt prefix + content")
                              (is (str/includes? (-> opts :messages first :content)
                                                 "pipeline { agent any")
                                  "Jenkinsfile content sent verbatim")
                              ;; Canned response
                              {:content [{:type "text"
                                          :text "1. **What this pipeline does** — runs make in a default agent.\n\n2. **Stages** — Build."}]
                               :stop-reason "end_turn"
                               :usage {:input-tokens 42 :output-tokens 18}})]
    (let [resp (post "/jobs/demo/ai/explain")
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (str/includes? body "runs make in a default agent")
          "rendered AI response text in the modal")
      (is (str/includes? body "Done")
          "footer surfaces end_turn → Done")
      (is (str/includes? body "in=42")
          "token usage rendered for operator cost-visibility"))))

(deftest optimize-happy-path-publishes-ai-suggested
  (features/set! :ai-authoring true)
  (register-job! "demo" "pipeline { agent any }")
  (let [received (atom [])]
    (bus/subscribe! (topics/topic-job "demo") #(swap! received conj %))
    (with-redefs [ai/messages (fn [_opts]
                                {:content [{:type "text" :text "## Suggestion 1: add caching"}]
                                 :stop-reason "end_turn"
                                 :usage {:input-tokens 100 :output-tokens 50}})]
      (let [resp (post "/jobs/demo/ai/optimize")]
        (is (= 200 (:status resp)))
        (is (= 1 (count @received))
            "exactly one :ai-suggested event published per optimize call")
        (let [evt (first @received)]
          (is (= :ai-suggested (:type evt)))
          (is (= "demo" (:job-name evt)))
          (is (= [{:text "## Suggestion 1: add caching"}]
                 (:suggestions evt))
              "suggestions vector carries the AI text"))))))

(deftest optimize-does-NOT-publish-on-refusal
  (features/set! :ai-authoring true)
  (register-job! "demo" "pipeline { agent any }")
  (let [received (atom [])]
    (bus/subscribe! (topics/topic-job "demo") #(swap! received conj %))
    (with-redefs [ai/messages (fn [_opts]
                                {:content [{:type "text" :text "I can't help with that."}]
                                 :stop-reason "refusal"
                                 :usage {:input-tokens 30 :output-tokens 10}})]
      (post "/jobs/demo/ai/optimize")
      (is (empty? @received)
          "a refusal stop_reason is NOT a successful suggestion — bus stays silent
           so live subscribers don't get a false 'new optimization available' signal"))))

(deftest optimize-does-NOT-publish-on-api-error
  (features/set! :ai-authoring true)
  (register-job! "demo" "pipeline { agent any }")
  (let [received (atom [])]
    (bus/subscribe! (topics/topic-job "demo") #(swap! received conj %))
    (with-redefs [ai/messages (fn [_opts]
                                (throw (ex-info "Anthropic API returned HTTP 429"
                                                {:status 429 :hint "Rate limited — wait and retry"})))]
      (let [resp (post "/jobs/demo/ai/optimize")
            body (:body resp)]
        (is (= 200 (:status resp))
            "error renders inside the modal, not a 500 page")
        (is (str/includes? body "HTTP 429"))
        (is (str/includes? body "Rate limited")
            "operator sees the hint surfaced from ex-data")
        (is (empty? @received)
            "no bus publish on API error")))))

;; ---------------------------------------------------------------------------
;; Buttons render on the job page (when flag on, only)
;; ---------------------------------------------------------------------------

(deftest job-page-shows-buttons-when-flag-on
  (features/set! :ai-authoring true)
  (register-job! "demo" "pipeline { agent any }")
  (let [handler (routes/make-handler)
        resp (handler {:request-method :get :uri "/jobs/demo"
                       :scheme :http :headers {"host" "test"}
                       :query-params {} :form-params {}})
        body (:body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? body "Explain this Jenkinsfile"))
    (is (str/includes? body "Optimize"))
    (is (str/includes? body "ai/explain"))
    (is (str/includes? body "ai/optimize"))
    (is (str/includes? body "ai-modal")
        "modal target is in the DOM so htmx swap finds it")))

(deftest job-page-omits-buttons-when-flag-off
  (features/set! :ai-authoring false)
  (register-job! "demo" "pipeline { agent any }")
  (let [handler (routes/make-handler)
        resp (handler {:request-method :get :uri "/jobs/demo"
                       :scheme :http :headers {"host" "test"}
                       :query-params {} :form-params {}})
        body (:body resp)]
    (is (= 200 (:status resp)))
    (is (not (str/includes? body "ai/explain"))
        "feature off → no buttons, no modal — page is identical to pre-T3.5")
    (is (not (str/includes? body "ai-modal")))))

;; ---------------------------------------------------------------------------
;; etaoin browser smoke — opt-in via `lein test :browser`
;; ---------------------------------------------------------------------------

(deftest ^:browser ai-modal-buttons-visible-in-firefox
  ;; Boots the server with the flag on and a seeded job, drives Firefox
  ;; to /jobs/demo, asserts that both buttons render in a real DOM and
  ;; have the right hx-post targets.  Does NOT click them — we don't
  ;; want to hit the real Anthropic API from CI.  The route-level
  ;; tests above prove the swap target lands correctly.
  ;;
  ;; Opt in:
  ;;   lein test :browser anvil.web.ai-handler-test
  (require '[etaoin.api :as e]
           '[anvil.web.server :as server])
  (let [e-firefox (resolve 'etaoin.api/firefox)
        e-go      (resolve 'etaoin.api/go)
        e-exists? (resolve 'etaoin.api/exists?)
        e-text    (resolve 'etaoin.api/get-element-text-el)
        e-find-el (resolve 'etaoin.api/query)
        e-quit    (resolve 'etaoin.api/quit)
        start!    (resolve 'anvil.web.server/start!)
        stop!     (resolve 'anvil.web.server/stop!)
        port 18767]
    (features/set! :ai-authoring true)
    (register-job! "demo" "pipeline { agent any }")
    (start! {:port port :host "127.0.0.1"})
    (let [driver (e-firefox {:headless true})]
      (try
        (e-go driver (str "http://127.0.0.1:" port "/jobs/demo"))
        (is (e-exists? driver {:css ".ai-authoring-controls"})
            "controls div renders when :ai-authoring is on")
        (is (e-exists? driver {:css "dialog#ai-modal"})
            "modal element is in the DOM, ready for htmx to populate")
        (is (e-exists? driver {:xpath "//button[contains(text(),'Explain')]"})
            "Explain button rendered in the real DOM")
        (is (e-exists? driver {:xpath "//button[contains(text(),'Optimize')]"})
            "Optimize button rendered")
        (finally
          (e-quit driver)
          (stop!))))))
