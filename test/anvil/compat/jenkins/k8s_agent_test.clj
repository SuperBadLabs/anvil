(ns anvil.compat.jenkins.k8s-agent-test
  "v0.6 T1 — `agent { kubernetes { yaml '...' } }` and
   `agent { kubernetes { containerTemplate(...) } }` translator + IR
   shape tests.

   Covers:
   - T1.3 declarative yaml form: hint extraction (image / namespace /
     resource limits) from the inline yaml payload
   - T1.4 containerTemplate form: structured args → IR
   - Empty `kubernetes { }` body degrades honestly
   - The dispatcher honors the spec when the :k8s-agent flag is on +
     image is extractable (no :agent/degraded effect)"
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.agent :as agent]
            [anvil.features :as features]))

;; ---------------------------------------------------------------------------
;; Declarative yaml form (T1.3)
;; ---------------------------------------------------------------------------

(deftest yaml-block-parses-into-kubernetes-ir
  (let [src (str "pipeline {\n"
                 "  agent {\n"
                 "    kubernetes {\n"
                 "      yaml '''\n"
                 "apiVersion: v1\n"
                 "kind: Pod\n"
                 "metadata:\n"
                 "  namespace: builds\n"
                 "spec:\n"
                 "  containers:\n"
                 "  - name: jnlp\n"
                 "    image: eclipse-temurin:21\n"
                 "    resources:\n"
                 "      limits:\n"
                 "        memory: 2Gi\n"
                 "        cpu: 1500m\n"
                 "'''\n"
                 "    }\n"
                 "  }\n"
                 "  stages { stage('s') { steps { sh 'true' } } }\n"
                 "}\n")
        ir (t/parse src "Jenkinsfile")
        a (:agent ir)]
    (testing "kubernetes IR carries yaml + raw-form"
      (is (some? (:kubernetes a)))
      (is (= :yaml (-> a :kubernetes :raw-form)))
      (is (string? (-> a :kubernetes :yaml))))
    (testing "image extracted from yaml"
      (is (= "eclipse-temurin:21" (-> a :kubernetes :image))))
    (testing "namespace extracted from yaml"
      (is (= "builds" (-> a :kubernetes :namespace))))
    (testing "resource limits extracted from yaml"
      (is (= 2048 (-> a :kubernetes :resource-limits :memory-mb))
          "memory: 2Gi → 2048 MB")
      (is (= 1.5 (-> a :kubernetes :resource-limits :cpus))
          "cpu: 1500m → 1.5 cores"))))

(deftest yaml-without-resource-limits-omits-the-key
  (let [src (str "pipeline {\n"
                 "  agent {\n"
                 "    kubernetes {\n"
                 "      yaml '''\n"
                 "apiVersion: v1\n"
                 "kind: Pod\n"
                 "spec:\n"
                 "  containers:\n"
                 "  - name: c\n"
                 "    image: busybox:1.36\n"
                 "'''\n"
                 "    }\n"
                 "  }\n"
                 "  stages { stage('s') { steps { sh 'true' } } }\n"
                 "}\n")
        ir (t/parse src "Jenkinsfile")
        a (:agent ir)]
    (is (= "busybox:1.36" (-> a :kubernetes :image)))
    (is (nil? (-> a :kubernetes :resource-limits))
        "no resources block in yaml → no :resource-limits in IR")))

(deftest empty-kubernetes-block-is-degrade-marker
  (let [src (str "pipeline {\n"
                 "  agent { kubernetes { } }\n"
                 "  stages { stage('s') { steps { sh 'true' } } }\n"
                 "}\n")
        ir (t/parse src "Jenkinsfile")
        a (:agent ir)]
    (is (= :unknown (-> a :kubernetes :raw-form)))
    (is (= :k8s-empty-block (:degrade-reason a)))))

;; ---------------------------------------------------------------------------
;; containerTemplate form (T1.4)
;; ---------------------------------------------------------------------------

(deftest container-template-form-parses-into-kubernetes-ir
  (let [src (str "pipeline {\n"
                 "  agent {\n"
                 "    kubernetes {\n"
                 "      containerTemplate(name: 'main', image: 'maven:3.9-eclipse-temurin-21', "
                 "resourceLimitMemory: '4Gi', resourceLimitCpu: '2')\n"
                 "    }\n"
                 "  }\n"
                 "  stages { stage('s') { steps { sh 'true' } } }\n"
                 "}\n")
        ir (t/parse src "Jenkinsfile")
        a (:agent ir)]
    (testing "kubernetes IR carries container-template raw-form"
      (is (= :container-template (-> a :kubernetes :raw-form))))
    (testing "image extracted from named arg"
      (is (= "maven:3.9-eclipse-temurin-21" (-> a :kubernetes :image))))
    (testing "resource limits extracted from named args"
      (is (= 4096 (-> a :kubernetes :resource-limits :memory-mb))
          "resourceLimitMemory: 4Gi → 4096 MB")
      (is (= 2.0 (-> a :kubernetes :resource-limits :cpus))
          "resourceLimitCpu: '2' → 2.0 cores"))
    (testing "structured container-template payload preserved for K8sBackend"
      (let [ct (-> a :kubernetes :container-template)]
        (is (= "main" (:name ct)))
        (is (= "maven:3.9-eclipse-temurin-21" (:image ct)))))))

;; ---------------------------------------------------------------------------
;; Agent summary + rejected? — old (pre-T1) shape removed
;; ---------------------------------------------------------------------------

(deftest agent-summary-handles-k8s-shape
  (is (= "kubernetes:busybox:1.36"
         (agent/agent-summary {:kubernetes {:image "busybox:1.36"
                                            :raw-form :yaml}})))
  (is (= "kubernetes:yaml"
         (agent/agent-summary {:kubernetes {:raw-form :yaml}}))
      "no-image yaml form still renders summary"))

(deftest k8s-no-longer-rejected
  ;; v0.6 T1 retires the blanket rejection so the dispatcher's
  ;; honor-or-degrade logic carries the decision.
  (is (false? (agent/rejected? {:kubernetes {:image "busybox:1.36"}})))
  (is (nil? (agent/rejection-reason {:kubernetes {:image "x"}}))))

;; ---------------------------------------------------------------------------
;; Deferred-ness reflects feature flag + IR completeness
;; ---------------------------------------------------------------------------

(deftest deferred-flips-with-flag-and-image-availability
  (testing "flag off → deferred"
    (features/set! :k8s-agent false)
    (is (true? (agent/deferred? {:kubernetes {:image "busybox:1.36"}}))))
  (testing "flag on + image → NOT deferred (runtime honors)"
    (features/set! :k8s-agent true)
    (is (false? (agent/deferred? {:kubernetes {:image "busybox:1.36"}}))))
  (testing "flag on + no image → deferred (degrade honestly)"
    (features/set! :k8s-agent true)
    (is (true? (agent/deferred? {:kubernetes {:raw-form :unknown}}))))
  ;; Restore the post-T1 default-on state.
  (features/set! :k8s-agent true))
