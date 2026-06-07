(ns anvil.ai.client-test
  "v0.4.1 T3.1 — tests for anvil.ai.client.

   Hermetic — no real API calls in CI.  The skill's anti-pattern
   list flags tiktoken-style estimators and `temperature+top_p`
   together as 400s; we test the body-builder honors that, plus
   the SSE parser's handling of the actual wire format."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [anvil.ai.client :as client]))

(defn- read-body
  "Pull the JSON body out of a build-body call for assertion."
  [opts]
  (json/read-str (#'client/build-body opts) :key-fn keyword))

;; ---------------------------------------------------------------------------
;; build-body — the prompt-shape contract
;; ---------------------------------------------------------------------------

(deftest build-body-includes-required-fields
  (testing "model, max_tokens, messages always emitted"
    (let [body (read-body {:messages [{:role "user" :content "hi"}]})]
      (is (= "claude-sonnet-4-6" (:model body))
          "v0.4 board default, updated to current 4-6 release")
      (is (= 4096 (:max_tokens body))
          "generous default for code-gen-shaped responses")
      (is (= [{:role "user" :content "hi"}] (:messages body))))))

(deftest build-body-respects-explicit-model
  (testing "operator can override via :anvil.ai/model equivalent"
    (let [body (read-body {:model "claude-opus-4-8"
                           :messages [{:role "user" :content "hi"}]})]
      (is (= "claude-opus-4-8" (:model body))))))

(deftest build-body-omits-sampling-params
  (testing "we NEVER send temperature/top_p/top_k — Opus 4.7+ returns 400"
    (let [body (read-body {:messages [{:role "user" :content "hi"}]})]
      (is (not (contains? body :temperature)))
      (is (not (contains? body :top_p)))
      (is (not (contains? body :top_k)))
      (is (not (contains? body :temperature_p)))))
  (testing "even if a caller tries to slip them through, they don't reach the body"
    (let [body (read-body {:messages [{:role "user" :content "hi"}]
                           :temperature 0.5})]
      (is (not (contains? body :temperature))
          "build-body only consumes the keys it documents"))))

(deftest build-body-system-prompt-optional
  (testing "no :system → no system field in body"
    (let [body (read-body {:messages [{:role "user" :content "hi"}]})]
      (is (not (contains? body :system)))))
  (testing "with :system → present as string"
    (let [body (read-body {:system "You are a Jenkinsfile expert."
                           :messages [{:role "user" :content "hi"}]})]
      (is (= "You are a Jenkinsfile expert." (:system body))))))

(deftest build-body-stream-flag
  (let [no-stream (read-body {:messages [{:role "user" :content "hi"}]})
        streamed  (read-body {:messages [{:role "user" :content "hi"}]
                              :stream? true})]
    (is (not (contains? no-stream :stream)))
    (is (true? (:stream streamed)))))

(deftest build-body-thinking-opt-in
  (testing "thinking off by default (default cost / latency)"
    (let [body (read-body {:messages [{:role "user" :content "hi"}]})]
      (is (not (contains? body :thinking)))))
  (testing ":thinking? true → adaptive thinking (the only supported mode on Opus 4.7+)"
    (let [body (read-body {:messages [{:role "user" :content "hi"}]
                           :thinking? true})]
      (is (= {:type "adaptive"} (:thinking body))))))

(deftest build-body-rejects-empty-messages
  (testing "explicit error rather than letting the API 400"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":messages must be a non-empty"
                          (#'client/build-body {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":messages must be a non-empty"
                          (#'client/build-body {:messages []})))))

;; ---------------------------------------------------------------------------
;; extract-text — content-block walker
;; ---------------------------------------------------------------------------

(deftest extract-text-concatenates-text-blocks
  (is (= "Hello, world"
         (client/extract-text
          {:content [{:type "text" :text "Hello, "}
                     {:type "text" :text "world"}]}))))

(deftest extract-text-skips-non-text-blocks
  (testing "thinking / tool_use blocks are ignored — we want only the answer"
    (is (= "answer text"
           (client/extract-text
            {:content [{:type "thinking" :thinking "...reasoning..."}
                       {:type "text" :text "answer text"}
                       {:type "tool_use" :id "toolu_..." :name "noop"}]})))))

(deftest extract-text-empty-when-no-text-blocks
  (is (= "" (client/extract-text {:content []})))
  (is (= "" (client/extract-text {:content [{:type "thinking" :thinking "..."}]}))))

;; ---------------------------------------------------------------------------
;; SSE parser — wire format
;; ---------------------------------------------------------------------------

(def ^:private golden-sse
  "A realistic SSE stream pulled from the API docs in the claude-api
   skill, shrunk to the events we care about.  Three text deltas,
   then the terminal message_delta + message_stop."
  (str "event: message_start\n"
       "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_01\",\"usage\":{\"input_tokens\":12,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}}}\n"
       "\n"
       "event: content_block_start\n"
       "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n"
       "\n"
       "event: content_block_delta\n"
       "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n"
       "\n"
       "event: content_block_delta\n"
       "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\", \"}}\n"
       "\n"
       "event: content_block_delta\n"
       "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"world\"}}\n"
       "\n"
       "event: content_block_stop\n"
       "data: {\"type\":\"content_block_stop\",\"index\":0}\n"
       "\n"
       "event: message_delta\n"
       "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}\n"
       "\n"
       "event: message_stop\n"
       "data: {\"type\":\"message_stop\"}\n"
       "\n"))

(defn- sse-from-string [s]
  (#'client/sse-event-seq
   (java.io.BufferedReader. (java.io.StringReader. s))))

(deftest sse-parser-yields-event-data-pairs
  (let [events (vec (sse-from-string golden-sse))]
    (is (seq events))
    (testing "first event is message_start with input-token count"
      (let [{:keys [event data]} (first events)]
        (is (= "message_start" event))
        (is (= 12 (get-in data [:message :usage :input_tokens])))))
    (testing "stream terminates at message_stop"
      (is (= "message_stop" (-> events last :event))))))

(deftest collect-stream-text-accumulates-deltas
  (let [result (client/collect-stream-text (sse-from-string golden-sse))]
    (testing "all three text_delta chunks concatenated"
      (is (= "Hello, world" (:text result))))
    (testing "stop_reason surfaced from message_delta"
      (is (= "end_turn" (:stop-reason result))))
    (testing "usage merged across message_start + message_delta"
      (is (= 12 (-> result :usage :input-tokens)))
      (is (= 3  (-> result :usage :output-tokens))))))

(deftest collect-stream-text-handles-empty-stream
  (testing "no events → empty text, nil stop-reason"
    (let [result (client/collect-stream-text [])]
      (is (= "" (:text result)))
      (is (nil? (:stop-reason result))))))

(deftest collect-stream-text-handles-refusal
  (testing "stop_reason: refusal — operator visibility for safety declines"
    (let [refusal-stream (str "event: message_start\n"
                              "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_x\",\"usage\":{\"input_tokens\":5}}}\n"
                              "\n"
                              "event: message_delta\n"
                              "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"},\"usage\":{\"output_tokens\":0}}\n"
                              "\n"
                              "event: message_stop\n"
                              "data: {\"type\":\"message_stop\"}\n"
                              "\n")
          result (client/collect-stream-text (sse-from-string refusal-stream))]
      (is (= "refusal" (:stop-reason result))
          "operators need to see 'refusal' verbatim — masking it would hide
           a real signal the user might need to retry differently"))))

;; ---------------------------------------------------------------------------
;; API-key resolution
;; ---------------------------------------------------------------------------

(deftest read-api-key-throws-with-actionable-message-when-missing
  (let [orig (System/getenv "ANTHROPIC_API_KEY")]
    (try
      ;; with-redefs won't catch System/getenv; verify behavior with a
      ;; mocked System property fallback would be circular.  Instead:
      ;; if the key happens to be unset in the test env, assert; else
      ;; assert that calling messages with an unparseable key shape
      ;; produces a sensible error path (we can't actually invoke the
      ;; network from CI).  Skip cleanly when the env has the key set.
      (if (some? orig)
        (is true "ANTHROPIC_API_KEY set in env — skipping miss-path assertion")
        (let [thrown (try (#'client/read-api-key)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo thrown))
          (is (re-find #"ANTHROPIC_API_KEY" (ex-message thrown)))
          (is (contains? (ex-data thrown) :fix)
              "error includes operator-actionable :fix hint")))
      (finally nil))))
