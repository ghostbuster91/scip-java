import org.scip_code.scip_java.buildlogic.cleanDirectoryBeforeRunning
import org.scip_code.scip_java.buildlogic.publishDirectoryArtifact
import org.scip_code.scip_java.buildlogic.shadowJarArtifact
import org.scip_code.scip_java.buildlogic.useScipJavac

plugins {
    id("scip.scala-library")
}

val javacShadowJar = shadowJarArtifact(":scip-javac", "javacShadowJar")

// Runtime classpath of the TASTy-based Scala 3 indexer.
val scipTasty: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    scipTasty(project(":scip-tasty"))
}

val scipTargetroot = layout.buildDirectory.dir("scip-targetroot")
val sourceroot = rootProject.rootDir.absolutePath
val targetroot = scipTargetroot.get().asFile.absolutePath

// Java sources compile first (Gradle's standard scala-plugin order) and clean the targetroot;
// Scala compiles against them and scip-tasty adds its shards afterwards.
val compileJavaTask = tasks.named<JavaCompile>("compileJava") {
    useScipJavac(rootDir, javacShadowJar, scipTargetroot)
    options.annotationProcessorPath = javacShadowJar
    options.compilerArgs.add(
        "-Xplugin:scip -sourceroot:$sourceroot -targetroot:$targetroot " +
            "-randomtimestamp=${System.nanoTime()}"
    )
    cleanDirectoryBeforeRunning(scipTargetroot)
}

val compileScalaTask = tasks.named<ScalaCompile>("compileScala") {
    scalaCompileOptions.additionalParameters = listOf("-release", "17", "-sourceroot", sourceroot)
}

val scipIndexScala3 = tasks.register<JavaExec>("scipIndexScala3") {
    dependsOn(compileScalaTask)
    mustRunAfter(compileJavaTask)
    classpath = scipTasty
    mainClass.set("org.scip_code.scip_java.tasty.Main")
    val scalaClassesDir = compileScalaTask.flatMap { it.destinationDirectory }
    val javaClassesDir = compileJavaTask.flatMap { it.destinationDirectory }
    val compileClasspath = sourceSets.main.map { it.compileClasspath }
    inputs.dir(scalaClassesDir)
    inputs.dir(javaClassesDir)
    inputs.files(compileClasspath)
    outputs.dir(scipTargetroot)
    // Locals only: the argument provider must not capture the build script (configuration cache).
    val sourcerootArg = sourceroot
    val targetrootArg = scipTargetroot
    argumentProviders.add(
        CommandLineArgumentProvider {
            val cpEntries =
                compileClasspath.get().files.map { it.absolutePath } +
                    javaClassesDir.get().asFile.absolutePath
            listOf(
                "--sourceroot",
                sourcerootArg,
                "--targetroot",
                targetrootArg.get().asFile.absolutePath,
                "--classpath",
                cpEntries.joinToString(File.pathSeparator),
                scalaClassesDir.get().asFile.absolutePath,
            )
        }
    )
}

tasks.named("classes") {
    dependsOn(scipIndexScala3)
}

publishDirectoryArtifact("scipTargetrootElements", scipTargetroot, tasks.named("classes"))
