# Contributing

Thanks for your interest in improving lein-junit-xml. This document covers how to get set up and
what the project expects from a change.

Please read the [code of conduct](CODE_OF_CONDUCT.md) first.

## Ways to contribute

- **Report a bug.** A failing `project.clj` snippet and the emitted XML are worth more than a
  description.
- **Report a CI incompatibility.** If a build server misreads a report this plugin produced, that is
  a bug here even when the XML is schema-valid. Say which tool and version.
- **Improve the docs.** The [reference](doc/reference.md) is meant to be exhaustive about edge cases;
  gaps in it are defects.
- **Send a patch.** Small, focused changes with tests are easiest to review.

## Getting started

```bash
git clone https://github.com/robinlahtinen/lein-junit-xml.git
cd lein-junit-xml
lein test
```

You need Leiningen 2.10.0+, a JDK, and [clj-kondo](https://github.com/clj-kondo/clj-kondo) on your path.
2.10.0 is where this repository's own suite stops passing: on 2.9.10 two end-to-end tests fail and 2.8.3 aborts.
`lein test`, `lein cljfmt check`, `lein install` and `lein test :integration` were all verified on 2.10.0.
Consumers of the plugin can use older Leiningen than contributors can
(see [the reference](doc/reference.md#requirements)).

The project sets `:eval-in-leiningen true`, so `lein test` runs the suite inside Leiningen's own JVM
against the real Leiningen APIs.

## Making changes

1. Branch from `main`.
2. Write the change, and a test that fails without it.
3. Run `lein test`, `lein cljfmt fix` and `clj-kondo --lint src test project.clj`.
4. Update `CHANGELOG.md` under `## [Unreleased]`.
5. Open a pull request describing what changed and why.

## Coding guidelines

**Do** keep the functional core pure. `impl.config`, `impl.collect` and `impl.xml` take values and
return values, and take the clock as an argument rather than reading it. That is what makes the
whole reporting path testable from a REPL, and it is not negotiable.

**Do** ground behavioural claims in a source you can cite. This plugin exists because the JUnit XML
format has no specification; decisions here are made against `junit-framework`'s own writer, the
vendored schema, and the Jenkins JUnit plugin's parser. If you change what the XML looks like, say
which of those you checked.

**Do** follow [the Clojure Style Guide](https://github.com/bbatsov/clojure-style-guide) and the
conventions already visible in the source: `!` for side-effecting or throwing functions, `?` for
predicates, `defn-` for private functions, house-format docstrings, and the copyright header plus
`(set! *warn-on-reflection* true)` in every file.

**Do** keep errors in the stable contract: `ex-info` carrying
`:com.github.robinlahtinen.lein-junit-xml/error` and
`:com.github.robinlahtinen.lein-junit-xml/context`, with unqualified data keys and no `:type` key.

**Don't** let anything in the reporting path throw. A bug in a reporter must never change whether
someone's tests pass; wrap new entry points in the existing `guard`.

**Don't** add a runtime dependency. The plugin ships with none, and anything added here lands on the
classpath of every project that uses it.

**Don't** use `gen-class`, `defonce`, `requiring-resolve`, top-level `resolve` or `eval` in `src/`.
CI rejects all of them.

**Don't** overuse vertical whitespace.

## Testing

```bash
lein test                                                         # everything except the integration suite
lein test com.github.robinlahtinen.lein-junit-xml.impl.xml-test   # one namespace
lein install && lein test :integration                            # the real subprocess path
lein cljfmt check
clj-kondo --lint src test project.clj
```

The suite has layers worth knowing about:

- **Property tests** (`impl/xml_property_test.clj`) generate hostile strings such as control bytes,
  ANSI escapes, unpaired surrogates, and `]]>`, asserting that every document still parses.
  If you touch the sanitiser or the emitter, these are the tests that matter.
- **Schema tests** (`schema_test.clj`) validate generated XML against the vendored schema. This is
  the check that substantiates the compliance claim.
- **A locale test** pins `time` formatting under a comma-decimal locale. Do not remove it; a
  US-locale CI box will not catch that class of bug.
- **End-to-end tests** (`e2e_test.clj`) drive the real task against `test_projects/sample` under
  `:eval-in :leiningen`.
- **Integration tests** (`integration_test.clj`) shell out to `lein junit-xml` in a throwaway project
  on the default `:subprocess` path, which is the only suite that exercises Leiningen resolving the
  published coordinate. They are excluded from the default selector because they need
  `lein install` first.
- **A direct-linking regression test** asserts that `report` methods observe every event and that a
  `do-report` wrapper would not. That is the assumption the whole instrumentation rests on, and it
  fails silently and partially if broken.

Tests that install the instrumentation must uninstall it in a `:once` fixture. Under
`:eval-in-leiningen` the project JVM is the JVM running this suite, so leaving `clojure.test` wrapped
makes test ordering load-bearing.

## Commit messages

[Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/), with the types `feat`,
`fix`, `docs`, `refactor`, `test`, `chore` and `perf`:

```
fix(impl): split CDATA payloads containing ]]>
```

## Pull request guidelines

**Do** keep the diff focused on one thing, and explain the reasoning rather than restating the diff.

**Do** note explicitly if you changed the emitted XML, and which consumer you verified against.

**Don't** bundle formatting churn with behavioural change.

## Releasing (maintainers)

1. Update `CHANGELOG.md`, moving `## [Unreleased]` entries under the new version.
2. Bump the version in `project.clj` **and** `leiningen.junit-xml/version`. A test enforces that
   they match; the task needs a resolvable coordinate before the project JVM starts, so the
   duplication is unavoidable.
3. Bump the version shown in `README.md` and `doc/user-guide.md`.
4. `git commit -am "chore(release): prepare v0.1.0"`
5. `git tag v0.1.0`
6. `git push --follow-tags` — the release workflow deploys to Clojars.
7. `git commit -am "chore(release): prepare next development cycle"` after bumping to the next
   `-SNAPSHOT`.

## Questions?

Open a [discussion](https://github.com/robinlahtinen/lein-junit-xml/discussions) or file
an [issue](https://github.com/robinlahtinen/lein-junit-xml/issues) if you have questions.
