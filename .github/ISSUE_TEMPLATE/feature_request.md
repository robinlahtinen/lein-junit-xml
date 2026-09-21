---
name: Feature request
about: Suggest an improvement to lein-junit-xml
title: "[FEATURE] "
labels: enhancement
assignees: ""
---

## The problem

What are you trying to do that the plugin makes difficult today?

## Proposed solution

## Which consumer needs this

The plugin targets the JUnit legacy XML that CI servers read, and the format has no official specification.
The fastest route to agreement is to name the tool that needs the change, ideally identifying the code or
schema in it that requires the behaviour.

## Alternatives considered

## Notes

Some things are deliberately out of scope; see [the reference](../../doc/reference.md) and
[the rationale](../../doc/rationale.md):

- per-test `<system-out>` capture
- capturing output written directly to `System/out`
- consolidating report files into one
- the Open Test Reporting format
