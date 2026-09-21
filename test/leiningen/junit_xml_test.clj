;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns leiningen.junit-xml-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [leiningen.core.project :as project]
   [leiningen.junit-xml :as sut])
  (:import
   (clojure.lang ExceptionInfo)))

(set! *warn-on-reflection* true)

(def ^:private opts {:output-dir "/tmp/reports"})

(deftest version-matches-project-clj-test
  (testing "the hard-coded version tracks project.clj"
    ;; The task must name a resolvable artifact before the project JVM starts,
    ;; so the version cannot be read from the running project. A test turns
    ;; it into an invariant CI enforces instead.
    (let [declared (:version (project/read-raw "project.clj"))]
      (is (= declared sut/version))))
  (testing "the coordinate the docs tell users to copy is the one that resolves"
    ;; A README.md advertising a version that was never published is the same class
    ;; of defect, and just as mechanical to catch.
    (let [declared   (:version (project/read-raw "project.clj"))
          coordinate (str "com.github.robinlahtinen/lein-junit-xml \"" declared "\"")]
      (doseq [doc ["README.md" "doc/user-guide.md"]]
        (is (str/includes? (slurp doc) coordinate)
            (str doc " must advertise " coordinate))))))

(deftest profile-injects-the-runtime-test
  (let [p                           (sut/profile {:root "/x"} opts)
        [require-form install-form] (:injections p)]
    (testing "the runtime namespace is required before it is called"
      ;; :injections are evaluated in order, so the require must come first or
      ;; the call would not compile.
      (is (= '(clojure.core/require (quote com.github.robinlahtinen.lein-junit-xml)) require-form)))
    (testing "install! is called with the resolved options inlined"
      (is (= '(com.github.robinlahtinen.lein-junit-xml/install! {:output-dir "/tmp/reports"})
             install-form)))))

(deftest injections-survive-being-printed-and-read-test
  (testing "the injected forms round trip through pr-str"
    ;; eval-in-project prints the form into a temp file with *print-dup* and
    ;; *print-meta* false; anything that does not round trip yields an
    ;; unreadable init file and a cryptic subprocess failure.
    (let [forms (:injections (sut/profile {:root "/x"} opts))]
      (is (= forms (read-string (pr-str forms)))))))

(deftest self-dependency-is-added-for-subprocess-runs-test
  (testing "a subprocess run pulls the plugin into the project JVM"
    (let [deps                                      (:dependencies (sut/profile {:root "/x"} opts))
          [artifact version & {:keys [exclusions]}] (first deps)]
      (is (= 1 (count deps)))
      (is (= 'com.github.robinlahtinen/lein-junit-xml artifact))
      (is (= sut/version version))
      (testing "and does not drag a Clojure version into the user's project"
        (is (= ['org.clojure/clojure] exclusions))))))

(deftest self-dependency-is-omitted-under-eval-in-leiningen-test
  (testing "eval-in-leiningen already has the plugin on the classpath"
    ;; Leiningen loads :dependencies the same way it loads plugins there, so
    ;; adding the released jar would shadow local source.
    (is (nil? (:dependencies (sut/profile {:root "/x" :eval-in :leiningen} opts))))
    (is (seq (:injections (sut/profile {:root "/x" :eval-in :leiningen} opts))))))

(deftest task-is-discoverable-and-documented-test
  (testing "the task resolves the way Leiningen looks tasks up"
    (is (some? (ns-resolve 'leiningen.junit-xml 'junit-xml))))
  (testing "help is passed through to the task rather than intercepted"
    (is (:pass-through-help (meta #'sut/junit-xml))))
  (testing "the docstring opens with the one-line summary lein help displays"
    (let [summary (first (str/split-lines (:doc (meta #'sut/junit-xml))))]
      (is (re-find #"^Run the project's tests" summary))))
  (testing "a project is required, so the task is not marked no-project-needed"
    (is (not (:no-project-needed (meta #'sut/junit-xml))))))

(deftest invalid-configuration-is-rejected-before-the-jvm-starts-test
  (testing "a bad :junit-xml value fails fast with the house error contract"
    (let [data (try (sut/junit-xml {:root "/x" :junit-xml {:output-dir 42}})
                    (catch ExceptionInfo e (ex-data e)))]
      (is (= :com.github.robinlahtinen.lein-junit-xml/invalid-type
             (:com.github.robinlahtinen.lein-junit-xml/error data))))))
