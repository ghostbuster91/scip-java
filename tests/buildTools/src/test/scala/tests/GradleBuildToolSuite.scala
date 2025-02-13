package tests

import java.nio.file.Files
import java.nio.file.StandardOpenOption

class GradleBuildToolSuite extends BaseBuildToolSuite {

  def gradleVersion(version: String = ""): List[String] = {
    createEmptyBuildScript()
    if (version.isEmpty || version == "latest")
      List("gradle", "wrapper")
    else
      List("gradle", "wrapper", "--gradle-version", version)
  }

  def createEmptyBuildScript(): Unit = {
    val script = workingDirectory.resolve("build.gradle")
    Files.createDirectories(script.getParent)
    Files.write(
      script,
      Array.emptyByteArray,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.CREATE
    )
  }

  List("latest" -> "implementation", "4.0" -> "compile").foreach {
    case (version, config) =>
      checkBuild(
        if (version == "latest")
          "basic-latest"
        else
          s"basic-$version".tag(Java8Only),
        s"""|/build.gradle
            |apply plugin: 'java'
            |repositories {
            |  mavenCentral()
            |}
            |dependencies {
            |  $config 'junit:junit:4.13.1'
            |}
            |/src/main/java/Example.java
            |import org.junit.Assert;
            |public class Example {}
            |/src/test/java/ExampleSuite.java
            |public class ExampleSuite {}
            |""".stripMargin,
        expectedSemanticdbFiles = 2,
        expectedPackages =
          """|maven:junit:junit:4.13.1
             |maven:org.hamcrest:hamcrest-core:1.3
             |""".stripMargin,
        initCommand = {
          gradleVersion(version)
        }
      )
  }

  List("3.3", "2.2.1").foreach { version =>
    checkBuild(
      s"legacy-$version".tag(Java8Only),
      s"""|/build.gradle
          |apply plugin: 'java'
          |/src/main/java/Example.java
          |public class Example {}
          |/src/test/java/ExampleSuite.java
          |public class ExampleSuite {}
          |""".stripMargin,
      expectedSemanticdbFiles = 2,
      initCommand = gradleVersion(version)
      // NOTE(olafur): no packages because we use more modern APIs.
    )
  }

  checkBuild(
    "toolchains-latest",
    """|/build.gradle
       |apply plugin: 'java'
       |java {
       |  toolchain {
       |    languageVersion = JavaLanguageVersion.of(8)
       |  }
       |}
       |repositories { mavenCentral() }
       |dependencies { implementation "junit:junit:4.13.1" }
       |/src/main/java/Example.java
       |import org.junit.Assert;
       |public class Example {}
       |/src/test/java/ExampleSuite.java
       |public class ExampleSuite {}
       |""".stripMargin,
    2
  )

  checkBuild(
    "toolchains-6.7".tag(Java8Only),
    """|/build.gradle
       |apply plugin: 'java'
       |java {
       |  toolchain {
       |    languageVersion = JavaLanguageVersion.of(8)
       |  }
       |}
       |repositories { mavenCentral() }
       |dependencies { implementation "junit:junit:4.13.1" }
       |/src/main/java/Example.java
       |public class Example {}
       |""".stripMargin,
    expectedError = Some { error =>
      assert(
        clue(error).contains(
          """error: scip-java does not support Gradle 6.7 when used together with Java toolchains. To fix this problem, upgrade to Gradle version 6.8 or newer and try again."""
        )
      )
    },
    initCommand = gradleVersion("6.7")
  )

  checkBuild(
    "toolchains-6.8".tag(Java8Only),
    """|/build.gradle
       |apply plugin: 'java'
       |java {
       |  toolchain {
       |    languageVersion = JavaLanguageVersion.of(8)
       |  }
       |}
       |/src/main/java/Example.java
       |public class Example {}
       |""".stripMargin,
    expectedSemanticdbFiles = 1,
    initCommand = gradleVersion("6.8")
  )

  checkBuild(
    "explicit",
    """|/build.gradle
       |apply plugin: 'java'
       |/src/main/java/Example.java
       |public class Example {}
       |/src/test/java/ExampleSuite.java
       |public class ExampleSuite {}
       |/pom.xml
       |<hello/>
       |""".stripMargin,
    2,
    extraArguments = List("--build-tool", "gradle")
  )

  checkBuild(
    "build-command",
    """|/build.gradle
       |apply plugin: 'java'
       |/src/main/java/Example.java
       |public class Example {}
       |/src/test/java/ExampleSuite.java
       |public class ExampleSuite {}
       |""".stripMargin,
    1,
    extraArguments = List("--", "compileJava")
  )

  checkBuild(
    "playframework".tag(Java8Only),
    """|/build.gradle
       |plugins {
       |  id 'org.gradle.playframework' version '0.11'
       |  id 'idea'
       |}
       |
       |play {
       |  platform {
       |    playVersion = '2.6.7'
       |    scalaVersion = '2.12'
       |    javaVersion = JavaVersion.VERSION_1_8
       |  }
       |  injectedRoutesGenerator = true
       |}
       |dependencies {
       |  implementation "com.typesafe.play:play-guice_2.12:2.6.7"
       |}
       |
       |repositories {
       |  mavenCentral()
       |  maven {
       |    name "lightbend-maven-releases"
       |    url "https://repo.lightbend.com/lightbend/maven-release"
       |  }
       |  ivy {
       |    name "lightbend-ivy-release"
       |    url "https://repo.lightbend.com/lightbend/ivy-releases"
       |    layout "ivy"
       |  }
       |}
       |/app/controllers/HomeController.java
       |package controllers;
       |import play.mvc.*;
       |import views.html.*;
       |public class HomeController extends Controller {
       |    public Result index() {
       |        return ok(index.render("Your new application is ready."));
       |    }
       |}
       |/app/views/index.scala.html
       |@(message: String)
       |<h1>@message</h1>
       |/conf/routes
       |GET / controllers.HomeController.index
       |""".stripMargin,
    2, // Two files because `conf/routes` generates a Java file.
    initCommand = gradleVersion("6.8")
  )

  checkBuild(
    "checkerframework".tag(Java8Only),
    """|/build.gradle
       |plugins {
       |    id 'java'
       |    id 'org.checkerframework' version '0.5.24'
       |}
       |repositories {
       |    mavenCentral()
       |}
       |java {
       |  toolchain {
       |    languageVersion = JavaLanguageVersion.of(8)
       |  }
       |}
       |/src/main/java/foo/Example.java
       |package foo;
       |public class Example {}
       |/src/test/java/foo/ExampleSuite.java
       |package foo;
       |public class ExampleSuite {}
       |""".stripMargin,
    2,
    initCommand = gradleVersion("6.8.3")
  )

  checkBuild(
    "scala",
    """|/build.gradle
       |plugins {
       |    id 'scala'
       |}
       |repositories {
       |    mavenCentral()
       |}
       |dependencies {
       |  implementation 'org.scala-lang:scala-library:2.12.12'
       |}
       |/src/main/java/foo/JExample.java
       |package foo;
       |public class JExample {}
       |/src/main/scala/foo/Example.scala
       |package foo
       |object Example {}
       |/src/test/java/foo/JExampleSuite.java
       |package foo;
       |public class JExampleSuite {}
       |/src/test/scala/foo/ExampleSuite.scala
       |package foo
       |class ExampleSuite {}
       |""".stripMargin,
    4
  )

  checkBuild(
    "kotlin",
    """|/build.gradle
       |plugins {
       |    id 'org.jetbrains.kotlin.jvm' version '1.5.31'
       |}
       |repositories {
       |    mavenCentral()
       |}
       |/src/main/java/foo/JExample.java
       |package foo;
       |public class JExample {}
       |/src/main/kotlin/foo/Example.kt
       |package foo
       |object Example {}
       |/src/test/java/foo/JExampleSuite.java
       |package foo;
       |public class JExampleSuite {}
       |/src/test/kotlin/foo/ExampleSuite.kt
       |package foo
       |class ExampleSuite {}
       |""".stripMargin,
    4
  )

  List("8", "11").foreach { version =>
    checkBuild(
      s"kotlin-jvm-toolchains-jdk-$version",
      s"""|/build.gradle
          |plugins {
          |    id 'java'
          |    id 'org.jetbrains.kotlin.jvm' version '1.5.31'
          |}
          |java {
          |  toolchain {
          |    languageVersion = JavaLanguageVersion.of($version)
          |  }
          |}
          |repositories { mavenCentral() }
          |/src/main/kotlin/foo/Example.kt
          |package foo
          |object Example {}
          |""".stripMargin,
      1
    )
  }

  List("jvm()" -> 2, "jvm { withJava() }" -> 4).foreach {
    case (jvmSettings, expectedSemanticdbFiles) =>
      checkBuild(
        s"kotlin-multiplatform-$jvmSettings",
        s"""|/build.gradle
            |plugins {
            |    id 'org.jetbrains.kotlin.multiplatform' version '1.5.31'
            |}
            |repositories {
            |    mavenCentral()
            |}
            |kotlin {
            |  ${jvmSettings}
            |  sourceSets {
            |    jvmTest {
            |      dependencies {
            |        implementation kotlin("test-junit")
            |      }
            |    }
            |  }
            |}
            |/gradle.properties
            |kotlin.mpp.stability.nowarn=true
            |/src/jvmMain/java/foo/ExampleJ.java
            |package foo;
            |public class ExampleJ {} // ignored by multiplatform
            |/src/jvmMain/kotlin/foo/Example.kt
            |package foo
            |object Example {}
            |/src/jvmTest/java/foo/ExampleJSuite.java
            |package foo;
            |class ExampleJSuite {} // ignored by multiplatform
            |/src/commonTest/kotlin/foo/ExampleJvmSuite.kt
            |package foo
            |class ExampleJvmSuite {}
            |""".stripMargin,
        expectedSemanticdbFiles
      )
  }
}
