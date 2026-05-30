(ns anvil.core-test
  "Smoke tests for the anvil daemon: the handler responds, version flows,
   the 501 Jenkins stub is in place, the health probe answers true."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.web.routes :as routes]
            [anvil.version :as v]
            [clojure.data.json :as json]))

(defn- req
  "Synthesize a minimal Ring request map for a GET to `uri`."
  [uri]
  {:request-method :get :uri uri :headers {} :body nil})

(deftest status-page-renders-test
  (testing "GET / returns the HTML status page with anvil branding"
    (let [handler (routes/make-handler)
          resp (handler (req "/"))]
      (is (= 200 (:status resp)))
      (is (= "text/html; charset=utf-8" (get-in resp [:headers "Content-Type"])))
      (is (= v/version (get-in resp [:headers "X-Anvil-Version"])))
      (is (re-find #"(?i)anvil" (:body resp)))
      (is (re-find #"(?i)Drop-in Jenkins replacement" (:body resp))))))

(deftest api-status-returns-json-test
  (testing "GET /api/status returns a structured JSON status"
    (let [handler (routes/make-handler)
          resp (handler (req "/api/status"))
          body (json/read-str (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "anvil" (get body "product")))
      (is (= v/version (get body "version")))
      (is (= "alpha-skeleton" (get body "status"))))))

(deftest health-probe-true-test
  (testing "GET /api/health responds true for ready"
    (let [handler (routes/make-handler)
          resp (handler (req "/api/health"))
          body (json/read-str (:body resp))]
      (is (= 200 (:status resp)))
      (is (true? (get body "ready"))))))

(deftest jenkins-shim-root-returns-real-jenkins-shape-test
  (testing "GET /jenkins/api/json returns a real Hudson-shape response (TX6)"
    (let [handler (routes/make-handler)
          resp (handler (req "/jenkins/api/json"))
          body (json/read-str (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "hudson.model.Hudson" (get body "_class"))))))

(deftest jenkins-shim-create-item-still-stubbed-test
  (testing "POST /jenkins/createItem remains a 501 stub — XML-config-as-source-of-truth is out of scope"
    (let [handler (routes/make-handler)
          resp (handler (assoc (req "/jenkins/createItem") :request-method :post))]
      (is (= 501 (:status resp))))))

(deftest unknown-route-404s-test
  (testing "An unknown URL returns 404 with the version header"
    (let [handler (routes/make-handler)
          resp (handler (req "/this-route-does-not-exist"))]
      (is (= 404 (:status resp)))
      (is (= v/version (get-in resp [:headers "X-Anvil-Version"]))))))
