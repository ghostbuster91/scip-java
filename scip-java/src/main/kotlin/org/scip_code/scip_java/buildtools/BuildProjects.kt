package org.scip_code.scip_java.buildtools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.scip_code.scip_java.aggregator.ScipProject

/**
 * Reads the build's projects back out of the per-project files in a targetroot, so the aggregator
 * can attribute each document against the classpath of the project that compiled it rather than the
 * whole build's.
 *
 * A project is whatever wrote a `<key>.dependencies.txt` -- one per sbt project, Gradle project or
 * Maven module. Alongside it, a build tool may write:
 * - `<key>.class-dirs.txt`: the directories the project compiles into, main and test, one per line.
 *   Without it, the directory entries of the dependency file stand in, which covers a project's
 *   main output only.
 * - `<key>.shards.txt`: the shards the project's sources were converted into, one per line,
 *   relative to the targetroot.
 *
 * and, once per targetroot, `projects.txt`: project keys, most preferred first, deciding which
 * project owns a source several projects compile. Projects it leaves out follow in key order.
 */
object BuildProjects {
    const val CLASS_DIRS_SUFFIX: String = ".class-dirs.txt"
    const val SHARDS_SUFFIX: String = ".shards.txt"
    const val PREFERENCE_FILE: String = "projects.txt"
    private const val DEPENDENCIES_SUFFIX: String = "dependencies.txt"

    @JvmStatic
    fun fromTargetroot(targetroot: Path): List<ScipProject> {
        if (!Files.isDirectory(targetroot)) return emptyList()
        // `javacopts.txt` describes a single compilation, so there is no project to tell apart.
        if (Files.isRegularFile(targetroot.resolve("javacopts.txt"))) return emptyList()
        val dependencyFiles =
            Files.list(targetroot).use { stream ->
                stream
                    .filter {
                        Files.isRegularFile(it) &&
                            it.fileName.toString().endsWith(DEPENDENCIES_SUFFIX)
                    }
                    .toList()
            }
        val byKey =
            dependencyFiles
                .associateBy { keyOf(it) }
                .toSortedMap()
                .mapValues { (key, file) -> project(targetroot, key, file) }
        val preferred = readLines(targetroot.resolve(PREFERENCE_FILE)).filter { it in byKey }
        return (preferred + (byKey.keys - preferred.toSet())).distinct().map { byKey.getValue(it) }
    }

    /** `foo-1a2b.dependencies.txt` -> `foo-1a2b`; Gradle's `_app.dependencies.txt` -> `_app`. */
    private fun keyOf(dependencies: Path): String =
        dependencies.fileName.toString().removeSuffix(DEPENDENCIES_SUFFIX).removeSuffix(".")

    private fun project(targetroot: Path, key: String, dependencies: Path): ScipProject {
        val classpath = ClasspathEntry.fromDependencies(dependencies).map { it.entry }
        val reportedClassDirs = readLines(targetroot.resolve(key + CLASS_DIRS_SUFFIX))
        val classDirs =
            if (reportedClassDirs.isNotEmpty()) reportedClassDirs.map { Paths.get(it) }
            else classpath.filter { Files.isDirectory(it) }
        val shards =
            readLines(targetroot.resolve(key + SHARDS_SUFFIX)).map { targetroot.resolve(it) }
        return ScipProject(key, classpath.toSet(), classDirs.distinct(), shards.toSet())
    }

    private fun readLines(file: Path): List<String> =
        if (!Files.isRegularFile(file)) emptyList()
        else
            Files.readAllLines(file, StandardCharsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
}
