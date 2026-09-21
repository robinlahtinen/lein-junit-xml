# Design

For contributors and anyone auditing how the plugin behaves inside their build.

## Architectural overview

The hard constraint is that a Leiningen plugin lives in two JVMs. The task runs in Leiningen's own
process; the code that instruments `clojure.test` has to run in the *project's* process, where the
plugin jar is not on the classpath by default.

```
  Leiningen JVM                              Project JVM
  ┌──────────────────────────┐   profile     ┌─────────────────────────────────────────┐
  │ leiningen.junit-xml      │ ────────────► │ com.github.robinlahtinen.lein-junit-xml │
  │   task: resolve, merge,  │  :deps +      │   imperative shell                      │
  │   delegate               │  :injections  │     ├─ impl.collect   (pure)            │
  │                          │               │     ├─ impl.xml       (pure)            │
  │ …lein-junit-xml.impl     │               │     └─ writes TEST-*.xml                │
  │   .config        (pure)  │               │                                         │
  └──────────────────────────┘               └─────────────────────────────────────────┘
                                                                 │
                                                   delegates to  ▼
                                                         leiningen.test/test
```

The task holds no reporting logic. It resolves configuration, merges a profile, and hands off to
`leiningen.test/test`, which is what makes selectors, `:test-paths`, `.lein-failures`, exit codes
and the abort contract work without being reimplemented.

## Functional core, imperative shell

Three namespaces are pure and hold every decision worth testing:

| Namespace      | Transformation                      |
|----------------|-------------------------------------|
| `impl.config`  | project map → validated options     |
| `impl.collect` | `clojure.test` events → suite value |
| `impl.xml`     | suite value → XML string            |

`impl.collect` takes the clock as an argument rather than reading it, so an entire test run can be
replayed from synthetic event maps in a plain REPL with neither Leiningen nor a test runner present.
That is what makes the property tests over hostile input possible at all.

The root namespace, `com.github.robinlahtinen.lein-junit-xml`, is the only one with side effects:
it installs the instrumentation, owns one atom, and writes files. It is also the only name the
injected form references, so it is the one name here that has to stay stable.

## Instrumenting clojure.test

Two touchpoints, of two different kinds. The difference is forced by direct linking, not taste.

```clojure
clojure.test/report    ; added defmethods, delegating to the ones they replace
#'clojure.test/test-ns ; alter-var-root, for suite output capture and timing
```

### Why not wrap `do-report`

`do-report` looks like the ideal single funnel: every event passes through it. But `clojure.test` is AOT-compiled with
direct linking, so its in-namespace callers (such as `test-var`, `test-ns`, and `run-tests`) call it without going
through the var. Wrapping it with `alter-var-root` yields only `:pass` and `:fail`, which originate in *user* code via
the `is` macro. Every lifecycle event is invisible, and the failure is silent and partial.

`report` is reached by a var deref from `do-report`, so multimethod dispatch always happens. Adding
methods sees everything.

### Why `defmethod` rather than `alter-var-root` on `report`

`report` is a `defmulti` carrying `:dynamic true`. Replacing the var's root with a plain function would cause any later
`(defmethod clojure.test/report …)` to die with a `ClassCastException`, whether it appears in a user's test namespace,
in `humane-test-output`, or in any custom reporter. Being dynamic, it can also be shadowed outright by a `binding`,
which is how the previous generation of this plugin silently disabled Leiningen's own failure recording and broke
`lein retest`.

Adding methods leaves the multimethod intact and composes with the `robert.hooke` hook Leiningen
installs on the same var. Because the plugin can run either before that hook (at `:injections` time)
or after it (under `:eval-in :leiningen`), the root namespace walks back through the wrapper's
metadata to find the multimethod rather than assuming which order won.

### Why `test-ns` is wrapped

`clojure.test` fixtures run strictly outside `test-var`, so capturing output at test granularity
would silently drop everything a `:once` or `:each` fixture printed. Wrapping `test-ns` gives exactly
suite-level semantics with one `binding` and no accumulation logic, and supplies the suite's
wall-clock start.

Capture uses a real `java.io.StringWriter`. A partial `proxy` over `Writer` is what made the previous
plugin throw an arity exception the moment the compiler printed a reflection warning mid-test.

Console output stays clean for free: Leiningen binds `clojure.test/*test-out*` to the real console
writer *outside* `test-ns`, so `clojure.test`'s own progress and failure reporting still reaches the
terminal while only what the tests themselves print lands in the report.

## Flushing

The primary flush is on `:end-test-ns`; the `test-ns` wrapper's `finally` is an idempotent fallback.
The ordering is easy to get backwards and worth stating.

Leiningen hooks `test-ns` too, with a fixture-error catcher. Because that hook is installed *after* ours, `robert.hooke`
's composition rules put it *outside* ours. So when a `:once` fixture throws, our `finally` runs *before* Leiningen
synthesises its `:error` and `:end-test-ns`. A fallback that only wrote would therefore lose the fixture error entirely,
producing a green suite for a namespace that blew up.

The wrapper instead catches the throwable, records it as an `initializationError` case itself, and
rethrows. Whichever path completes first, the error is in the report. A written-set makes the second
attempt a no-op. Nothing needs a JVM shutdown hook.

This also covers `:monkeypatch-clojure-test false`, where Leiningen installs no catcher at all and
the exception would otherwise abort the run with no `:end-test-ns` for any namespace.

## Never change the outcome of a test run

Reporting is unprotected inside `clojure.test/test-var`: it catches only around the test body. An exception escaping a
reporter surfaces as a bogus "Uncaught exception in test fixture" against the *user's* namespace and skips the rest of
it, leading the user to file a bug against their own code.

Every entry point in the root namespace is therefore wrapped in `try`/`catch Throwable`, reporting
`System/err` and swallowing. Warnings go to `System/err` directly rather than `*err*`, which is
rebound to a capture buffer while a namespace runs.

## Memory

One atom holds `{:suites {ns …} :written #{ns}}`. A suite entry is dissoc'd the moment its file is
written, so peak memory is one namespace rather than the whole run. Only the small written-set
persists.

Documents are rendered whole and written once. Streaming XML into an open file as events arrive is what leaves a
truncated, unclosed root element when anything goes wrong during a run. This structure completely avoids that entire
class of failure.

## Concurrency

Stock `clojure.test` is strictly sequential, and nothing here adds parallelism: the payload is small
and the work is I/O-bound, so concurrency would buy nothing and would break output capture.

What the plugin does do is stay safe if someone runs it under a parallel runner. All accumulated
state is immutable values in one atom, the flush claims a namespace with an atomic `swap-vals!` so
two threads cannot both write it, and events are attributed via `*testing-vars*`, which is
thread-local.

## Testing strategy

| Layer           | Approach                                                                                                                                                                         |
|-----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `impl.xml`      | Unit tests per contract rule, plus `test.check` properties over hostile code points, `]]>`, lone surrogates and control bytes, asserting everything still parses and round trips |
| Locale          | A regression test pinning `time` formatting under a comma-decimal locale, which catches the one defect a US-locale CI box would never detect.                                    |
| `impl.collect`  | Synthetic event maps, including Leiningen's synthesised fixture error, which arrives with no open case and a metadata-carrying symbol instead of a Var                           |
| `impl.config`   | Validation categories, and a round trip through `pr-str`/`read`                                                                                                                  |
| Schema          | Generated XML validated against the vendored schema with `javax.xml.validation`                                                                                                  |
| Instrumentation | A namespace built at runtime with `intern`, so its deliberate failures never reach this project's own summary, exercised through real `clojure.test/test-ns` calls               |
| End to end      | `test_projects/sample`, driven through the actual task                                                                                                                           |

The end-to-end fixture sets `:eval-in :leiningen`, so no `lein install` is needed to resolve the
plugin. That is not a test-only trick: the task omits its self-dependency in that mode anyway,
because Leiningen loads `:dependencies` there the same way it loads plugins and the released jar
would otherwise shadow local source.

Both the instrumentation and end-to-end suites uninstall the instrumentation afterwards. Under
`:eval-in-leiningen` the project JVM *is* the JVM running this project's own tests, so leaving
`clojure.test` wrapped would affect every namespace that runs later and make test ordering
load-bearing.

## Design decisions and trade-offs

### Why not use `clojure.data.xml` or a StAX writer?

The schema is tiny and fixed. JUnit itself uses the JDK's `XMLStreamWriter` and still has to hand-roll illegal-character
replacement, CDATA splitting, and attribute-whitespace escaping on top of it. Consequently, StAX adds indirection
without removing the work, and a dependency would be added to every consuming project for roughly 150 lines of string
building.

### Why not a `robert.hooke` hook or Leiningen middleware?

Both are discouraged in Leiningen 2.13, and hooks have been documented as deprecated since 2.8.0.
Leiningen's own `test.clj` explicitly recommends overriding the test task with an alias instead,
which is exactly the shape offered here.

### Why not capture output per test case?

It would require wrapping `test-var` as well, would still miss fixture output unless `test-ns` were
wrapped too, and would still not catch raw `System/out` writes. Suite-level capture is simple and
honest about what it covers.
