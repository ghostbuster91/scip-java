package org.scip_code.scip_java

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import org.scip_code.scip_java.buildtools.ProcessResult

object Embedded {

    val javacLauncherJvmOptions: List<String> by lazy {
        val properties = Properties()
        val input =
            Embedded::class.java.getResourceAsStream("/javac-internals.properties")
                ?: error("missing embedded resource: /javac-internals.properties")
        input.use { properties.load(it) }
        val jvmOptions =
            properties.getProperty("javac.jvmOptions")
                ?: error("missing javac.jvmOptions in /javac-internals.properties")
        jvmOptions.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { "-J$it" }
    }

    fun scipJar(tmpDir: Path): Path = copyFile(tmpDir, "scip-plugin.jar")

    fun gradlePluginJar(tmpDir: Path): Path = copyFile(tmpDir, "gradle-plugin.jar")

    /**
     * Materializes the sbt `AutoPlugin` that scip-java injects into a build with
     * `-Dsbt.global.plugins` and returns the directory to pass to that property. sbt compiles the
     * sources of a global plugins directory itself, so the plugin ships as a source file instead of
     * a jar.
     */
    fun sbtGlobalPluginsDirectory(tmpDir: Path): Path {
        val out = tmpDir.resolve("sbt-plugins")
        copyResource(out.resolve("ScipSbtPlugin.scala"), "scip-java/ScipSbtPlugin.scala")
        return out
    }

    private fun javacErrorpath(tmp: Path): Path = tmp.resolve("errorpath.txt")

    /**
     * A `javac` wrapper that injects `-Xplugin:scip` into the compiler options it is invoked with.
     * Build tools either run [executable] directly or, when they only accept a whole JDK (sbt), run
     * `bin/javac` out of [javaHome].
     */
    data class CustomJavac(
        val executable: Path,
        val javaHome: Path,
        val environment: Map<String, String>,
    )

    /**
     * @param javacopts where the wrapper dumps the original compiler options. Defaults to
     *   `javacopts.txt` in the targetroot, which scip-java then reads to resolve the Maven
     *   coordinates of the dependency jars. Build tools that report their dependencies through
     *   `dependencies.txt` instead pass a path outside of the targetroot, because
     *   [org.scip_code.scip_java.buildtools.ClasspathEntry] prefers `javacopts.txt` when it finds
     *   both.
     */
    fun customJavac(
        sourceroot: Path,
        targetroot: Path,
        tmp: Path,
        javacopts: Path = targetroot.resolve("javacopts.txt"),
    ): CustomJavac {
        val bin = tmp.resolve("bin")
        val javac = bin.resolve("javac")
        val java = bin.resolve("java")
        val pluginpath = scipJar(tmp)
        val errorpath = javacErrorpath(tmp)
        Files.createDirectories(targetroot)
        Files.createDirectories(bin)
        copyResource(java, "scip-java/java-forwarder.sh")
        copyResource(javac, "scip-java/custom-javac.sh")
        javac.toFile().setExecutable(true)
        java.toFile().setExecutable(true)
        return CustomJavac(
            javac,
            tmp,
            mapOf(
                "SCIP_ERRORPATH" to errorpath.toString(),
                "SCIP_JAVAC_LAUNCHER_JVM_OPTIONS" to javacLauncherJvmOptions.joinToString("\n"),
                "SCIP_JAVAC_OPTIONS_PREFIX" to tmp.resolve("javac_newarguments").toString(),
                "SCIP_OLD_JAVAC_OPTS" to javacopts.toString(),
                "SCIP_PLUGINPATH" to pluginpath.toString(),
                "SCIP_SOURCEROOT" to sourceroot.toString(),
                "SCIP_TARGETROOT" to targetroot.toString(),
            ),
        )
    }

    /**
     * The custom javac wrapper reports errors to a specific file if unexpected errors happen. The
     * javac wrapper gets invoked by builds tools like Gradle/Maven, which hide the actual errors
     * from the script because they assume the standard output is from javac. This file is used a
     * side-channel to avoid relying on the error reporting from Gradle/Maven.
     */
    fun reportUnexpectedJavacErrors(reporter: CliReporter, tmp: Path): ProcessResult? {
        val errorpath = javacErrorpath(tmp)
        if (!Files.isRegularFile(errorpath)) return null
        reporter.error("unexpected javac compile errors")
        Files.readAllLines(errorpath).forEach { reporter.error(it) }
        return ProcessResult(1)
    }

    /** Returns the string contents of the scip_java.bzl file on disk. */
    fun bazelAspectFile(tmpDir: Path): String {
        val tmpFile = copyFile(tmpDir, "scip-java/scip_java.bzl")
        val contents = String(Files.readAllBytes(tmpFile), StandardCharsets.UTF_8)
        Files.deleteIfExists(tmpFile)
        return contents
    }

    private fun copyFile(tmpDir: Path, filename: String): Path {
        val out = tmpDir.resolve(filename)
        copyResource(out, filename)
        return out
    }

    private fun copyResource(out: Path, resource: String) {
        val input =
            Embedded::class.java.getResourceAsStream("/$resource")
                ?: error("missing embedded resource: /$resource")
        Files.createDirectories(out.parent)
        input.use { Files.copy(it, out, StandardCopyOption.REPLACE_EXISTING) }
    }
}
