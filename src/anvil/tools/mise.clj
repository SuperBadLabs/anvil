(ns anvil.tools.mise
  "mise / asdf tool-version detection + provisioning (T7 of the v0.3
   board).

   At build start, if the workspace declares its tool versions via
   `.mise.toml`, `mise.toml`, or `.tool-versions`, anvil pre-provisions
   the required runtimes so subsequent `sh` steps see them.

   Detection priority (matches the AV3-7 decision):
     1. .mise.toml or mise.toml   — modern mise format
     2. .tool-versions            — asdf-format (mise reads it too)

   Provisioning backend priority:
     1. mise (if on PATH)         — `mise install` in the workspace
     2. asdf (if on PATH)         — `asdf install`
     3. neither                   — log WARN and continue (the build's
                                    sh steps may still work via the host's
                                    pre-installed tools)

   ## Why we don't try to install mise/asdf ourselves at build time

   - Risky: install-from-net during a build is a third-party-supply-chain
     attack surface.
   - The recommended path is `anvil setup tools` (T7.4) as a one-time
     operator action."
  (:require [babashka.process :as bp]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Detection (T7.1)
;; ---------------------------------------------------------------------------

(defn- read-tool-versions-file
  "Parse a `.tool-versions` file. Lines like:
     nodejs 22.5.1
     python 3.12.7
   Returns {tool → version}."
  [^java.io.File f]
  (let [lines (str/split-lines (slurp f))]
    (into {}
          (for [line lines
                :let [trimmed (str/trim line)]
                :when (and (seq trimmed) (not (str/starts-with? trimmed "#")))
                :let [parts (str/split trimmed #"\s+")]
                :when (>= (count parts) 2)]
            [(first parts) (second parts)]))))

(defn- parse-mise-toml
  "Tiny TOML reader for the [tools] section of a .mise.toml. Lines like:
     [tools]
     nodejs = '22.5.1'
     python = '3.12.7'
   We don't take a full TOML dep for this — the file format is
   stable and the [tools] block is consistently quoted-string style."
  [^java.io.File f]
  (let [text (slurp f)
        ;; Pull the [tools] section
        section (or (second (re-find #"(?ms)^\[tools\]\s*\n(.+?)(?:\n\[|\z)" text))
                    text)]
    (into {}
          (for [line (str/split-lines section)
                :let [m (re-find #"^\s*([a-zA-Z0-9_\-]+)\s*=\s*['\"]([^'\"]+)['\"]" line)]
                :when m]
            [(nth m 1) (nth m 2)]))))

(defn detect
  "Look for tool-version declarations in `workspace-dir`. Returns:
     {:source #{:mise-toml :tool-versions}     ; which file matched
      :path   <absolute-path-of-file>
      :tools  {tool → version}}
   or nil if no declarations were found."
  [workspace-dir]
  (let [ws (io/file (str workspace-dir))
        mise-toml (some #(let [f (io/file ws %)]
                           (when (.isFile f) f))
                        [".mise.toml" "mise.toml"])
        tool-versions (let [f (io/file ws ".tool-versions")]
                        (when (.isFile f) f))]
    (cond
      mise-toml
      {:source :mise-toml
       :path (.getAbsolutePath ^java.io.File mise-toml)
       :tools (parse-mise-toml mise-toml)}

      tool-versions
      {:source :tool-versions
       :path (.getAbsolutePath ^java.io.File tool-versions)
       :tools (read-tool-versions-file tool-versions)}

      :else nil)))

;; ---------------------------------------------------------------------------
;; Backend resolution
;; ---------------------------------------------------------------------------

(defn- on-path? [exe]
  (try
    (let [{:keys [exit]} (bp/sh "which" exe)]
      (zero? exit))
    (catch Throwable _ false)))

(defn resolve-backend
  "Returns :mise, :asdf, or :none. Lets tests stub this via with-redefs."
  []
  (cond
    (on-path? "mise") :mise
    (on-path? "asdf") :asdf
    :else             :none))

;; ---------------------------------------------------------------------------
;; Provisioning (T7.2)
;; ---------------------------------------------------------------------------

(defn provision!
  "Run the provisioning backend against the workspace. Returns
   {:backend KW :status #{:ok :failed :skipped} :output STR}.
   :skipped means no tools file was detected.
   :failed means the backend ran but exit != 0."
  [workspace-dir]
  (if-let [det (detect workspace-dir)]
    (let [backend (resolve-backend)]
      (case backend
        :mise
        (let [{:keys [exit out err]} (bp/sh {:dir (str workspace-dir)} "mise" "install")]
          {:backend :mise
           :status (if (zero? exit) :ok :failed)
           :output (str out "\n" err)
           :tools (:tools det)})

        :asdf
        (let [{:keys [exit out err]} (bp/sh {:dir (str workspace-dir)} "asdf" "install")]
          {:backend :asdf
           :status (if (zero? exit) :ok :failed)
           :output (str out "\n" err)
           :tools (:tools det)})

        :none
        (do (log/warn (str "anvil.mise: tools declared in " (:path det)
                           " but neither mise nor asdf on PATH; build will use host tools"))
            {:backend :none :status :skipped
             :tools (:tools det)})))
    {:backend :none :status :skipped}))
