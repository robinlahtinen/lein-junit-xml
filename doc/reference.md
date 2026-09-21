# Reference

A precise technical reference, not a tutorial. For a task-oriented introduction see the
[user guide](user-guide.md).

## Configuration

Set under the `:junit-xml` key in `project.clj`. Unknown keys are rejected rather than ignored.

| Key           | Type   | Default              | Description                                                                                                        |
|---------------|--------|----------------------|--------------------------------------------------------------------------------------------------------------------|
| `:output-dir` | string | `"target/junit-xml"` | Directory report files are written to. Relative paths resolve against the project root, not the working directory. |

```clojure
:junit-xml {:output-dir "target/junit-xml"}
```

## Requirements

| Component                                  | Version             | How it was established                    |
|--------------------------------------------|---------------------|-------------------------------------------|
| Leiningen                                  | **2.10.0 or newer** | Lowest release the plugin is exercised on |
| Clojure, in the project under test         | **1.9 or newer**    | Hard floor — 1.8 fails to compile         |
| Java, in the project under test            | **8 or newer**      | Full run verified on 8u482                |
| Runtime dependencies added to your project | none                |                                           |

These are measured, not assumed. Each was established by running the plugin, not by reading release
notes, and the evidence is recorded below so the numbers can be rechecked rather than trusted.

Two Clojure versions are in play and only one is the plugin's own. The task half (`leiningen.junit-xml`
and `impl.config`) runs on **Leiningen's** bundled Clojure and needs only 1.5. The reporting half is
loaded into **your** project's JVM and runs on **your** Clojure, so it declares no Clojure dependency
and stays syntax-compatible with 1.9, with no 1.12-only reader syntax anywhere under `src/com/github/robinlahtinen/`.
CI loads those namespaces against 1.9, 1.10, 1.11 and 1.12 on every push.

## The task

|              |                                                                             |
|--------------|-----------------------------------------------------------------------------|
| Task         | `lein junit-xml`                                                            |
| Arguments    | identical to `lein test`: namespaces, test file paths, `:selector` keywords |
| Exit code    | `0` when the suite passes, `1` otherwise                                    |
| Side effects | writes one file per test namespace under `:output-dir`; nothing else        |

## XML contract

One file per test namespace, named `TEST-<namespace>.xml`. Characters outside `[A-Za-z0-9._-]` in
the namespace become `_` so the name is valid on Windows; the unmodified name stays in the `name`
attribute. The root element is always `<testsuite>`; no `<testsuites>` wrapper is emitted, matching
JUnit.

### `<testsuite>`

Attributes, in the order JUnit writes them:

| Attribute   | Value                                                               |
|-------------|---------------------------------------------------------------------|
| `name`      | the test namespace                                                  |
| `tests`     | total cases, **including** skipped ones                             |
| `skipped`   | cases that did not run                                              |
| `failures`  | cases with at least one failed assertion                            |
| `errors`    | cases that threw                                                    |
| `time`      | wall-clock seconds, three decimals, always `.` as the separator     |
| `hostname`  | the reporting host, or `<unknown host>` if lookup fails             |
| `timestamp` | suite start as an ISO-8601 UTC instant, e.g. `2026-09-20T16:18:05Z` |

Child order is `(properties?, testcase*, system-out?, system-err?)`.

### `<testcase>`

| Attribute   | Value                              |
|-------------|------------------------------------|
| `name`      | the `deftest` var name             |
| `classname` | the fully-qualified namespace      |
| `time`      | wall-clock seconds, three decimals |

Child order is `(skipped?, error*, failure*)`.

`classname` drives the whole Package → Class → Test tree in Jenkins, which splits it at the last
dot. A single-segment namespace therefore lands under Jenkins' `(root)` package; that is an accurate
reflection of your namespace, and no package is invented to avoid it.

### Result elements

| Element     | Attributes                              | Body                                        |
|-------------|-----------------------------------------|---------------------------------------------|
| `<failure>` | `message`                               | expected form, actual form, and `file:line` |
| `<error>`   | `message`, `type` (the exception class) | the full stack trace                        |
| `<skipped>` | none                                    | the reason                                  |

`<skipped>` carries no `message` attribute: the schema types it as a simple type, so an attribute
there is invalid. Jenkins falls back to the element's text, which is also what JUnit writes.

### Aggregates count cases, not assertions

A `deftest` containing three failing `is` forms is **one** failure carrying three `<failure>`
children. This matches JUnit, where a test method is a single result regardless of how many
assertions it makes.

### `<properties>`

A curated three: `clojure.version`, `java.version`, `os.name`. JUnit dumps the entire
`System.getProperties()`, which leaks the build environment into every file; Jenkins discards them
altogether unless the job opts in with `keepProperties`.

### Character handling

Every string entering a document passes through one filter.

- Characters outside XML 1.0's `Char` production, defined as `#x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] |
  [#x10000-#x10FFFF]`, are replaced with `U+FFFD`. This covers `ESC` from ANSI colour output, `NUL`,
  other control bytes, non-characters, and unpaired surrogates.
- `& < >` are escaped in text; `& < > "` in attribute values.
- Newlines, carriage returns and tabs in attribute values are escaped numerically (`&#10;`, `&#13;`,
  `&#9;`) so multi-line failure messages survive attribute-value normalisation.
- A `]]>` inside a CDATA payload is split across two adjacent sections so it cannot terminate the
  section early.

This matters because Jenkins parses with a strict SAX reader and replaces an entire unparseable
suite with a synthetic red `[failed-to-read]` case. One bad byte does not degrade a report; it
destroys it.

## Deliberate deviations from JUnit 6.2.0

| # | JUnit writes                                           | We write                              | Why                                                                                                                        |
|---|--------------------------------------------------------|---------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| 1 | `timestamp` as a zone-less local date-time             | an explicit UTC instant ending in `Z` | Jenkins appends `Z` to a zone-less value and reads it as UTC, shifting every suite start by the agent's offset             |
| 2 | several `<system-out>` elements per case               | exactly one                           | Jenkins reads only the first and silently discards the rest                                                                |
| 3 | `<failure>` before `<error>`                           | `<error>` before `<failure>`          | the schema sequence is ordered; JUnit's order is invalid when a case has both, and Jenkins is order-insensitive either way |
| 4 | every system property                                  | three                                 | size and environment leakage, for data Jenkins discards by default                                                         |
| 5 | `time` via `NumberFormat(Locale.US)`, grouping enabled | `%.3f` with `Locale/ROOT`             | a grouping comma above 1000 seconds is invalid against the Surefire schema and breaks non-Jenkins consumers                |

## Schema conformance

Reports are validated in CI against `dev-resources/jenkins-junit.xsd`, the schema
`junit-framework`'s own `XmlReportAssertions` validates every legacy report against.

`dev-resources/surefire-test-report-3.0.2.xsd` is **not** a second gate, and cannot be: it permits
no `timestamp` and no `hostname` on `<testsuite>` and declares no `anyAttribute`, so JUnit 6.2.0's
own output is invalid against it. Dropping those attributes to satisfy it would lose data Jenkins
actually uses. The common subset is asserted instead, and the divergence is itself covered by a test
so it is not later "fixed" in the wrong direction.

This is also why no `assertions`, `file` or `line` attributes appear on `<testcase>` even though
they are common conventions elsewhere and the data is available: neither schema permits them.

## Edge cases and limits

| Situation                                         | Behaviour                                                                                                                                                                                                                                                                                                      |
|---------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A namespace with no tests                         | A file is still written, with `tests="0"`. Jenkins discards suites with no cases, so it will not appear there.                                                                                                                                                                                                 |
| A `:once` fixture throws                          | The error is recorded as an `initializationError` case and the suite is written. Tests after the fixture never ran and are absent.                                                                                                                                                                             |
| `:monkeypatch-clojure-test false`                 | Still supported; the plugin owns the failure path rather than relying on Leiningen's catcher.                                                                                                                                                                                                                  |
| A compile error in any test namespace             | **No files at all.** Leiningen requires every test namespace before running anything, so the run aborts before a result exists.                                                                                                                                                                                |
| The same namespace run twice in one JVM           | Each run reports independently; per-run state resets on each invocation.                                                                                                                                                                                                                                       |
| Output written to `System/out` directly           | Not captured. See below.                                                                                                                                                                                                                                                                                       |
| Leiningen's own `lein test :only …` failure hints | Captured into `<system-out>`. Leiningen prints them with `println`, so they go to the same stream as your test output. Filtering them would mean matching on their exact wording, which is more fragile than reporting honestly what was printed.                                                              |
| Output from other threads                         | Not captured; `*out*` rebinding is thread-local.                                                                                                                                                                                                                                                               |
| Duplicate test names                              | Emitted as-is. Jenkins keeps both and disambiguates only their URLs.                                                                                                                                                                                                                                           |
| A namespace defining `test-ns-hook`               | Degraded. `clojure.test` bypasses `test-var` entirely there, so no per-test boundaries are reported: passing tests do not appear, and each failure becomes its own `initializationError` case. The suite is still written and still valid. `test-ns-hook` is also incompatible with fixtures, so this is rare. |

### Why `System/out` is not captured

`System/setOut` does not redirect Clojure's `*out*`, whose root writer is constructed over
`System.out` when the runtime initialises. Capturing both would require `System/setOut` *and*
`alter-var-root` on `#'*out*`, which is process-global and unsafe under `:eval-in :leiningen` or any
parallel runner. The plugin takes the safe side.

## Error states

Configuration errors throw `ex-info` before the project JVM starts, carrying:

```clojure
{:com.github.robinlahtinen.lein-junit-xml/error   <category>
 :com.github.robinlahtinen.lein-junit-xml/context :com.github.robinlahtinen.lein-junit-xml/junit-xml
 ...}
```

| Category                                                 | Raised when                                                 |
|----------------------------------------------------------|-------------------------------------------------------------|
| `:com.github.robinlahtinen.lein-junit-xml/invalid-type`  | `:junit-xml` is not a map, or `:output-dir` is not a string |
| `:com.github.robinlahtinen.lein-junit-xml/invalid-value` | `:output-dir` is blank                                      |
| `:com.github.robinlahtinen.lein-junit-xml/unsupported`   | an unrecognised key is present under `:junit-xml`           |

Errors *inside* the reporting path never propagate. They are printed to `System/err` prefixed with
`com.github.robinlahtinen/lein-junit-xml:` and swallowed, because a bug in a reporter must not change whether your tests
pass.

## Internal data shapes

The suite value, produced by `com.github.robinlahtinen.lein-junit-xml.impl.collect`
and consumed by `com.github.robinlahtinen.lein-junit-xml.impl.xml`:

```clojure
{:name       "my.app.core-test"                                   ; namespace, as a string
 :timestamp  1758394685000                                        ; epoch millis, suite start
 :duration   1234                                                 ; millis
 :hostname   "build-07"
 :properties {"clojure.version" "1.12.6"}
 :out        "captured stdout"                                    ; absent when empty
 :err        "captured stderr"                                    ; absent when empty
 :cases      [{:name      "adds-test"
               :classname "my.app.core-test"
               :duration  12
               :results   [{:type    :fail                        ; :fail | :error | :skipped
                            :message "expected: (= 1 2)"
                            :class   "java.lang.RuntimeException" ; :error only
                            :detail  "expected/actual, or a stack trace"}]}]}
```

An empty `:results` vector means the case passed.
