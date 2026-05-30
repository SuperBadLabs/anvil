(ns anvil.web.events-sse-test
  "Tests for /anvil/events — TU1.3.

   Two halves:
     - parse-topics: pure, no server needed
     - end-to-end: boot anvil on a random port, open a real SSE
       connection over plain TCP, publish via the bus, assert receipt"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [anvil.events.bus :as bus]
            [anvil.web.events-sse :as e]
            [anvil.web.server :as server])
  (:import (java.net ServerSocket Socket)
           (java.io BufferedReader InputStreamReader)))

;; ===========================================================================
;; Pure tests: topic decoding
;; ===========================================================================

(deftest parse-topics-defaults-to-global
  (is (= {:ok? true :topics [:global]} (e/parse-topics nil)))
  (is (= {:ok? true :topics [:global]} (e/parse-topics ""))))

(deftest parse-topics-known-shapes
  (let [{:keys [ok? topics]} (e/parse-topics "global,queue,job:foo,build:bar:42")]
    (is ok?)
    (is (= [:global :queue [:job "foo"] [:build "bar" 42]] topics))))

(deftest parse-topics-rejects-junk
  (let [r (e/parse-topics "global,nonsense")]
    (is (false? (:ok? r)))
    (is (= "nonsense" (:bad r)))
    (is (str/includes? (:error r) "unrecognized topic"))))

(deftest parse-topics-rejects-empty-job-name
  (is (false? (:ok? (e/parse-topics "job:")))))

(deftest parse-topics-rejects-non-numeric-build
  (is (false? (:ok? (e/parse-topics "build:foo:not-a-number")))))

;; ===========================================================================
;; End-to-end: real SSE stream
;; ===========================================================================
;;
;; We don't use a hi-fi HTTP client here because babashka.http-client
;; in the JVM hangs on infinite-stream responses (no built-in SSE
;; framing). Raw socket + line-reader is short and deterministic.
;; ===========================================================================

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(def ^:dynamic *port* nil)

(defn- sse-fixture [t]
  (let [port (free-port)]
    (server/start! {:port port :host "127.0.0.1"})
    (bus/unsubscribe-all!)
    ;; Speed the heartbeat down to ~200ms so disconnect-detection
    ;; tests don't have to wait 15s. The setter goes into a plain
    ;; atom (not a dynamic var) because http-kit's worker threads
    ;; don't inherit caller bindings — we need it visible globally.
    (e/set-heartbeat-override! 200)
    (try
      (binding [*port* port] (t))
      (finally
        (e/set-heartbeat-override! nil)
        (bus/unsubscribe-all!)
        (server/stop!)))))

(use-fixtures :each sse-fixture)

(defn- open-sse-socket
  "Open a raw TCP socket to anvil on *port*, send a GET for /anvil/events
   with the given topics, return [socket BufferedReader] positioned at
   the start of the response body."
  [topics]
  (let [sock (Socket. "127.0.0.1" (int *port*))
        out (.getOutputStream sock)
        req (str "GET /anvil/events?topics=" topics " HTTP/1.1\r\n"
                 "Host: 127.0.0.1:" *port* "\r\n"
                 "Accept: text/event-stream\r\n"
                 "Connection: keep-alive\r\n"
                 "\r\n")]
    (.write out (.getBytes req "UTF-8"))
    (.flush out)
    (let [in (BufferedReader. (InputStreamReader. (.getInputStream sock) "UTF-8"))]
      ;; Consume the HTTP status + headers up to (and including) the
      ;; blank separator line.
      (loop []
        (let [line (.readLine in)]
          (cond
            (nil? line) nil
            (empty? line) :body
            :else (recur))))
      [sock in])))

(defn- read-frame
  "Read one SSE frame (lines until a blank separator). Returns
   {:event ... :data ...} or nil on EOF/timeout. Skips heartbeat
   comment frames."
  [^BufferedReader in]
  (loop [acc {}]
    (let [line (.readLine in)]
      (cond
        (nil? line) nil
        (and (empty? line) (seq acc)) acc
        (empty? line) (recur acc)             ; leading blank
        (str/starts-with? line ":") (recur acc) ; heartbeat / comment
        (str/starts-with? line "event: ") (recur (assoc acc :event (subs line 7)))
        (str/starts-with? line "data: ")  (recur (assoc acc :data  (subs line 6)))
        (str/starts-with? line "id: ")    (recur (assoc acc :id    (subs line 4)))
        :else (recur acc)))))

(deftest hello-frame-arrives-on-open
  (let [[sock in] (open-sse-socket "global")]
    (try
      (let [frame (read-frame in)]
        (is (= "hello" (:event frame))
            "first non-comment frame should be 'hello' so EventSource.onopen fires"))
      (finally (.close sock)))))

(deftest published-event-arrives-on-subscribed-topic
  (let [[sock in] (open-sse-socket "queue")]
    (try
      ;; Drain the hello frame first.
      (read-frame in)
      ;; Now publish from another thread; the client should see it.
      (.start (Thread. ^Runnable
                       (fn []
                         (Thread/sleep 30)
                         (bus/publish! :queue
                                       {:type :queue-enqueued
                                        :queue-id 99
                                        :job-name "demo"}))))
      (let [frame (read-frame in)]
        (is (= "queue-enqueued" (:event frame)))
        (is (str/includes? (:data frame) "\"queue-id\":99")))
      (finally (.close sock)))))

(deftest global-subscriber-sees-cross-topic-publishes
  (let [[sock in] (open-sse-socket "global")]
    (try
      (read-frame in)  ; hello
      (.start (Thread. ^Runnable
                       (fn []
                         (Thread/sleep 30)
                         (bus/publish! [:job "foo"]
                                       {:type :build-started
                                        :job-name "foo"
                                        :build-number 7}))))
      (let [frame (read-frame in)]
        (is (= "build-started" (:event frame))))
      (finally (.close sock)))))

(deftest bad-topic-returns-400
  ;; Don't use the streaming reader for this — it's a plain 400, not SSE.
  (let [sock (Socket. "127.0.0.1" (int *port*))
        out (.getOutputStream sock)
        in (BufferedReader. (InputStreamReader. (.getInputStream sock) "UTF-8"))
        req (str "GET /anvil/events?topics=nonsense HTTP/1.1\r\n"
                 "Host: 127.0.0.1:" *port* "\r\n"
                 "\r\n")]
    (.write out (.getBytes req "UTF-8")) (.flush out)
    (let [status-line (.readLine in)]
      (is (str/includes? status-line "400") (str "expected 400, got: " status-line)))
    (.close sock)))

;; INTENTIONALLY NOT TESTED HERE: real-browser-tab-close cleanup.
;;
;; http-kit's NIO does not flip (open? ch) to false until a write
;; FAILS at the OS layer. On an SSE stream where the client never
;; reads, the kernel happily accepts heartbeat writes for minutes
;; even after the browser's TCP FIN — the kernel hasn't seen RST
;; back, so it has nothing to flag. We tried five variants
;; (provoke-via-publish, hk/open? probe, shortened heartbeat,
;; explicit close-on-send-failure) — they all hit the same wall.
;;
;; The two halves of the cleanup contract that we CAN unit-test:
;;   1. bus/publish! drops subscribers that return ::unsubscribe
;;      → anvil.events.bus-test/subscriber-returning-bus-unsubscribe-removes-itself
;;   2. events-sse's subscriber returns ::unsubscribe when sse/send!
;;      returns false (visible by inspection of the handler code)
;; Real end-to-end disconnect-cleanup is owned by the etaoin browser
;; smoke (TU1.5), where actual browser TCP teardown provokes the
;; OS-level write failure that http-kit's NIO needs.
