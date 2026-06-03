(ns anvil.web.log-tail
  "Per-build log-file tail thread (TU2.1).

   When a build starts, runner/run-build! spawns one of these to
   read the on-disk console log incrementally and publish each
   complete line as a :console-line event on the
   [:build job-name build-number] bus topic.

   Why tail-the-file and not intercept inside the dispatcher:

     - The dispatcher's streaming mode (the default) hands stdout +
       stderr directly to a ProcessBuilder$Redirect, which forks all
       I/O to the kernel — anvil never sees the bytes in-process.
       Adding a line-callback intercept would mean rewriting
       shell-execute to pump streams in-JVM, regressing throughput
       on large outputs and risking secrets-masker bypasses.

     - The on-disk file is already the source of truth (the
       /consoleText REST endpoint reads it). Tailing it is purely
       additive: no engine changes, no contention with the writer.

   Mechanics:

     - Polls the file's length every 50 ms (TODO: java.nio
       WatchService when the leak it causes on linux+JDK 21 is fixed).
     - Reads any newly-appended bytes via RandomAccessFile at the
       cursor position, appends to a small StringBuilder buffer.
     - Splits the buffer on `\\n` (handling `\\r\\n`); emits each
       complete line as a bus event. Trailing partial line stays in
       the buffer for the next iteration.
     - On stop!: drains, emits any partial line, exits.

   Performance:

     - 50 ms poll period → ≤50 ms latency from `printf` in the
       subprocess to the browser DOM.
     - Each cycle reads at most (current-length minus last-position)
       bytes. On a 10k-line/sec emitter this is ~500 KB/cycle —
       trivial.
     - Single tail thread per build. The bus + SSE fan-out handles
       N subscribers without contention."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [anvil.events.bus :as bus]
            [taoensso.timbre :as log])
  (:import (java.io File RandomAccessFile)))

(def ^:private poll-interval-ms 50)
(def ^:private absent-file-poll-ms 100)
(def ^:private drain-grace-ms 200)

(defn- emit-problem-if-match!
  "v0.3 T2.2 — when the :problem-matchers feature is enabled, run
   the line through anvil.compat.problem-matchers/match-line. On a
   hit, publish :problem-found on the build's topic AND persist via
   anvil.storage.problems. Catches everything — a matcher bug must
   not kill the tail thread."
  [topic ^long seq-no ^String line]
  (try
    (let [feat-on? ((requiring-resolve 'anvil.features/enabled?) :problem-matchers)]
      (when feat-on?
        (let [match-line-fn (requiring-resolve 'anvil.compat.problem-matchers/match-line)
              p (match-line-fn line)]
          (when p
            (bus/publish! topic
                          {:type :problem-found
                           :seq seq-no
                           :problem p})
            ;; Topic shape: [:build <job> <n>]. Persist only when it
            ;; matches that shape so dispatcher unit tests (which
            ;; publish to ad-hoc topics) don't try to write to a
            ;; non-existent build row.
            (when (and (vector? topic) (= :build (first topic)))
              (let [job-name (nth topic 1 nil)
                    build-number (nth topic 2 nil)
                    record! (requiring-resolve 'anvil.storage.problems/record-problem!)]
                (when (and job-name build-number)
                  (record! job-name build-number seq-no p))))))))
    (catch Throwable t
      (log/warn t "anvil.web.log-tail/emit-problem-if-match! threw — line skipped"))))

(defn- emit-line!
  "Publish one line as a bus event on the build's topic. `seq-no` is
   monotonically increasing per tail thread — useful for clients that
   reconnect mid-build and want to dedup.

   T2.2 — after the console-line publish, runs the line through the
   problem-matcher framework (gated on the :problem-matchers flag)."
  [topic ^long seq-no stream line]
  (bus/publish! topic
                {:type :console-line
                 :seq seq-no
                 :stream stream
                 :line line})
  (emit-problem-if-match! topic seq-no line))

(defn- split-and-emit!
  "Drain complete lines (delimited by `\\n`, with optional `\\r`
   pair) from the buffer, publishing each. Leaves any trailing
   partial line in the buffer."
  [^StringBuilder buf topic seq-no-atom]
  (let [s (.toString buf)
        last-nl (.lastIndexOf s "\n")]
    (when (>= last-nl 0)
      (let [complete-block (subs s 0 last-nl)
            remainder (subs s (inc last-nl))
            ;; rebuild the buffer with just the remainder
            _ (.setLength buf 0)
            _ (.append buf remainder)
            lines (str/split complete-block #"\n")]
        (doseq [raw lines]
          (let [line (if (and (pos? (count raw))
                              (= \return (.charAt raw (dec (count raw)))))
                       (subs raw 0 (dec (count raw)))
                       raw)
                seq-no (swap! seq-no-atom inc)]
            (emit-line! topic seq-no :stdout line)))))))

(defn- read-new-bytes!
  "Read [pos, len) into a String from `file`. Returns the String, or
   nil if no new bytes."
  [^File file ^long pos]
  (let [len (.length file)]
    (when (> len pos)
      (with-open [raf (RandomAccessFile. file "r")]
        (.seek raf pos)
        (let [need (- len pos)
              buf (byte-array need)]
          (.readFully raf buf)
          {:bytes-read need
           :text (String. buf "UTF-8")})))))

(defn start!
  "Spawn a tail thread for `log-file`. Returns a 0-arg stop! fn that
   signals the thread to drain and exit. Idempotent — calling stop!
   twice is fine.

   The thread is daemon; the JVM won't wait on it to exit. Callers
   SHOULD call stop! at end-of-build so the partial line (if any)
   gets emitted promptly, but a missed stop! is not catastrophic —
   the thread sees the file stop growing and idles at 50 ms poll."
  [job-name build-number ^File log-file]
  (let [topic [:build job-name build-number]
        stop? (atom false)
        seq-no (atom 0)
        partial-buf (StringBuilder.)
        pos-cursor (atom 0)]
    (doto (Thread.
           ^Runnable
           (fn []
             (try
               ;; The file may not exist yet (subprocess hasn't opened
               ;; it). Wait briefly with a longer interval.
               (while (and (not @stop?) (not (.exists log-file)))
                 (Thread/sleep absent-file-poll-ms))
               (while (not @stop?)
                 (when-let [{:keys [bytes-read text]} (read-new-bytes! log-file @pos-cursor)]
                   (swap! pos-cursor + bytes-read)
                   (.append partial-buf text)
                   (split-and-emit! partial-buf topic seq-no))
                 (Thread/sleep poll-interval-ms))
               ;; Drain pass after stop! — give the writer a brief
               ;; grace window to flush.
               (Thread/sleep drain-grace-ms)
               (when-let [{:keys [bytes-read text]}
                          (read-new-bytes! log-file @pos-cursor)]
                 (swap! pos-cursor + bytes-read)
                 (.append partial-buf text))
               (split-and-emit! partial-buf topic seq-no)
               ;; Emit any trailing partial line so the last log
               ;; line on a no-newline-terminated stream still shows.
               (when (pos? (.length partial-buf))
                 (let [n (swap! seq-no inc)]
                   (emit-line! topic n :stdout (.toString partial-buf))))
               (bus/publish! topic
                             {:type :console-end
                              :total-lines @seq-no})
               (catch InterruptedException _ nil)
               (catch Throwable t
                 (log/warn t "log-tail thread crashed for"
                           job-name "#" build-number))))
           (str "anvil-log-tail-" job-name "-" build-number))
      (.setDaemon true)
      (.start))
    (fn stop! [] (reset! stop? true))))
