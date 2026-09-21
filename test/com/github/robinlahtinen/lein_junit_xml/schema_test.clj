;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml.schema-test
  "Schema conformance: the check that actually substantiates \"JUnit compliant\".

  jenkins-junit.xsd is a hard gate. It is the schema junit-framework's own
  XmlReportAssertions validates every legacy report against, and it is the
  lineage the Jenkins JUnit plugin descends from.

  surefire-test-report-3.0.2.xsd cannot also be a gate: it permits no @timestamp
  and no @hostname on <testsuite> and declares no anyAttribute, so JUnit 6.2.0's
  own output is invalid against it. We assert the common subset instead, and
  encode the divergence as a test so it is not silently \"fixed\" the wrong way."
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [com.github.robinlahtinen.lein-junit-xml.impl.xml :as xml])
  (:import
   (java.io ByteArrayInputStream StringReader)
   (javax.xml XMLConstants)
   (javax.xml.parsers DocumentBuilderFactory)
   (javax.xml.transform.stream StreamSource)
   (javax.xml.validation Schema SchemaFactory Validator)
   (org.w3c.dom Document Node)
   (org.xml.sax SAXException)))

(set! *warn-on-reflection* true)

(defn- schema
  "Loads a vendored XSD from dev-resources."
  ^Schema [resource-name]
  (let [factory (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)]
    (.newSchema factory (io/resource resource-name))))

(def ^:private jenkins-schema (delay (schema "jenkins-junit.xsd")))
(def ^:private surefire-schema (delay (schema "surefire-test-report-3.0.2.xsd")))

(defn- validation-error
  "Returns the validation error message for xml against schema, or nil if valid."
  [^Schema s ^String xml]
  (let [^Validator v (.newValidator s)]
    (try
      (.validate v (StreamSource. (StringReader. xml)))
      nil
      (catch SAXException e (.getMessage e)))))

(defn- jenkins-valid?
  [suite]
  (let [err (validation-error @jenkins-schema (xml/render suite))]
    (when err (println "jenkins-junit.xsd:" err))
    (nil? err)))

(def ^:private suites
  "One suite value per shape the plugin can emit."
  {:passing
   {:name       "my.app.pass-test"                                                           :timestamp 1789229885123 :duration 120 :hostname "build-07"
    :properties {"clojure.version" "1.12.6" "java.version" "26"}
    :cases      [{:name "adds-test" :classname "my.app.pass-test" :duration 12 :results []}]}

   :failing
   {:name  "my.app.fail-test"                                                                                                               :timestamp 1789229885123 :duration 120 :hostname "build-07"
    :cases [{:name    "subs-test"                                                                :classname "my.app.fail-test" :duration 30
             :results [{:type :fail :message "expected: (= 1 2)" :detail "at core_test.clj:42"}
                       {:type :fail :message "expected: (= 3 4)" :detail "at core_test.clj:43"}]}]}

   :erroring
   {:name  "my.app.error-test"                                                                                                                                                         :timestamp 1789229885123 :duration 120 :hostname "build-07"
    :cases [{:name    "boom-test"                                                                                                           :classname "my.app.error-test" :duration 5
             :results [{:type   :error                                                  :message "boom" :class "java.lang.RuntimeException"
                        :detail "java.lang.RuntimeException: boom\n\tat foo(bar.clj:1)"}]}]}

   :skipped
   {:name  "my.app.skip-test"                                                                                           :timestamp 1789229885123 :duration 0 :hostname "build-07"
    :cases [{:name    "pending-test"                                          :classname "my.app.skip-test" :duration 0
             :results [{:type :skipped :message "excluded by test selector"}]}]}

   :with-output
   {:name  "my.app.chatty-test"                                                            :timestamp 1789229885123         :duration 7 :hostname "build-07"
    :out   "printed to stdout\n"                                                           :err       "printed to stderr\n"
    :cases [{:name "chatty-test" :classname "my.app.chatty-test" :duration 7 :results []}]}

   :empty
   {:name  "my.app.empty-test" :timestamp 1789229885123 :duration 0 :hostname "build-07"
    :cases []}

   :hostile
   (let [nasty (str "\u001B[31mred\u001B[0m" (char 0) "]]>" (char 0xD800) "<&>\"'\n\t")]
     {:name  "my.app.hostile-test"                                                                                :timestamp 1789229885123 :duration 1 :hostname "build-07"
      :out   nasty                                                                                                :err       nasty
      :cases [{:name    "nasty-test"                                 :classname "my.app.hostile-test" :duration 1
               :results [{:type :fail :message nasty :detail nasty}]}]})})

(deftest every-shape-validates-against-the-jenkins-schema-test
  (doseq [[shape suite] suites]
    (testing (str "a " (name shape) " suite is schema valid")
      (is (jenkins-valid? suite)))))

(deftest a-case-carrying-both-a-failure-and-an-error-validates-test
  (testing "error elements precede failure elements, as the schema sequence requires"
    ;; JUnit's own writer emits failure before error and is invalid here; we
    ;; follow the schema, which costs nothing since Jenkins is order-insensitive.
    (is (jenkins-valid?
         {:name  "my.app.both-test"                                                                                                             :timestamp 0 :duration 1 :hostname "h"
          :cases [{:name    "both-test"                                                               :classname "my.app.both-test" :duration 1
                   :results [{:type :error :message "e" :class "java.lang.Exception" :detail "trace"}
                             {:type :fail :message "f" :detail "detail"}]}]}))))

(deftest surefire-accepts-the-common-subset-test
  (testing "output without @timestamp and @hostname satisfies the Surefire schema too"
    (let [subset (-> (:failing suites) (dissoc :timestamp :hostname))]
      (is (nil? (validation-error @surefire-schema (xml/render subset)))))))

(deftest surefire-and-jenkins-schemas-genuinely-disagree-test
  (testing "the Surefire schema rejects @timestamp, which JUnit itself emits"
    ;; This is why Surefire is documented as a non-target rather than a gate.
    ;; If this assertion ever starts failing, the divergence has gone away and
    ;; the plan's reasoning should be revisited rather than the test deleted.
    (let [xml (xml/render (:failing suites))]
      (is (nil? (validation-error @jenkins-schema xml)))
      (is (some? (validation-error @surefire-schema xml))))))

;; There is no official JUnit XML specification. testmoapp/junitxml catalogues
;; what tools actually emit and consume; these sets restate the element and
;; attribute names documented there for the parts of the format we use, so the
;; emitter cannot quietly grow a name no consumer recognises.

(def ^:private documented-elements
  #{"testsuite" "properties" "property" "testcase" "failure" "error" "skipped"
    "system-out" "system-err"})

(def ^:private documented-attributes
  {"testsuite"  #{"name" "tests" "failures" "errors" "skipped" "assertions" "time"
                  "timestamp" "hostname" "id" "package" "file" "group"}
   "property"   #{"name" "value"}
   "testcase"   #{"name" "classname" "assertions" "time" "file" "line" "status" "group"}
   "failure"    #{"message" "type"}
   "error"      #{"message" "type"}
   "skipped"    #{"message"}
   "properties" #{}
   "system-out" #{}
   "system-err" #{}})

(defn- elements-and-attributes
  "Returns [#{element-name} {element-name #{attribute-name}}] for an XML string."
  [^String xml]
  (let [doc ^Document (-> (DocumentBuilderFactory/newInstance)
                          (.newDocumentBuilder)
                          (.parse (ByteArrayInputStream. (.getBytes xml "UTF-8"))))]
    (loop [[^Node n & more] [(.getDocumentElement doc)]
           els              #{}
           attrs            {}]
      (if (nil? n)
        [els attrs]
        (let [tag   (.getNodeName n)
              as    (.getAttributes n)
              names (set (map #(.getNodeName ^Node (.item as %)) (range (.getLength as))))
              kids  (let [cs (.getChildNodes n)]
                      (->> (range (.getLength cs))
                           (map #(.item cs %))
                           (filter #(= Node/ELEMENT_NODE (.getNodeType ^Node %)))))]
          (recur (concat more kids)
                 (conj els tag)
                 (update attrs tag (fnil into #{}) names)))))))

(deftest emitted-names-are-all-documented-conventions-test
  (doseq [[shape suite] suites]
    (let [[els attrs] (elements-and-attributes (xml/render suite))]
      (testing (str "a " (name shape) " suite emits only documented element names")
        (is (empty? (remove documented-elements els))))
      (testing (str "a " (name shape) " suite emits only documented attribute names")
        (doseq [[el names] attrs]
          (is (empty? (remove (documented-attributes el #{}) names))
              (str el " carries an undocumented attribute")))))))

(deftest we-emit-no-attribute-junit-itself-omits-test
  (testing "no assertions, file or line on testcase"
    ;; These are documented conventions and we have the data, but neither schema
    ;; permits them, so emitting them would cost the compliance claim.
    (let [[_ attrs] (elements-and-attributes (xml/render (:failing suites)))]
      (is (empty? (set/intersection (get attrs "testcase" #{})
                                    #{"assertions" "file" "line"}))))))
