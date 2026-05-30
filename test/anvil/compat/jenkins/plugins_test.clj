(ns anvil.compat.jenkins.plugins-test
  "Plugin SDK + dispatcher integration. Registers a custom adapter for a
   step name that anvil doesn't natively know about and verifies the
   dispatcher routes through it instead of marking it :jenkins/unsupported."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.plugins :as plugins]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.translator :as t]))

(use-fixtures :each (fn [f] (plugins/clear-registry!) (f) (plugins/clear-registry!)))

(deftest registry-roundtrip-test
  (testing "register! / find-adapter / unregister! basic flow"
    (let [calls (atom 0)
          adapter (plugins/fn-adapter "test-noop" #{"myCustomStep"}
                                      (fn [_step ctx]
                                        (swap! calls inc)
                                        {:status :ok :ctx ctx}))]
      (plugins/register! adapter)
      (is (= adapter (plugins/find-adapter "myCustomStep")))
      (is (contains? (plugins/registered-step-names) "myCustomStep"))

      (plugins/unregister! "myCustomStep")
      (is (nil? (plugins/find-adapter "myCustomStep"))))))

(deftest custom-adapter-handles-unknown-step-test
  (testing "a registered plugin adapter intercepts unknown steps before the
            dispatcher falls through to :unknown"
    (let [calls (atom [])]
      (plugins/register!
       (plugins/fn-adapter
        "my-plugin"
        #{"myCustomStep"}
        (fn [step ctx]
          (swap! calls conj {:step step :ctx ctx})
          {:status :ok :ctx ctx :output "custom step executed"})))

      ;; Build IR with a step the dispatcher doesn't natively know.
      (let [d (ad/make)
            ir (t/parse "pipeline { agent any; stages { stage('S') { steps {
                          myCustomStep param: 'foo'
                        } } } }")
            flat {:stages (mapv #(select-keys % [:name :steps]) (:stages ir))}
            result (d/run-pipeline flat d {})]
        (is (= :ok (:status result)) "pipeline succeeded")
        (is (= 1 (count @calls)) "the plugin handler ran exactly once")
        (let [evs @(:effects d)]
          (is (not (some #(= :unknown (first %)) evs))
              "the fallthrough :unknown effect was NOT emitted — the plugin handled it"))))))

(deftest plugin-handler-failure-propagates-test
  (testing "a plugin handler that returns :failed aborts the stage"
    (plugins/register!
     (plugins/fn-adapter
      "fragile"
      #{"willFail"}
      (fn [_step _ctx]
        {:status :failed :error :boom :message "test"})))

    (let [d (ad/make)
          ir (t/parse "pipeline { agent any; stages { stage('S') { steps {
                          willFail param: 'x'
                          sh 'never-runs'
                        } } } }")
          flat {:stages (mapv #(select-keys % [:name :steps]) (:stages ir))}
          result (d/run-pipeline flat d {})]
      (is (= :failed (:status result)))
      (let [s1-results (-> result :stages first :step-results)]
        (is (= 1 (count s1-results))
            "only the failed plugin step is recorded; sh was skipped")
        (is (= :failed (-> s1-results first :status)))))))

(deftest fn-adapter-validation-test
  (testing "fn-adapter rejects malformed args"
    (is (thrown? AssertionError (plugins/fn-adapter 1 #{"x"} (fn [_ _]))))
    (is (thrown? AssertionError (plugins/fn-adapter "x" ["x"] (fn [_ _]))))
    (is (thrown? AssertionError (plugins/fn-adapter "x" #{"x"} :not-a-fn)))))

(deftest describe-registry-test
  (testing "describe-registry produces a readable summary"
    (plugins/register! (plugins/fn-adapter "alpha" #{"a1" "a2"} (fn [_ _])))
    (plugins/register! (plugins/fn-adapter "beta"  #{"b1"}      (fn [_ _])))
    (let [s (plugins/describe-registry)]
      (is (clojure.string/includes? s "alpha"))
      (is (clojure.string/includes? s "beta"))
      (is (clojure.string/includes? s "a1"))
      (is (clojure.string/includes? s "b1")))))
