// scip-java's sbt integration.
//
// `scip-java index` copies this file into a temporary directory and points sbt
// at it with `-Dsbt.global.plugins`, so an sbt build can be indexed without
// modifying any file in the repository. sbt compiles the sources of its global
// plugins directory itself, which is why this ships as a source file rather
// than as a published plugin.
//
// The plugin does three things:
//
//   1. Points `javaHome` at the `javac` wrapper that scip-java generated. zinc
//      forks `$javaHome/bin/javac` for every Java compilation whenever
//      `javaHome` is set, and that wrapper injects `-Xplugin:scip` into the
//      compiler options.
//   2. Writes the Maven coordinates of every project and of the jars on its
//      classpath to a `*.dependencies.txt` file in the targetroot, which
//      scip-java reads to enable cross-repository navigation.
//   3. Writes the `semanticdbTargetRoot` of every project to a
//      `*.semanticdb-roots.txt` file, which is where scip-java looks for the
//      SemanticDB files it converts into shards to index Scala sources.
//      Emitting them is sbt's own `semanticdbEnabled`, turned on by passing
//      `-Dsbt.semanticdb=true`, so this plugin only reports the locations.
//
// Both paths arrive as the `scip.javaHome` and `scip.targetroot` system
// properties. Each is optional: without them the plugin is a no-op, which keeps
// a stale global plugins directory from breaking an unrelated build.
import sbt._
import sbt.Keys._

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object ScipSbtPlugin extends AutoPlugin {

  // `allRequirements` plus a `JvmPlugin` requirement means: enable for every
  // project that compiles JVM sources, without the build opting in.
  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  private val scipJavaHome: Option[File] = sys.props.get("scip.javaHome").map(file)
  private val scipTargetroot: Option[Path] = sys.props.get("scip.targetroot").map(Paths.get(_))

  object autoImport {
    val scipWriteDependencies = taskKey[Unit](
      "Writes the Maven coordinates of this project and its dependencies for scip-java."
    )
    val scipWriteSemanticdbRoots = taskKey[Unit](
      "Writes the directories scalac emits SemanticDB files into for scip-java."
    )
  }
  import autoImport._

  override def globalSettings: Seq[Setting[?]] = Seq(commands += scipCompileAll)

  override def projectSettings: Seq[Setting[?]] =
    // Leave `javaHome` alone when the property is missing: there is no wrapper
    // to point it at, and overriding it with `None` would discard a build's own
    // value for no reason.
    scipJavaHome.toList.map(home => javaHome := Some(home)) ++
      Seq(
        scipWriteDependencies := writeDependencies.value,
        scipWriteSemanticdbRoots := writeSemanticdbRoots.value,
      )

  /**
   * sbt-crossproject defines one project per platform. Only the JVM one ever runs javac, and a
   * cross-project shares its sources between platforms, so indexing the others would emit a
   * duplicate shard for every shared Java source. Matched by label, which is the fully qualified
   * name of the plugin object, so that this plugin keeps compiling without Scala.js or Scala Native
   * on its classpath.
   */
  private val nonJvmPlatformPlugins = Set(
    "org.scalajs.sbtplugin.ScalaJSPlugin",
    "scala.scalanative.sbtplugin.ScalaNativePlugin",
  )

  /**
   * Compiles every project of every build in the session, including the ones the root project does
   * not aggregate. Each phase is a single `all` command so that sbt runs the tasks it contains in
   * parallel.
   *
   * Takes an optional Scala version to index a cross-built repository at. Given one, it switches to
   * that version and leaves out the projects that do not list it in `crossScalaVersions` — the same
   * projects `++` itself leaves behind. Compiling those too is not an option: they would keep their
   * old Scala version while their dependencies switched, so their classpath would mix two
   * incompatible Scala versions and nothing would compile.
   */
  private def scipCompileAll: Command =
    Command.args("scipCompileAll", "[<scala-version>]") { (state, args) =>
      val requestedScalaVersion = args.headOption.filter(_.nonEmpty)
      val structure = Project.extract(state).structure

      // Mirrors sbt's own reading of the setting, where an empty
      // `crossScalaVersions` means the project is built at one version only.
      def crossVersions(ref: ProjectRef): Seq[String] =
        (ref / crossScalaVersions).get(structure.data).getOrElse(Nil) match {
          case Nil      => (ref / scalaVersion).get(structure.data).toSeq
          case versions => versions
        }

      def isIndexable(project: ResolvedProject, ref: ProjectRef): Boolean =
        project.autoPlugins.contains(plugins.JvmPlugin) &&
          !project.autoPlugins.exists(plugin => nonJvmPlatformPlugins.contains(plugin.label)) &&
          requestedScalaVersion.forall(crossVersions(ref).contains)

      val projects = structure.allProjectPairs.collect {
        case (project, ref) if isIndexable(project, ref) => "{" + ref.build + "}" + ref.project
      }
      // Switch first, so the projects selected above compile at the requested
      // version. Issued even when nothing matched, so that sbt reports why.
      val switch = requestedScalaVersion.map("++" + _).toList
      if (projects.isEmpty) switch ::: state
      else {
        // Clean first, so incremental compilation cannot skip sources that an
        // earlier build already compiled, and so that cleaning a project cannot
        // wipe the targetroot from underneath the shards we are about to write.
        val clean = all(projects.map(_ + "/clean"))
        val compile = all(projects.flatMap { project =>
          List(
            project + "/Compile/compile",
            project + "/Test/compile",
            project + "/scipWriteDependencies",
            project + "/scipWriteSemanticdbRoots",
          )
        })
        switch ::: List(clean, compile) ::: state
      }
    }

  private def all(tasks: Seq[String]): String = ("all" +: tasks).mkString(" ")

  private def writeDependencies: Def.Initialize[Task[Unit]] = Def.task {
    val converter = fileConverter.value
    val projectId = thisProject.value.id
    val base = baseDirectory.value
    val classpath =
      (Compile / externalDependencyClasspath).value ++ (Test / externalDependencyClasspath).value

    val lines = scala.collection.mutable.LinkedHashSet.empty[String]
    def add(org: String, name: String, revision: String, entry: String): Unit =
      lines += List(org, name, revision, entry).mkString("\t")

    // The project's own coordinates first, so that the symbols it defines can
    // be resolved from other repositories. `CrossVersion` applies the suffix of
    // the published artifact, e.g. `example` -> `example_3`.
    val projectModule =
      CrossVersion(scalaVersion.value, scalaBinaryVersion.value)(projectID.value)
    add(
      projectModule.organization,
      projectModule.name,
      projectModule.revision,
      (Compile / classDirectory).value.toString,
    )
    classpath.foreach { entry =>
      // Entries without this metadata are the outputs of other projects in the
      // build, which write their own coordinates above.
      entry.get(Keys.moduleIDStr).foreach { serialized =>
        val module = Classpaths.moduleIdJsonKeyFormat.read(serialized)
        add(
          module.organization,
          module.name,
          module.revision,
          converter.toPath(entry.data).toString,
        )
      }
    }

    write(projectId, base, ".dependencies.txt", lines.toSeq)
  }

  /**
   * Reports where scalac writes its SemanticDB output. scip-java converts those files into SCIP
   * shards, which is how Scala sources end up in the index; `semanticdbEnabled` itself is turned on
   * with `-Dsbt.semanticdb=true` on the command line.
   *
   * The paths are reported rather than guessed because `semanticdbTargetRoot` is configurable and
   * its default depends on sbt's target layout.
   */
  private def writeSemanticdbRoots: Def.Initialize[Task[Unit]] = Def.task {
    val roots = Seq((Compile / semanticdbTargetRoot).value, (Test / semanticdbTargetRoot).value)
    write(
      thisProject.value.id,
      baseDirectory.value,
      ".semanticdb-roots.txt",
      roots.map(_.toString).distinct,
    )
  }

  /**
   * Writes one file per project into the targetroot, because sbt runs these tasks in parallel and
   * scip-java globs for the suffix. The base directory goes into the name as well: two builds of a
   * multi-build session can each define a project with the same id.
   */
  private def write(projectId: String, base: File, suffix: String, lines: Seq[String]): Unit =
    scipTargetroot.foreach { targetroot =>
      val name = sanitize(projectId) + "-" + Integer.toHexString(base.toString.hashCode)
      Files.createDirectories(targetroot)
      Files.write(
        targetroot.resolve(name + suffix),
        lines.mkString("", "\n", "\n").getBytes(UTF_8),
      )
    }

  private def sanitize(name: String): String = name.replaceAll("[^a-zA-Z0-9_-]", "_")
}
