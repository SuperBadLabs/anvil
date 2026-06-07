(ns anvil.ai.repo-context
  "v0.4.1 T3.2 — Repo-context scanner.  Walks a repo directory and
   returns a structured map summarizing what's there: detected
   languages (by file-extension counts), package files (`pom.xml`,
   `package.json`, etc.), CI hints (existing `.github/workflows/`,
   `Jenkinsfile`, `.gitlab-ci.yml`), and tool-version files
   (`.tool-versions`, `.nvmrc`, etc.).

   The result feeds the `anvil init` prompt: a Jenkinsfile scaffold
   that actually matches what the repo is built with.

   R3 contract reminder: only this *structured summary* goes to the
   Anthropic API — not the file contents.  We name what's there, not
   what's in it.  Operators reviewing the data-flow doc can trust
   that only their `pom.xml`'s existence (not its dependency list) is
   what we send."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Detection tables
;; ---------------------------------------------------------------------------

(def ^:private extension->language
  "File-extension → language label.  Picked for what shows up in real
   Jenkinsfiles — we don't need to recognize every esoteric extension
   here, just the ones a builder is plausibly going to be."
  {"clj"   "Clojure"   "cljs"  "ClojureScript" "cljc" "Clojure"
   "java"  "Java"      "kt"    "Kotlin"        "scala" "Scala"   "groovy" "Groovy"
   "py"    "Python"
   "js"    "JavaScript" "ts"   "TypeScript"    "jsx"  "JavaScript" "tsx" "TypeScript"
   "go"    "Go"
   "rs"    "Rust"
   "rb"    "Ruby"
   "php"   "PHP"
   "swift" "Swift"
   "c"     "C"          "h"    "C"
   "cpp"   "C++"        "cc"   "C++"           "cxx"  "C++"        "hpp" "C++"
   "cs"    "C#"
   "ex"    "Elixir"     "exs"  "Elixir"
   "erl"   "Erlang"
   "hs"    "Haskell"
   "elm"   "Elm"
   "ml"    "OCaml"
   "lua"   "Lua"
   "sh"    "Shell"      "bash" "Shell"
   "dockerfile" "Docker"})

(def ^:private package-files
  "Map of `marker filename` → build-tool name.  Order matters: when
   multiple match, all are reported (some repos are polyglot)."
  {"pom.xml"             "Maven"
   "build.gradle"        "Gradle"
   "build.gradle.kts"    "Gradle"
   "settings.gradle"     "Gradle"
   "settings.gradle.kts" "Gradle"
   "package.json"        "npm/Node"
   "yarn.lock"           "Yarn"
   "pnpm-lock.yaml"      "pnpm"
   "Cargo.toml"          "Cargo (Rust)"
   "go.mod"              "Go modules"
   "Gemfile"             "Bundler (Ruby)"
   "composer.json"       "Composer (PHP)"
   "pyproject.toml"      "Python (pyproject)"
   "setup.py"            "Python (setup.py)"
   "requirements.txt"    "pip"
   "Pipfile"             "pipenv"
   "poetry.lock"         "Poetry"
   "mix.exs"             "Mix (Elixir)"
   "stack.yaml"          "Stack (Haskell)"
   "cabal.project"       "Cabal (Haskell)"
   "project.clj"         "Leiningen"
   "deps.edn"            "tools.deps"
   "build.sbt"           "sbt (Scala)"
   "Makefile"            "Make"
   "CMakeLists.txt"      "CMake"
   "Dockerfile"          "Docker"})

(def ^:private ci-hint-files
  "CI configs already present in the repo.  Helps the prompt — if
   they already have `.github/workflows/`, scaffolding a Jenkinsfile
   *parallel to* that workflow is the right shape, not a from-scratch
   build pipeline."
  {".github/workflows" "GitHub Actions"
   ".gitlab-ci.yml"    "GitLab CI"
   ".circleci/config.yml" "CircleCI"
   ".travis.yml"       "Travis CI"
   "azure-pipelines.yml" "Azure Pipelines"
   "Jenkinsfile"       "Jenkins (existing)"
   "bitbucket-pipelines.yml" "Bitbucket Pipelines"})

(def ^:private tool-version-files
  "Tool-version pinning files anvil cares about (T7 mise integration)."
  #{".tool-versions" ".nvmrc" ".python-version" ".ruby-version"
    ".java-version" ".node-version" ".terraform-version"})

;; ---------------------------------------------------------------------------
;; Walk + classify
;; ---------------------------------------------------------------------------

(def ^:private skip-dirs
  "Directories we don't descend into — they explode walk time and
   their contents don't change the build-tool detection."
  #{".git" "node_modules" "target" "build" "dist" "out"
    ".gradle" ".m2" "vendor" "__pycache__" ".tox" ".venv" "venv"
    ".idea" ".vscode" ".cache"})

(defn- file-extension
  "Extract the lowercased extension after the last `.`, or nil.
   Special-cases `Dockerfile` → \"dockerfile\" since it has no dot."
  [path]
  (let [name (-> path fs/file-name str)]
    (cond
      (= name "Dockerfile") "dockerfile"
      (str/includes? name ".") (-> name (str/split #"\.") last str/lower-case)
      :else nil)))

(defn- walk-repo
  "Return a lazy seq of every regular-file Path under `root`, skipping
   directories named in `skip-dirs`.  Caps total files at `max-files`
   to keep large monorepos from blowing memory at scan time."
  [root max-files]
  (let [root-path (fs/path root)
        ;; Manual walk so we can prune dirs (fs/glob doesn't expose it)
        result (atom [])
        budget (volatile! max-files)]
    (letfn [(walk [^java.nio.file.Path p]
              (when (pos? @budget)
                (cond
                  (fs/directory? p)
                  (when-not (contains? skip-dirs (str (fs/file-name p)))
                    (doseq [child (fs/list-dir p)
                            :while (pos? @budget)]
                      (walk child)))

                  (fs/regular-file? p)
                  (do (swap! result conj p)
                      (vswap! budget dec)))))]
      (walk root-path))
    @result))

(defn scan
  "Scan `root` and return a context map for the AI prompt.

   Options:
     :max-files   safety cap on the file walk (default 50000).
                  Large monorepos will trigger this cap; the result
                  still represents what was seen, with a :truncated?
                  flag set.

   Result shape:
     {:root          \"/abs/path/to/repo\"
      :languages     {\"Clojure\" 42  \"Java\" 5}    ; by file count
      :build-tools   [\"Leiningen\" \"Maven\"]        ; alphabetical
      :ci-systems    [\"GitHub Actions\"]             ; already present
      :tool-versions {\".tool-versions\" true}        ; for mise/asdf
      :package-files [\"project.clj\" \"pom.xml\"]    ; the actual files
      :file-count    47
      :truncated?    false}"
  ([root] (scan root {}))
  ([root {:keys [max-files] :or {max-files 50000}}]
   (let [root-str  (str (fs/absolutize root))
         files     (walk-repo root max-files)
         truncated? (>= (count files) max-files)
         rel-paths (mapv (fn [p]
                           (str (fs/relativize (fs/path root) p)))
                         files)
         ;; Language counts by file extension
         lang-counts (->> files
                          (keep file-extension)
                          (keep extension->language)
                          frequencies)
         ;; Build-tool detection — look for marker files at any depth
         ;; (real-world: multi-module repos have pom.xml in subdirs)
         present-build-files (filter #(contains? package-files
                                                 (str (fs/file-name (fs/path %))))
                                     rel-paths)
         build-tools (->> present-build-files
                          (map #(get package-files (str (fs/file-name (fs/path %)))))
                          distinct
                          sort
                          vec)
         ;; CI hints — match either a literal file or a dir prefix
         ci-systems (->> ci-hint-files
                         (keep (fn [[marker label]]
                                 (when (or (some #{marker} rel-paths)
                                           ;; dir prefix match (e.g. .github/workflows/foo.yml)
                                           (some #(str/starts-with? % (str marker "/")) rel-paths))
                                   label)))
                         distinct
                         sort
                         vec)
         ;; Tool-version files — boolean map (presence is the signal)
         tv-found (->> tool-version-files
                       (filter #(some #{%} rel-paths))
                       (map (fn [f] [f true]))
                       (into {}))]
     {:root          root-str
      :languages     lang-counts
      :build-tools   build-tools
      :ci-systems    ci-systems
      :tool-versions tv-found
      :package-files (vec (sort (distinct present-build-files)))
      :file-count    (count files)
      :truncated?    truncated?})))

(defn primary-language
  "Best-guess primary language from a `scan` result — the one with
   the most files.  Returns nil for an empty repo."
  [{:keys [languages]}]
  (when (seq languages)
    (->> languages
         (sort-by val >)
         first
         key)))

(defn summary-string
  "Render a `scan` result as a compact, human-readable string suitable
   for embedding directly into an AI prompt.  This is what actually
   goes to the API; review it to verify the R3 data-flow contract."
  [ctx]
  (let [{:keys [languages build-tools ci-systems tool-versions
                package-files file-count truncated?]} ctx
        lang-line (if (seq languages)
                    (->> languages
                         (sort-by val >)
                         (take 5)
                         (map (fn [[lang n]] (str lang " (" n " files)")))
                         (str/join ", "))
                    "<none detected>")
        tv-line (if (seq tool-versions)
                  (str/join ", " (keys tool-versions))
                  "<none>")]
    (str/join "\n"
              (cond-> ["Languages: " lang-line]
                (seq build-tools)
                (concat ["Build tools: " (str/join ", " build-tools)])
                (seq package-files)
                (concat ["Package files: " (str/join ", " package-files)])
                (seq ci-systems)
                (concat ["Existing CI: " (str/join ", " ci-systems)])
                true
                (concat ["Tool-version files: " tv-line
                         (str "Total files scanned: " file-count
                              (when truncated? " (truncated)"))])))))
