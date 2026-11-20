package org.scip_code.scip_java.buildtools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import org.scip_code.scip_java.Embedded
import org.scip_code.scip_java.commands.IndexCommand

/**
 * Indexes an sbt build by pointing sbt's `javaHome` at scip-java's `javac` wrapper: zinc forks
 * `$javaHome/bin/javac` for every Java compilation whenever that setting is defined, and the
 * wrapper adds `-Xplugin:scip` to the compiler options.
 *
 * The setting, a command that compiles every project and the dependency dump used for
 * cross-repository navigation all come from an `AutoPlugin` that gets injected into the build with
 * `-Dsbt.global.plugins`, so nothing in the repository is modified. See
 * `scip-java/ScipSbtPlugin.scala`.
 */
class SbtBuildTool(index: IndexCommand) : BuildTool("sbt", index) {

    override fun usedInCurrentDirectory(): Boolean =
        Files.isRegularFile(index.workingDirectory.resolve("build.sbt")) ||
            Files.isRegularFile(buildProperties)

    override fun generateScip(): Int {
        val version = sbtVersion()
        if (version == null) {
            index.app.error(
                "No sbt version detected. To fix this problem, run the following command and try again: " +
                    "echo 'sbt.version=$MINIMUM_VERSION.0' >> project/build.properties"
            )
            return 1
        }
        val unsupported = unsupportedVersionMessage(version)
        if (unsupported != null) {
            index.app.error(unsupported)
            return 1
        }
        val result = runBuild()
        if (result.exitCode == 0) reportMissingScipOutput()
        return generateScipFromTargetroot(result, targetroot(), index)
    }

    fun targetroot(): Path = index.finalTargetroot(defaultTargetroot)

    private val defaultTargetroot: Path = Paths.get("target", "scip-targetroot")

    private val buildProperties: Path
        get() = index.workingDirectory.resolve("project").resolve("build.properties")

    private fun sbtVersion(): String? {
        if (!Files.isRegularFile(buildProperties)) return null
        val properties = Properties()
        Files.newInputStream(buildProperties).use { properties.load(it) }
        return properties.getProperty("sbt.version")?.trim()?.ifEmpty { null }
    }

    /**
     * Diagnoses the case where sbt succeeded without our compiler plugin writing a single shard,
     * which the aggregator would otherwise only report as "No SCIP shards found".
     */
    private fun reportMissingScipOutput() {
        if (containsFileWithSuffix(targetroot(), ".scip")) return
        index.app.warning(
            "sbt finished successfully but produced no SCIP shards in ${targetroot()}. " +
                "scip-java only indexes Java sources, so this is expected for a build that has none. " +
                "Otherwise, the most likely cause is that the build defines `javaHome` itself, which " +
                "overrides the `javac` wrapper that scip-java indexes through."
        )
    }

    private fun containsFileWithSuffix(root: Path, suffix: String): Boolean {
        if (!Files.isDirectory(root)) return false
        return try {
            Files.find(
                    root,
                    Integer.MAX_VALUE,
                    { path, attributes ->
                        attributes.isRegularFile && path.fileName.toString().endsWith(suffix)
                    },
                )
                .use { stream -> stream.findFirst().isPresent }
        } catch (_: Exception) {
            false
        }
    }

    private fun runBuild(): ProcessResult =
        TemporaryFiles.withDirectory(index) { tmp ->
            // `scipCompileAll` cleans the projects it compiles, which covers the
            // default targetroot under `target/`, but an explicit `--targetroot`
            // can live anywhere and would otherwise keep stale shards around.
            targetroot().toFile().deleteRecursively()
            val javac =
                Embedded.customJavac(
                    sourceroot = index.workingDirectory,
                    targetroot = targetroot(),
                    tmp = tmp,
                    // The injected plugin reports dependencies through
                    // `dependencies.txt`, which also covers the coordinates of the
                    // sbt projects themselves. Keep `javacopts.txt` out of the
                    // targetroot so it doesn't take precedence over it.
                    javacopts = tmp.resolve("javacopts.txt"),
                )
            val command = mutableListOf<String>()
            command += sbtScript()
            // sbt 2 defaults to the native thin client, which forwards the build to
            // a server that outlives this process and that may already be running
            // with different system properties. `--server` runs sbt in the
            // foreground instead.
            command += "--server"
            command += "--batch"
            command += "--no-colors"
            command += "-Dsbt.supershell=false"
            command += "-Dsbt.global.plugins=${Embedded.sbtGlobalPluginsDirectory(tmp)}"
            command += "-Dscip.javaHome=${javac.javaHome}"
            command += "-Dscip.targetroot=${targetroot()}"
            command += index.finalBuildCommand(listOf("scipCompileAll"))

            val exit = index.app.runProcess(command, env = javac.environment)
            Embedded.reportUnexpectedJavacErrors(index.app.reporter, tmp) ?: exit
        }

    /** Prefers a launcher script vendored in the repository, as Maven and Gradle do. */
    private fun sbtScript(): String {
        val local = index.workingDirectory.resolve("sbt")
        return if (Files.isRegularFile(local) && Files.isExecutable(local)) local.toString()
        else "sbt"
    }

    companion object {
        /**
         * The oldest supported sbt version. sbt 1.x was supported through the `sbt-sourcegraph`
         * plugin, which no longer exists.
         */
        const val MINIMUM_VERSION: String = "2.1"

        /** Returns an error message when [version] is not an sbt version scip-java can index. */
        fun unsupportedVersionMessage(version: String): String? {
            val segments = version.takeWhile { it.isDigit() || it == '.' }.split('.')
            val major = segments.getOrNull(0)?.toIntOrNull()
            val minor = segments.getOrNull(1)?.toIntOrNull()
            if (major == null || minor == null) {
                return "Failed to parse the sbt version '$version' in 'project/build.properties'. " +
                    "To fix this problem, set 'sbt.version' to $MINIMUM_VERSION.0 or newer."
            }
            if (major < 2 || (major == 2 && minor < 1)) {
                return "Unsupported sbt version '$version'. scip-java can only index sbt $MINIMUM_VERSION and newer. " +
                    "To fix this problem, set 'sbt.version=$MINIMUM_VERSION.0' or newer in 'project/build.properties'."
            }
            return null
        }
    }
}
