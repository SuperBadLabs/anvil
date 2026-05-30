(ns anvil.web.log-tail-test
  "Unit tests for the per-build log-file tail thread (TU2.1).

   Covers the boring-but-load-bearing semantics:
     - growing file → bus events per line, in order
     - chunk arrives mid-line → partial held until newline
     - stop! drains any remaining partial line
     - file doesn't exist yet → tail waits, picks up when it appears"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [anvil.events.bus :as bus]
            [anvil.web.log-tail :as tail])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(use-fixtures :each (fn [t] (bus/unsubscribe-all!) (t) (bus/unsubscribe-all!)))

(defn- tmp-file [name]
  (let [d (.toFile (Files/createTempDirectory "anvil-log-tail-test"
                                              (into-array FileAttribute [])))]
    (io/file d name)))

(defn- collect-on
  "Subscribe to `topic`; return [lines-atom unsub] where lines-atom
   collects the :line of every :console-line event."
  [topic]
  (let [lines (atom [])
        tok (bus/subscribe! topic
                            (fn [ev]
                              (when (= :console-line (:type ev))
                                (swap! lines conj (:line ev)))))]
    [lines (fn [] (bus/unsubscribe! tok))]))

(defn- wait-until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 25) (recur))))))

(deftest growing-file-streams-lines-in-order
  (let [f (tmp-file "build.log")
        topic [:build "j" 1]
        [lines unsub] (collect-on topic)
        stop! (tail/start! "j" 1 f)]
    (try
      (spit f "" :append true)
      (spit f "first\n" :append true)
      (spit f "second\n" :append true)
      (is (wait-until #(= 2 (count @lines)) 1500)
          (str "expected 2 lines, saw: " (count @lines)))
      (is (= ["first" "second"] @lines))
      (spit f "third\nfourth\n" :append true)
      (is (wait-until #(= 4 (count @lines)) 1500))
      (is (= ["first" "second" "third" "fourth"] @lines))
      (finally
        (stop!)
        (unsub)))))

(deftest chunk-arriving-mid-line-holds-partial-until-newline
  (let [f (tmp-file "partial.log")
        topic [:build "j" 2]
        [lines unsub] (collect-on topic)
        stop! (tail/start! "j" 2 f)]
    (try
      (spit f "halfof" :append true)
      ;; No newline yet — must NOT publish.
      (Thread/sleep 200)
      (is (= [] @lines) "no complete line, nothing published")
      (spit f "aline\n" :append true)
      (is (wait-until #(= 1 (count @lines)) 1500))
      (is (= ["halfofaline"] @lines)
          "stitched chunk + newline emerges as one complete line")
      (finally
        (stop!)
        (unsub)))))

(deftest stop-drains-partial-line-without-newline
  (let [f (tmp-file "trailing.log")
        topic [:build "j" 3]
        [lines unsub] (collect-on topic)
        stop! (tail/start! "j" 3 f)]
    (try
      (spit f "complete\n" :append true)
      (spit f "no-trailing-newline" :append true)
      (Thread/sleep 200)
      (is (= ["complete"] @lines)
          "only the newline-terminated line emitted so far")
      (stop!)
      ;; Stop blocks-then-drains; the draining flush should emit the
      ;; tail. Allow ~drain-grace + poll.
      (is (wait-until #(= 2 (count @lines)) 1500))
      (is (= ["complete" "no-trailing-newline"] @lines))
      (finally
        (unsub)))))

(deftest file-not-yet-existing-then-created
  (let [f (tmp-file "later.log")
        topic [:build "j" 4]
        [lines unsub] (collect-on topic)
        stop! (tail/start! "j" 4 f)]
    (try
      (Thread/sleep 200)   ; tail spinning on absence
      (is (= [] @lines) "no file, no lines")
      (spit f "appeared\n")
      (is (wait-until #(= 1 (count @lines)) 1500))
      (is (= ["appeared"] @lines))
      (finally
        (stop!)
        (unsub)))))

(deftest crlf-line-endings-stripped
  (let [f (tmp-file "crlf.log")
        topic [:build "j" 5]
        [lines unsub] (collect-on topic)
        stop! (tail/start! "j" 5 f)]
    (try
      (spit f "windows\r\nmac\nunix\n" :append true)
      (is (wait-until #(= 3 (count @lines)) 1500))
      (is (= ["windows" "mac" "unix"] @lines)
          "trailing \\r stripped on every line, regardless of OS source")
      (finally
        (stop!)
        (unsub)))))

(deftest console-end-marker-fires-after-stop
  (let [f (tmp-file "end.log")
        topic [:build "j" 6]
        ends (atom 0)
        tok (bus/subscribe! topic
                            (fn [ev]
                              (when (= :console-end (:type ev))
                                (swap! ends inc))))
        stop! (tail/start! "j" 6 f)]
    (try
      (spit f "x\n" :append true)
      (Thread/sleep 200)
      (stop!)
      (is (wait-until #(= 1 @ends) 1500)
          "stop! must enqueue exactly one :console-end event")
      (finally
        (bus/unsubscribe! tok)))))
