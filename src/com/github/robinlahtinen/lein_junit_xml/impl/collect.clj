;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml.impl.collect
  "Folds clojure.test report events into a suite value.

  This namespace is an implementation detail; do not use from application code.

  Every function here is pure: the caller supplies the clock, so a whole run can
  be replayed from synthetic event maps in a plain REPL with no Leiningen and no
  clojure.test runner present.

  Commands:
    suite  - open a new suite accumulator for a namespace.
    step   - fold one clojure.test event into the accumulator.
    finish - close the accumulator into a suite value ready for rendering.

  The accumulator is the suite value plus a :current key holding the open case."
  {:skip-wiki true
   :no-doc    true}
  (:require
   [clojure.string :as str])
  (:import
   (java.io PrintWriter StringWriter)))

(set! *warn-on-reflection* true)

(def ^:private synthetic-case-name
  "Name given to a case synthesised for a failure that arrived outside any test.

  Leiningen's fixture-error catcher reports an :error with no preceding
  :begin-test-var, so there is no open case to attribute it to. Dropping it would
  emit a green suite for a namespace whose fixture blew up."
  "initializationError")

(defn- case-name
  "Returns a printable test name for v, which may be a Var or a bare symbol.

  Leiningen's synthesised fixture error binds *testing-vars* to a symbol carrying
  metadata rather than a Var, so this must not assume clojure.lang.Var."
  [v]
  (cond
    (var? v) (name (:name (meta v)))
    (symbol? v) (name v)
    (nil? v) synthetic-case-name
    :else (str v)))

(defn- stack-trace
  "Returns the printed stack trace of throwable, or its pr-str when not throwable."
  ^String [t]
  (if (instance? Throwable t)
    (let [sw (StringWriter.)]
      (.printStackTrace ^Throwable t (PrintWriter. sw true))
      (.toString sw))
    (pr-str t)))

(defn- location
  "Returns a \"file:line\" suffix for an event, or nil when unknown."
  [event]
  (when-let [file (:file event)]
    (str file ":" (:line event))))

(defn- fail-result
  "Builds a :fail result from a clojure.test :fail event."
  [event]
  (let [detail (->> [(:message event)
                     (str "expected: " (pr-str (:expected event)))
                     (str "  actual: " (pr-str (:actual event)))
                     (some->> (location event) (str "at "))]
                    (remove nil?)
                    (str/join \newline))]
    {:type    :fail
     :message (or (:message event) (str "expected: " (pr-str (:expected event))))
     :detail  detail}))

(defn- error-result
  "Builds an :error result from a clojure.test :error event."
  [event]
  (let [actual     (:actual event)
        throwable? (instance? Throwable actual)]
    {:type    :error
     :message (or (:message event)
                  (when throwable? (.getMessage ^Throwable actual))
                  "error")
     :class   (when throwable? (.getName (class actual)))
     :detail  (->> [(:message event) (some->> (location event) (str "at ")) (stack-trace actual)]
                   (remove nil?)
                   (str/join \newline))}))

(defn suite
  "Returns a new accumulator for the namespace named suite-name.

  Args:
    suite-name - the namespace name, as a string.
    now        - epoch milliseconds at which the suite started.

  Returns an accumulator map."
  [suite-name now]
  {:name      (str suite-name)
   :timestamp (long now)
   :started   (long now)
   :cases     []
   :current   nil})

(defn- close-current
  "Moves the open case, if any, into :cases with its duration filled in."
  [state now]
  (if-let [current (:current state)]
    (-> state
        (update :cases conj (-> current
                                (assoc :duration (- (long now) (long (:started current))))
                                (dissoc :started)))
        (assoc :current nil))
    state))

(defn- open-case
  "Opens a case named case-name, closing any case still open."
  [state case-name now]
  (-> (close-current state now)
      (assoc :current {:name      case-name
                       :classname (:name state)
                       :started   (long now)
                       :results   []})))

(defn- add-result
  "Attaches result to the open case, synthesising one when nothing is open.

  A failure with no open case is how Leiningen reports a throwing :once fixture."
  [state result now]
  (let [state (if (:current state) state (open-case state synthetic-case-name now))]
    (update-in state [:current :results] conj result)))

(defn step
  "Folds one clojure.test report event into state and returns the new state.

  Args:
    state - an accumulator from suite.
    event - a clojure.test report map, with at least a :type key.
    now   - epoch milliseconds at which the event was observed.

  Unrecognised event types, including :pass and :summary, are returned unchanged;
  passing assertions carry no information the report needs."
  [state event now]
  (case (:type event)
    :begin-test-var (open-case state (case-name (:var event)) now)
    :end-test-var (close-current state now)
    :fail (add-result state (fail-result event) now)
    :error (add-result state (error-result event) now)
    state))

(defn finish
  "Closes state into a suite value ready for the emitter.
  See com.github.robinlahtinen.lein-junit-xml.impl.xml/render.

  Args:
    state - an accumulator from suite.
    now   - epoch milliseconds at which the suite ended.
    opts  - extra suite fields to merge.

  Options:
    :hostname   - the reporting host name.
    :properties - a map of property name to value.
    :out        - captured standard output for the whole namespace.
    :err        - captured standard error for the whole namespace.
    :skipped    - a seq of names of tests that were never run.

  Returns a suite value; see doc/reference.md for its shape."
  [state now {:keys [hostname properties out err skipped]}]
  (let [{:keys [name timestamp started cases]} (close-current state now)
        skipped-cases                          (for [s skipped]
                                                 {:name      (str s)
                                                  :classname name
                                                  :duration  0
                                                  :results   [{:type    :skipped
                                                               :message "excluded by test selector"}]})]
    (cond-> {:name      name
             :timestamp timestamp
             :duration  (- (long now) (long started))
             :cases     (into cases skipped-cases)}
      hostname (assoc :hostname hostname)
      (seq properties) (assoc :properties properties)
      (seq out) (assoc :out out)
      (seq err) (assoc :err err))))
