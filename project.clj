(defproject anvil/anvil "0.1.0-SNAPSHOT"
  :description
  "anvil — a free, OSS, single-team CI server that runs your Jenkinsfile.

   The Trojan-horse top-of-funnel for chengis-core.  Jenkins users install
   anvil, their existing Jenkinsfiles run unchanged on a modern engine, they
   get the perf differential immediately, and over time they discover that
   anvil's secondary DSL (Chengisfile) is more expressive — at which point
   they're effectively running on chengis-core and the upgrade to Chengis
   (the enterprise tier — multi-tenant, RBAC, audit, HA, SaaS) is a
   one-command operation.

   anvil ships:
     - Jenkinsfile parsing + the full Pipeline DSL runtime
     - Chengisfile parsing as 'native mode' (consumed from chengis-core)
     - Jenkins REST API shim (read-mostly + build trigger)
     - A minimal UI: build list, log viewer, manual trigger
     - Single-binary install, SQLite-backed, local-executor by default
     - `anvil import jenkinsfile` migration tooling

   anvil does NOT ship: multi-tenant orgs, RBAC, SSO, audit trail,
   compliance reports, HA controller, hosted SaaS — those distinguish
   Chengis."

  :url "https://anvilci.io"   ;; placeholder; final name decision pending
  :license {:name "Apache-2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"}

  ;; anvil consumes chengis-core. During monorepo development the dependency
  ;; is a :source-paths merge across the sibling directory (../chengis-core/src).
  ;; When chengis-core ships as a separate Maven artifact, this drops to a
  ;; normal :dependencies entry and the :source-paths reverts to ["src"] only.
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.clojure/core.async "1.8.741"]
                 [org.clojure/tools.cli "1.3.250"]

                 ;; HTTP server + routing
                 [http-kit/http-kit "2.8.1"]
                 [metosin/reitit-ring "0.10.0"]
                 [ring/ring-defaults "0.7.0"]
                 [hiccup/hiccup "2.0.0"]
                 [org.clojure/data.json "2.5.2"]

                 ;; Storage — SQLite only at v1; no Postgres dep
                 [com.github.seancorfield/next.jdbc "1.3.1093"]
                 [com.github.seancorfield/honeysql "2.7.1368"]
                 [org.xerial/sqlite-jdbc "3.51.2.0"]
                 [hikari-cp/hikari-cp "4.0.0"]
                 [migratus/migratus "1.6.5"]

                 ;; Observability
                 [com.taoensso/timbre "6.8.0"]
                 [clj-commons/iapetos "0.1.14"]
                 [org.slf4j/slf4j-nop "2.0.17"]

                 ;; Process / shell
                 [babashka/process "0.6.25"]

                 ;; Misc
                 [clj-commons/clj-yaml "1.0.29"]

                 ;; Groovy — for Jenkinsfile parsing (AST walk) + Pipeline DSL
                 ;; runtime (script {} block execution). Apache 2.0. ~7 MB.
                 [org.apache.groovy/groovy "4.0.21"]
                 [org.apache.groovy/groovy-jsr223 "4.0.21"]]

  ;; Dev-time source path merge: chengis-core lives at ../chengis-core
  ;; relative to this project.
  :source-paths   ["src"      "../chengis-core/src"]
  :resource-paths ["resources" "../chengis-core/resources"]

  :main anvil.core

  :jvm-opts ["--enable-native-access=ALL-UNNAMED"
             "-Xmx256m" "-Xms64m"
             "-XX:+UseG1GC" "-XX:MaxGCPauseMillis=200"]

  :global-vars {*warn-on-reflection* false}

  :target-path "target/%s"

  ;; Opt-in browser-test selector (TU0.5). Tag tests with `^:browser`
  ;; and run `lein test :browser`; default `lein test` excludes them so
  ;; no contributor needs Firefox+geckodriver installed just to ship.
  :test-selectors {:default (complement :browser)
                   :browser :browser
                   :all (constantly true)}

  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:dependencies [[org.clojure/test.check "1.1.3"]
                                  ;; TU0.5: WebDriver wrapper for browser-side
                                  ;; smoke. Opt-in via `lein test :browser`.
                                  [etaoin/etaoin "1.1.43"]]}
             ;; TX8 benchmarks — `lein with-profile +bench run -m anvil.bench.runner`
             :bench {:source-paths ["benchmarks/src"]
                     :dependencies [[criterium/criterium "0.4.6"]]
                     :main anvil.bench.runner
                     :jvm-opts ["--enable-native-access=ALL-UNNAMED"
                                "-Xmx512m" "-Xms256m" "-server"
                                "-XX:+UseG1GC"]}})
