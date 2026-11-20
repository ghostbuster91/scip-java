ThisBuild / organization := "org.scip-code"
ThisBuild / version := "1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "example",
    scalaVersion := "3.7.4",
  )
