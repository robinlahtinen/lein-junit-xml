;; Fixture project for lein-junit-xml's end-to-end test. Not part of the plugin.
;;
;; :eval-in :leiningen runs the test form in the calling JVM, so the end-to-end
;; test needs no prior `lein install` to resolve the plugin.
(defproject sample "0.1.0"
  :description "Fixture project with one test of each outcome."
  :eval-in :leiningen
  :test-paths ["test"]
  :junit-xml {:output-dir "target/junit-xml-sample"}
  ;; The documented opt-in: make plain `lein test` write reports too.
  :aliases {"test" ["junit-xml"]}
  :test-selectors {:only-passing (fn [m] (contains? m :passing))})
