import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("scip.java-base")
    `java-library`
    scala
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val scala3Version = libs.findVersion("scala3").get().requiredVersion

dependencies {
    // Gradle's ScalaBasePlugin infers the Scala 3 toolchain (zinc + the matching
    // `scala3-compiler_3`) from the `scala3-library_3` it finds on the compile
    // classpath, so declaring the library is all that is needed.
    "implementation"("org.scala-lang:scala3-library_3:$scala3Version")
}

tasks.withType<ScalaCompile>().configureEach {
    // Keep bytecode at JVM 17, matching `options.release.set(17)` for Java everywhere else.
    scalaCompileOptions.additionalParameters = listOf("-release", "17")
}
