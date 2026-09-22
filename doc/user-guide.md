# User guide

## Installation

### Project-level

Add the plugin to `project.clj` so everyone building the project gets reports:

```clojure
(defproject my-app "1.0.0"
            :plugins [[com.github.robinlahtinen/lein-junit-xml "0.1.0"]])
```

### User-level

To have it available everywhere without touching a project, add it to the `:user` profile in
`~/.lein/profiles.clj`:

```clojure
{:user {:plugins [[com.github.robinlahtinen/lein-junit-xml "0.1.0"]]}}
```

### Requirements

| Component    | Version                                 |
|--------------|-----------------------------------------|
| Leiningen    | 2.10.0 or newer                         |
| Clojure      | 1.9 or newer, in the project under test |
| Java         | 8 or newer, in the project under test   |
| Dependencies | none added to your project              |

Leiningen 2.13.0 itself needs Java 11, so a Java 8 build must stay on Leiningen 2.12.0 or older.
That is a Leiningen constraint, not this plugin's, as plain `lein test` fails the same way.
The **Requirements** section of [the reference](reference.md) records how each floor was
measured.

## Quick start

```bash
$ lein junit-xml
```

Reports appear in `target/junit-xml`, one file per test namespace:

```
target/junit-xml/
├── TEST-my.app.core-test.xml
└── TEST-my.app.util-test.xml
```

The task takes the same arguments as `lein test`:

```bash
$ lein junit-xml :unit                     # a test selector
$ lein junit-xml my.app.core-test          # a single namespace
$ lein junit-xml test/my/app/core_test.clj
```

The exit code is the same too: zero when the suite passes, one when it does not.

## Core concepts

### One file per namespace

Each test namespace becomes one `<testsuite>` in one `TEST-<namespace>.xml` file. This bounds memory
to a single namespace at a time and means a run that dies partway through keeps the results it
already produced.

There is no need to consolidate the files. Jenkins globs `**/TEST-*.xml` and merges same-named suites
itself, and so do the other major consumers.

### How results map

| `clojure.test`                | Report                                                                 |
|-------------------------------|------------------------------------------------------------------------|
| A passing `deftest`           | `<testcase>` with no children                                          |
| A failed `is` assertion       | a `<failure>` inside the case                                          |
| An uncaught exception         | an `<error>` inside the case, with the exception class and stack trace |
| A test excluded by a selector | `<skipped>`                                                            |
| A throwing `:once` fixture    | an `initializationError` case carrying the exception                   |

A `deftest` with several failing assertions is **one** failing case with several `<failure>`
children, which is how JUnit counts and how CI dashboards expect to read it.

## Making `lein test` write reports

Alias the built-in task:

```clojure
:aliases {"test" ["junit-xml"]}
```

Now `lein test` behaves exactly as before and also writes reports. Nothing else changes: selectors,
exit codes and `lein retest` all keep working.

## Configuration

One option:

```clojure
:junit-xml {:output-dir "target/junit-xml"}
```

Relative paths resolve against the project root, not your shell's working directory. Unknown keys
are rejected with a clear error rather than silently ignored, so a typo fails immediately.

### Overriding the directory for one run

Use Leiningen's built-in `update-in` task, as no extra configuration surface is needed:

```bash
$ lein update-in :junit-xml assoc :output-dir '"build/reports"' -- junit-xml
```

## CI recipes

### Jenkins

```groovy
sh 'lein junit-xml'
junit testResults: 'target/junit-xml/TEST-*.xml'
```

Two settings are worth knowing:

- **Stdio retention.** By default, Jenkins keeps only the first and last 500 characters of
  `<system-out>`. Raise it on the step if you need whole logs.
- **Properties.** Jenkins discards `<properties>` unless the job sets `keepProperties: true`.

Make sure the step still runs when tests fail, or a failing build will archive nothing:

```groovy
sh script: 'lein junit-xml', returnStatus: true
junit testResults: 'target/junit-xml/TEST-*.xml'
```

### GitHub Actions

```yaml
-   name: Test
    run: lein junit-xml
-   name: Publish results
    uses: mikepenz/action-junit-report@v6
    if: always()
    with:
        report_paths: target/junit-xml/TEST-*.xml
```

### GitLab CI

```yaml
test:
    script: lein junit-xml
    artifacts:
        when: always
        reports:
            junit: target/junit-xml/TEST-*.xml
```

## Troubleshooting

### No files were written at all

Almost always a compile error in a test namespace. Leiningen requires every test namespace before
running any test, so the run aborts before a single result exists. The actual error is in the console
output above.

### My log output is missing from `<system-out>`

Output written straight to `System/out` by tools such as SLF4J, Logback, `java.util.logging`,
and most Java libraries bypasses Clojure's `*out*` and is not captured. Test code using
`println` is captured. See "Why `System/out` is not captured" in [the reference](reference.md) for why this is not
worked around.

Output from threads other than the test thread is not captured either.

### Everything shows up under "(root)" in Jenkins

Jenkins builds its Package → Class → Test tree by splitting `classname` at the last dot. A
single-segment test namespace has no dot, so it lands under `(root)`. Use a multi-segment namespace
such as `my.app.core-test`.

### A namespace shows zero tests, or is missing from Jenkins

A namespace with no tests gets a file with `tests="0"`, and Jenkins discards suites with no cases.
That is Jenkins' behaviour, not a lost report — the file is on disk.

### Warnings prefixed `com.github.robinlahtinen/lein-junit-xml:`

A problem inside the plugin itself. These are reported and swallowed deliberately: a reporting bug
must never change whether your tests pass. Please
[open an issue](https://github.com/robinlahtinen/lein-junit-xml/issues) with the message.
