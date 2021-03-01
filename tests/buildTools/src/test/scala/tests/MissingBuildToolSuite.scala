package tests

class MissingBuildToolSuite extends BaseBuildToolSuite {
  checkErrorOutput(
    "basic",
    List(),
    expectedOutput =
      """|error: No build tool detected in workspace '/private/workingDirectory'. At the moment, the only supported build tools are: Gradle, Maven.
         |""".stripMargin,
    workingDirectoryLayout = ""
  )

  checkErrorOutput(
    "ambiguous",
    List(),
    expectedOutput =
      """|error: Multiple build tools detected: Gradle, Maven. To fix this problem, use the '--build-tools=BUILD_TOOL_NAME' flag to specify which build tool to run.
         |""".stripMargin,
    workingDirectoryLayout =
      """|/pom.xml
         |<hello/>
         |/build.gradle
         |def foo= 42
         |""".stripMargin
  )

  checkErrorOutput(
    "no-matching-explicit",
    List("--build-tool", "Gradle"),
    expectedOutput =
      """|error: Automatically detected the build tool(s) Maven but none of them match the explicitly provided flag '--build-tool=Gradle'. To fix this problem, run again with the --build-tool flag set to one of the detected build tools.
         |""".stripMargin,
    workingDirectoryLayout =
      """|/pom.xml
         |<hello/>
         |""".stripMargin
  )

  checkErrorOutput(
    "levenshtein",
    List("--build-tool", "Mave"),
    expectedOutput =
      """|error: Automatically detected the build tool(s) Maven but none of them match the explicitly provided flag '--build-tool=Mave'. Did you mean --build-tool=Maven?
         |""".stripMargin,
    workingDirectoryLayout =
      """|/pom.xml
         |<hello/>
         |""".stripMargin
  )
}
