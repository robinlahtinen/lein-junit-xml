---
name: Bug report
about: Report incorrect or missing output from lein-junit-xml
title: "[BUG] "
labels: bug
assignees: ""
---

## What happened

A clear description of the incorrect behaviour.

## What you expected

## Environment

| Component      | Version                                         |
|----------------|-------------------------------------------------|
| lein-junit-xml | e.g., 0.1.0                                     |
| Leiningen      | e.g., 2.13.0                                    |
| Clojure        | e.g., 1.12.6                                    |
| Java           | e.g., 26.0.2.1                                  |
| OS             | e.g., Windows 11, Ubuntu 24.04                  |
| CI consumer    | e.g., Jenkins JUnit plugin 1428.vef95b_fa_89508 |

## Configuration

```clojure
;; The :plugins and :junit-xml entries from your project.clj
```

## Reproduction

Steps, or a minimal test namespace that triggers it.

<details>
<summary>Emitted XML</summary>

```xml
<!-- The contents of the TEST-*.xml file, or the relevant part of it -->
```

</details>

<details>
<summary>Console output</summary>

```
<!-- Output from the lein junit-xml run, including any lein-junit-xml: warnings -->
```

</details>

## Notes

If a CI server misread the report, please say which tool and version, and what it displayed. A
schema-valid file that a build server reads wrongly is still a bug here.
