;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml.e2e-test
  "Drives the whole plugin: task -> profile -> injections -> instrumentation -> files.

  The fixture project under test_projects/sample sets :eval-in :leiningen, so the
  test form is evaluated in this JVM and no prior `lein install` is needed to
  resolve the plugin. That is the same rule the task applies in production, not a
  test-only special case.

  The instrumentation is torn down afterwards: leaving clojure.test wrapped would
  affect every namespace this project's own suite runs later."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.github.robinlahtinen.lein-junit-xml :as plugin]
   [leiningen.core.main :as main]
   [leiningen.core.project :as project]
   [leiningen.junit-xml :as task])
  (:import
   (clojure.lang ExceptionInfo)
   (java.io ByteArrayInputStream File StringWriter)
   (javax.xml.parsers DocumentBuilderFactory)
   (org.w3c.dom Document Element)))

(set! *warn-on-reflection* true)

(def ^:private ^String project-dir "test_projects/sample")
(def ^:private ^File output-dir (File. project-dir "target/junit-xml-sample"))

(defn- clean-output! []
  (doseq [^File f (reverse (file-seq output-dir))]
    (.delete f)))

(defn- restore-clojure-test [test-fn]
  (try (test-fn) (finally (plugin/uninstall!))))

(use-fixtures :once restore-clojure-test)

(defn- run-task!
  "Runs `lein junit-xml` against the fixture project with the given arguments.

  Returns the exit code: zero when the suite passed, one when it did not."
  [& args]
  (clean-output!)
  (let [p (project/init-project (project/read (str project-dir "/project.clj") [:default]))]
    (binding [main/*exit-process?* false
              *out*                (StringWriter.)]
      (try
        (apply task/junit-xml p args)
        0
        (catch ExceptionInfo e
          (:exit-code (ex-data e) 1))))))

(defn- report-files []
  (->> (file-seq output-dir)
       (filter #(str/ends-with? (.getName ^File %) ".xml"))
       (sort-by #(.getName ^File %))))

(defn- parse ^Document [^File f]
  (-> (DocumentBuilderFactory/newInstance)
      (.newDocumentBuilder)
      (.parse (ByteArrayInputStream. (.getBytes (slurp f :encoding "UTF-8") "UTF-8")))))

(defn- tags [^Element e ^String tag]
  (let [nodes (.getElementsByTagName e tag)]
    (map #(.item nodes %) (range (.getLength nodes)))))

(defn- attr ^String [^Element e ^String a] (.getAttribute e a))

(deftest writes-reports-for-a-real-lein-test-run-test
  (let [exit  (run-task!)
        files (report-files)]
    (testing "a report file is written for the namespace that ran"
      (is (= ["TEST-sample.core-test.xml"] (mapv #(.getName ^File %) files))))
    (testing "the exit code still reflects the failing suite"
      ;; Delegating to leiningen.test/test rather than rebuilding the test form is what preserves this.
      (is (= 1 exit)))
    (let [suite (.getDocumentElement (parse (first files)))]
      (testing "the suite reports every outcome the fixture produces"
        (is (= "4" (attr suite "tests")))
        (is (= "1" (attr suite "failures")))
        (is (= "1" (attr suite "errors"))))
      (testing "cases are attributed to the namespace, giving Jenkins its tree"
        (is (every? #(= "sample.core-test" (attr % "classname")) (tags suite "testcase"))))
      (testing "what the tests printed is captured"
        (is (str/includes? (.getTextContent ^Element (first (tags suite "system-out")))
                           "sample stdout marker"))))))

(deftest reports-land-in-the-configured-directory-test
  (testing "the :junit-xml project key chooses the output directory"
    (run-task!)
    (is (.isDirectory output-dir))
    (is (seq (report-files)))))

(deftest selectors-pass-straight-through-test
  (let [exit  (run-task! ":only-passing")
        suite (.getDocumentElement (parse (first (report-files))))]
    (testing "only the selected test runs, and the suite passes"
      (is (zero? exit))
      (is (= "0" (attr suite "failures")))
      (is (= "0" (attr suite "errors"))))
    (testing "tests the selector excluded are reported as skipped, not dropped"
      (is (= "3" (attr suite "skipped")))
      (is (= #{"fails-test" "errors-test" "prints-test"}
             (set (map #(attr % "name")
                       (filter #(seq (tags % "skipped")) (tags suite "testcase")))))))))

(deftest namespace-arguments-pass-straight-through-test
  (testing "a bare namespace argument selects that namespace"
    (run-task! "sample.core-test")
    (is (= ["TEST-sample.core-test.xml"] (mapv #(.getName ^File %) (report-files))))))

(deftest leiningen-retest-support-survives-test
  (testing ".lein-failures is still written, so lein retest keeps working"
    (run-task!)
    ;; Leiningen spits .lein-failures relative to the JVM's working directory.
    ;; Under :eval-in :leiningen that is this project, not the fixture project.
    (let [failures (io/file ".lein-failures")]
      (is (.exists failures))
      (let [recorded (read-string (slurp failures))]
        (is (= #{"fails-test" "errors-test"}
               (set (get recorded "sample.core-test"))))))))

(deftest documented-test-alias-works-test
  ;; :aliases {"test" ["junit-xml"]} is the documented way to make plain
  ;; `lein test` write reports. Leiningen marks every alias value ^:replace and
  ;; strips the alias being run to prevent recursion, so this goes through
  ;; resolve-and-apply rather than calling the task function directly.
  (clean-output!)
  (let [p    (project/init-project (project/read (str project-dir "/project.clj") [:default]))
        exit (binding [main/*exit-process?* false
                       *out*                (StringWriter.)]
               (try (main/resolve-and-apply p ["test"]) 0
                    (catch ExceptionInfo e (:exit-code (ex-data e) 1))))]
    (testing "running the aliased task writes reports and does not recurse"
      (is (= ["TEST-sample.core-test.xml"] (mapv #(.getName ^File %) (report-files)))))
    (testing "and still reports the failing suite"
      (is (= 1 exit)))))
