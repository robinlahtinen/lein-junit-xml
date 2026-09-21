;; Deliberately contains failing and erroring tests: this namespace exists to be
;; reported on, not to pass.
(ns sample.core-test
  (:require [clojure.test :refer [deftest is]]))

(deftest ^:passing passes-test
  (is (= 1 1)))

(deftest fails-test
  (is (= 1 2)))

(deftest errors-test
  (throw (RuntimeException. "sample boom")))

(deftest prints-test
  (println "sample stdout marker")
  (is true))
