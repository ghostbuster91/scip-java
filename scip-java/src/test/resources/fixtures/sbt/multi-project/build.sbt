ThisBuild / organization := "org.scip-code"
ThisBuild / version := "1.0-SNAPSHOT"

// Java-only projects: no Scala library on the compile classpath and no `_3`
// suffix on the published artifacts.
lazy val javaOnly = Seq(crossPaths := false, autoScalaLibrary := false)

lazy val root = (project in file("."))
  // `b` is deliberately left out, so that indexing it proves `scipCompileAll`
  // does not rely on aggregation.
  .aggregate(a)
  .settings(name := "root")
  .settings(javaOnly)

lazy val a = (project in file("a")).settings(name := "a").settings(javaOnly)

lazy val b = (project in file("b")).settings(name := "b").settings(javaOnly)
