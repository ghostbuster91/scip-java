# SCIP snapshots

This directory contains end-to-end snapshot fixtures for `scip-javac`,
`scip-kotlin-analysis`, and `scip-tasty` (including Java interop cases).

## Layout

```text
cases/
  java/common/      Java fixtures compiled with `--release 17`.
  java-25/          Java 25 preview/language-feature fixtures (JDK 25+).
  kotlin/common/    Kotlin fixtures, including Java interop consumers.
  scala3/common/    Scala 3 TASTy fixtures, including Java interop.

expected/
  java/common/      Golden snapshots for `cases/java/common`.
  java-25/          Golden snapshots for `cases/java-25`.
  kotlin/common/    Golden snapshots for `cases/kotlin/common`.
  scala3/common/    Golden snapshots for `cases/scala3/common`.
```

`common` cases should stay stable across the supported host JDK matrix. Add
version-specific cases when a fixture needs newer language features, for example
`cases/java-25`.

Run `gradle :scip-snapshots:test` to compare goldens and
`gradle :scip-snapshots:saveSnapshots` to regenerate them.
