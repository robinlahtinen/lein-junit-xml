;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml.impl.xml
  "Renders a suite value as JUnit legacy XML.

  This namespace is an implementation detail; do not use from application code.

  Every function here is pure. The rendered shape matches what JUnit 6.2.0's own
  XmlReportWriter emits, and validates against jenkins-junit.xsd.

  Commands:
    allowed-char? - is a code point permitted in an XML 1.0 document?
    sanitize      - replace characters XML 1.0 forbids.
    escape-attr   - escape a string for use as an attribute value.
    cdata         - wrap a string in one or more CDATA sections.
    seconds       - format milliseconds as locale-independent seconds.
    timestamp     - format epoch milliseconds as an ISO-8601 UTC instant.
    file-name     - the TEST-<ns>.xml file name for a suite.
    outcome       - classify a case as :pass, :skipped, :error or :fail.
    render        - render a whole suite value as an XML document string."
  {:skip-wiki true
   :no-doc    true}
  (:require
   [clojure.string :as str])
  (:import
   (java.time Instant)
   (java.time.format DateTimeFormatter)
   (java.time.temporal ChronoUnit)
   (java.util Locale)))

(set! *warn-on-reflection* true)

(def ^:private illegal-replacement
  "Replacement for code points XML 1.0 forbids, matching JUnit's writer."
  (char 0xFFFD))

(defn allowed-char?
  "Returns true if code-point is permitted in an XML 1.0 document.

  Source: https://www.w3.org/TR/xml/#charsets. Unpaired surrogates (0xD800-0xDFFF)
  fall outside every allowed range and are therefore rejected."
  [^long code-point]
  (or (= code-point 0x9)
      (= code-point 0xA)
      (= code-point 0xD)
      (<= 0x20 code-point 0xD7FF)
      (<= 0xE000 code-point 0xFFFD)
      (<= 0x10000 code-point 0x10FFFF)))

(defn sanitize
  "Returns s with every character XML 1.0 forbids replaced by U+FFFD.

  Jenkins parses with a strict SAX reader and discards an entire report file on a
  parse error, so this is applied to every string that enters the document.
  A nil input yields an empty string."
  ^String [^String s]
  (if (nil? s)
    ""
    (let [n  (.length s)
          sb (StringBuilder. n)]
      (loop [i 0]
        (if (< i n)
          (let [cp (.codePointAt s i)]
            (if (allowed-char? cp)
              (.appendCodePoint sb cp)
              (.append sb illegal-replacement))
            (recur (+ i (Character/charCount cp))))
          (.toString sb))))))

(defn escape-attr
  "Returns s sanitized and escaped for use as an XML attribute value.

  Whitespace is escaped numerically so multi-line failure messages survive a
  parse/serialise round trip; XMLStreamWriter implementations do not do this,
  which is why JUnit wraps its own writer to compensate."
  ^String [^String s]
  (let [s  (sanitize s)
        n  (.length s)
        sb (StringBuilder. n)]
    (dotimes [i n]
      (let [c (.charAt s i)]
        (case c
          \& (.append sb "&amp;")
          \< (.append sb "&lt;")
          \> (.append sb "&gt;")
          \" (.append sb "&quot;")
          \newline (.append sb "&#10;")
          \return (.append sb "&#13;")
          \tab (.append sb "&#9;")
          (.append sb c))))
    (.toString sb)))

(def ^:private cdata-split
  "Splits between ]] and > so a payload cannot terminate its own section."
  #"(?<=]])(?=>)")

(defn cdata
  "Returns s sanitized and wrapped in one or more CDATA sections."
  ^String [^String s]
  (->> (str/split (sanitize s) cdata-split)
       (map #(str "<![CDATA[" % "]]>"))
       (str/join)))

(defn seconds
  "Returns millis rendered as seconds with three decimal places.

  Always uses Locale/ROOT: a locale-dependent decimal comma is schema-invalid
  (the Surefire XSD types @time as xs:float) and Jenkins' TimeToFloat strips
  commas as thousands separators, silently inflating the duration 1000-fold."
  ^String [millis]
  (String/format Locale/ROOT "%.3f" (to-array [(/ (double (long millis)) 1000.0)])))

(def ^:private ^DateTimeFormatter instant-formatter DateTimeFormatter/ISO_INSTANT)

(defn timestamp
  "Returns epoch millis as an ISO-8601 UTC instant at second precision.

  JUnit writes a zone-less local date-time; Jenkins then appends 'Z' and reads it
  as UTC, shifting every suite start by the agent's offset. Emitting a real UTC
  instant is both schema-valid and correct in Jenkins."
  ^String [millis]
  (.format instant-formatter
           (.truncatedTo (Instant/ofEpochMilli (long millis)) ChronoUnit/SECONDS)))

(defn file-name
  "Returns the report file name for a suite named suite-name.

  Characters outside [A-Za-z0-9._-] become '_' so the name is safe on Windows.
  Windows reserved device names cannot arise because of the TEST- prefix."
  ^String [^String suite-name]
  (str "TEST-" (str/replace (sanitize suite-name) #"[^A-Za-z0-9._-]" "_") ".xml"))

(defn outcome
  "Returns the single JUnit outcome for test-case: :skipped, :error, :fail or :pass.

  A case is counted once in the suite aggregates even when it produced several
  failing assertions."
  [test-case]
  (let [types (into #{} (map :type) (:results test-case))]
    (cond
      (types :skipped) :skipped
      (types :error) :error
      (types :fail) :fail
      :else :pass)))

(defn- attr
  "Appends ` k=\"v\"` to sb, skipping nil values."
  [^StringBuilder sb ^String k v]
  (when (some? v)
    (doto sb
      (.append " ")
      (.append k)
      (.append "=\"")
      (.append (escape-attr (str v)))
      (.append "\""))))

(defn- append-body
  "Appends an element with a CDATA body, or an empty element when body is blank."
  [^StringBuilder sb ^String tag attrs ^String body]
  (.append sb "<")
  (.append sb tag)
  (doseq [[k v] attrs] (attr sb k v))
  (if (str/blank? body)
    (.append sb "/>")
    (doto sb
      (.append ">")
      (.append (cdata body))
      (.append "</")
      (.append tag)
      (.append ">")))
  (.append sb "\n"))

(defn- append-result
  [^StringBuilder sb result]
  (if (= :skipped (:type result))
    ;; jenkins-junit.xsd declares <skipped> as a simple type, so it may carry no
    ;; attributes at all. JUnit likewise writes the reason as text content, and
    ;; Jenkins' parser falls back to element text when @message is absent.
    (append-body sb "skipped" nil (or (:detail result) (:message result)))
    (append-body sb (if (= :error (:type result)) "error" "failure")
                 [["message" (:message result)] ["type" (:class result)]]
                 (:detail result))))

(defn- append-case
  [^StringBuilder sb test-case]
  (.append sb "<testcase")
  (attr sb "name" (:name test-case))
  (attr sb "classname" (:classname test-case))
  (attr sb "time" (seconds (:duration test-case 0)))
  (let [results (:results test-case)]
    (if (empty? results)
      (.append sb "/>\n")
      (do
        (.append sb ">\n")
        ;; Child order follows the jenkins-junit.xsd sequence:
        ;; (skipped?, error*, failure*, system-out?, system-err?)
        (doseq [t [:skipped :error :fail]
                r (filter #(= t (:type %)) results)]
          (append-result sb r))
        (.append sb "</testcase>\n")))))

(defn render
  "Returns suite rendered as a complete JUnit legacy XML document string.

  Args:
    suite - a suite value; see doc/reference.md for its shape.

  Returns a String beginning with an XML declaration and containing a single
  <testsuite> root element."
  ^String [suite]
  (let [cases      (vec (:cases suite))
        by-outcome (frequencies (map outcome cases))
        sb         (StringBuilder. 4096)]
    (.append sb "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    (.append sb "<testsuite")
    (attr sb "name" (:name suite))
    (attr sb "tests" (count cases))
    (attr sb "skipped" (get by-outcome :skipped 0))
    (attr sb "failures" (get by-outcome :fail 0))
    (attr sb "errors" (get by-outcome :error 0))
    (attr sb "time" (seconds (:duration suite 0)))
    (attr sb "hostname" (:hostname suite))
    (attr sb "timestamp" (some-> (:timestamp suite) timestamp))
    (.append sb ">\n")
    ;; Suite sequence: (properties?, testcase*, system-out?, system-err?)
    (when (seq (:properties suite))
      (.append sb "<properties>\n")
      (doseq [[k v] (sort-by key (:properties suite))]
        (.append sb "<property")
        (attr sb "name" k)
        (attr sb "value" v)
        (.append sb "/>\n"))
      (.append sb "</properties>\n"))
    (doseq [c cases] (append-case sb c))
    (when-not (str/blank? (:out suite)) (append-body sb "system-out" nil (:out suite)))
    (when-not (str/blank? (:err suite)) (append-body sb "system-err" nil (:err suite)))
    (.append sb "</testsuite>\n")
    (.toString sb)))
