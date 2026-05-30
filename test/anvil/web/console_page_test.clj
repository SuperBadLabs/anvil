(ns anvil.web.console-page-test
  "Tests for the build console page + download endpoints (TU2.2/4/5/6/7/8).

   The view's HTML structure is contract — etaoin tests assert on it
   and the htmx-sse extension uses sse-connect attrs. Lock the
   important shapes here so a careless layout edit fails loudly."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [anvil.web.routes :as routes]
            [anvil.web.jenkins-api.jobs :as jobs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-log [content]
  (let [d (.toFile (Files/createTempDirectory "anvil-console-test"
                                              (into-array FileAttribute [])))
        f (io/file d "build.log")]
    (spit f content)
    f))

(defn- register-build!
  "Register a job + push a synthetic build into the jobs store so the
   console view has something to render. Returns the build map."
  [job-name n effects log-file]
  (jobs/register-job! {:name job-name
                       :jenkinsfile-source "pipeline { agent any }"
                       :buildable? true
                       :max-concurrent-builds 1})
  (let [num (jobs/record-build-start! job-name {})]
    (jobs/record-build-end! job-name num
                            {:result :success
                             :effects effects
                             :log-path (.getAbsolutePath log-file)})
    (jobs/find-build job-name num)))

(deftest console-page-renders-stage-folds
  (let [f (tmp-log "before\n")
        _ (register-build!
           "stage-job" 1
           [[:agent/stage-enter {:stage "Build"}]
            [:sh {:cmd "make"}]
            [:stdout "compiling..."]
            [:agent/stage-leave {:stage "Build"}]
            [:agent/stage-enter {:stage "Test"}]
            [:sh {:cmd "make test"}]
            [:stdout "ok"]
            [:agent/stage-leave {:stage "Test"}]]
           f)
        h (routes/make-handler)
        resp (h {:request-method :get :uri "/jobs/stage-job/1/console"})
        body (str (:body resp))]
    (is (= 200 (:status resp)))
    (testing "one <details> per stage"
      (is (re-find #"details class=\"stage-fold\"" body))
      (is (str/includes? body "Build"))
      (is (str/includes? body "Test")))
    (testing "compiled commands rendered with '+ ' prefix"
      (is (str/includes? body "+ make")))
    (testing "step-count chart present"
      (is (str/includes? body "duration-chart"))
      (is (str/includes? body "1 step")))
    (testing "download links wired with correct query params"
      (is (str/includes? body "?download=raw"))
      (is (str/includes? body "?download=text")))))

(deftest console-page-renders-ansi
  (let [f (tmp-log "x")
        ESC (str (char 27))
        _ (register-build!
           "ansi-job" 2
           [[:agent/stage-enter {:stage "go"}]
            [:stdout (str ESC "[31merror" ESC "[0m: boom")]
            [:agent/stage-leave {:stage "go"}]]
           f)
        h (routes/make-handler)
        resp (h {:request-method :get :uri "/jobs/ansi-job/1/console"})
        body (str (:body resp))]
    (is (str/includes? body "ansi-fg-red"))
    (is (str/includes? body "error"))))

(deftest console-page-handles-missing-build
  (let [h (routes/make-handler)
        resp (h {:request-method :get :uri "/jobs/nope/99/console"})]
    (is (= 200 (:status resp)))
    (is (str/includes? (str (:body resp)) "Build not found"))))

(deftest console-download-raw-includes-ansi
  (let [ESC (str (char 27))
        log-bytes (str ESC "[31mboom" ESC "[0m\nnext\n")
        f (tmp-log log-bytes)
        _ (register-build! "dl-raw" 1 [] f)
        h (routes/make-handler)
        ;; Use :query-string, not :query-params — wrap-params parses
        ;; the former and STOMPS the latter even if pre-populated.
        resp (h {:request-method :get :uri "/jobs/dl-raw/1/console"
                 :query-string "download=raw"})]
    (is (= 200 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Content-Disposition"]) "attachment;"))
    (is (str/includes? (:body resp) ESC) "raw download keeps ESC sequences")))

(deftest console-download-text-strips-ansi
  (let [ESC (str (char 27))
        f (tmp-log (str ESC "[31mboom" ESC "[0m\n"))
        _ (register-build! "dl-text" 1 [] f)
        h (routes/make-handler)
        resp (h {:request-method :get :uri "/jobs/dl-text/1/console"
                 :query-string "download=text"})]
    (is (= 200 (:status resp)))
    (is (not (str/includes? (:body resp) ESC))
        "text download MUST have ANSI stripped")
    (is (str/includes? (:body resp) "boom"))))

(deftest console-download-handles-missing-log
  (let [_ (register-build! "no-log" 1 [] (io/file "/nonexistent/path"))
        h (routes/make-handler)
        resp (h {:request-method :get :uri "/jobs/no-log/1/console"
                 :query-string "download=raw"})]
    (is (= 404 (:status resp)))))
