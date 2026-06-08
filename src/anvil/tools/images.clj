(ns anvil.tools.images
  "AN8-1 — operator-mapped pre-baked docker images for `tools { maven 'X' jdk 'Y' }`.

   anvil never provisions toolchains itself (per the v0.6 anti-goal:
   no anvil-managed JDK installer / asdf / sdkman). Instead the
   operator maintains a map from `tools` directive surface to a
   pre-baked docker image in `anvil.edn`:

     {:anvil.tools/images
        {\"maven-3.9-jdk-17\"               \"maven:3.9-eclipse-temurin-17\"
         \"maven_3_latest+jdk_17_latest\"   \"maven:3.9-eclipse-temurin-17\"
         \"jdk_17_latest\"                  \"eclipse-temurin:17-jdk\"
         \"maven_latest+jdk_1.8_latest\"    \"maven:3.9-eclipse-temurin-8\"}}

   At translator time the `tools` block produces a vector of
   {:type :maven :version \"X\"} maps. At dispatch time, this
   namespace tries lookup keys in priority order:

     1. The literal versions joined by `+` in declaration order,
        e.g. `\"maven_3_latest+jdk_17_latest\"` — gives operators a
        direct hook for the raw Jenkinsfile surface they see in
        wild-corpus heavies (struts, ambari, zookeeper).
     2. The same join in canonical-sort order (type-name alphabetical).
     3. For each individual tool, the single-tool key
        `\"<version>\"` and `\"<type>-<version>\"`.
     4. A `*` wildcard key the operator can use as a last-resort
        fallback for any tools spec they haven't mapped explicitly.

   The first hit wins. Without a hit, `resolve-image` returns nil and
   the dispatcher emits a `:tools/unmapped` effect carrying ALL the
   candidate keys tried, so the operator can pick the one they want
   to map.

   ## Hot-reload

   The map is read once via `anvil.config/load-edn` and cached in an
   atom. Tests call `clear-cache!` between fixtures. Production
   operators get the v0.5.x restart-to-reload contract (matching
   `agents.edn` / `libraries.edn`); a future watcher (parallel to
   `anvil.build-overrides`) can be wired in if hot-reload becomes a
   shipped requirement."
  (:require [anvil.config :as config]
            [clojure.string :as str]))

(def ^:private cache (atom nil))

(defn- load-images []
  (or (get (config/load-edn "anvil" {}) :anvil.tools/images)
      {}))

(defn all
  "Return the full {key → image} map from anvil.edn. Lazy-loads on
   first call; tests use `clear-cache!` between fixtures."
  []
  (or @cache
      (swap! cache (fn [v] (or v (load-images))))))

(defn clear-cache!
  "Drop the cached map so the next call re-reads anvil.edn."
  []
  (reset! cache nil))

(defn- tool-key-segment
  "Build a single `<type>_<version>` segment for `tool-keys`."
  [{:keys [type version]}]
  (let [t (some-> type name)
        v (some-> version str)]
    (cond
      (and t v) (str t "-" v)
      v         v
      t         t
      :else     nil)))

(defn candidate-keys
  "Given a vector of tool specs (the IR shape from the translator),
   return the ordered list of lookup keys the resolver will try
   against the operator's :anvil.tools/images map.

   Order:
     1. Raw declaration-order join: \"maven_3_latest+jdk_17_latest\"
     2. Same join, sorted by :type name (canonical)
     3. Type-version composite join: \"maven-maven_3_latest+jdk-jdk_17_latest\"
     4. Same composite, sorted
     5. For each individual tool: \"<version>\" then \"<type>-<version>\"
     6. \"*\" wildcard

   Returned in priority order with no duplicates. Tools without a
   :version are skipped from individual entries but contribute their
   :type name to the composite key."
  [tools]
  (let [versions (->> tools (keep :version) (mapv str))
        type+ver (->> tools (mapv tool-key-segment) (remove nil?))
        sorted-versions (vec (sort versions))
        sorted-tv (vec (sort type+ver))
        joined #(str/join "+" %)
        keys (concat
              (when (seq versions) [(joined versions)])
              (when (and (seq sorted-versions)
                         (not= sorted-versions versions))
                [(joined sorted-versions)])
              (when (seq type+ver) [(joined type+ver)])
              (when (and (seq sorted-tv) (not= sorted-tv type+ver))
                [(joined sorted-tv)])
              (for [t tools
                    :let [v (some-> (:version t) str)
                          tn (some-> (:type t) name)]
                    k (cond
                        (and v tn) [v (str tn "-" v) tn]
                        v          [v]
                        tn         [tn]
                        :else      nil)
                    :when k]
                k)
              ["*"])]
    (vec (distinct (filter some? keys)))))

(defn resolve-image
  "Given `tools` (the translator's IR vector) and `images-map` (the
   operator's :anvil.tools/images map), return
     {:image \"…\" :matched-key \"…\" :candidate-keys [...]}
   on a hit, or
     {:image nil :candidate-keys [...]}
   on a miss. The dispatcher uses the miss shape to construct the
   `:tools/unmapped` effect.

   When `images-map` is omitted, the loaded `all` map is consulted."
  ([tools] (resolve-image tools (all)))
  ([tools images-map]
   (let [keys (candidate-keys tools)
         hit (some (fn [k]
                     (when-let [img (get images-map k)]
                       {:image img :matched-key k}))
                   keys)]
     (or (assoc hit :candidate-keys keys)
         {:image nil :candidate-keys keys}))))
