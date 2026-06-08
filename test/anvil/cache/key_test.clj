(ns anvil.cache.key-test
  "v0.5 T1.1 — cache key invariants. R1: wrong key collisions corrupt
   downstream builds, so each invariant is its own test."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.cache.key :as k]))

(def base-step
  {:image-digest "sha256:0000000000000000000000000000000000000000000000000000000000000000"
   :command "mvn install"
   :env {"JAVA_HOME" "/opt/jdk" "MAVEN_OPTS" "-Xmx2g"}
   :input-tree [["pom.xml" "sha256:aaa"]
                ["src/main/java/Main.java" "sha256:bbb"]]})

(deftest derive-returns-64-char-hex
  (let [r (k/derive base-step)]
    (is (string? r))
    (is (= 64 (count r)))
    (is (re-matches #"[0-9a-f]{64}" r)
        "key is lower-case hex SHA-256")))

(deftest missing-image-digest-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"image-digest"
                        (k/derive (assoc base-step :image-digest nil))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"image-digest"
                        (k/derive (assoc base-step :image-digest "   ")))))

;; --- Invariants: SAME key ---

(deftest env-reorder-same-key
  (is (k/parts-equal? base-step
                      (update base-step :env
                              #(into (sorted-map-by (comp - compare)) %)))))

(deftest env-keyword-vs-string-same-key
  (let [via-kw (assoc base-step :env {:JAVA_HOME "/opt/jdk" :MAVEN_OPTS "-Xmx2g"})]
    (is (k/parts-equal? base-step via-kw))))

(deftest input-tree-reorder-same-key
  (let [reordered (update base-step :input-tree reverse)]
    (is (k/parts-equal? base-step reordered))))

(deftest empty-env-and-nil-env-same-key
  (is (k/parts-equal? (assoc base-step :env nil)
                      (assoc base-step :env {}))))

(deftest empty-tree-and-nil-tree-same-key
  (is (k/parts-equal? (assoc base-step :input-tree nil)
                      (assoc base-step :input-tree []))))

;; --- Invariants: DIFFERENT key ---

(deftest different-image-digest-different-key
  (is (not= (k/derive base-step)
            (k/derive (assoc base-step :image-digest "sha256:1111111111111111111111111111111111111111111111111111111111111111")))))

(deftest different-command-different-key
  (is (not= (k/derive base-step)
            (k/derive (assoc base-step :command "mvn package"))))
  (is (not= (k/derive base-step)
            (k/derive (assoc base-step :command "mvn install ")))
      "trailing whitespace IS sensitive — commands are not normalized"))

(deftest removing-env-var-different-key
  (is (not= (k/derive base-step)
            (k/derive (update base-step :env dissoc "MAVEN_OPTS")))))

(deftest adding-env-var-different-key
  (is (not= (k/derive base-step)
            (k/derive (update base-step :env assoc "GIT_REF" "main")))))

(deftest changing-env-value-different-key
  (is (not= (k/derive base-step)
            (k/derive (assoc-in base-step [:env "JAVA_HOME"] "/opt/jdk21")))))

(deftest changing-a-tree-file-hash-different-key
  (is (not= (k/derive base-step)
            (k/derive (assoc-in base-step [:input-tree 0 1] "sha256:CHANGED")))))

(deftest adding-tree-file-different-key
  (is (not= (k/derive base-step)
            (k/derive (update base-step :input-tree conj
                              ["src/test/java/T.java" "sha256:ccc"])))))

;; --- Boundary attack: NUL inside an input field ---

(deftest nul-byte-in-input-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"NUL"
                        (k/derive (assoc base-step :command (str "mvn" (char 0) "install"))))
      "NUL in command would let an attacker collide field boundaries")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"NUL"
                        (k/derive (assoc-in base-step [:env "JAVA_HOME"]
                                            (str "/opt/jdk" (char 0) "evil"))))
      "NUL in env value — same attack vector"))

;; --- Determinism ---

(deftest deterministic-across-calls
  (let [k1 (k/derive base-step)
        k2 (k/derive base-step)]
    (is (= k1 k2))))
