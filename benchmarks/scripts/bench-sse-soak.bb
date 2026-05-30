#!/usr/bin/env bb

;; bench-sse-soak.bb — TU1.7 soak test for anvil's SSE bus.
;;
;; The "many tabs open" promise: 100 concurrent EventSource sessions
;; consume < 5% CPU at idle on the bench host. Validates that the
;; in-process bus + http-kit's NIO scale to "every dev keeps 10 tabs
;; open" workloads without burning the box.
;;
;; Assumes anvil is already running. Defaults to http://localhost:8765,
;; matching bench-vs-jenkins.bb + bench-ui.bb so harnesses chain.
;;
;; Methodology:
;;   1. Open N parallel TCP connections to /anvil/events?topics=global
;;   2. Each connection runs a reader thread that consumes frames
;;      (so we don't artificially backpressure the server)
;;   3. Idle the connections for `soak-seconds`
;;   4. Sample anvil's CPU usage via /proc/<pid>/stat at start and end
;;   5. Report avg %CPU over the soak window
;;
;; Usage:
;;   ./bench-sse-soak.bb                            # 100 sessions × 30s
;;   ./bench-sse-soak.bb --sessions 200 --soak 60
;;   ./bench-sse-soak.bb --anvil-url http://localhost:8080
;;   ./bench-sse-soak.bb --emit-edn results/sse-soak-2026-05-30.edn

(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[clojure.tools.cli :as cli]
         '[clojure.pprint :as pp])

(import '(java.net Socket)
        '(java.io BufferedReader InputStreamReader))

(def cli-options
  [[nil "--sessions N"   "Concurrent SSE sessions" :default 100 :parse-fn #(Integer/parseInt %)]
   [nil "--soak N"       "Seconds to idle"         :default 30  :parse-fn #(Integer/parseInt %)]
   [nil "--anvil-url URL" "" :default "http://localhost:8765"]
   [nil "--emit-edn PATH" "If set, write the result map as EDN to PATH"]
   ["-h" "--help"]])

;; ---------------------------------------------------------------------------
;; Find anvil's pid by port — works on Linux/macOS without lsof asnoff
;; ---------------------------------------------------------------------------

(defn- anvil-pid
  "Use /proc/net/tcp + /proc/<pid>/fd/* to find the JVM listening on
   the given port. Linux-only; falls back to nil on macOS (CPU
   sampling is skipped in that case)."
  [port]
  (when (fs/exists? "/proc/net/tcp")
    (let [hex-port (format "%04X" port)]
      (some (fn [pid]
              (let [fd-dir (str "/proc/" pid "/fd")]
                (when (fs/readable? fd-dir)
                  (let [matches?
                        (try
                          (->> (fs/list-dir fd-dir)
                               (some (fn [f]
                                       (try
                                         (let [tgt (str (fs/read-link f))]
                                           (str/includes? tgt
                                                          (str "socket:[" hex-port)))
                                         (catch Exception _ false)))))
                          (catch Exception _ false))]
                    (when matches? pid)))))
            ;; Iterate over /proc/<n> dirs
            (->> (fs/list-dir "/proc")
                 (map fs/file-name)
                 (filter #(re-matches #"\d+" %)))))))

(defn- read-pid-cpu-ms
  "Read /proc/<pid>/stat field 14 (utime) + 15 (stime), in clock ticks.
   Convert to ms via Hertz (assume 100 — Linux default). Returns nil
   on read failure."
  [pid]
  (try
    (let [stat (slurp (str "/proc/" pid "/stat"))
          ;; comm field can have spaces inside parens; split AFTER )
          after-comm (subs stat (inc (.lastIndexOf stat ")")))
          fields (->> (str/split after-comm #"\s+") (remove str/blank?) vec)
          ;; field 14 = utime, 15 = stime (1-indexed including pid+comm),
          ;; which after-comm puts at indexes 11 and 12 (0-indexed)
          utime (Long/parseLong (nth fields 11))
          stime (Long/parseLong (nth fields 12))]
      (* 10 (+ utime stime)))   ; assume 100 Hz → 10ms per tick
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; SSE session
;; ---------------------------------------------------------------------------

(defn- parse-url
  "Crude — just split http://host:port[/path]"
  [url]
  (let [m (re-matches #"http://([^:/]+):(\d+)(.*)" url)]
    {:host (nth m 1)
     :port (Integer/parseInt (nth m 2))
     :path (or (nth m 3) "/")}))

(defn- open-session
  "Open one SSE connection. Returns the Socket so the caller can
   later close it. A daemon thread drains the input stream."
  [{:keys [host port]} topics]
  (let [sock (Socket. ^String host (int port))
        out (.getOutputStream sock)
        req (str "GET /anvil/events?topics=" topics " HTTP/1.1\r\n"
                 "Host: " host ":" port "\r\n"
                 "Accept: text/event-stream\r\n"
                 "Connection: keep-alive\r\n\r\n")]
    (.write out (.getBytes req "UTF-8"))
    (.flush out)
    (let [reader (BufferedReader. (InputStreamReader. (.getInputStream sock) "UTF-8"))
          t (Thread. ^Runnable
                     (fn []
                       (try (while (some? (.readLine reader)) nil)
                            (catch Exception _ nil))))]
      (.setDaemon t true)
      (.start t)
      sock)))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [{:keys [options]} (cli/parse-opts args cli-options)
        {:keys [sessions soak anvil-url emit-edn help]} options]
    (when help (println "see header comment") (System/exit 0))

    (println "\n══════════════════════════════════════════════════════════════════════")
    (println "  anvil SSE soak (TU1.7)")
    (println (str "  Server:   " anvil-url))
    (println (str "  Sessions: " sessions))
    (println (str "  Soak:     " soak "s"))
    (println (str "  Budget:   < 5% CPU at idle on this host"))
    (println "══════════════════════════════════════════════════════════════════════\n")

    (let [{:keys [host port]} (parse-url anvil-url)
          pid (anvil-pid port)
          _ (println (if pid
                       (str "  anvil pid: " pid " (Linux /proc/<pid>/stat sampling enabled)")
                       "  WARN: could not find anvil pid; CPU sampling skipped"))
          t0 (System/currentTimeMillis)
          cpu0 (when pid (read-pid-cpu-ms pid))
          ;; Open all N sessions in parallel.
          _ (println (str "  Opening " sessions " SSE sessions ..."))
          socks (vec
                 (pmap (fn [_]
                         (try (open-session {:host host :port port} "global")
                              (catch Exception e
                                (println "  ⚠ session open failed:" (.getMessage e))
                                nil)))
                       (range sessions)))
          open-count (count (filter some? socks))
          _ (println (str "  " open-count " / " sessions " sessions established."))
          _ (println (str "  Idling for " soak "s ..."))
          _ (Thread/sleep (* 1000 soak))
          t1 (System/currentTimeMillis)
          cpu1 (when pid (read-pid-cpu-ms pid))
          elapsed-ms (- t1 t0)
          ;; CPU as % of one core: (cpu-time / wall-time) × 100.
          cpu-pct (when (and cpu0 cpu1)
                    (* 100.0 (/ (double (- cpu1 cpu0)) elapsed-ms)))]
      (println)
      (println (format "  Elapsed:    %d ms" elapsed-ms))
      (when cpu-pct
        (println (format "  CPU used:   %.2f%% of one core" cpu-pct))
        (println (format "  → %s the AU11-adjacent budget (< 5%%)"
                         (if (< cpu-pct 5.0) "WITHIN" "OVER"))))
      (println "\n  Closing sessions ...")
      (doseq [s socks]
        (when s (try (.close ^Socket s) (catch Exception _ nil))))
      (println "  Done.")
      (println "\n══════════════════════════════════════════════════════════════════════")
      (when emit-edn
        (fs/create-dirs (fs/parent emit-edn))
        (spit emit-edn
              (with-out-str
                (pp/pprint
                 {:bench :anvil-sse-soak
                  :anvil-url anvil-url
                  :sessions-requested sessions
                  :sessions-established open-count
                  :soak-seconds soak
                  :elapsed-ms elapsed-ms
                  :pid pid
                  :cpu-pct cpu-pct
                  :budget-pct 5.0
                  :within-budget? (when cpu-pct (< cpu-pct 5.0))})))
        (println (str "  ⤷ wrote EDN to " emit-edn))))))

(apply -main *command-line-args*)
