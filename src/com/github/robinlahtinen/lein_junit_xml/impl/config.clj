;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns com.github.robinlahtinen.lein-junit-xml.impl.config
  "Boundary validation and normalisation for the :junit-xml project key.

  All functions are pure. Each validator throws ex-info on failure using the
  stable public error contract:

    {:com.github.robinlahtinen.lein-junit-xml/error   <category-keyword>
     :com.github.robinlahtinen.lein-junit-xml/context <operation-keyword>
     ...operation-specific keys...}

  The resolved options map is embedded in the form Leiningen evaluates in the
  project JVM, and that form is printed with *print-dup* and *print-meta* false.
  Only plain, read-printable EDN may therefore survive normalisation.

  This namespace is an implementation detail; do not use from application code."
  {:skip-wiki true}
  (:require
   [clojure.string :as str])
  (:import
   (java.io File)))

(set! *warn-on-reflection* true)

(def ^:private context
  "Value used as :com.github.robinlahtinen.lein-junit-xml/context in every error thrown from here."
  :com.github.robinlahtinen.lein-junit-xml/junit-xml)

(def default-output-dir
  "Where reports are written when :output-dir is not configured."
  "target/junit-xml")

(def known-keys
  "Every key the :junit-xml map accepts."
  #{:output-dir})

(defn require-map!
  "Throw ex-info when v is neither a map nor nil.

  Args:
    v    - value to check.
    name - parameter name used in the error message.

  Throws:
    ex-info :com.github.robinlahtinen.lein-junit-xml/invalid-type when v is not a map."
  [v name]
  (when-not (or (nil? v) (map? v))
    (throw (ex-info
            (str name " must be a map")
            {:com.github.robinlahtinen.lein-junit-xml/error   :com.github.robinlahtinen.lein-junit-xml/invalid-type
             :com.github.robinlahtinen.lein-junit-xml/context context
             :name                                            name
             :value                                           (type v)}))))

(defn require-non-blank-string!
  "Throw ex-info when v is not a non-blank string.

  Args:
    v    - value to check.
    name - parameter name used in the error message.

  Throws:
    ex-info :com.github.robinlahtinen.lein-junit-xml/invalid-type  when v is not a string.
    ex-info :com.github.robinlahtinen.lein-junit-xml/invalid-value when v is blank."
  [v name]
  (when-not (string? v)
    (throw (ex-info
            (str name " must be a string")
            {:com.github.robinlahtinen.lein-junit-xml/error   :com.github.robinlahtinen.lein-junit-xml/invalid-type
             :com.github.robinlahtinen.lein-junit-xml/context context
             :name                                            name
             :value                                           (type v)})))
  (when (str/blank? v)
    (throw (ex-info
            (str name " must not be blank")
            {:com.github.robinlahtinen.lein-junit-xml/error   :com.github.robinlahtinen.lein-junit-xml/invalid-value
             :com.github.robinlahtinen.lein-junit-xml/context context
             :name                                            name
             :value                                           v}))))

(defn reject-unknown-keys!
  "Throw ex-info when config contains a key the plugin does not understand.

  A silently ignored typo in project.clj costs far more to diagnose than an
  immediate, named error.

  Args:
    config - the :junit-xml map.

  Throws:
    ex-info :com.github.robinlahtinen.lein-junit-xml/unsupported when an unrecognised key is present."
  [config]
  (let [unknown (remove known-keys (keys config))]
    (when (seq unknown)
      (throw (ex-info
              (str "Unknown :junit-xml "
                   (if (next unknown) "keys: " "key: ")
                   (str/join ", " (sort (map pr-str unknown))))
              {:com.github.robinlahtinen.lein-junit-xml/error   :com.github.robinlahtinen.lein-junit-xml/unsupported
               :com.github.robinlahtinen.lein-junit-xml/context context
               :keys                                            (vec (sort unknown))
               :known                                           (vec (sort known-keys))})))))

(defn- absolute-path
  "Resolves dir against root, leaving an already-absolute dir untouched.

  Resolution happens here, in the Leiningen JVM, because it is the only place
  that reliably knows the project root: under :eval-in :leiningen the working
  directory is the user's shell, not the project."
  ^String [^String dir ^String root]
  (let [f (File. dir)]
    (if (or (.isAbsolute f) (str/blank? root))
      (.getPath f)
      (.getPath (File. root dir)))))

(defn options
  "Validates and normalises the :junit-xml configuration.

  Args:
    config - the value of the :junit-xml project key; may be nil.
    root   - the project root directory, used to absolutise :output-dir.

  Options:
    :output-dir - directory reports are written to; defaults to
                  \"target/junit-xml\". Relative paths resolve against root.

  Returns a map with an absolute :output-dir, safe to embed in an injected form.

  Throws:
    ex-info :com.github.robinlahtinen.lein-junit-xml/invalid-type  when config or :output-dir has the wrong type.
    ex-info :com.github.robinlahtinen.lein-junit-xml/invalid-value when :output-dir is blank.
    ex-info :com.github.robinlahtinen.lein-junit-xml/unsupported   when an unrecognised key is present.

  Example:
    (options {:output-dir \"build/reports\"} \"/home/dev/app\")"
  [config root]
  (require-map! config ":junit-xml")
  (reject-unknown-keys! config)
  (let [dir (:output-dir config default-output-dir)]
    (require-non-blank-string! dir ":output-dir")
    {:output-dir (absolute-path dir root)}))
