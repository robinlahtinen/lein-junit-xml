(defproject com.github.robinlahtinen/lein-junit-xml "0.1.0"
  :description "A Leiningen plugin that writes JUnit-compliant XML reports from clojure.test results."
  :url "https://robinlahtinen.github.io/lein-junit-xml/"
  :license {:name         "MIT License"
            :url          "https://github.com/robinlahtinen/lein-junit-xml/blob/main/LICENSE"
            :distribution :repo}
  ;; Plugins inherit Leiningen's own Clojure; declaring a compile-scope Clojure
  ;; dependency would drag a version into every consuming project.
  :eval-in-leiningen true
  ;; The lowest Leiningen this repository's own suite runs green on; see
  ;; doc/reference.md for how the consumer-side floors were measured.
  :min-lein-version "2.10.0"
  :dependencies []
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.3"]]}}
  :plugins [[dev.weavejester/lein-cljfmt "0.16.5"]
            [lein-codox "0.10.8"]]
  :codox {:output-path "target/docs"
          :doc-paths   ["doc"]
          :source-uri  "https://github.com/robinlahtinen/lein-junit-xml/blob/main/{filepath}#L{line}"
          :metadata    {:doc/format :plaintext}
          :project     {:name "lein-junit-xml"}}
  ;; The integration suite shells out to `lein junit-xml` on the default
  ;; :subprocess path, so it needs this plugin installed first:
  ;;   lein install && lein test :integration
  :test-selectors {:default     (complement :integration)
                   :integration :integration}
  :deploy-repositories [["clojars" {:url           "https://repo.clojars.org"
                                    :username      :env/clojars_username
                                    :password      :env/clojars_password
                                    :sign-releases false}]])
