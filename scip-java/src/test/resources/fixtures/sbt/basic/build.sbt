ThisBuild / organization := "org.scip-code"
ThisBuild / version := "1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "example",
    // A Java-only project: no Scala library on the compile classpath and no
    // `_3` suffix on the published artifact.
    crossPaths := false,
    autoScalaLibrary := false,
    libraryDependencies += "junit" % "junit" % "4.13.1" % Test,
  )
