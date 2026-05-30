(ns anvil.web.build-summary
  "Pure helpers that turn a build's :effects vector into the shapes
   the build / compare / artifacts views render — kept apart from
   any view ns so the same logic backs the HTML pages AND a future
   JSON endpoint (and so it can be unit-tested without touching
   Hiccup).

   The :effects vector is anvil's source of truth for what happened
   during a build (see anvil.compat.jenkins.dispatcher). It's an
   ordered list of [:tag payload-map] tuples — :agent/stage-enter,
   :agent/stage-leave, :sh, :stdout, :stderr, :echo, :archive,
   :junit, :delete-dir, :dir/enter, :dir/leave, :exception."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Stage / step extraction (TU3.2 + TU3.4)
;; ---------------------------------------------------------------------------

(defn steps-by-stage
  "Walk the effects vector, return a vec of
     {:stage STR :steps [{:cmd STR :exit INT?} …] :failed? BOOL}
   in the order they appeared. A 'step' is currently a :sh event; we
   can broaden when more step kinds need timeline rendering."
  [effects]
  (let [stages (atom [])
        current (atom nil)]
    (doseq [[tag payload] effects]
      (cond
        (= tag :agent/stage-enter)
        (do (when @current (swap! stages conj @current))
            (reset! current {:stage (:stage payload) :steps []}))

        (= tag :agent/stage-leave)
        (do (when @current (swap! stages conj @current))
            (reset! current nil))

        (and @current (= tag :sh))
        (swap! current update :steps conj
               (select-keys payload [:cmd :exit :cwd]))))
    (when @current (swap! stages conj @current))
    (mapv (fn [s]
            (assoc s :failed?
                   (boolean (some (fn [st] (and (some? (:exit st)) (not (zero? (:exit st)))))
                                  (:steps s)))))
          @stages)))

(defn step-summary
  "Per-build aggregate. Convenient for the build header strip."
  [effects]
  (let [stages (steps-by-stage effects)]
    {:stage-count (count stages)
     :step-count  (reduce + 0 (map #(count (:steps %)) stages))
     :failed-step-count
     (reduce + 0 (map (fn [s] (count (filter (fn [st]
                                               (and (some? (:exit st)) (not (zero? (:exit st)))))
                                             (:steps s))))
                      stages))}))

;; ---------------------------------------------------------------------------
;; Param-diff (TU3.2)
;;
;; v1 ships "param diff" (the explicit Jenkins parameters block). True
;; env-diff (with the full Jenkins env vars: NODE_NAME, BUILD_URL, etc.)
;; needs storing the env-vars map on the build record — small engine
;; change, deferred to TU3.x with an honest label on the page.
;; ---------------------------------------------------------------------------

(defn param-diff
  "Return {:added {k v} :removed {k v} :changed {k [old new]}} for
   the parameters maps of two builds. Nil → empty map."
  [a-params b-params]
  (let [a (or a-params {})
        b (or b-params {})
        ak (set (keys a))
        bk (set (keys b))
        added   (select-keys b (set/difference bk ak))
        removed (select-keys a (set/difference ak bk))
        common  (set/intersection ak bk)
        changed (into {} (for [k common
                               :when (not= (get a k) (get b k))]
                           [k [(get a k) (get b k)]]))]
    {:added added :removed removed :changed changed}))

;; ---------------------------------------------------------------------------
;; Artifacts (TU3.5)
;;
;; The :archive effect records the configured glob (e.g. "target/*.jar").
;; To list real files we walk the build's workspace directory and
;; match the glob via java.nio.file PathMatcher.
;; ---------------------------------------------------------------------------

(defn- archive-globs
  "All glob patterns this build configured via archiveArtifacts."
  [effects]
  (->> effects
       (filter #(= :archive (first %)))
       (keep (fn [[_ p]] (:artifacts p)))))

(defn- workspace-dir
  "Reconstruct the workspace path from the log-path. Runner stores
   logs at <build-root>/logs/<n>.log; the workspace lives at
   <build-root>/<n>/. Return nil if the log-path doesn't fit the
   convention or the dir doesn't exist."
  [^String log-path]
  (when log-path
    (try
      (let [logs-dir (.getParentFile (java.io.File. log-path))
            build-root (.getParentFile logs-dir)
            n (.getName (java.io.File. log-path))
            n-no-ext (subs n 0 (.lastIndexOf n "."))
            ws (java.io.File. build-root n-no-ext)]
        (when (and (.exists ws) (.isDirectory ws)) ws))
      (catch Exception _ nil))))

(defn- relative-path
  [^java.io.File root ^java.io.File f]
  (let [rp (.toString (.relativize (.toPath root) (.toPath f)))]
    (if (str/blank? rp) (.getName f) rp)))

(defn- matches-any-glob?
  [^String rel-path globs]
  (let [fs (java.nio.file.FileSystems/getDefault)]
    (some (fn [glob]
            (try
              (let [m (.getPathMatcher fs (str "glob:" glob))]
                (.matches m (.getPath fs rel-path (make-array String 0))))
              (catch Exception _ false)))
          globs)))

(defn artifacts-for
  "Return a vec of {:rel-path STR :size-bytes LONG} for files matching
   any :archive glob recorded by the build. Returns nil if no
   workspace can be located."
  [build]
  (when-let [ws (workspace-dir (:log-path build))]
    (let [globs (archive-globs (:effects build))]
      (when (seq globs)
        (let [root ws]
          (->> (file-seq ws)
               (filter #(.isFile ^java.io.File %))
               (map (fn [^java.io.File f]
                      {:rel-path (relative-path root f)
                       :size-bytes (.length f)
                       :file f}))
               (filter #(matches-any-glob? (:rel-path %) globs))
               (sort-by :rel-path)
               vec))))))

(defn artifact-file
  "Locate the file at `rel-path` under the build's workspace. Returns
   the java.io.File or nil. Does NOT enforce that the file matched an
   archive glob — that's a separate access-control concern; for v1 we
   just refuse anything outside the workspace (no traversal)."
  [build ^String rel-path]
  (when-let [ws (workspace-dir (:log-path build))]
    (let [ws-canon (.getCanonicalFile ws)
          f (java.io.File. ws rel-path)
          fc (.getCanonicalFile f)
          ws-path (.getAbsolutePath ws-canon)]
      (when (and (.exists fc)
                 (.isFile fc)
                 (str/starts-with? (.getAbsolutePath fc) ws-path))
        fc))))

(defn guess-content-type
  "Crude MIME by extension. Returns 'application/octet-stream' for
   unknown. v1 covers the artifact types CI actually produces; rich
   sniffing is a Future Problem."
  [^String name]
  (let [n (.toLowerCase name)]
    (cond
      (str/ends-with? n ".jar")  "application/java-archive"
      (str/ends-with? n ".zip")  "application/zip"
      (str/ends-with? n ".tar")  "application/x-tar"
      (str/ends-with? n ".gz")   "application/gzip"
      (str/ends-with? n ".txt")  "text/plain; charset=utf-8"
      (str/ends-with? n ".log")  "text/plain; charset=utf-8"
      (str/ends-with? n ".xml")  "application/xml"
      (str/ends-with? n ".json") "application/json"
      (str/ends-with? n ".html") "text/html; charset=utf-8"
      (str/ends-with? n ".css")  "text/css"
      (str/ends-with? n ".js")   "application/javascript"
      (str/ends-with? n ".png")  "image/png"
      (str/ends-with? n ".jpg")  "image/jpeg"
      (str/ends-with? n ".jpeg") "image/jpeg"
      (str/ends-with? n ".svg")  "image/svg+xml"
      (str/ends-with? n ".pdf")  "application/pdf"
      :else                       "application/octet-stream")))
