(ns anvil.compat.jenkins.deploy-degrade-test
  "AN5-4 — Lock down the mvn-deploy → mvn-package rewriter.

   The rewriter must:
     - leave non-mvn commands alone
     - leave mvn commands without `deploy` alone
     - rewrite `mvn ... deploy ...` to `mvn ... package ...` cleanly
     - NOT rewrite plugin-mojo invocations like `deploy:deploy-file`
     - NOT rewrite profile names like `-Pdeploy`
     - NOT rewrite directory references like `cd src/deploy`
     - NOT fire at all when the feature flag is off (default)"
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.compat.jenkins.deploy-degrade :as dd]))

;; ---------------------------------------------------------------------------
;; detect-mvn-deploy
;; ---------------------------------------------------------------------------

(deftest detects-simple-mvn-deploy
  (is (:matched? (dd/detect-mvn-deploy "mvn deploy"))))

(deftest detects-clean-deploy
  (is (:matched? (dd/detect-mvn-deploy "mvn clean deploy"))))

(deftest detects-mvn-deploy-with-flags
  (is (:matched? (dd/detect-mvn-deploy "mvn -B -Pci -ntp deploy -DskipTests"))))

(deftest detects-mvnw-clean-deploy
  (is (:matched? (dd/detect-mvn-deploy "./mvnw clean deploy"))))

(deftest detects-mvn-install-deploy
  (is (:matched? (dd/detect-mvn-deploy "mvn clean install deploy"))))

(deftest skips-non-mvn-commands
  (is (nil? (dd/detect-mvn-deploy "echo deploy"))
      "shell-only deploy mention must NOT match")
  (is (nil? (dd/detect-mvn-deploy "cd src/deploy && ls"))
      "directory named deploy must NOT match")
  (is (nil? (dd/detect-mvn-deploy "ant deploy"))
      "ant deploy isn't mvn"))

(deftest skips-mvn-without-deploy-goal
  (is (nil? (dd/detect-mvn-deploy "mvn clean install"))
      "no deploy goal → no match")
  (is (nil? (dd/detect-mvn-deploy "mvn package -DskipTests"))
      "mvn package → no match")
  (is (nil? (dd/detect-mvn-deploy "mvn verify"))
      "mvn verify → no match"))

(deftest skips-deploy-plugin-mojo
  (is (nil? (dd/detect-mvn-deploy "mvn deploy:deploy-file -Dfile=x.jar"))
      "plugin-mojo `deploy:deploy-file` is NOT a lifecycle phase deploy")
  (is (nil? (dd/detect-mvn-deploy "mvn org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy"))
      "explicit plugin form must NOT match"))

(deftest skips-deploy-profile-name
  (is (nil? (dd/detect-mvn-deploy "mvn package -Pdeploy"))
      "-Pdeploy is a profile name, not a goal — must NOT match")
  (is (nil? (dd/detect-mvn-deploy "mvn install -Ddeploy=true"))
      "-Ddeploy=true is a property, not a goal"))

;; ---------------------------------------------------------------------------
;; degrade-cmd
;; ---------------------------------------------------------------------------

(deftest degrades-simple-deploy-to-package
  (is (= "mvn package" (dd/degrade-cmd "mvn deploy"))))

(deftest degrades-clean-deploy
  (is (= "mvn clean package" (dd/degrade-cmd "mvn clean deploy"))))

(deftest degrades-clean-install-deploy
  (testing "the FIRST `deploy` token becomes `package`; if subsequent ones exist
            (rare) the operator's mvn invocation handles them"
    (is (= "mvn clean install package" (dd/degrade-cmd "mvn clean install deploy")))))

(deftest degrades-with-flags
  (is (= "mvn -B -Pci -ntp package -DskipTests"
         (dd/degrade-cmd "mvn -B -Pci -ntp deploy -DskipTests"))))

(deftest non-degrade-leaves-cmd-alone
  (is (= "mvn clean install" (dd/degrade-cmd "mvn clean install")))
  (is (= "echo deploy" (dd/degrade-cmd "echo deploy")))
  (is (= "mvn deploy:deploy-file -Dfile=x.jar"
         (dd/degrade-cmd "mvn deploy:deploy-file -Dfile=x.jar"))))

;; ---------------------------------------------------------------------------
;; maybe-degrade — top-level entry with feature flag gating
;; ---------------------------------------------------------------------------

(deftest maybe-degrade-no-op-when-flag-off
  (testing "feature flag OFF (default) → pure no-op even on mvn deploy commands"
    (let [r (dd/maybe-degrade "mvn clean deploy" false)]
      (is (false? (:degraded? r)))
      (is (nil? (:rewritten r)))
      (is (nil? (:reason r))))))

(deftest maybe-degrade-acts-when-flag-on
  (testing "feature flag ON + matching cmd → rewrite + reason"
    (let [r (dd/maybe-degrade "mvn clean deploy" true)]
      (is (true? (:degraded? r)))
      (is (= "mvn clean deploy" (:original r)))
      (is (= "mvn clean package" (:rewritten r)))
      (is (= :no-deploy-credentials (:reason r))))))

(deftest maybe-degrade-no-op-on-non-mvn
  (let [r (dd/maybe-degrade "echo deploy" true)]
    (is (false? (:degraded? r))
        "non-mvn `deploy` mention is left alone even with flag on")))

(deftest maybe-degrade-no-op-on-mvn-without-deploy
  (let [r (dd/maybe-degrade "mvn package" true)]
    (is (false? (:degraded? r))
        "mvn package — already producing artifacts, no rewrite needed")))

(deftest maybe-degrade-handles-blank-and-nil
  (is (false? (:degraded? (dd/maybe-degrade nil true))))
  (is (false? (:degraded? (dd/maybe-degrade "" true)))))

;; ---------------------------------------------------------------------------
;; Edge cases — the wild-corpus shapes specifically
;; ---------------------------------------------------------------------------

(deftest wild-corpus-camel-quarkus-shape
  (testing "the exact shape that crashed in the dirty-dozen hunt"
    (let [cmd "./mvnw -B -e -ntp -Ddeploy -Dquickly clean deploy"
          r (dd/maybe-degrade cmd true)]
      (is (true? (:degraded? r)))
      (is (= "./mvnw -B -e -ntp -Ddeploy -Dquickly clean package"
             (:rewritten r))
          "the -Ddeploy property is NOT touched; only the lifecycle goal is rewritten"))))

(deftest wild-corpus-streampipes-shape
  (testing "long multi-flag invocation"
    (let [cmd "mvn -Pci -B -e -DskipTests clean install deploy"
          r (dd/maybe-degrade cmd true)]
      (is (true? (:degraded? r)))
      (is (= "mvn -Pci -B -e -DskipTests clean install package"
             (:rewritten r))))))

;; ---------------------------------------------------------------------------
;; PR #40 Copilot review: compound shell commands with `deploy` BEFORE mvn
;; must NOT trigger a rewrite. Locks down the scoping behavior.
;; ---------------------------------------------------------------------------

(deftest compound-cd-deploy-then-mvn-install-is-not-degraded
  (testing "directory named `deploy` BEFORE the mvn call is not a goal"
    (let [cmd "cd deploy && mvn install"
          r (dd/maybe-degrade cmd true)]
      (is (false? (:degraded? r))
          "cd deploy && mvn install must NOT rewrite — `deploy` is a dir token"))))

(deftest compound-cd-src-deploy-then-mvn-install-is-not-degraded
  (testing "directory path `src/deploy` BEFORE the mvn call is not a goal"
    (let [cmd "cd src/deploy && mvn install -DskipTests"
          r (dd/maybe-degrade cmd true)]
      (is (false? (:degraded? r))
          "src/deploy is a directory path, not a maven goal"))))

(deftest compound-cd-deploy-then-mvn-deploy-rewrites-only-goal
  (testing "dir BEFORE mvn is left alone; goal AFTER mvn IS rewritten"
    (let [cmd "cd deploy && mvn clean deploy"
          r (dd/maybe-degrade cmd true)]
      (is (true? (:degraded? r)))
      (is (= "cd deploy && mvn clean package" (:rewritten r))
          "the `cd deploy` part stays; only the post-mvn `deploy` rewrites"))))

(deftest cd-deploy-without-mvn-call-is-not-detected
  (testing "no mvn at all → no rewrite, even with `deploy` in cmd"
    (let [cmd "cd deploy && make install"
          r (dd/maybe-degrade cmd true)]
      (is (false? (:degraded? r))
          "no mvn anywhere → detector returns nil"))))
