;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml-test
  "Exercises the instrumentation against a real clojure.test run.

  The namespace under test is built at runtime rather than committed as a file,
  so its deliberate failures never reach this project's own test summary and
  `lein test` cannot pick it up.

  install! is scoped with a :once fixture. Under :eval-in-leiningen the project
  JVM is the JVM running this suite, so leaving clojure.test instrumented would
  wrap every namespace that runs afterwards."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.github.robinlahtinen.lein-junit-xml :as sut])
  (:import
   (clojure.lang MultiFn)
   (java.io ByteArrayInputStream File StringWriter)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (javax.xml.parsers DocumentBuilderFactory)
   (org.w3c.dom Document Element)))

(set! *warn-on-reflection* true)

(def ^:private ^String output-dir
  (str (Files/createTempDirectory "lein-junit-xml-test" (make-array FileAttribute 0))))

(def ^:private subject-ns 'com.github.robinlahtinen.lein-junit-xml.synthetic-subject-test)

(defn- build-subject-ns!
  "Interns a namespace holding one test of each outcome the plugin must report."
  []
  (let [n (create-ns subject-ns)]
    (intern n (with-meta 'passes-test {:test #(is (= 1 1))}))
    (intern n (with-meta 'fails-test {:test #(do (is (= 1 2)) (is (= 3 4)))}))
    (intern n (with-meta 'errors-test {:test #(throw (RuntimeException. "boom"))}))
    (intern n (with-meta 'prints-test {:test #(do (println "captured stdout") (is true))}))
    ;; Leiningen's selector machinery parks suppressed tests under this key.
    (intern n (with-meta 'excluded-test {:leiningen/skipped-test #(is true)}))
    n))

(defn- run-subject!
  "Runs the synthetic namespace, muffling its reporting output.

  Re-installs first so each test gets fresh per-run state. A namespace is only
  written once per run, so without this a later test would silently read the
  file an earlier one produced."
  []
  (sut/install! {:output-dir output-dir})
  (binding [*out*                   (StringWriter.)
            clojure.test/*test-out* (StringWriter.)]
    (clojure.test/test-ns subject-ns)))

(defn- with-instrumentation
  [test-fn]
  (build-subject-ns!)
  (sut/install! {:output-dir output-dir})
  (try (test-fn)
       (finally (sut/uninstall!))))

(use-fixtures :once with-instrumentation)

(defn- report-file ^File [] (File. output-dir (str "TEST-" subject-ns ".xml")))

(defn- parse ^Document [^File f]
  (-> (DocumentBuilderFactory/newInstance)
      (.newDocumentBuilder)
      (.parse (ByteArrayInputStream. (.getBytes (slurp f :encoding "UTF-8") "UTF-8")))))

(defn- tags [^Element e ^String tag]
  (let [nodes (.getElementsByTagName e tag)]
    (map #(.item nodes %) (range (.getLength nodes)))))

(defn- attr ^String [^Element e ^String a] (.getAttribute e a))

(defn- case-named ^Element [^Element suite ^String n]
  (first (filter #(= n (attr % "name")) (tags suite "testcase"))))

(deftest writes-one-file-per-namespace-test
  (run-subject!)
  (testing "a report file named after the namespace appears in the output directory"
    (is (.exists (report-file)))))

(deftest reports-every-outcome-test
  (run-subject!)
  (let [suite (.getDocumentElement (parse (report-file)))]
    (testing "the suite aggregates count cases rather than assertions"
      ;; fails-test has two failing assertions but is one failing case.
      (is (= "1" (attr suite "failures")))
      (is (= "1" (attr suite "errors")))
      (is (= "1" (attr suite "skipped"))))
    (testing "a passing test yields a case with no result children"
      (is (empty? (tags (case-named suite "passes-test") "failure"))))
    (testing "a failing test yields one failure element per assertion"
      (is (= 2 (count (tags (case-named suite "fails-test") "failure")))))
    (testing "an uncaught exception is reported as an error carrying its class"
      (let [err (first (tags (case-named suite "errors-test") "error"))]
        (is (= "java.lang.RuntimeException" (attr err "type")))
        (is (str/includes? (.getTextContent ^Element err) "boom"))))
    (testing "a selector-excluded test is reported as skipped rather than vanishing"
      (is (seq (tags (case-named suite "excluded-test") "skipped"))))
    (testing "every case is attributed to the namespace under test"
      (is (every? #(= (str subject-ns) (attr % "classname")) (tags suite "testcase"))))))

(deftest captures-suite-level-output-test
  (run-subject!)
  (let [suite (.getDocumentElement (parse (report-file)))
        out   (first (tags suite "system-out"))]
    (testing "what the tests printed is captured at suite level"
      (is (some? out))
      (is (str/includes? (.getTextContent ^Element out) "captured stdout")))))

(deftest suite-carries-timing-and-environment-test
  (run-subject!)
  (let [suite (.getDocumentElement (parse (report-file)))]
    (testing "time is a locale-independent decimal"
      (is (re-matches #"\d+\.\d{3}" (attr suite "time"))))
    (testing "timestamp is an explicit UTC instant, not a zone-less local time"
      (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z" (attr suite "timestamp"))))
    (testing "a curated property set is emitted, not the whole environment"
      (is (= #{"clojure.version" "java.version" "os.name"}
             (set (map #(attr % "name") (tags suite "property"))))))))

(defn- multifn
  "Reaches the report multimethod the same way the runtime does."
  ^MultiFn []
  (loop [f clojure.test/report depth 0]
    (cond
      (instance? MultiFn f) f
      (>= depth 8) nil
      :else (when-let [o (some (fn [[k v]] (when (= "original" (name k)) v)) (meta f))]
              (recur o (inc depth))))))

(deftest report-methods-observe-every-event-type-test
  ;; The load-bearing assumption of the whole design. clojure.test is
  ;; AOT-compiled with direct linking, so instrumenting an internal callee is
  ;; invisible to its in-namespace callers; dispatch through the report
  ;; multimethod is not. If anyone ever refactors the instrumentation onto
  ;; do-report, this is the test that catches it.
  (let [seen  (atom #{})
        types [:begin-test-ns :begin-test-var :end-test-var :end-test-ns :pass :fail :error]
        multi (multifn)
        saved (into {} (map (juxt identity #(get-method multi %))) types)]
    (is (some? multi) "the report multimethod must be reachable")
    (doseq [t types]
      (.addMethod multi t (fn [m] (swap! seen conj t) (when-let [o (saved t)] (o m)))))
    (try
      (run-subject!)
      (finally
        (doseq [[t m] saved] (if m (.addMethod multi t m) (remove-method multi t)))))
    (testing "every event the runtime relies on reaches an added report method"
      (is (= #{:begin-test-var :end-test-var :end-test-ns :pass :fail :error}
             (disj @seen :begin-test-ns))))
    (testing ":begin-test-ns is the one event Leiningen withholds"
      ;; Its report hook prints "lein test <ns>" for that type and does not
      ;; delegate, which is why the runtime takes suite start from the test-ns
      ;; wrapper instead of from an event.
      (is (not (contains? @seen :begin-test-ns))))))

(deftest wrapping-do-report-would-not-work-test
  ;; Pins the reason for the design above rather than leaving it as a comment.
  ;; If a future Clojure stopped direct-linking clojure.test this would start
  ;; failing, which is the signal to revisit the choice deliberately.
  (let [seen     (atom #{})
        original clojure.test/do-report]
    (alter-var-root #'clojure.test/do-report
                    (fn [f] (fn [m] (swap! seen conj (:type m)) (f m))))
    (try
      (run-subject!)
      (finally (alter-var-root #'clojure.test/do-report (constantly original))))
    (testing "a do-report wrapper sees only events raised from user code"
      (is (contains? @seen :fail) "is/are expand into user code, so these are visible")
      (is (not-any? @seen [:begin-test-var :end-test-var :end-test-ns])
          "direct-linked in-namespace callers bypass the var entirely"))))

(deftest install-is-idempotent-test
  (testing "a second install! is a no-op, so wrappers cannot stack"
    ;; Under :eval-in :leiningen the JVM persists across tasks; stacking would
    ;; record every event twice and double all the counts.
    (is (false? (sut/install! {:output-dir output-dir})))))

(deftest instrumentation-does-not-disturb-the-run-test
  (testing "test-ns still returns clojure.test's own counters"
    (let [counters (run-subject!)]
      (is (= 4 (:test counters)) "four vars carry :test metadata")
      (is (= 2 (:fail counters)))
      (is (= 1 (:error counters)))))
  (testing "the report multimethod is left intact and still extensible"
    ;; We add methods rather than alter-var-root the var, so a user's own
    ;; defmethod keeps working. Note the var itself holds robert.hooke's
    ;; dispatcher here, because Leiningen hooked it before this suite started;
    ;; the multimethod is reachable behind it, which is what we assert.
    (let [multi (loop [f clojure.test/report depth 0]
                  (cond
                    (instance? MultiFn f) f
                    (>= depth 8) nil
                    :else (when-let [o (some (fn [[k v]] (when (= "original" (name k)) v))
                                             (meta f))]
                            (recur o (inc depth)))))
          seen  (atom false)]
      (is (some? multi) "the multimethod must still be reachable")
      (.addMethod ^MultiFn multi ::custom (fn [_] (reset! seen true)))
      (try
        (clojure.test/report {:type ::custom})
        (is @seen "a newly added method must still dispatch")
        (finally (remove-method multi ::custom))))))

(deftest recovers-from-a-throwing-fixture-test
  (testing "a namespace whose :once fixture throws still produces a report"
    (let [broken 'com.github.robinlahtinen.lein-junit-xml.synthetic-broken-test
          n      (create-ns broken)]
      (intern n (with-meta 'never-runs-test {:test #(is true)}))
      (alter-meta! n assoc :clojure.test/once-fixtures
                   [(fn [_] (throw (RuntimeException. "fixture boom")))])
      ;; The exception does not escape: Leiningen's own fixture-error catcher is
      ;; hooked onto test-ns outside our wrapper and converts it into a
      ;; synthesised :error. Our wrapper records the throwable first, so the
      ;; report is complete either way.
      (binding [*out*                   (StringWriter.)
                clojure.test/*test-out* (StringWriter.)]
        (clojure.test/test-ns broken))
      (let [f (File. output-dir (str "TEST-" broken ".xml"))]
        (is (.exists f) "the suite must still be written when the fixture blows up")
        (let [suite (.getDocumentElement (parse f))]
          (is (= "1" (attr suite "errors")))
          (is (str/includes? (.getTextContent suite) "fixture boom")))))))

(deftest uninstall-restores-clojure-test-test
  (testing "uninstall! puts the original test-ns and report methods back"
    (let [instrumented clojure.test/test-ns]
      (is (true? (sut/uninstall!)))
      (is (not (identical? instrumented clojure.test/test-ns)))
      (is (false? (sut/uninstall!)) "a second uninstall! is a no-op")
      ;; Leave the fixture's own finally block with nothing to undo, and restore
      ;; instrumentation so fixture teardown stays symmetric.
      (sut/install! {:output-dir output-dir}))))

(deftest cleanup-test
  (testing "report files were written where configured"
    (is (seq (filter #(str/ends-with? (.getName ^File %) ".xml")
                     (file-seq (io/file output-dir)))))))

(deftest survives-a-user-replacing-a-report-method-test
  ;; A plain (defmethod clojure.test/report :fail …) replaces rather than wraps,
  ;; and user test namespaces load *after* :injections run. Without re-asserting
  ;; our methods we would silently record nothing for that event type.
  (let [multi    (multifn)
        saved    (get-method multi :fail)
        user-saw (atom 0)]
    (.addMethod multi :fail (fn [_] (swap! user-saw inc)))
    (try
      (run-subject!)
      (finally (.addMethod multi :fail saved)))
    (testing "the user's own method still runs"
      (is (pos? @user-saw)))
    (testing "and the failure is still recorded in the report"
      (let [suite (.getDocumentElement (parse (report-file)))]
        (is (= "1" (attr suite "failures")))
        (is (= 2 (count (tags (case-named suite "fails-test") "failure"))))))))

(deftest survives-a-throwing-report-method-test
  (let [multi (multifn)
        saved (get-method multi :fail)]
    (.addMethod multi :fail (fn [_] (throw (RuntimeException. "reporter blew up"))))
    (try
      (try (run-subject!) (catch Throwable _ nil))
      (finally (.addMethod multi :fail saved)))
    (testing "the report is still written, and is still well formed"
      (is (.exists (report-file)))
      (is (some? (parse (report-file)))))))

(deftest survives-a-reflection-warning-during-a-test-test
  (let [n (create-ns 'com.github.robinlahtinen.lein-junit-xml.synthetic-reflective-test)]
    (intern n (with-meta 'reflects-test
                {:test #(binding [*warn-on-reflection* true]
                          (eval '(fn [x] (.length x)))
                          (is true))}))
    (binding [*out*                   (StringWriter.)
              clojure.test/*test-out* (StringWriter.)]
      (clojure.test/test-ns 'com.github.robinlahtinen.lein-junit-xml.synthetic-reflective-test))
    (let [f (File. output-dir "TEST-com.github.robinlahtinen.lein-junit-xml.synthetic-reflective-test.xml")]
      (testing "the run completes and the report is valid"
        (is (.exists f))
        (is (some? (parse f))))
      (testing "the warning lands in captured stderr rather than breaking capture"
        (is (str/includes? (.getTextContent (.getDocumentElement (parse f)))
                           "Reflection warning"))))))
