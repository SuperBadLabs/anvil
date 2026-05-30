(ns anvil.web.queue-test
  "Async build queue tests. Covers:
     - enqueue/dequeue mechanics
     - per-job concurrency cap (default 1)
     - cancellation before pickup
     - end-to-end: trigger → queue → worker → build complete"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.routes :as routes]))

(defn- mk-req
  ([method uri] (mk-req method uri {}))
  ([method uri {:keys [headers]}]
   {:request-method method
    :uri uri
    :scheme :http
    :headers (merge {"host" "anvil.test"} headers)
    :query-params {} :form-params {}}))

(defn- wait-until
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) :done
        (> (System/currentTimeMillis) deadline) :timeout
        :else (do (Thread/sleep 25) (recur))))))

(use-fixtures :each
  (fn [f]
    (jobs/clear!)
    (queue/clear!)
    (f)
    (queue/clear!)
    (jobs/clear!)))

;; ---------------------------------------------------------------------------
;; Pure-data queue mechanics
;; ---------------------------------------------------------------------------

(deftest enqueue-returns-monotonic-ids-test
  (testing "enqueue! returns increasing queue ids"
    (jobs/register-job!
     {:name "demo" :jenkinsfile-source "pipeline { agent any; stages { stage('S') { steps { echo 'hi' } } } }"})
    (let [a (queue/enqueue! "demo" {})
          b (queue/enqueue! "demo" {})
          c (queue/enqueue! "demo" {})]
      (is (< (:queue-id a) (:queue-id b)))
      (is (< (:queue-id b) (:queue-id c))))))

(deftest queue-snapshot-reflects-additions-test
  (testing "queue-snapshot returns the pending items in FIFO order"
    (jobs/register-job! {:name "demo" :jenkinsfile-source "pipeline { }"})
    (queue/enqueue! "demo" {})
    (queue/enqueue! "demo" {:parameters {"VERSION" "1.0"}})
    (let [items (queue/queue-snapshot)]
      (is (= 2 (count items)))
      (is (= "demo" (-> items first :job-name)))
      (is (= {"VERSION" "1.0"} (-> items second :parameters))))))

(deftest cancel-marks-queued-item-test
  (testing "cancel! flips :cancelled? on a queued item"
    (jobs/register-job! {:name "demo" :jenkinsfile-source "pipeline { }"})
    (let [item (queue/enqueue! "demo" {})]
      (is (true? (queue/cancel! (:queue-id item))))
      (let [snap (queue/queue-snapshot)]
        (is (true? (-> snap first :cancelled?)))))))

;; ---------------------------------------------------------------------------
;; Worker dispatch
;; ---------------------------------------------------------------------------

(deftest worker-dispatches-queued-item-test
  (testing "a worker picks up a queued item and invokes the run-fn"
    (jobs/register-job! {:name "demo" :jenkinsfile-source "pipeline { }"})
    (let [calls (atom [])
          done? (atom false)
          run-fn (fn [job-name opts]
                   (swap! calls conj {:job-name job-name :opts opts})
                   (reset! done? true))]
      (queue/start-workers! 1 run-fn)
      (queue/enqueue! "demo" {:parameters {"K" "V"}})
      (let [r (wait-until #(deref done?) 2000)]
        (is (= :done r) "worker ran the run-fn within 2s"))
      (is (= 1 (count @calls)))
      (is (= "demo" (-> @calls first :job-name)))
      (is (= {"K" "V"} (-> @calls first :opts :parameters))))))

(deftest cancelled-item-is-skipped-test
  (testing "a cancelled item is dropped from the queue before pickup;
            run-fn is never invoked for it"
    (jobs/register-job! {:name "demo" :jenkinsfile-source "pipeline { }"})
    (let [calls (atom [])
          run-fn (fn [job-name opts] (swap! calls conj job-name))
          item (queue/enqueue! "demo" {})
          _ (queue/cancel! (:queue-id item))]
      (queue/start-workers! 1 run-fn)
      (Thread/sleep 400)
      (is (empty? @calls)))))

(deftest per-job-concurrency-cap-test
  (testing "with max-concurrent-builds=1, a second request waits while the first runs"
    (jobs/register-job!
     {:name "single"
      :jenkinsfile-source "pipeline { }"
      :max-concurrent-builds 1})
    (let [latch (java.util.concurrent.CountDownLatch. 1)
          started (atom 0)
          finished (atom 0)
          run-fn (fn [job-name _opts]
                   (swap! started inc)
                   (.await latch)             ; block until released
                   (swap! finished inc))]
      (queue/start-workers! 2 run-fn)         ; 2 workers
      (queue/enqueue! "single" {})
      (queue/enqueue! "single" {})
      (Thread/sleep 200)
      (is (= 1 @started) "only one build runs at a time for this job")
      (.countDown latch)
      (wait-until #(= 2 @finished) 2000)
      (is (= 2 @finished)))))

;; ---------------------------------------------------------------------------
;; Full HTTP trigger → queue → worker path
;; ---------------------------------------------------------------------------

(deftest trigger-build-enqueues-and-returns-queue-id-test
  (testing "POST /jenkins/job/<n>/build enqueues and returns a queue id"
    (jobs/register-job!
     {:name "demo"
      :jenkinsfile-source "pipeline { agent any; stages { stage('S') { steps { echo 'hi' } } } }"})
    (let [handler (routes/make-handler)
          resp (handler (mk-req :post "/jenkins/job/demo/build"))
          body (json/read-str (:body resp))]
      (is (= 201 (:status resp)))
      (is (str/starts-with? (get-in resp [:headers "Location"])
                            "/jenkins/queue/item/"))
      (is (number? (get body "id")))
      (is (= "demo" (get-in body ["task" "name"]))))))

(deftest queue-endpoint-shows-pending-items-test
  (testing "GET /jenkins/queue/api/json returns the pending items"
    (jobs/register-job! {:name "demo" :jenkinsfile-source "pipeline { }"})
    (queue/enqueue! "demo" {})
    (let [handler (routes/make-handler)
          resp (handler (mk-req :get "/jenkins/queue/api/json"))
          body (json/read-str (:body resp))]
      (is (= 200 (:status resp)))
      (is (= 1 (count (get body "items"))))
      (is (= "demo" (get-in body ["items" 0 "task" "name"]))))))