(ns anvil.config
  "Tiny config loader. Each anvil feature that needs an operator-editable
   config file lands an EDN under `anvil/config/<feature>.edn` (e.g.
   `agents.edn`, `libraries.edn`, `credentials.edn` later). At load time
   we search, in order:

     1. `$ANVIL_CONFIG_DIR/<feature>.edn` (operator override)
     2. `./config/<feature>.edn` (working-directory override)
     3. classpath: `anvil/config/<feature>.edn` (bundled default)

   No hot-reload — these files are read once. Re-reading is an operator
   action (process restart). This keeps the runtime predictable; if you
   want config-as-data, you can curl the admin REST endpoints at
   runtime instead."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))

(defn- candidate-paths [feature]
  (let [fname (str feature ".edn")]
    (->> [(when-let [d (System/getenv "ANVIL_CONFIG_DIR")]
            (io/file d fname))
          (io/file "config" fname)
          (io/resource (str "anvil/config/" fname))]
         (remove nil?))))

(defn- read-source [src]
  (with-open [r (io/reader src)]
    (edn/read (java.io.PushbackReader. r))))

(defn load-edn
  "Load `<feature>.edn` from the first existing candidate location.
   Returns the parsed EDN, or the provided `default` if no candidate
   exists. Throws on malformed EDN — operators want to know."
  ([feature] (load-edn feature nil))
  ([feature default]
   (let [candidates (candidate-paths feature)
         picked (some (fn [c]
                        (when (and c
                                   (cond
                                     (instance? java.net.URL c) true
                                     :else (.exists ^java.io.File c)))
                          c))
                      candidates)]
     (if picked
       (do (log/debug (str "anvil.config: loading " feature " from " picked))
           (read-source picked))
       default))))
