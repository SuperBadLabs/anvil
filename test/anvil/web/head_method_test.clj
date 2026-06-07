(ns anvil.web.head-method-test
  "v0.4 dogfood-driven: confirm HEAD requests on :get-only routes
   no longer 405. Reitit's default behavior is to 405 HEAD when only
   :get is registered; the wrap-head-as-get middleware in
   anvil.web.routes upcasts HEAD to GET and strips the body.

   Locks down: every wrap-feature route (the most operator-visible
   case — surfaced when the v0.4 dogfood hit /flaky with HEAD)
   stays consistent."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.web.routes :as routes]))

(defn- head-req [uri]
  {:request-method :head :uri uri :scheme :http
   :headers {"host" "anvil.test"} :query-params {} :form-params {}})

(defn- get-req [uri]
  {:request-method :get :uri uri :scheme :http
   :headers {"host" "anvil.test"} :query-params {} :form-params {}})

(deftest head-on-feature-disabled-route-returns-404-not-405
  (testing "/flaky with the feature flag off — HEAD returns the same status as GET (404), not 405"
    (let [handler (routes/make-handler)
          get-resp  (handler (get-req "/flaky"))
          head-resp (handler (head-req "/flaky"))]
      (is (= 404 (:status get-resp))
          "GET path returns feature-disabled 404 (sanity)")
      (is (= 404 (:status head-resp))
          "HEAD path also returns 404 — middleware upcasts HEAD → GET")
      (is (nil? (:body head-resp))
          "HEAD response strips the body per HTTP semantics")
      (is (some? (get-in head-resp [:headers "Content-Length"]))
          "Content-Length set to the would-have-been body byte count"))))

(deftest head-on-non-existent-route-stays-404
  (testing "HEAD on a route that doesn't exist still 404s through the default handler"
    (let [handler (routes/make-handler)
          resp (handler (head-req "/no-such-route"))]
      (is (= 404 (:status resp)))
      (is (nil? (:body resp))))))

(deftest head-on-api-status-mirrors-get-status
  (testing "HEAD /api/status — JSON response, body stripped, headers preserved"
    (let [handler (routes/make-handler)
          get-resp  (handler (get-req "/api/status"))
          head-resp (handler (head-req "/api/status"))]
      (is (= 200 (:status get-resp)))
      (is (= 200 (:status head-resp)))
      (is (nil? (:body head-resp)))
      (is (= (get-in get-resp [:headers "Content-Type"])
             (get-in head-resp [:headers "Content-Type"]))
          "Content-Type preserved on HEAD"))))

(deftest head-leaves-non-head-methods-untouched
  (testing "GET requests pass through wrap-head-as-get unchanged (regression guard)"
    (let [handler (routes/make-handler)
          resp (handler (get-req "/api/health"))]
      (is (= 200 (:status resp)))
      (is (some? (:body resp))
          "GET response keeps its body"))))
