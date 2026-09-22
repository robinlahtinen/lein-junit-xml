# Rationale

*September 2026*

## The problem

CI servers speak JUnit XML. Jenkins, GitLab, GitHub Actions reporters, Buildkite and Azure Pipelines
all read the same broadly-compatible `<testsuite>` dialect, and none of them speak `clojure.test`.
A Clojure project that runs `lein test` in CI has a test suite its build server cannot see.

The existing answer, `lein-test2junit`, has been unmaintained since 2017. Its open issue tracker
reads as a specification of what a replacement has to get right:

- **[#12] Colourised output breaks the generated XML.** ANSI escape sequences are not legal XML 1.0
  characters. Coloured test output is near-universal in Clojure tooling.
- **[#14] Reflection warnings break it.** A partial `Writer` implementation threw an arity exception
  the moment the compiler printed a warning mid-test.
- **[#17] An error in a test reporter produces empty or malformed XML** with no failure indicator —
  so CI reports green for a run that never completed.
- **[#13], [#16] Silent failures and strange terminal output** when a custom reporter is in use.
- **[#10] File and line pointed at the plugin's own source** rather than the failing test.
- **[#2] Test failures did not produce a non-zero exit status.**

Three of these are still open. They are not incidental bugs; they are the consequences of a few
structural choices, and they are the reason this plugin exists rather than a patch.

## The approach

### Keep a functional core

Everything that decides what the XML says is a pure function: `events → suite value → string`. The clock is passed in
rather than read. The consequence is that a complete test run, including a `:once` fixture exploding, can be replayed
from synthetic maps in a REPL, and the character handling can be attacked with generative tests over arbitrary code
points. The parts of this problem that actually bite are all in that core, and now they are all directly testable.

### Render whole, write once

The previous plugin streamed XML into an open file as events arrived, and bound `clojure.test/*test-out*`
to that same file. Two of its worst bugs follow immediately: anything else that printed to that
writer corrupted the document ([#12], [#14]), and any exception mid-run left a truncated file with
an unclosed root element ([#17]).

Building a complete value, rendering a complete string and writing it in one call makes that entire
class of failure unreachable.

### Add methods; replace nothing

`clojure.test/report` is a multimethod. Rebinding it, as the previous plugin did, shadows the hook Leiningen installs to
record failures, which silently empties `.lein-failures` and leaves `lein retest` running nothing, and swallows the
`:summary` event so the familiar "Ran N tests" line never prints. Replacing the var's root instead breaks every
downstream `defmethod`, which is the likely cause of [#13] and [#16].

Adding methods that delegate to the ones they replace leaves everything else working.

### Delegate the test run itself

`lein junit-xml` does not reimplement `lein test`. It merges a profile and calls `leiningen.test/test`. Selectors,
`:test-paths`, `.lein-failures`, the exit-code contract, and the abort behaviour are inherited rather than reproduced,
so they cannot drift. This is how [#2] and [#9] stop being possible.

Leiningen's own `test.clj` recommends exactly this shape, in a comment that also explains why the proposal in [#3] to
"submit this to `clojure.test`" never happened:

> This is a massive and terrible monkeypatch to work around the fact that the built-in clojure.test
> library does not accept patches from outside the core team. […] We recommend that projects
> override the test task with an alias that calls out to a third-party testing library instead.

### Target the parser, not the folklore

There is no official JUnit XML specification; the format spread by convention, and emitters disagree
on almost every optional field. Rather than guess, this plugin was built against two concrete
artifacts: the schema `junit-framework` validates its own legacy reports with, and the actual source
of the Jenkins JUnit plugin's parser. Where they disagree, Jenkins wins and the deviation is
[documented](reference.md).

That is also why some seemingly attractive additions are absent. While `assertions`, `file`, and `line` on `<testcase>`
are common conventions and the data is readily available, no schema permits them, and Jenkins ignores them. Adding them
would trade the one hard, checkable compliance claim for nothing.

## Why not `clojure.test.junit`?

Clojure ships a JUnit XML reporter of its own. `clojure.test.junit` is `{:added "1.1"}`, and its `with-junit-output`
macro wraps a `run-tests` call and emits JUnit-compatible XML. It is the obvious first answer, and it was the prior
art's actual starting point: `test2junit`'s reporter is that file, forked from Clojure 1.5.1 and grown by half again to
supply what was missing. The fork is the evidence: the namespace is close enough to be tempting, and closing the
remaining gap is a rewrite.

- **It writes to a stream, not to files.** `with-junit-output` prints one `<testsuites>` document to
  `clojure.test/*test-out*`, which Leiningen binds to `*out*` around `run-tests`. Consequently, running `lein test`
  sends the XML directly to the terminal. The docstring's remedy, rebinding `*test-out*` to your own writer, produces
  one file for the whole run. There is no seam for the per-namespace `TEST-<ns>.xml` files Jenkins globs for.
- **Its only entry point replaces `report`.** `with-junit-output` binds `clojure.test/report` to a
  separate multimethod. That shadows the hook Leiningen installs, so `.lein-failures` is written
  empty and `lein retest` runs nothing; it bypasses every downstream `defmethod`, including
  `humane-test-output`; and its `:default` method is a no-op, so `:summary` is swallowed and the
  console loses both the failure reports and the "Ran N tests" line. *Add methods; replace nothing*,
  above, is the direct response; the difference is that here it is not a bug but the contract of the
  macro. Exit codes do survive: the report counters are incremented normally, and `run-tests` builds
  its summary from what `test-ns` returns rather than from `report`.
- **The output misses what the schema requires.** Its `<testsuite>` carries only `name` and an optional `package`. It
  omits `tests` (which `jenkins-junit.xsd` marks `use="required"`), counts for `failures`, `errors`, and `skipped`,
  `time` on suite or case, `timestamp`, `hostname`, `<skipped>`, and `<system-out>`/`<system-err>`. As a result, it does
  not validate against the schema JUnit checks its own legacy reports with, and Jenkins reports every test as
  instantaneous.
- **Escaping covers five characters.** `escape-xml` maps `'`, `<`, `>`, `"` and `&`. There is no XML
  1.0 character policy, so one ANSI escape from coloured output reaches Jenkins' strict dom4j parser
  and takes the entire suite down with it. That is [#12], at its source.
- **Failure locations come from `(file-position 5)`**, a hardcoded stack depth deprecated in Clojure 1.2 in favour of
  the `:file` and `:line` keys that `do-report` has merged into the event map ever since. That is [#10], at its source.
- **There is no upstream path.** Several of these are small fixes in principle, but `clojure.test`
  does not accept patches from outside the core team; the comment quoted above says so, and
  recommends precisely the override-the-test-task shape this plugin takes. The namespace last
  changed substantively in 2014. Forking it, as `test2junit` did, also carries EPL-1.0 code into an
  MIT project.

It should be cleared of at least one charge: a single `<testsuites>` root is legal because the schema declares it and
Jenkins merges same-named suites itself, so that is not one of the reasons.

## Trade-offs

**Output is captured per namespace, not per test.** Per-test capture means wrapping more of
`clojure.test`, still missing fixture output, and still missing raw `System/out` writes. Suite-level
capture is simple and honest about its boundaries.

**Raw `System/out` is not captured at all.** Catching Logback or `java.util.logging` output would need `System/setOut`
*plus* an `alter-var-root` on `#'*out*`. That kind of process-global surgery on the user's JVM is unsafe under
`:eval-in :leiningen` and under any parallel runner. Not worth it uninvited.

## Common misconceptions

### "JUnit 6 replaced this format with Open Test Reporting."

JUnit 6.2.0 ships both. Open Test Reporting is opt-in and off by default; the legacy XML writer is
marked `@API(status = STABLE)` and is not deprecated. Jenkins parses only the legacy format.

### "Validating against more schemas is strictly better."

Not when they contradict each other. The Surefire 3.0.2 schema permits no `timestamp` and no
`hostname` on `<testsuite>` and declares no `anyAttribute` — so JUnit 6.2.0's own output fails it.
Satisfying both would mean discarding data Jenkins actually uses.

### "The XML files should be consolidated into one."

Jenkins globs `**/TEST-*.xml` and merges same-named suites itself ([#19]). One file per namespace is
also what bounds memory and what lets a crashed run keep the results it already produced.

## Credits

The format research rests on the JUnit team's `XmlReportWriter`, the Jenkins JUnit plugin's
`SuiteResult` and `CaseResult`, and the community conventions catalogued by
[testmoapp/junitxml](https://github.com/testmoapp/junitxml). `lein-test2junit` by Ruediger Gad is
the prior art this replaces, and its issue tracker shaped the requirements; `clojure.test.junit` by
Jason Sankey is the common ancestor both of them start from.

[#2]: https://github.com/ruedigergad/test2junit/issues/2

[#3]: https://github.com/ruedigergad/test2junit/issues/3

[#9]: https://github.com/ruedigergad/test2junit/issues/9

[#10]: https://github.com/ruedigergad/test2junit/issues/10

[#12]: https://github.com/ruedigergad/test2junit/issues/12

[#13]: https://github.com/ruedigergad/test2junit/issues/13

[#14]: https://github.com/ruedigergad/test2junit/issues/14

[#16]: https://github.com/ruedigergad/test2junit/issues/16

[#17]: https://github.com/ruedigergad/test2junit/issues/17

[#19]: https://github.com/ruedigergad/test2junit/issues/19
