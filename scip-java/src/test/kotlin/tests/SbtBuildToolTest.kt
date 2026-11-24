package tests

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class SbtBuildToolTest : BuildToolHarness() {

    @TestFactory
    fun tests(): List<DynamicTest> =
        listOf(
            checkBuild(
                "basic",
                fixture = "sbt/basic",
                expectedScipFiles = 2,
                // sbt has no wrapper script to fall back on, unlike Maven and Gradle.
                requiresCommand = "sbt",
                // The default location, which `sbt clean` wipes along with the
                // rest of the root project's `target/` before compiling.
                targetRoot = "target/scip-targetroot",
                expectedPackages =
                    """
                    |maven:junit:junit:4.13.1
                    |maven:org.hamcrest:hamcrest-core:1.3
                    |maven:org.scip-code:example:1.0-SNAPSHOT
                    |"""
                        .trimMargin(),
            ),
            checkBuild(
                "build-command",
                fixture = "sbt/basic",
                // `Compile / compile` leaves out the test sources that
                // `scipCompileAll` would also index.
                expectedScipFiles = 1,
                requiresCommand = "sbt",
                extraArguments = listOf("--", "clean", "Compile/compile"),
            ),
            checkBuild(
                "multi-project",
                fixture = "sbt/multi-project",
                // One shard per project, including the one the root project
                // does not aggregate.
                expectedScipFiles = 2,
                requiresCommand = "sbt",
                expectedPackages =
                    """
                    |maven:org.scip-code:a:1.0-SNAPSHOT
                    |maven:org.scip-code:b:1.0-SNAPSHOT
                    |maven:org.scip-code:root:1.0-SNAPSHOT
                    |"""
                        .trimMargin(),
            ),
            checkBuild(
                "scala",
                fixture = "sbt/scala",
                // One shard from TASTy for the Scala source, one from the javac
                // plugin for the Java source.
                expectedScipFiles = 2,
                requiresCommand = "sbt",
                // The count alone cannot distinguish the two Scala front-ends: if TASTy wrote
                // nothing, SemanticDB would cover the same source and the count would be
                // unchanged. Assert that TASTy is the one that did it.
                expectedOutputContains = listOf("indexed 1 Scala 3 source(s) from TASTy"),
                expectedPackages =
                    """
                    |maven:org.scala-lang:scala-library:2.13.16
                    |maven:org.scala-lang:scala3-library_3:3.7.4
                    |maven:org.scip-code:example_3:1.0-SNAPSHOT
                    |"""
                        .trimMargin(),
            ),
            checkBuild(
                // `scipCompileAll <version>` switches to that version and leaves
                // out the projects that do not declare it, so only the
                // cross-built project is indexed here.
                "cross-version",
                fixture = "sbt/cross-version",
                expectedScipFiles = 1,
                requiresCommand = "sbt",
                extraArguments = listOf("--", "scipCompileAll 3.3.9"),
                expectedPackages =
                    """
                    |maven:org.scala-lang:scala-library:2.13.18
                    |maven:org.scala-lang:scala3-library_3:3.3.9
                    |maven:org.scip-code:both_3:1.0-SNAPSHOT
                    |"""
                        .trimMargin(),
            ),
            checkErrorOutput(
                "unsupported-version",
                listOf("index"),
                expectedOutput =
                    "error: Unsupported sbt version '1.11.3'. scip-java can only index sbt 2.1 " +
                        "and newer. To fix this problem, set 'sbt.version=2.1.0' or newer in " +
                        "'project/build.properties'.",
                fixture = "sbt/unsupported-version",
            ),
            checkErrorOutput(
                "missing-version",
                listOf("index"),
                expectedOutput =
                    "error: No sbt version detected. To fix this problem, run the following " +
                        "command and try again: echo 'sbt.version=2.1.0' >> project/build.properties",
                fixture = "sbt/missing-version",
            ),
        )
}
