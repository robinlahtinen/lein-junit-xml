;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml.integration-test
  "Proves the self-dependency wiring works the way a real user experiences it.

  Every other suite runs the plugin under :eval-in :leiningen, where the plugin
  is already on the classpath. That deliberately does not exercise the one path
  real projects take: Leiningen resolving the published coordinate and loading
  the runtime half into a genuine subprocess JVM.

  This suite therefore shells out to `lein junit-xml` in a throwaway project and
  needs the plugin installed first:

      lein install && lein test :integration

  It is excluded from the default selector so an ordinary `lein test` never
  depends on the state of the local Maven repository."
  {:integration true}
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [leiningen.junit-xml :as task])
  (:import
   (java.io ByteArrayInputStream File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (javax.xml XMLConstants)
   (javax.xml.parsers DocumentBuilderFactory)
   (javax.xml.transform.stream StreamSource)
   (javax.xml.validation SchemaFactory)
   (org.w3c.dom Document Element)))

(set! *warn-on-reflection* true)

(def ^:private ^:dynamic *project-dir* nil)

(def ^:private project-clj
  "A throwaway consumer project depending on the locally installed plugin."
  (str "(defproject integration-subject \"0.1.0\"\n"
       "  :dependencies [[org.clojure/clojure \"1.12.0\"]]\n"
       "  :plugins [[com.github.robinlahtinen/lein-junit-xml \"" task/version "\"]]\n"
       "  :test-selectors {:fast (fn [m] (contains? m :fast))})\n"))

(def ^:private test-clj
  "Deliberately hostile: ANSI colour, a CDATA terminator, a failure and a throw."
  (str "(ns subject.core-test\n"
       "  (:require [clojure.test :refer [deftest is]]))\n\n"
       "(deftest ^:fast passes-test\n"
       "  (println \"\\u001b[32mgreen\\u001b[0m and a ]]> terminator\")\n"
       "  (is (= 2 (+ 1 1))))\n\n"
       "(deftest fails-test\n"
       "  (is (= 1 2)))\n\n"
       "(deftest errors-test\n"
       "  (throw (ex-info \"kaboom\" {:a 1})))\n"))

(defn- write! [^File f ^String content]
  (io/make-parents f)
  (spit f content :encoding "UTF-8"))

(defn- with-subject-project [test-fn]
  (let [dir (str (Files/createTempDirectory "lein-junit-xml-it" (make-array FileAttribute 0)))]
    (write! (File. dir "project.clj") project-clj)
    (write! (io/file dir "test" "subject" "core_test.clj") test-clj)
    (binding [*project-dir* dir]
      (try (test-fn)
           (finally (doseq [^File f (reverse (file-seq (io/file dir)))] (.delete f)))))))

(use-fixtures :once with-subject-project)

(defn- lein
  "Runs lein in the subject project. Returns {:exit :out :err}."
  [& args]
  (let [launcher (if (str/starts-with? (System/getProperty "os.name") "Windows")
                   ["cmd" "/c" "lein"]
                   ["lein"])]
    (apply shell/sh (concat launcher args [:dir *project-dir*]))))

(defn- report-file ^File []
  (io/file *project-dir* "target" "junit-xml" "TEST-subject.core-test.xml"))

(defn- root ^Element [^File f]
  (.getDocumentElement
   ^Document (-> (DocumentBuilderFactory/newInstance)
                 (.newDocumentBuilder)
                 (.parse (ByteArrayInputStream. (.getBytes (slurp f :encoding "UTF-8") "UTF-8"))))))

(defn- attr ^String [^Element e ^String a] (.getAttribute e a))

(deftest ^:integration resolves-and-reports-in-a-real-subprocess-test
  (let [{:keys [exit out err]} (lein "junit-xml")]
    (testing "the plugin resolves from the local repository and runs"
      (is (not (str/includes? (str out err) "Could not find artifact"))
          "run `lein install` before `lein test :integration`"))
    (testing "a failing suite still exits non-zero"
      (is (= 1 exit)))
    (testing "the report is written into the project's target directory"
      (is (.exists (report-file))))
    (let [suite (root (report-file))]
      (testing "outcomes are reported correctly through the subprocess path"
        (is (= "3" (attr suite "tests")))
        (is (= "1" (attr suite "failures")))
        (is (= "1" (attr suite "errors"))))
      (testing "hostile test output did not corrupt the document"
        ;; The ESC bytes must have been replaced and the ]]> split, or this
        ;; document would not have parsed at all.
        (is (str/includes? (.getTextContent suite) "terminator"))
        (is (not (str/includes? (.getTextContent suite) "\u001b")))))))

(deftest ^:integration report-validates-against-the-schema-test
  (lein "junit-xml")
  (testing "the file a real run produces is schema valid on disk"
    (let [schema (.newSchema (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)
                             (io/resource "jenkins-junit.xsd"))]
      (is (nil? (.validate (.newValidator schema) (StreamSource. (report-file))))))))

(deftest ^:integration selectors-and-retest-survive-the-subprocess-path-test
  (testing "a selector passes through and the suite passes"
    (let [{:keys [exit]} (lein "junit-xml" ":fast")]
      (is (zero? exit))
      (is (= "0" (attr (root (report-file)) "failures")))))
  (testing "lein retest still works, so Leiningen's failure recording is intact"
    ;; test2junit's headline bug: it shadowed the hook that writes .lein-failures,
    ;; leaving retest with nothing to run.
    (lein "junit-xml")
    (is (.exists (io/file *project-dir* ".lein-failures")))
    (let [{:keys [exit out]} (lein "retest")]
      (is (= 1 exit) "retest reruns the failures and reports them")
      (is (str/includes? out "fails-test")))))
