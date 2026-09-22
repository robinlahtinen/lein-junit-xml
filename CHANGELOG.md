# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-09-22

Initial release of lein-junit-xml.

#### Reporting

- **`lein junit-xml` runs the project's tests and writes JUnit legacy XML.** One
  `TEST-<namespace>.xml` file per test namespace, matching the element and attribute set JUnit
  6.2.0's own report writer emits.
- **Suite-level `<system-out>` and `<system-err>` capture**, covering output from `:once` and
  `:each` fixtures as well as test bodies.
- **Selector-excluded tests are reported as `<skipped>`** rather than silently vanishing, so CI
  totals stay honest.
- **A throwing `:once` fixture is reported** as an `initializationError` case instead of producing
  an empty, green suite.

#### Correctness

- **One character policy for every string entering a document.** Characters outside XML 1.0's `Char` production
  (including ANSI escapes, control bytes, non-characters, and unpaired surrogates) are replaced; `]]>` inside CDATA is
  split; attribute whitespace is escaped numerically.
- **Documents are rendered whole and written once**, so no failure mid-run can leave a truncated
  file with an unclosed root element.
- **Reporting errors never change the outcome of a test run.** Every entry point catches and reports
  rather than propagating.
- **`time` is formatted with `Locale/ROOT`**, so a comma-decimal default locale cannot be misread by
  Jenkins as a thousand separator.
- **`timestamp` is an explicit UTC instant**, deviating from JUnit's zone-less local time, which
  Jenkins misinterprets as UTC.

#### Compatibility

- **`lein test` is left intact.** The task delegates to `leiningen.test/test`, so selectors,
  `:test-paths`, exit codes and `.lein-failures` (and therefore `lein retest`) keep working.
- **Custom `clojure.test/report` methods keep working**, because the plugin adds delegating methods
  rather than replacing the multimethod.
- Verified against `jenkins-junit.xsd` in CI, and against the Jenkins JUnit plugin
  `1428.vef95b_fa_89508` parser.
- **Requires Leiningen 2.10.0+, Clojure 1.9+ and Java 8+.** These are measured rather than assumed:
  every floor was established by running the plugin, and CI exercises the oldest Clojure on the
  oldest Java. See [the reference](doc/reference.md#requirements) for the evidence, including why
  the advertised Leiningen floor is higher than the oldest version observed to work.

#### Configuration

- `:junit-xml {:output-dir "target/junit-xml"}`. Unknown keys are rejected rather than ignored.

#### Dependencies

- None. Clojure and the JDK only.

[Unreleased]: https://github.com/robinlahtinen/lein-junit-xml/compare/v0.1.0...HEAD

[0.1.0]: https://github.com/robinlahtinen/lein-junit-xml/releases/tag/v0.1.0
