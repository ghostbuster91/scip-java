ThisBuild / organization := "org.scip-code"
ThisBuild / version := "1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .aggregate(both, newer)
  .settings(name := "root", crossScalaVersions := Nil)

// Indexable at either version.
lazy val both = (project in file("both"))
  .settings(
    name := "both",
    scalaVersion := "3.7.4",
    crossScalaVersions := Seq("3.3.9", "3.7.4"),
  )

// Only builds at the newer version, so indexing at 3.3.9 has to leave it out:
// `++` would not switch it, and compiling it against switched dependencies
// would mix two incompatible Scala versions.
lazy val newer = (project in file("newer"))
  .settings(
    name := "newer",
    scalaVersion := "3.7.4",
    crossScalaVersions := Seq("3.7.4"),
  )
