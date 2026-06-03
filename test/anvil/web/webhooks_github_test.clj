(ns anvil.web.webhooks-github-test
  "Tests for the POST /anvil/webhooks/github route + signature
   verification + repo→job dispatch."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [anvil.features :as features]
            [anvil.integration.github :as gh]
            [anvil.web.webhooks-github :as wh]
            [anvil.web.jenkins-api.jobs :as jobs]))

(defn- fresh-jobs [f]
  (jobs/clear!)
  (try (f)
       (finally (jobs/clear!))))

(use-fixtures :each fresh-jobs)

(deftest returns-503-when-feature-off
  (features/set! :pr-checks false)
  (let [resp (wh/handler {:request-method :post
                          :uri "/anvil/webhooks/github"
                          :headers {}
                          :body ""})]
    (is (= 503 (:status resp)))))

(deftest returns-401-on-bad-signature
  (features/set! :pr-checks true)
  (with-redefs [gh/webhook-secret (constantly "topsecret")]
    (let [resp (wh/handler {:request-method :post
                            :uri "/anvil/webhooks/github"
                            :headers {"x-hub-signature-256" "sha256=bogus0000"
                                      "x-github-event" "pull_request"}
                            :body "{}"})]
      (is (= 401 (:status resp))))))

(deftest accepts-correctly-signed-pull-request-event
  (features/set! :pr-checks true)
  (with-redefs [gh/webhook-secret (constantly "topsecret")
                ;; Stub jobs/record-build-start! so we don't need a DB
                jobs/record-build-start! (fn [_ _] nil)]
    (let [body (json/write-str
                {:action "opened"
                 :repository {:full_name "foo/bar"}
                 :pull_request {:head {:sha "abc123" :ref "feature"}}})
          ;; Stub repo→job lookup by stubbing the config loader
          calls (atom 0)
          orig (resolve 'anvil.web.webhooks-github/repo->job-name)]
      (with-redefs [anvil.web.webhooks-github/repo->job-name
                    (fn [_repo] (swap! calls inc) "demo")]
        (let [sig (str "sha256=" (gh/hmac-sha256 "topsecret" body))
              resp (wh/handler {:request-method :post
                                :uri "/anvil/webhooks/github"
                                :headers {"x-hub-signature-256" sig
                                          "x-github-event" "pull_request"}
                                :body body})]
          (is (= 200 (:status resp)))
          (is (pos? @calls)))))))

(deftest unknown-event-still-200s
  (features/set! :pr-checks true)
  (with-redefs [gh/webhook-secret (constantly nil)]   ; sig-not-required
    (let [resp (wh/handler {:request-method :post
                            :uri "/anvil/webhooks/github"
                            :headers {"x-github-event" "issue_comment"}
                            :body "{}"})]
      (is (= 200 (:status resp))))))
