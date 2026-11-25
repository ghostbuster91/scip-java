ThisBuild / organization := "org.scip-code"
ThisBuild / version := "1.0-SNAPSHOT"

// Two Scala 3 subprojects, each referencing a different JDK type. Converting them in one
// merged TASTy context would risk one project's external-symbols shard overwriting the
// other's; each must be indexed -- and its external symbols reported -- independently.
lazy val root = (project in file("."))
  .aggregate(p1, p2)
  .settings(name := "root", scalaVersion := "3.7.4", crossScalaVersions := Nil)

lazy val p1 = (project in file("p1")).settings(name := "p1", scalaVersion := "3.7.4")

lazy val p2 = (project in file("p2")).settings(name := "p2", scalaVersion := "3.7.4")
