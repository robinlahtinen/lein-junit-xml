;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml.impl.xml-property-test
  "Property tests for the sanitiser and emitter.

  This is the highest-risk code in the plugin: Jenkins parses report files with a
  strict SAX reader and replaces an entire suite with a synthetic [failed-to-read]
  case on any parse error. The inputs are arbitrary test output and Throwable
  messages, so the emitter must survive anything."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [com.github.robinlahtinen.lein-junit-xml.impl.xml :as sut])
  (:import
   (java.io ByteArrayInputStream)
   (javax.xml.parsers DocumentBuilderFactory)
   (org.w3c.dom Document)))

(set! *warn-on-reflection* true)

(defn- parse
  ^Document [^String s]
  (-> (DocumentBuilderFactory/newInstance)
      (.newDocumentBuilder)
      (.parse (ByteArrayInputStream. (.getBytes s "UTF-8")))))

(def ^:private gen-code-point
  "Code points weighted towards the cases that actually break XML emitters."
  (gen/frequency [[4 (gen/choose 0x20 0x7E)]                ; plain ASCII
                  [3 (gen/choose 0x00 0x1F)]                ; control chars, incl. ESC and NUL
                  [2 (gen/choose 0xD800 0xDFFF)]            ; unpaired surrogates
                  [2 (gen/choose 0x80 0xD7FF)]              ; the rest of the BMP
                  [1 (gen/choose 0xE000 0xFFFF)]            ; incl. 0xFFFE/0xFFFF non-characters
                  [1 (gen/choose 0x10000 0x10FFFF)]]))      ; astral planes

(def ^:private gen-hostile-string
  "Strings mixing hostile code points with sequences that terminate XML syntax."
  (gen/fmap
   (fn [parts] (apply str parts))
   (gen/vector
    (gen/frequency
     [[6 (gen/fmap #(String. (Character/toChars %)) gen-code-point)]
      [1 (gen/elements ["]]>" "<![CDATA[" "&" "<" ">" "\"" "'" "&#10;" "\r\n" "--" "?>"])]])
    0 40)))

(defn- normalise-line-endings
  "XML 1.0 normalises \\r\\n and lone \\r to \\n in content, including inside CDATA."
  [^String s]
  (-> s (str/replace "\r\n" "\n") (str/replace "\r" "\n")))

(defspec cdata-round-trips-any-content 200
  (prop/for-all [s gen-hostile-string]
                (let [doc (parse (str "<r>" (sut/cdata s) "</r>"))]
                  (= (normalise-line-endings (sut/sanitize s))
                     (.getTextContent (.getDocumentElement doc))))))

(defspec attributes-round-trip-any-value 200
  ;; Whitespace is escaped numerically precisely so that attribute-value
  ;; normalisation cannot turn newlines and tabs into spaces.
  (prop/for-all [s gen-hostile-string]
                (let [doc (parse (str "<r a=\"" (sut/escape-attr s) "\"/>"))]
                  (= (sut/sanitize s)
                     (.getAttribute (.getDocumentElement doc) "a")))))

(defspec rendered-suites-always-parse 200
  (prop/for-all [s gen-hostile-string]
                (let [xml (sut/render {:name       s
                                       :hostname   s
                                       :duration   0
                                       :timestamp  0
                                       :properties {s s}
                                       :out        s
                                       :err        s
                                       :cases      [{:name      s
                                                     :classname s
                                                     :duration  1
                                                     :results   [{:type :fail :message s :detail s}
                                                                 {:type :error :message s :class s :detail s}
                                                                 {:type :skipped :message s :detail s}]}]})]
                  (some? (parse xml)))))

(defspec sanitised-output-contains-only-legal-characters 200
  (prop/for-all [s gen-hostile-string]
                (every? sut/allowed-char? (.toArray (.codePoints (sut/sanitize s))))))

(deftest generator-actually-produces-hostile-input-test
  (testing "the generator is not silently degenerate"
    ;; A property suite that only ever sees clean ASCII proves nothing.
    (let [samples     (gen/sample gen-hostile-string 200)
          ^String all (apply str samples)]
      (is (some #(not (sut/allowed-char? %)) (.toArray (.codePoints all)))
          "expected at least one character XML forbids")
      (is (str/includes? all "]]>")
          "expected at least one CDATA terminator"))))
