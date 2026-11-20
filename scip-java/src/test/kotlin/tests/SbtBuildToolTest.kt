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
