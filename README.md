[**API**][api] | [**Docs**][docs] | Latest release: [v0.1.0-SNAPSHOT][latest-release]

[![Tests][tests-badge]][tests-url]
[![Clojars][clojars-badge]][clojars-url]

# lein-junit-xml

### JUnit XML reports from `lein test` for any CI/CD pipeline.

A **zero-dependency** Leiningen plugin that turns `clojure.test` results into the JUnit legacy XML
that CI servers read. It writes one `TEST-<namespace>.xml` file per test namespace, matching the
element and attribute set JUnit 6.2.0's own report writer emits.

**Verified against:** the schema `junit-framework` validates its own reports with, and the parser in
Jenkins JUnit plugin `1428.vef95b_fa_89508`.

## Why lein-junit-xml?

- **It cannot corrupt your reports.** Every string is filtered through one XML 1.0 character policy
  before it reaches the document, and each file is rendered whole and written once. ANSI colour codes,
  control bytes, and `]]>` in test output are all handled, eliminating the failure modes that produce
  a red `[failed-to-read]` suite in CI/CD.
- **It cannot change your test results.** Every entry point is wrapped so a bug in the plugin is
  reported and swallowed rather than surfacing as a phantom failure in your namespace.
- **It leaves `lein test` intact.** Selectors, `:test-paths`, exit codes, `lein retest` and custom
  `clojure.test/report` methods all keep working, because the plugin delegates to the built-in task
  and adds multimethod methods instead of replacing anything.
- **Selector-excluded tests are reported as skipped**, not silently dropped, so your totals in
  Jenkins stay honest.
- **No dependencies.** Clojure and the JDK only; nothing is added to your project's classpath but the
  plugin itself.

## Installation

Add the plugin to `:plugins` in `project.clj`:

```clojure
:plugins [[com.github.robinlahtinen/lein-junit-xml "0.1.0-SNAPSHOT"]]
```

Requires Leiningen 2.10.0 or newer. The reporting half runs in your project's JVM, and supports
Clojure 1.9 or newer and Java 8 or newer there. See [the reference][reference] for compatibility details.

## Quick example

```bash
$ lein junit-xml                  # run every test namespace
$ lein junit-xml :unit            # pass a selector through
$ lein junit-xml my.app.core-test # pass a namespace through
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="my.app.core-test" tests="3" skipped="1" failures="1" errors="0"
           time="1.234" hostname="build-07" timestamp="2026-09-20T16:18:05Z">
    <properties>
        <property name="clojure.version" value="1.12.6"/>
    </properties>
    <testcase name="adds-test" classname="my.app.core-test" time="0.012"/>
    <testcase name="subs-test" classname="my.app.core-test" time="0.030">
        <failure message="expected: (= 1 2)"><![CDATA[expected: (= 1 2)
  actual: (not (= 1 2))
at core_test.clj:42]]></failure>
    </testcase>
    <system-out><![CDATA[anything your tests printed]]></system-out>
</testsuite>
```

## Configuration

```clojure
:junit-xml {:output-dir "target/junit-xml"} ; the only option; this is the default
```

To make plain `lein test` write reports too, alias it:

```clojure
:aliases {"test" ["junit-xml"]}
```

To override the directory for one CI run without editing `project.clj`, use Leiningen's built-in
`update-in` task:

```bash
$ lein update-in :junit-xml assoc :output-dir '"build/reports"' -- junit-xml
```

## Things to be aware of

1. **Output is captured per namespace, not per test.** `<system-out>` and `<system-err>` hold
   everything the namespace printed, including from `:once` and `:each` fixtures.
2. **Output written straight to `System/out` is not captured.** SLF4J, Logback, `java.util.logging`
   and any Java library that grabs the stream at class-init bypass Clojure's `*out*`. Catching it
   would require process-global redirection, which is not safe to impose on your JVM.
3. **A compile error in a test namespace produces no report files at all.** Leiningen requires every
   test namespace before running any test, so the run aborts before a single result exists. The
   error is in the console output.
4. **Jenkins truncates `<system-out>` by default**, keeping the first and last 500 characters. Raise
   the JUnit step's stdio retention if you need the whole log.
5. **Consolidating the files is unnecessary.** Jenkins globs `**/TEST-*.xml` and merges same-named
   suites itself.
6. **`timestamp` is a real UTC instant**, which deviates deliberately from JUnit's zone-less local
   time. See [the reference][reference] for why.
7. **On Java 8, stay on Leiningen 2.12.0 or older.** Leiningen 2.13.0 calls a Java 11 API during
   `eval-in-project`, so every task that runs your code, including `lein test`, fails there. That
   is a Leiningen constraint; this plugin runs fine on Java 8.

## Documentation

- [Rationale][rationale] — why this exists and what it does differently
- [User guide][user-guide] — installation, configuration and CI recipes
- [Reference][reference] — the XML contract, every option, and every known limitation
- [Design][design] — how the plugin is put together
- [API documentation][api]

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

Copyright © 2026 Robin Lahtinen.
Distributed under the [MIT License](LICENSE).

---

*JUnit is a trademark of the JUnit team. Jenkins is a registered trademark of Software in the Public
Interest, Inc. This project is not affiliated with either.*

<!-- Links -->

[api]: https://robinlahtinen.github.io/lein-junit-xml/com.github.robinlahtinen.lein-junit-xml.html

[docs]: https://robinlahtinen.github.io/lein-junit-xml/

[latest-release]: https://github.com/robinlahtinen/lein-junit-xml/releases/tag/v0.1.0-SNAPSHOT

[rationale]: doc/rationale.md

[user-guide]: doc/user-guide.md

[reference]: doc/reference.md

[design]: doc/design.md

[tests-url]: https://github.com/robinlahtinen/lein-junit-xml/actions/workflows/tests.yml

[tests-badge]: https://github.com/robinlahtinen/lein-junit-xml/actions/workflows/tests.yml/badge.svg

[clojars-url]: https://clojars.org/com.github.robinlahtinen/lein-junit-xml

[clojars-badge]: https://img.shields.io/clojars/v/com.github.robinlahtinen/lein-junit-xml.svg
