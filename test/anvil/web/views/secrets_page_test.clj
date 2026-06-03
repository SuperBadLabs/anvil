(ns anvil.web.views.secrets-page-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [anvil.storage.db :as db]
            [anvil.storage.credentials :as creds]
            [anvil.web.views.secrets-page :as sp]))

(def ^:private tmp-db (str (System/getProperty "java.io.tmpdir") "/anvil-secrets-page-test.db"))

(defn- with-fresh-db [f]
  (db/close!)
  (when (.exists (io/file tmp-db)) (.delete (io/file tmp-db)))
  (db/init! tmp-db)
  (try (f)
       (finally (db/close!) (.delete (io/file tmp-db)))))

(use-fixtures :each with-fresh-db)

(deftest forbidden-from-non-allowlisted-ip
  (let [resp (sp/page {:remote-addr "203.0.113.5"
                       :request-method :get :uri "/secrets"})]
    (is (= 403 (:status resp)))
    (is (str/includes? (:body resp) "Forbidden"))))

(deftest allowed-from-loopback-when-no-config
  (let [resp (sp/page {:remote-addr "127.0.0.1"
                       :request-method :get :uri "/secrets"})]
    ;; layout/page returns the rendered HTML string for non-error paths.
    (is (str/includes? (str resp) "Secrets ("))))

(deftest page-never-renders-plaintext
  (creds/add! {:id "demo" :type :string :value "VERY-SECRET-VALUE"
               :description ""})
  (let [resp (sp/page {:remote-addr "127.0.0.1"
                       :request-method :get :uri "/secrets"})
        body (str resp)]
    (is (not (str/includes? body "VERY-SECRET-VALUE")) "the plaintext must never appear")
    (is (str/includes? body "demo") "the id is visible")))
