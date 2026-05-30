(ns anvil.web.sse-test
  "Tests for the SSE wire-format helpers. Live channel behavior is
   exercised by the etaoin smoke (TU0.5) and the TU1 bus integration
   tests; here we just nail down the framing contract."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [anvil.web.sse :as sse]))

(deftest sse-headers-contract
  (let [h (sse/sse-headers)]
    (testing "Content-Type signals SSE"
      (is (= "text/event-stream; charset=utf-8" (get h "Content-Type"))))
    (testing "Caching disabled — clients must NOT reuse SSE responses"
      (is (str/includes? (get h "Cache-Control") "no-cache")))
    (testing "Connection kept alive"
      (is (= "keep-alive" (get h "Connection"))))
    (testing "Proxy buffering disabled (nginx X-Accel-Buffering)"
      (is (= "no" (get h "X-Accel-Buffering"))))))

(deftest format-event-frames
  ;; Reach into the private to test the wire format directly — the
  ;; channel-bound API is tested in TU1 integration.
  (let [fmt #'sse/format-event]
    (testing "string data: single data line, trailing blank"
      (is (= "data: hello\n\n"
             (fmt {:data "hello"}))))
    (testing "map data: JSON-encoded"
      (let [out (fmt {:data {:n 42 :s "x"}})]
        (is (str/starts-with? out "data: "))
        (is (str/ends-with? out "\n\n"))
        ;; JSON map field order is not guaranteed, just check both keys
        (is (str/includes? out "\"n\":42"))
        (is (str/includes? out "\"s\":\"x\""))))
    (testing "event-type emits event: line BEFORE data:"
      (let [out (fmt {:event-type "build-done" :data "ok"})]
        (is (= "event: build-done\ndata: ok\n\n" out))))
    (testing "id emits id: line FIRST"
      (let [out (fmt {:id "42" :event-type "tick" :data "ok"})]
        (is (= "id: 42\nevent: tick\ndata: ok\n\n" out))))
    (testing "frame always ends with the blank-line separator"
      (doseq [evt [{:data "x"}
                   {:event-type "e" :data "x"}
                   {:id "1" :data "x"}
                   {:id "1" :event-type "e" :data "x"}]]
        (is (str/ends-with? (fmt evt) "\n\n")
            (str "missing terminator on " evt))))))

(deftest heartbeat-frame-is-comment
  ;; Comment lines start with `:` and are ignored by browsers, but
  ;; proxies see them as traffic. Critical for not getting timed out.
  (let [hb #'sse/heartbeat-frame]
    (is (= ":\n\n" (hb))
        "heartbeat must be a single comment line with the blank-line terminator")))
