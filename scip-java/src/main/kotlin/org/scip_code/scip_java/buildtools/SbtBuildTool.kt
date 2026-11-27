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
            // TASTy first, and SemanticDB only for what it did not cover, project by project.
            // Both derive a shard path from the source path alone, so letting them both write the
            // same source would silently overwrite one with the other.
            val unsupported = TastyVersion.unsupported(scalaVersions())
            if (unsupported.isNotEmpty()) {
                // Reported rather than worked around. TASTy rejects a newer minor outright, so
                // carrying on would quietly produce an index describing none of the Scala, and
                // version skew is handled by using the matching scip-java branch instead.
                index.app.error(TastyVersion.mismatchMessage(unsupported))
                return 1
            }
            convertScala()
            reportMissingScipOutput()
        }
        return generateScipFromTargetroot(result, targetroot(), index)
    }

    /**
     * Turns what the Scala compilers emitted into SCIP shards, one sbt project at a time: TASTy for
     * Scala 3, then SemanticDB for whatever TASTy did not cover -- Scala 2 sources, and any whose
     * TASTy this build of scip-java is too old to read.
     *
     * Projects are converted most preferred first (see [rankProjects]), and every source a project
     * converted is skipped by the projects after it. A shard path is a function of the source path
     * alone, so when cross-built projects compile one shared source directory only one of them can
     * describe it; this makes that the project on the build's default Scala version rather than
     * whichever happened to be converted last.
     *
     * Each project's shards are recorded in `<key>.shards.txt`, and the ranking in `projects.txt`,
     * so the aggregator attributes each document against the classpath of the project that wrote
     * it.
     */
    private fun convertScala() {
        val ranking = rankProjects(readScalaVersions())
        val keys = (tastyProjectKeys() + semanticdbProjectKeys()).distinct()
        val ordered = ranking.filter { it in keys } + (keys - ranking.toSet()).sorted()
        val covered = mutableSetOf<String>()
        val tasty = Conversions("TASTy")
        val semanticdb = Conversions("SemanticDB")
        for (key in ordered) {
            val shards = mutableListOf<Path>()
            convertTasty(key, covered)?.let { result ->
                tasty.add(result.shardsWritten(), result.warnings())
                covered += result.coveredSources()
                shards += result.writtenShards()
            }
            convertSemanticdb(key, covered)?.let { result ->
                semanticdb.add(result.shardsWritten, result.warnings)
                covered += result.coveredSources
                shards += result.writtenShards
            }
            if (shards.isNotEmpty()) {
                writeLines(
                    key + BuildProjects.SHARDS_SUFFIX,
                    shards.map { targetroot().relativize(it).toString() },
                )
            }
        }
        if (ranking.isNotEmpty()) writeLines(BuildProjects.PREFERENCE_FILE, ranking)
        tasty.report { "indexed $it Scala 3 source(s) from TASTy" }
        semanticdb.report { "converted $it SemanticDB file(s) into SCIP shards" }
    }

    /** Shards written and warnings collected across every project, reported once at the end. */
    private inner class Conversions(private val name: String) {
        private var written = 0
        private val warnings = mutableListOf<String>()

        fun add(shards: Int, more: List<String>) {
            written += shards
            warnings += more
        }

        // Warnings, not errors: each one costs at most one source file's worth of index, and
        // reporting them as errors would stop the aggregator from writing an index for everything
        // that did convert.
        fun report(summary: (Int) -> String) {
            warnings.take(MAX_REPORTED_WARNINGS).forEach { index.app.warning(it) }
            val unreported = warnings.size - MAX_REPORTED_WARNINGS
            if (unreported > 0) {
                index.app.warning("$unreported more $name conversion warning(s) not shown")
            }
            if (written > 0) index.app.info(summary(written))
        }
    }

    /**
     * Converts the SemanticDB files of one project, read from the `<key>.semanticdb-roots.txt` the
     * injected sbt plugin wrote for it, skipping sources an earlier conversion covered.
     */
    private fun convertSemanticdb(key: String, covered: Set<String>): SemanticdbToScip.Result? {
        val roots =
            readReportedPath("$key.semanticdb-roots.txt")
                .filter { Files.isDirectory(it) }
                .distinct()
        if (roots.isEmpty()) return null
        return SemanticdbToScip(index.workingDirectory, targetroot())
            .skipping(covered)
            .convert(roots)
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
     * Converts the TASTy of one project, skipping sources an earlier conversion covered.
     *
     * Converted one sbt project at a time, each with its own classpath, rather than merging every
     * subproject's classpath into one `TastyToScip.convert` call: a merged classpath holds every
     * subproject's dependency jars at once, which is both wasted peak memory
     * (`ClasspathLoaders.read` loads every entry eagerly) and a correctness risk -- if two
     * subprojects depend on different versions of the same library, only one version's classfiles
     * can occupy one merged classpath, and the other subproject resolves against the wrong one.
     */
    private fun convertTasty(key: String, covered: Set<String>): TastyToScip.Result? {
        // The class directories and the compile classpath both come from `<key>.tasty-roots.txt`
        // and `<key>.tasty-classpath.txt`, which the injected sbt plugin writes one pair of per
        // project. The classpath is needed because TASTy leaves the types of most subexpressions
        // implicit, to be reconstructed from the classpath, so it cannot be read without one.
        val classDirs =
            readReportedPath("$key.tasty-roots.txt").filter { Files.isDirectory(it) }.distinct()
        if (classDirs.isEmpty()) return null
        // This project's own class directory also appears on its own reported classpath, so it is
        // reported twice. Dropping the duplicate keeps `ClasspathLoaders.read` -- which loads every
        // entry eagerly into memory -- from holding the same classes twice, and keeps the directory
        // from being enumerated as both a project and a dependency.
        val classpath =
            (readReportedPath("$key.tasty-classpath.txt").distinct() - classDirs.toSet()) +
                jdkClasspath()
        return TastyToScip.convert(
            index.workingDirectory,
            targetroot(),
            classDirs,
            classpath,
            key,
            covered.toSet(),
        )
    }

    /**
     * Each project's Scala version and the build's default, from the `<key>.scala-version.txt`
     * files the injected sbt plugin wrote: the project's version on the first line, the build's on
     * the second.
     */
    private fun readScalaVersions(): Map<String, ScalaVersions> =
        reportedKeys(".scala-version.txt")
            .mapNotNull { key ->
                val lines = readReportedLines("$key.scala-version.txt")
                if (lines.size >= 2) key to ScalaVersions(lines[0], lines[1]) else null
            }
            .toMap()

    private fun semanticdbProjectKeys(): List<String> = reportedKeys(".semanticdb-roots.txt")

    /**
     * One key per sbt project that reported TASTy output, matching `ScipSbtPlugin.write`'s
     * `<sanitized-projectId>-<hash(baseDir)>` naming.
     */
    private fun tastyProjectKeys(): List<String> = reportedKeys(".tasty-roots.txt")

    /** The keys of every project that wrote a `<key><suffix>` file, in name order. */
    private fun reportedKeys(suffix: String): List<String> {
        val targetroot = targetroot()
        if (!Files.isDirectory(targetroot)) return emptyList()
        return Files.list(targetroot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(suffix) }
                .map { it.fileName.toString().removeSuffix(suffix) }
                .toList()
                .sorted()
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

    /** Reads the line-separated paths out of one specific file the sbt plugin wrote. */
    private fun readReportedPath(filename: String): List<Path> {
        val file = targetroot().resolve(filename)
        return if (Files.isRegularFile(file)) readPathLines(file) else emptyList()
    }

    private fun readPathLines(file: Path): List<Path> =
        Files.readAllLines(file).filter { it.isNotBlank() }.map { Paths.get(it.trim()) }

    private fun readReportedLines(filename: String): List<String> {
        val file = targetroot().resolve(filename)
        return if (Files.isRegularFile(file))
            Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }
        else emptyList()
    }

    private fun writeLines(filename: String, lines: List<String>) {
        Files.writeString(targetroot().resolve(filename), lines.joinToString("") { it + "\n" })
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

    /** A project's Scala version, and the build's default it was reported next to. */
    data class ScalaVersions(val project: String, val build: String)

    companion object {
        /**
         * Project keys, most preferred first, for deciding which project describes a source several
         * projects compile.
         *
         * Projects on the build's default Scala version come first. The default is `ThisBuild /
         * scalaVersion` when any project compiles with it; a build that sets the version per
         * project only -- `ThisBuild` then keeps sbt's own default, which nothing may use -- falls
         * back to the version most projects use. The remaining projects follow newest Scala version
         * first, so a cross-built source none of whose projects is on the default is described by
         * its newest build. Ties go to the key, so the ranking never depends on listing order.
         */
        fun rankProjects(versions: Map<String, ScalaVersions>): List<String> {
            if (versions.isEmpty()) return emptyList()
            val used = versions.values.map { it.project }
            val build =
                versions.values
                    .map { it.build }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
            val default =
                build?.takeIf { it in used }
                    ?: used
                        .groupingBy { it }
                        .eachCount()
                        .entries
                        .sortedWith(
                            compareByDescending<Map.Entry<String, Int>> { it.value }
                                .thenComparator { a, b -> compareVersions(b.key, a.key) }
                        )
                        .first()
                        .key
            return versions.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, ScalaVersions>> {
                            it.value.project == default
                        }
                        .thenComparator { a, b ->
                            compareVersions(b.value.project, a.value.project)
                        }
                        .thenBy { it.key }
                )
                .map { it.key }
        }

        /** Compares dotted versions numerically, segment by segment: `2.13.16` > `2.9.3`. */
        internal fun compareVersions(a: String, b: String): Int {
            val left = a.split('.', '-')
            val right = b.split('.', '-')
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrNull(i) ?: return -1
                val r = right.getOrNull(i) ?: return 1
                val byNumber = (l.toIntOrNull() ?: -1).compareTo(r.toIntOrNull() ?: -1)
                val compared = if (byNumber != 0) byNumber else l.compareTo(r)
                if (compared != 0) return compared
            }
            return 0
        }

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
