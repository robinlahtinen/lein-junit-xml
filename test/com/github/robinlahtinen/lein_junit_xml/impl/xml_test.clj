;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml.impl.xml-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.github.robinlahtinen.lein-junit-xml.impl.xml :as sut])
  (:import
   (java.io ByteArrayInputStream)
   (java.util Locale)
   (javax.xml.parsers DocumentBuilderFactory)
   (org.w3c.dom Document Element)))

(set! *warn-on-reflection* true)

(defn- parse
  "Parses s as XML, throwing if it is not well-formed. Returns the Document."
  ^Document [^String s]
  (-> (DocumentBuilderFactory/newInstance)
      (.newDocumentBuilder)
      (.parse (ByteArrayInputStream. (.getBytes s "UTF-8")))))

(defn- root
  "Returns the document element of the XML string s."
  ^Element [^String s]
  (.getDocumentElement (parse s)))

(defn- tags
  "Returns the elements named tag beneath e, as a seq."
  [^Element e ^String tag]
  (let [nodes (.getElementsByTagName e tag)]
    (map #(.item nodes %) (range (.getLength nodes)))))

(defn- attr
  "Returns the value of attribute a on element e (empty string when absent)."
  ^String [^Element e ^String a]
  (.getAttribute e a))

(defn- text
  "Returns the text content of element e."
  ^String [^Element e]
  (.getTextContent e))

(defn- restore-locale
  "Guards against a stray Locale/setDefault leaking into other tests."
  [test-fn]
  (let [original (Locale/getDefault)]
    (try (test-fn) (finally (Locale/setDefault original)))))

(use-fixtures :once restore-locale)

(deftest allowed-char-boundaries-test
  (testing "the XML 1.0 Char production boundaries are exact"
    (is (every? sut/allowed-char? [0x9 0xA 0xD 0x20 0xD7FF 0xE000 0xFFFD 0x10000 0x10FFFF]))
    (is (not-any? sut/allowed-char? [0x0 0x8 0xB 0xC 0xE 0x1F 0xD800 0xDFFF 0xFFFE 0xFFFF]))))

(deftest sanitize-test
  (testing "nil becomes an empty string"
    (is (= "" (sut/sanitize nil))))
  (testing "legal text is returned unchanged"
    (is (= "hello\tworld\n" (sut/sanitize "hello\tworld\n"))))
  (testing "control characters are replaced, including ESC from ANSI colour"
    (is (= "a�b" (sut/sanitize (str "a" (char 0x1B) "b"))))
    (is (= "a�b" (sut/sanitize (str "a" (char 0x00) "b")))))
  (testing "an unpaired surrogate is replaced"
    (is (= "a�b" (sut/sanitize (str "a" (char 0xD800) "b")))))
  (testing "an astral character survives as a single code point"
    (let [emoji (String. (Character/toChars 0x1F600))]
      (is (= emoji (sut/sanitize emoji))))))

(deftest escape-attr-test
  (testing "markup and quote characters are escaped"
    (is (= "a&lt;b&gt;&amp;c&quot;d" (sut/escape-attr "a<b>&c\"d"))))
  (testing "whitespace is escaped numerically so it survives a round trip"
    (is (= "a&#10;b&#13;c&#9;d" (sut/escape-attr "a\nb\rc\td"))))
  (testing "an apostrophe needs no escaping inside double quotes"
    (is (= "it's" (sut/escape-attr "it's")))))

(deftest cdata-test
  (testing "a plain payload is wrapped once"
    (is (= "<![CDATA[hello]]>" (sut/cdata "hello"))))
  (testing "an embedded ]]> is split across two sections rather than terminating early"
    (is (= "<![CDATA[x]]]]><![CDATA[>y]]>" (sut/cdata "x]]>y"))))
  (testing "a payload containing ]]> still parses and round trips"
    (is (= "a]]>b" (text (root (str "<r>" (sut/cdata "a]]>b") "</r>")))))))

(deftest seconds-test
  (testing "milliseconds render as seconds with exactly three decimals"
    (is (= "0.000" (sut/seconds 0)))
    (is (= "1.234" (sut/seconds 1234)))
    (is (= "0.012" (sut/seconds 12))))
  (testing "durations over 1000s carry no grouping separator"
    (is (= "1234.567" (sut/seconds 1234567)))
    (is (not (str/includes? (sut/seconds 1234567) ",")))))

(deftest seconds-is-locale-independent-test
  (testing "a comma-decimal default locale cannot leak into the output"
    ;; Jenkins' TimeToFloat strips "," as a thousands separator, so "0,123"
    ;; would be read as 123 seconds. A US-locale CI box never catches this.
    (Locale/setDefault (Locale/forLanguageTag "fi-FI"))
    (is (= "0,123" (format "%.3f" 0.123)) "precondition: the default locale uses a decimal comma")
    (is (= "0.123" (sut/seconds 123)))
    (is (= "1234.567" (sut/seconds 1234567)))))

(deftest timestamp-test
  (testing "epoch millis render as an ISO-8601 UTC instant truncated to seconds"
    (is (= "1970-01-01T00:00:00Z" (sut/timestamp 0)))
    (is (= "2026-09-12T16:18:05Z" (sut/timestamp 1789229885123)))))

(deftest file-name-test
  (testing "a conventional namespace maps straight through"
    (is (= "TEST-my.app.core-test.xml" (sut/file-name "my.app.core-test"))))
  (testing "characters Windows forbids are replaced"
    (is (= "TEST-weird_ns_.xml" (sut/file-name "weird?ns*")))
    (is (= "TEST-a_b_c.xml" (sut/file-name "a<b>c")))))

(deftest outcome-test
  (testing "a case with no results passed"
    (is (= :pass (sut/outcome {:results []}))))
  (testing "skipped outranks everything"
    (is (= :skipped (sut/outcome {:results [{:type :skipped} {:type :fail}]}))))
  (testing "error outranks fail"
    (is (= :error (sut/outcome {:results [{:type :fail} {:type :error}]}))))
  (testing "fail alone is a fail"
    (is (= :fail (sut/outcome {:results [{:type :fail} {:type :fail}]})))))

(def ^:private sample-suite
  {:name       "my.app.core-test"
   :timestamp  1789229885123
   :duration   1234
   :hostname   "build-07"
   :properties {"java.version" "26" "clojure.version" "1.12.6"}
   :out        "hello stdout"
   :cases      [{:name "ok-test" :classname "my.app.core-test" :duration 12 :results []}
                {:name    "bad-test"                                                                 :classname "my.app.core-test" :duration 30
                 :results [{:type :fail :message "expected: (= 1 2)" :detail "at core_test.clj:42"}
                           {:type :fail :message "expected: (= 3 4)" :detail "at core_test.clj:43"}]}
                {:name    "boom-test"                                                                 :classname "my.app.core-test" :duration 5
                 :results [{:type   :error        :message "boom" :class "java.lang.RuntimeException"
                            :detail "stack trace"}]}
                {:name    "skip-test"                                        :classname "my.app.core-test" :duration 0
                 :results [{:type :skipped :message "excluded by selector"}]}]})

(deftest render-test
  (let [xml   (sut/render sample-suite)
        suite (root xml)]
    (testing "the document is well formed with a single testsuite root"
      (is (str/starts-with? xml "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
      (is (= "testsuite" (.getTagName suite))))
    (testing "aggregates count cases, not assertions"
      ;; bad-test has two failing assertions but counts as one failure.
      (is (= "4" (attr suite "tests")))
      (is (= "1" (attr suite "failures")))
      (is (= "1" (attr suite "errors")))
      (is (= "1" (attr suite "skipped"))))
    (testing "suite attributes carry the JUnit set"
      (is (= "my.app.core-test" (attr suite "name")))
      (is (= "1.234" (attr suite "time")))
      (is (= "build-07" (attr suite "hostname")))
      (is (= "2026-09-12T16:18:05Z" (attr suite "timestamp"))))
    (testing "attributes JUnit does not emit are absent, since no schema permits them"
      (let [case-el (first (tags suite "testcase"))]
        (is (= "" (attr case-el "assertions")))
        (is (= "" (attr case-el "file")))
        (is (= "" (attr case-el "line")))))
    (testing "a failing case carries one failure element per assertion"
      (is (= 2 (count (tags suite "failure")))))
    (testing "an error element carries its exception class as type"
      (is (= "java.lang.RuntimeException" (attr (first (tags suite "error")) "type"))))
    (testing "a skipped case carries its reason as text, not an attribute"
      ;; jenkins-junit.xsd types <skipped> as xs:string, so an attribute there
      ;; would be schema-invalid; Jenkins falls back to element text.
      (let [el (first (tags suite "skipped"))]
        (is (= "" (attr el "message")))
        (is (= "excluded by selector" (text el)))))
    (testing "properties are emitted before the cases, sorted by name"
      (is (str/includes? xml "<property name=\"clojure.version\" value=\"1.12.6\"/>"))
      (is (< (str/index-of xml "<properties>") (str/index-of xml "<testcase"))))
    (testing "suite output comes last, after the cases"
      (is (< (str/last-index-of xml "</testcase>") (str/index-of xml "<system-out>"))))))

(deftest render-empty-suite-test
  (testing "a namespace with no cases still renders a valid, honest document"
    (let [suite (root (sut/render {:name "empty.ns" :duration 0 :timestamp 0}))]
      (is (= "0" (attr suite "tests")))
      (is (empty? (tags suite "testcase"))))))

(deftest render-hostile-input-test
  (testing "ANSI colour, control bytes and ]]> in test output cannot break the document"
    (let [nasty (str "[31mred[0m" (char 0) "]]>" (char 0xD800) "<&>")
          xml   (sut/render {:name     nasty
                             :duration 0
                             :out      nasty
                             :cases    [{:name    nasty                                        :classname nasty :duration 0
                                         :results [{:type :fail :message nasty :detail nasty}]}]})]
      (is (some? (root xml)) "the document must still parse"))))
