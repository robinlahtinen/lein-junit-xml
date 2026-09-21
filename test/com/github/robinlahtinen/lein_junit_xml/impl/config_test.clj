;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml.impl.config-test
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [com.github.robinlahtinen.lein-junit-xml.impl.config :as sut])
  (:import
   (clojure.lang ExceptionInfo)
   (java.io File)))

(set! *warn-on-reflection* true)

(def ^:private ^String root
  (.getPath (File. (System/getProperty "java.io.tmpdir") "lein-junit-xml-root")))

(defn- error-of
  "Returns the :com.github.robinlahtinen.lein-junit-xml/error category thrown by calling f, or nil."
  [f]
  (try (f) nil (catch ExceptionInfo e (:com.github.robinlahtinen.lein-junit-xml/error (ex-data e)))))

(deftest defaults-test
  (testing "no configuration yields the default output directory under the root"
    (let [opts (sut/options nil root)]
      (is (= (.getPath (File. root "target/junit-xml")) (:output-dir opts)))))
  (testing "an empty map behaves the same as no configuration"
    (is (= (sut/options nil root) (sut/options {} root)))))

(deftest output-dir-resolution-test
  (testing "a relative directory resolves against the project root"
    (is (= (.getPath (File. root "build/reports"))
           (:output-dir (sut/options {:output-dir "build/reports"} root)))))
  (testing "an absolute directory is left untouched"
    (let [abs (.getPath (File. (System/getProperty "java.io.tmpdir") "abs-reports"))]
      (is (= abs (:output-dir (sut/options {:output-dir abs} root))))))
  (testing "a missing root leaves the path as given"
    (is (= (.getPath (File. "build/reports"))
           (:output-dir (sut/options {:output-dir "build/reports"} nil))))))

(deftest rejects-bad-configuration-test
  (testing "a non-map :junit-xml value is an invalid type"
    (is (= :com.github.robinlahtinen.lein-junit-xml/invalid-type (error-of #(sut/options "nope" root)))))
  (testing "a non-string :output-dir is an invalid type"
    (is (= :com.github.robinlahtinen.lein-junit-xml/invalid-type (error-of #(sut/options {:output-dir 42} root)))))
  (testing "a blank :output-dir is an invalid value"
    (is (= :com.github.robinlahtinen.lein-junit-xml/invalid-value (error-of #(sut/options {:output-dir "  "} root)))))
  (testing "an unrecognised key is reported rather than silently ignored"
    ;; A typo in project.clj that is quietly dropped is far more expensive to
    ;; diagnose than an immediate, named error.
    (is (= :com.github.robinlahtinen.lein-junit-xml/unsupported (error-of #(sut/options {:output-dirr "x"} root))))))

(deftest error-data-follows-the-house-contract-test
  (testing "thrown data carries the category and context, and no :type key"
    (let [data (try (sut/options {:output-dir 42} root)
                    (catch ExceptionInfo e (ex-data e)))]
      (is (= :com.github.robinlahtinen.lein-junit-xml/invalid-type
             (:com.github.robinlahtinen.lein-junit-xml/error data)))
      (is (= :com.github.robinlahtinen.lein-junit-xml/junit-xml
             (:com.github.robinlahtinen.lein-junit-xml/context data)))
      (is (not (contains? data :type)))
      (is (= ":output-dir" (:name data))))))

(deftest options-survive-being-printed-and-read-test
  (testing "the resolved options are plain EDN"
    ;; The options map is embedded in the form Leiningen evaluates in the project
    ;; JVM, printed with *print-dup* and *print-meta* false. Anything that does
    ;; not round trip through pr-str/read produces an unreadable init file.
    (let [opts (sut/options {:output-dir "build/reports"} root)]
      (is (= opts (edn/read-string (pr-str opts)))))))
