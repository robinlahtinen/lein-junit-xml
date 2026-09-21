;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml
  "Instruments clojure.test in the project JVM and writes the report files.

  This is the only namespace with side effects, and the only one the injected
  form references by name. Everything it decides is delegated to the pure
  namespaces beneath it.

  Commands:
    install!   - instrument clojure.test; idempotent.
    uninstall! - restore clojure.test to how it was found.

  Two touchpoints, of two different kinds, and the difference is forced by
  direct linking rather than taste:

    clojure.test/report     added defmethods, delegating to the ones they replace.
    #'clojure.test/test-ns  alter-var-root, for suite output capture and timing.

  clojure.test is AOT-compiled with direct linking, so wrapping an internal
  callee such as do-report is invisible to its in-namespace callers: only :pass
  and :fail would ever be seen, because those originate in user code. Dispatch
  through the report multimethod always happens, so adding methods sees every
  event. Adding methods also leaves the multimethod intact, so a user's own
  defmethod keeps working, and it composes with the robert.hooke wrapper
  Leiningen installs on the same var."
  (:require
   [clojure.java.io :as io]
   [clojure.test]
   [com.github.robinlahtinen.lein-junit-xml.impl.collect :as collect]
   [com.github.robinlahtinen.lein-junit-xml.impl.xml :as xml])
  (:import
   (clojure.lang MultiFn Namespace)
   (java.io File StringWriter)
   (java.net InetAddress)))

(set! *warn-on-reflection* true)

(def ^:private unknown-host
  "Fallback host name, matching what JUnit writes when lookup fails."
  "<unknown host>")

(def ^:private reported-types
  "The report event types we observe. :begin-test-ns is deliberately absent:
  Leiningen's own report hook diverts exactly that one event and never delegates,
  so it would never reach us. The test-ns wrapper supplies the suite start."
  [:begin-test-var :end-test-var :fail :error :end-test-ns])

(def ^:private state
  "{:opts {} :suites {ns-sym {:acc .. :out .. :err ..}} :written #{ns-sym}}.

  Suite entries are dissoc'd as soon as their file is written, so peak memory is
  one namespace rather than the whole run; only the small written set persists.
  Deliberately a plain def; the static-analysis job forbids the once-only
  definition form in src/."
  (atom {}))

(def ^:private originals
  "The clojure.test vars and methods as we found them, for uninstall!."
  (atom nil))

(defn- now ^long [] (System/currentTimeMillis))

(defn- warn!
  "Reports a plugin-internal problem without disturbing the test run.

  Writes to System/err directly: *err* is rebound to a capture buffer while a
  namespace is running, and a plugin warning does not belong in the report."
  [^String message ^Throwable t]
  (.println System/err (str "com.github.robinlahtinen/lein-junit-xml: " message
                            (when t (str " (" (.getName (class t)) ": " (.getMessage t) ")")))))

(defmacro ^:private guard
  "Evaluates body, swallowing and reporting any Throwable.

  This plugin must never change the outcome of a test run. Reporting is
  unprotected inside clojure.test/test-var, so an exception escaping here would
  surface as a bogus \"Uncaught exception in test fixture\" against the user's
  own namespace and skip the rest of it."
  [message & body]
  `(try ~@body (catch Throwable t# (warn! ~message t#) nil)))

(defn- ns-key
  "Normalises a Namespace, symbol or string to a plain namespace symbol.

  Both ends of the accumulator key must agree: test-ns receives whatever
  run-tests passed it, while an :end-test-ns event carries a Namespace object.
  Leiningen's synthesised fixture error supplies a bare symbol instead."
  [x]
  (cond
    (instance? Namespace x) (ns-name x)
    (symbol? x) x
    (string? x) (symbol x)
    :else nil))

(defn- event-ns
  "Returns the namespace symbol an event belongs to, or nil.

  *testing-vars* is thread-local, so a parallel runner attributes correctly."
  [event]
  (or (ns-key (:ns event))
      (ns-key (:ns (meta (:var event))))
      (ns-key (:ns (meta (first clojure.test/*testing-vars*))))))

(defn- host-name
  "Returns this host's name, or JUnit's literal fallback when lookup fails."
  ^String []
  (try (.getHostName (InetAddress/getLocalHost))
       (catch Throwable _ unknown-host)))

(defn- properties
  "A small, curated property set.

  JUnit dumps the entire System.getProperties(), which leaks the environment into
  every file; Jenkins discards them altogether unless the job opts in."
  []
  {"clojure.version" (clojure-version)
   "java.version"    (System/getProperty "java.version")
   "os.name"         (System/getProperty "os.name")})

(defn- skipped-tests
  "Names of tests Leiningen's selector machinery suppressed in this namespace.

  form-for-suppressing-unselected-tests moves :test metadata to
  :leiningen/skipped-test for the duration of the run, so selector-excluded tests
  are reported as skipped instead of silently vanishing from the report."
  [ns-sym]
  (when-let [n (find-ns ns-sym)]
    (->> (ns-interns n)
         (keep (fn [[sym v]] (when (:leiningen/skipped-test (meta v)) sym)))
         (sort)
         (vec))))

(defn- write-suite!
  "Renders suite and writes it to a single file under output-dir."
  [suite ^String output-dir]
  (let [file (File. output-dir ^String (xml/file-name (:name suite)))]
    (io/make-parents file)
    ;; Rendered whole, then written once: a partially written document is the
    ;; failure mode that leaves an unclosed root element Jenkins cannot parse.
    (spit file (xml/render suite) :encoding "UTF-8")))

(defn- flush-suite!
  "Writes the report for ns-sym, unless it has already been written.

  Idempotent by design: the primary flush happens on :end-test-ns, and the
  test-ns wrapper's finally block is a fallback for the paths where that event
  never arrives."
  [ns-sym]
  (when ns-sym
    ;; Claim the namespace atomically, so two threads cannot both write it.
    (let [[old new] (swap-vals! state
                                (fn [s]
                                  (if (and (get-in s [:suites ns-sym :acc])
                                           (not (contains? (:written s) ns-sym)))
                                    (-> s
                                        (update :suites dissoc ns-sym)
                                        (update :written (fnil conj #{}) ns-sym))
                                    s)))]
      (when-not (identical? old new)
        (let [{:keys [acc ^StringWriter out ^StringWriter err]} (get-in old [:suites ns-sym])
              suite                                             (collect/finish acc (now)
                                                                                {:hostname   (host-name)
                                                                                 :properties (properties)
                                                                                 :out        (str out)
                                                                                 :err        (str err)
                                                                                 :skipped    (skipped-tests ns-sym)})]
          (write-suite! suite (get-in old [:opts :output-dir])))))))

(defn- record!
  "Folds an event into the accumulator for its namespace, if one is open."
  [event]
  (when-let [k (event-ns event)]
    (let [at (now)]
      ;; Guard inside the swap: update-in would otherwise conjure an entry for a
      ;; namespace that has no open accumulator, e.g. an event arriving after the
      ;; suite was already flushed.
      (swap! state (fn [s]
                     (if (get-in s [:suites k :acc])
                       (update-in s [:suites k :acc] collect/step event at)
                       s))))))

(defn- report-multifn
  "Returns the report multimethod, seeing through any wrapper on the var.

  Leiningen installs a robert.hooke hook on #'clojure.test/report, which replaces
  the var's root with a plain dispatcher function. Whether that has happened yet
  depends on when we run: at :injections time it has not, but under
  :eval-in :leiningen, or when this plugin's own suite is already running, it
  has. Hooke keeps the value it wrapped in the wrapper's metadata, so we walk
  back to the multimethod rather than depending on which order won.

  Matching on the key's name keeps this independent of the namespace Leiningen
  renames its injected copy of hooke to."
  ^MultiFn []
  (loop [f     clojure.test/report
         depth 0]
    (cond
      (instance? MultiFn f) f
      (>= depth 8) nil
      :else (when-let [original (some (fn [[k v]]
                                        (when (= "original" (name k)) v))
                                      (meta f))]
              (recur original (inc depth))))))

(defn- delegating-method
  "Returns a report method that records the event and then calls through to orig.

  Tagged so we can recognise our own work later and never wrap it twice: a chain
  of our wrappers would record every event once per link."
  [orig]
  (with-meta
    (fn [m]
      (guard "could not record an event" (record! m))
      (when (= :end-test-ns (:type m))
        (guard "could not write a report" (flush-suite! (event-ns m))))
      (when orig (orig m)))
    {::ours true}))

(defn- ours?
  "Is m a report method this namespace installed?"
  [m]
  (boolean (::ours (meta m))))

(defn- install-report-method!
  "Adds a delegating method for event type t, remembering what it replaced."
  [t]
  (when-let [multi (report-multifn)]
    (let [orig (get-method multi t)]
      (swap! originals assoc-in [:methods t] orig)
      (.addMethod multi t (delegating-method orig)))))

(defn- ensure-report-methods!
  "Re-asserts our methods over any that replaced them, and returns nil.

  A plain (defmethod clojure.test/report :fail …) replaces rather than wraps, and
  user test namespaces are loaded *after* :injections run - so a project with a
  custom reporter would otherwise silently lose our recording for that event
  type. Re-wrapping here, from inside the test-ns wrapper, happens after every
  namespace has been required and keeps the user's method in the delegate chain.

  A method already tagged as ours is left alone, so repeated calls cannot build a
  chain that records the same event more than once."
  []
  (when-let [multi (report-multifn)]
    (doseq [t reported-types]
      (let [current (get-method multi t)]
        (when-not (ours? current)
          (.addMethod multi t (delegating-method current)))))))

(defn- wrap-test-ns
  "Wraps clojure.test/test-ns to capture suite output, timing and failures."
  [orig]
  (fn [ns]
    (let [k   (ns-key ns)
          out (StringWriter.)
          err (StringWriter.)]
      (guard "could not re-assert report methods" (ensure-report-methods!))
      (guard "could not open suite"
             (swap! state assoc-in [:suites k] {:acc (collect/suite k (now)) :out out :err err}))
      (try
        ;; *test-out* was bound to the real console writer outside test-ns, so
        ;; clojure.test's own progress and failure reporting still reaches the
        ;; terminal; only what the tests themselves print lands in the report.
        (binding [*out* out *err* err]
          (try
            (orig ns)
            (catch Throwable t
              ;; Record the failure ourselves before rethrowing. Leiningen's
              ;; fixture-error catcher sits outside us and will synthesise the
              ;; same error afterward, but the fallback flush below runs first.
              (guard "could not record a fixture error"
                     (record! {:type     :error
                               :message  "Uncaught exception in test fixture"
                               :expected nil
                               :actual   t}))
              (throw t))))
        (finally
          (guard "could not write a report" (flush-suite! k)))))))

(defn install!
  "Instruments clojure.test so that test results are written as JUnit XML.

  Args:
    opts - resolved options from com.github.robinlahtinen.lein-junit-xml.impl.config/options.

  Options:
    :output-dir - absolute directory the report files are written to.

  Instrumenting is idempotent: calling this again leaves the existing wrappers
  alone rather than stacking a second set, which under :eval-in :leiningen (where
  the JVM persists across tasks) would record every event twice.

  Per-run state is reset on every call, so a second invocation in the same JVM
  reports its own results instead of being suppressed as already written.

  Returns true when it instrumented, false when it was already instrumented."
  [opts]
  (let [fresh? (not (:installed? @state))]
    (swap! state assoc :opts opts :installed? true :suites {} :written #{})
    (when fresh?
      (reset! originals {:test-ns clojure.test/test-ns :methods {}})
      (alter-var-root #'clojure.test/test-ns wrap-test-ns)
      (if (report-multifn)
        (run! install-report-method! reported-types)
        (warn! "could not reach clojure.test/report; no reports will be written" nil)))
    fresh?))

(defn uninstall!
  "Restores clojure.test to the state install! found it in.

  Needed wherever the JVM outlives the run, most importantly when this plugin's
  own test suite exercises the instrumentation under :eval-in-leiningen.

  Returns true when it uninstalled, false when it was not installed."
  []
  (if-not (:installed? @state)
    false
    (let [{:keys [test-ns methods]} @originals]
      (alter-var-root #'clojure.test/test-ns (constantly test-ns))
      (when-let [multi (report-multifn)]
        (doseq [[t m] methods]
          ;; Only restore where our method is still the one installed; a user
          ;; who replaced it since keeps theirs.
          (when (ours? (get-method multi t))
            (if m
              (.addMethod multi t m)
              (remove-method multi t)))))
      (reset! originals nil)
      (reset! state {})
      true)))
