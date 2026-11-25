package org.scip_code.scip_java.buildtools

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import org.scip_code.scip_java.Embedded
import org.scip_code.scip_java.commands.IndexCommand
import org.scip_code.scip_java.semanticdb.SemanticdbToScip
import org.scip_code.scip_java.tasty.TastyToScip
import org.scip_code.scip_java.tasty.TastyVersion

/**
 * Indexes an sbt build by pointing sbt's `javaHome` at scip-java's `javac` wrapper: zinc forks
 * `$javaHome/bin/javac` for every Java compilation whenever that setting is defined, and the
 * wrapper adds `-Xplugin:scip` to the compiler options.
 *
 * Scala 3 sources are indexed from TASTy, which every Scala 3 compilation emits next to the class
 * files whether or not the build asks for it, so [TastyToScip] needs nothing from sbt beyond being
 * told where the class directories and the compile classpath are. SemanticDB then fills in only the
 * sources TASTy could not describe -- Scala 2 rows, and anything whose TASTy this build of
 * scip-java is too old to read.
 *
 * The `javaHome` setting, a command that compiles every project and the metadata used for
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
        if (result.exitCode == 0) {
            // TASTy first, and SemanticDB only for what it did not cover. Both derive a shard
            // path from the source path alone, so letting them both write the same source would
            // silently overwrite one with the other.
            val unsupported = TastyVersion.unsupported(scalaVersions())
            if (unsupported.isNotEmpty()) {
                // Reported rather than worked around. TASTy rejects a newer minor outright, so
                // carrying on would quietly produce an index describing none of the Scala, and
                // version skew is handled by using the matching scip-java branch instead.
                index.app.error(TastyVersion.mismatchMessage(unsupported))
                return 1
            }
            val covered = convertTasty()
            convertSemanticdb(covered)
            reportMissingScipOutput()
        }
        return generateScipFromTargetroot(result, targetroot(), index)
    }

    /**
     * Turns the SemanticDB files scalac emitted into SCIP shards, which is how Scala sources get
     * indexed. The directories to read come from `*.semanticdb-roots.txt` files that the injected
     * sbt plugin writes, one per project.
     */
    private fun convertSemanticdb(alreadyCovered: Set<String>) {
        val roots =
            readReportedPaths(".semanticdb-roots.txt").filter { Files.isDirectory(it) }.distinct()
        if (roots.isEmpty()) return
        val result =
            SemanticdbToScip(index.workingDirectory, targetroot())
                .skipping(alreadyCovered)
                .convert(roots)
        // Warnings, not errors: each one costs at most one source file's worth of
        // index, and reporting them as errors would stop the aggregator from
        // writing an index for everything that did convert.
        result.warnings.take(MAX_REPORTED_WARNINGS).forEach { index.app.warning(it) }
        val unreported = result.warnings.size - MAX_REPORTED_WARNINGS
        if (unreported > 0) {
            index.app.warning("$unreported more SemanticDB conversion warning(s) not shown")
        }
        if (result.shardsWritten > 0) {
            index.app.info("converted ${result.shardsWritten} SemanticDB file(s) into SCIP shards")
        }
    }

    /**
     * The Scala 3 versions this build compiles against, read back from the coordinates the injected
     * plugin reported.
     *
     * `scala3-library_3` is on the classpath of every Scala 3 project, and its revision is the
     * Scala version, so no extra reporting is needed to find out whether this build of scip-java
     * can read the TASTy that was produced.
     */
    private fun scalaVersions(): List<String> {
        val targetroot = targetroot()
        if (!Files.isDirectory(targetroot)) return emptyList()
        return Files.list(targetroot)
            .use { stream ->
                stream
                    .filter {
                        Files.isRegularFile(it) &&
                            it.fileName.toString().endsWith(".dependencies.txt")
                    }
                    .toList()
            }
            .flatMap { Files.readAllLines(it) }
            .mapNotNull { line ->
                val fields = line.split("\t")
                if (fields.size >= 3 && fields[1] == "scala3-library_3") fields[2] else null
            }
            .distinct()
    }

    /**
     * Turns the TASTy the Scala 3 compiler emitted into SCIP shards, and reports which sources it
     * covered so [convertSemanticdb] can leave those alone.
     *
     * Converted one sbt project at a time, each with its own `tastyProjectKeys()` classpath, rather
     * than merging every subproject's classpath into one `TastyToScip.convert` call: a merged
     * classpath holds every subproject's dependency jars at once, which is both wasted peak memory
     * (`ClasspathLoaders.read` loads every entry eagerly) and a correctness risk -- if two
     * subprojects depend on different versions of the same library, only one version's classfiles
     * can occupy one merged classpath, and the other subproject resolves against the wrong one.
     */
    private fun convertTasty(): Set<String> {
        val keys = tastyProjectKeys()
        if (keys.isEmpty()) return emptySet()
        val covered = mutableSetOf<String>()
        val warnings = mutableListOf<String>()
        var written = 0
        for (key in keys) {
            // The class directories and the compile classpath both come from
            // `<key>.tasty-roots.txt` and `<key>.tasty-classpath.txt`, which the injected sbt
            // plugin writes one pair of per project. The classpath is needed because TASTy
            // leaves the types of most subexpressions implicit, to be reconstructed from the
            // classpath, so it cannot be read without one.
            val classDirs =
                readReportedPath("$key.tasty-roots.txt").filter { Files.isDirectory(it) }.distinct()
            if (classDirs.isEmpty()) continue
            // This project's own class directory also appears on its own reported classpath, so
            // it is reported twice. Dropping the duplicate keeps `ClasspathLoaders.read` -- which
            // loads every entry eagerly into memory -- from holding the same classes twice, and
            // keeps the directory from being enumerated as both a project and a dependency.
            val classpath =
                (readReportedPath("$key.tasty-classpath.txt").distinct() - classDirs.toSet()) +
                    jdkClasspath()

            val result =
                TastyToScip.convert(index.workingDirectory, targetroot(), classDirs, classpath, key)
            // Accessors on the Scala-defined result are methods, not Kotlin properties.
            warnings += result.warnings()
            written += result.shardsWritten()
            covered += result.coveredSources()
        }
        // Warnings, not errors, for the same reason as SemanticDB: the worst a file this cannot
        // read should cost is its own document, and SemanticDB may well cover it anyway.
        warnings.take(MAX_REPORTED_WARNINGS).forEach { index.app.warning(it) }
        val unreported = warnings.size - MAX_REPORTED_WARNINGS
        if (unreported > 0) {
            index.app.warning("$unreported more TASTy conversion warning(s) not shown")
        }
        if (written > 0) {
            index.app.info("indexed $written Scala 3 source(s) from TASTy")
        }
        return covered
    }

    /**
     * One key per sbt project that reported TASTy output, matching `ScipSbtPlugin.write`'s
     * `<sanitized-projectId>-<hash(baseDir)>` naming.
     */
    private fun tastyProjectKeys(): List<String> {
        val targetroot = targetroot()
        if (!Files.isDirectory(targetroot)) return emptyList()
        return Files.list(targetroot).use { stream ->
            stream
                .filter {
                    Files.isRegularFile(it) && it.fileName.toString().endsWith(".tasty-roots.txt")
                }
                .map { it.fileName.toString().removeSuffix(".tasty-roots.txt") }
                .toList()
        }
    }

    /**
     * The JRE, which tasty-query requires on the classpath explicitly -- it resolves even
     * `java.lang` through the classpath it is given and has no implicit view of the platform.
     *
     * Every module is included, not just `java.base`: a source referencing `java.sql`, `java.xml`,
     * `java.net.http`, `java.logging` or `java.management` would otherwise fail to resolve against
     * the classpath tasty-query is given.
     */
    private fun jdkClasspath(): List<Path> {
        val modulesRoot = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("modules")
        return Files.list(modulesRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }.toList()
        }
    }

    /** Reads the line-separated paths out of every `*<suffix>` file the sbt plugin wrote. */
    private fun readReportedPaths(suffix: String): List<Path> {
        val targetroot = targetroot()
        if (!Files.isDirectory(targetroot)) return emptyList()
        return Files.list(targetroot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(suffix) }
                .toList()
                .flatMap(::readPathLines)
        }
    }

    /** Reads the line-separated paths out of one specific file the sbt plugin wrote. */
    private fun readReportedPath(filename: String): List<Path> {
        val file = targetroot().resolve(filename)
        return if (Files.isRegularFile(file)) readPathLines(file) else emptyList()
    }

    private fun readPathLines(file: Path): List<Path> =
        Files.readAllLines(file).filter { it.isNotBlank() }.map { Paths.get(it.trim()) }

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
                "This is expected for a build with no Java or Scala sources. Otherwise, the most " +
                "likely cause is that the build defines `javaHome` or `semanticdbEnabled` itself, " +
                "overriding the settings scip-java indexes through."
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
            // Make scalac emit SemanticDB, which `convertSemanticdb` turns into SCIP
            // shards. sbt has built-in support for this, so no plugin wiring is needed.
            command += "-Dsbt.semanticdb=true"
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
        /** Enough to see the shape of a problem without burying the build log. */
        private const val MAX_REPORTED_WARNINGS: Int = 10

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
