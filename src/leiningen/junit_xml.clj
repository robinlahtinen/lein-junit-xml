;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns leiningen.junit-xml
  "The lein junit-xml task.

  This namespace runs in Leiningen's own JVM. It resolves configuration, merges
  a profile that carries the runtime half into the project JVM, and then hands
  off to leiningen.test/test. It deliberately holds no reporting logic: selectors,
  :test-paths, .lein-failures, exit codes and the abort contract are all
  inherited by delegating rather than by rebuilding the test form.

  Commands:
    junit-xml - run the project's tests and write JUnit XML reports."
  (:require
   [com.github.robinlahtinen.lein-junit-xml.impl.config :as config]
   [leiningen.core.project :as project]
   [leiningen.test]))

(set! *warn-on-reflection* true)

(def version
  "This plugin's own version, used to pull the runtime half into the project JVM.

  Duplicated from project.clj because the task must name a resolvable artifact
  before the project JVM starts. A test asserts the two never drift apart."
  "0.1.0-SNAPSHOT")

(def ^:private coordinate
  'com.github.robinlahtinen/lein-junit-xml)

(defn profile
  "Returns the profile that carries the runtime half into the project JVM.

  Args:
    project - the Leiningen project map.
    opts    - resolved options from com.github.robinlahtinen.lein-junit-xml.impl.config/options.

  :injections are spliced by eval-in-project after its init form and before the
  test form, which is exactly the window needed to instrument clojure.test.

  The self-dependency is omitted under :eval-in :leiningen. There the plugin is
  already on the classpath, and Leiningen loads :dependencies the same way it
  loads plugins, so including it would place the released jar ahead of local
  source. This is also what lets this project's own end-to-end tests run without
  a prior lein install.

  Returns a profile map."
  [project opts]
  (cond-> {:injections [`(require 'com.github.robinlahtinen.lein-junit-xml)
                        `(com.github.robinlahtinen.lein-junit-xml/install! ~opts)]}
    (not= :leiningen (:eval-in project))
    (assoc :dependencies [[coordinate version :exclusions ['org.clojure/clojure]]])))

(defn ^:pass-through-help junit-xml
  "Run the project's tests, writing JUnit XML reports for each namespace.

  Takes the same arguments as `lein test`: bare namespaces, test file paths, and
  :selector keywords are all passed straight through.

  Commands:
    lein junit-xml                 ; every test namespace
    lein junit-xml :unit           ; only the :unit selector
    lein junit-xml my.app.core-test

  Reports are written to target/junit-xml by default, one TEST-<namespace>.xml
  file per namespace, in the JUnit legacy format the Jenkins JUnit plugin reads.

  Configure the output directory in project.clj:

    :junit-xml {:output-dir \"target/junit-xml\"}

  To make plain `lein test` write reports too, add an alias:

    :aliases {\"test\" [\"junit-xml\"]}

  The exit code matches `lein test`: zero when the suite passes, one otherwise."
  [project & args]
  (let [opts (config/options (:junit-xml project) (:root project))]
    (apply leiningen.test/test
           (project/merge-profiles project [(profile project opts)])
           args)))
