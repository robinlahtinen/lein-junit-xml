;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml.impl.collect-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.github.robinlahtinen.lein-junit-xml.impl.collect :as sut])) ; system under test

(set! *warn-on-reflection* true)

(defn- fold
  "Folds events into a finished suite value, using each event's :at as the clock."
  ([events] (fold events {}))
  ([events opts]
   (let [state (reduce (fn [s e] (sut/step s (dissoc e :at) (:at e 0)))
                       (sut/suite "my.app.core-test" 0)
                       events)]
     (sut/finish state (:at (last events) 0) opts))))

(deftest records-a-passing-test-test
  (testing "a test with no failing assertions yields a case with no results"
    (let [suite (fold [{:type :begin-test-var :var #'clojure.core/+ :at 100}
                       {:type :pass :at 105}
                       {:type :end-test-var :var #'clojure.core/+ :at 120}])
          [c]   (:cases suite)]
      (is (= 1 (count (:cases suite))))
      (is (= "+" (:name c)))
      (is (= "my.app.core-test" (:classname c)))
      (is (= 20 (:duration c)))
      (is (empty? (:results c))))))

(deftest records-each-failing-assertion-test
  (testing "one case carries one result per failing assertion"
    (let [suite (fold [{:type :begin-test-var :var #'clojure.core/+ :at 0}
                       {:type :fail           :expected '(= 1 2) :actual '(not (= 1 2))
                        :file "core_test.clj" :line     42       :at     5}
                       {:type :fail :expected '(= 3 4) :actual '(not (= 3 4)) :at 6}
                       {:type :end-test-var :var #'clojure.core/+ :at 10}])
          [c]   (:cases suite)]
      (is (= 2 (count (:results c))))
      (is (every? #(= :fail (:type %)) (:results c))))))

(deftest fail-detail-carries-expected-actual-and-location-test
  (testing "the failure body holds what a developer needs to diagnose it"
    (let [suite  (fold [{:type :begin-test-var :var #'clojure.core/+ :at 0}
                        {:type     :fail           :message "numbers should match"
                         :expected '(= 1 2)        :actual  '(not (= 1 2))
                         :file     "core_test.clj" :line    42                     :at 5}
                        {:type :end-test-var :var #'clojure.core/+ :at 10}])
          result (first (:results (first (:cases suite))))]
      (is (= "numbers should match" (:message result)))
      (is (str/includes? (:detail result) "expected: (= 1 2)"))
      (is (str/includes? (:detail result) "actual: (not (= 1 2))"))
      (is (str/includes? (:detail result) "at core_test.clj:42"))))
  (testing "without an explicit message the expected form becomes the message"
    (let [suite  (fold [{:type :begin-test-var :var #'clojure.core/+ :at 0}
                        {:type :fail :expected '(= 1 2) :actual '(not (= 1 2)) :at 5}
                        {:type :end-test-var :var #'clojure.core/+ :at 10}])
          result (first (:results (first (:cases suite))))]
      (is (= "expected: (= 1 2)" (:message result))))))

(deftest records-errors-with-class-and-stack-trace-test
  (testing "an error result carries the exception class and its stack trace"
    (let [suite  (fold [{:type :begin-test-var :var #'clojure.core/+ :at 0}
                        {:type     :error :message "Uncaught exception, not in assertion."
                         :expected nil    :actual  (RuntimeException. "boom")              :at 5}
                        {:type :end-test-var :var #'clojure.core/+ :at 10}])
          result (first (:results (first (:cases suite))))]
      (is (= :error (:type result)))
      (is (= "java.lang.RuntimeException" (:class result)))
      (is (str/includes? (:detail result) "java.lang.RuntimeException: boom"))))
  (testing "a non-throwable :actual degrades gracefully rather than throwing"
    (let [suite  (fold [{:type :begin-test-var :var #'clojure.core/+ :at 0}
                        {:type :error :message "odd" :actual {:not "a throwable"} :at 5}
                        {:type :end-test-var :var #'clojure.core/+ :at 10}])
          result (first (:results (first (:cases suite))))]
      (is (= :error (:type result)))
      (is (nil? (:class result)))
      (is (str/includes? (:detail result) "a throwable")))))

(deftest synthesises-a-case-for-a-fixture-error-test
  (testing "an error arriving outside any test is still reported"
    ;; Leiningen's fixture-error catcher emits an :error with no preceding
    ;; :begin-test-var and no :var key. Dropping it would emit a green suite
    ;; for a namespace whose :once fixture blew up.
    (let [suite (fold [{:type     :error :message "Uncaught exception in test fixture"
                        :expected nil    :actual  (RuntimeException. "fixture boom")   :at 5}
                       {:type :end-test-ns :at 6}])
          [c]   (:cases suite)]
      (is (= 1 (count (:cases suite))))
      (is (= "initializationError" (:name c)))
      (is (= "my.app.core-test" (:classname c)))
      (is (= :error (:type (first (:results c))))))))

(deftest ignores-events-that-carry-no-report-information-test
  (testing "pass, summary and unknown event types leave the accumulator untouched"
    (let [state (sut/suite "my.ns" 0)]
      (is (= state (sut/step state {:type :pass} 1)))
      (is (= state (sut/step state {:type :summary :test 1 :pass 1 :fail 0 :error 0} 1)))
      (is (= state (sut/step state {:type :begin-test-ns :ns 'my.ns} 1)))
      (is (= state (sut/step state {:type :some-third-party-event} 1))))))

(deftest tolerates-unbalanced-var-events-test
  (testing "an end without a begin is a no-op rather than an error"
    (let [suite (fold [{:type :end-test-var :var #'clojure.core/+ :at 5}])]
      (is (empty? (:cases suite)))))
  (testing "a begin without an end still closes the case when the suite finishes"
    (let [suite (fold [{:type :begin-test-var :var #'clojure.core/+ :at 0}
                       {:type :fail :expected '(= 1 2) :actual false :at 5}])]
      (is (= 1 (count (:cases suite)))))))

(deftest finish-adds-skipped-cases-and-suite-fields-test
  (testing "selector-excluded tests appear as skipped cases"
    (let [suite   (fold [{:type :begin-test-var :var #'clojure.core/+ :at 0}
                         {:type :end-test-var :var #'clojure.core/+ :at 10}]
                        {:hostname   "build-07"
                         :properties {"java.version" "26"}
                         :out        "stdout"
                         :err        "stderr"
                         :skipped    ['other-test 'third-test]})
          skipped (filter #(= :skipped (:type (first (:results %)))) (:cases suite))]
      (is (= 3 (count (:cases suite))))
      (is (= ["other-test" "third-test"] (mapv :name skipped)))
      (is (every? #(zero? (:duration %)) skipped))
      (is (= "build-07" (:hostname suite)))
      (is (= {"java.version" "26"} (:properties suite)))
      (is (= "stdout" (:out suite)))
      (is (= "stderr" (:err suite)))))
  (testing "absent optional fields are omitted rather than set to nil"
    (let [suite (fold [{:type :begin-test-var :var #'clojure.core/+ :at 0}
                       {:type :end-test-var :var #'clojure.core/+ :at 10}])]
      (is (not (contains? suite :hostname)))
      (is (not (contains? suite :out)))
      (is (not (contains? suite :properties))))))

(deftest suite-timing-test
  (testing "the suite records its start instant and wall-clock duration"
    (let [state (sut/suite "my.ns" 1000)
          suite (sut/finish state 1750 {})]
      (is (= 1000 (:timestamp suite)))
      (is (= 750 (:duration suite))))))
