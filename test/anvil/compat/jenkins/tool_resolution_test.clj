(ns anvil.compat.jenkins.tool-resolution-test
  "AN4-3: tool('descriptor') routes through chengis.tools/resolve!,
   recording :tool-unresolved effects that the AN4-1 classifier
   reclassifies as :failure (rule :tool-unresolved). Closes the
   anvil v0.3 silent regression where tool() returned \"\"."
  (:require [chengis.tools :as tools]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.compat.jenkins.classification :as classify]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.runtime :as runtime]))

(use-fixtures :each
  (fn [t]
    (tools/clear-registry!)
    (t)
    (tools/clear-registry!)))

;; ---------------------------------------------------------------------------
;; Classification consumes :tool-unresolved as :failure
;; ---------------------------------------------------------------------------

(deftest classifier-reads-tool-unresolved-as-failure
  (let [effs [[:sh {:cmd "echo $JAVA_HOME" :cwd "/w" :exit 0
                    :streamed? false :stdout-bytes 0 :stderr-bytes 0}]
              [:tool-unresolved {:descriptor "jdk_17_latest"
                                  :rule :no-installer
                                  :explain "no registered installer..."}]]
        c (classify/classify-build {:status :ok} effs {})]
    (is (= :failure (:result c)))
    (is (= :tool-unresolved (:rule c)))
    (is (re-find #"jdk_17_latest" (:explain c)))))

(deftest classifier-reads-credential-unresolved-as-failure
  (let [effs [[:sh {:cmd "make" :cwd "/w" :exit 0
                    :streamed? false :stdout-bytes 0 :stderr-bytes 0}]
              [:credential-unresolved {:credential-id "apache-snapshots"
                                        :rule :credential-unresolved
                                        :explain "not in store"}]]
        c (classify/classify-build {:status :ok} effs {})]
    (is (= :failure (:result c)))
    (is (= :credential-unresolved (:rule c)))))

;; ---------------------------------------------------------------------------
;; Runtime: tool() wiring against the chengis.tools registry
;;
;; We exercise the runtime through `run-script-block` against a tiny Groovy
;; snippet. The runtime is heavy to spin up; if Groovy's compile fails we
;; skip cleanly. Each test seeds its own installer fixture.
;; ---------------------------------------------------------------------------

(defn- run-script
  "Run a Groovy script source against `dispatcher` and return the
   script's final expression value. Throws on real runtime errors —
   the earlier swallow-all-throwables form let regressions in the
   Groovy runtime pass these tests silently (Copilot review feedback
   on PR #27)."
  [dispatcher source]
  (runtime/run-script-block source dispatcher (atom {:env {}})))

(deftest tool-with-no-installer-records-unresolved-effect
  (let [d (ad/make)]
    (run-script d "def jdk = tool('jdk_17_latest'); echo jdk.toString()")
    (let [ts (filter #(= :tool-unresolved (first %)) @(:effects d))]
      (is (= 1 (count ts))
          "tool() with no installer must record one :tool-unresolved effect")
      (is (= "jdk_17_latest"
             (get-in (second (first ts)) [:descriptor]))))))

(deftest tool-with-resolving-installer-returns-real-path
  (let [d (ad/make)
        ;; A directory we know exists, so DirPinned resolves to it.
        pin-path (System/getProperty "java.io.tmpdir")]
    (tools/register-installer!
     (tools/dir-pinned-installer
      {:pins {[:jdk "17"] pin-path}}))
    ;; Capture the script's final expression so we can assert tool()
    ;; actually returned the resolved path (Copilot review on PR #27).
    (let [returned (run-script
                    d "def p = tool('jdk_17_latest'); p")
          ts (filter #(= :tool-unresolved (first %)) @(:effects d))]
      (is (= pin-path (str returned))
          "tool() must return the resolved path on :ok")
      (is (empty? ts)
          "resolved tool() must NOT record an :tool-unresolved effect"))))

(deftest tool-with-blank-descriptor-records-bad-descriptor
  (let [d (ad/make)]
    (run-script d "tool('')")
    (let [ts (filter #(= :tool-unresolved (first %)) @(:effects d))]
      (is (= 1 (count ts)))
      (is (= :bad-descriptor (get-in (second (first ts)) [:rule]))
          "empty-string descriptor must NOT trigger tools/resolve!"))))

(deftest tool-with-named-arg-style-still-resolves
  (let [d (ad/make)]
    (tools/register-installer!
     (tools/dir-pinned-installer
      {:pins {[:jdk "17"] (System/getProperty "java.io.tmpdir")}}))
    (let [returned (run-script d "tool(name: 'jdk_17_latest')")]
      (is (= (System/getProperty "java.io.tmpdir") (str returned))
          "tool(name: '...') must resolve through the same path"))))

(deftest unresolved-tool-end-to-end-classifies-as-failure
  (testing "anvil v0.3 returned \"\" silently and built was SUCCESS;
            with AN4-3 wiring + AN4-1 classifier, the same build now
            classifies as :failure with rule :tool-unresolved"
    (let [d (ad/make)]
      (run-script d "tool('jdk_17_latest')")
      ;; Add a successful sh effect — so the build is otherwise green,
      ;; the only failure signal is the unresolved tool
      (swap! (:effects d) conj
             [:sh {:cmd "javac -version" :cwd "/w" :exit 0
                   :streamed? false :stdout-bytes 0 :stderr-bytes 0}])
      (let [c (classify/classify-build {:status :ok} @(:effects d) {})]
        (is (= :failure (:result c))
            "unresolved tool() must drive the build to :failure")
        (is (= :tool-unresolved (:rule c)))))))
