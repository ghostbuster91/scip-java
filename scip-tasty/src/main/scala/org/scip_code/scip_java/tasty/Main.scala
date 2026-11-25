package org.scip_code.scip_java.tasty

import java.io.File
import java.net.URI
import java.nio.file.{FileSystems, Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Command-line entry point, primarily used by the `scip-snapshots` harness. The `scip-java` CLI
  * calls [[TastyToScip.convert]] in-process instead.
  *
  * Usage: `--sourceroot <dir> --targetroot <dir> [--classpath <path-separated entries>]
  * <classDir>...`
  */
object Main {

  def main(args: Array[String]): Unit = {
    var sourceroot: Option[Path] = None
    var targetroot: Option[Path] = None
    var classpath: List[Path] = Nil
    val classDirs = List.newBuilder[Path]

    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--sourceroot" =>
          i += 1
          sourceroot = Some(Paths.get(args(i)))
        case "--targetroot" =>
          i += 1
          targetroot = Some(Paths.get(args(i)))
        case "--classpath" =>
          i += 1
          classpath = args(i)
            .split(File.pathSeparator)
            .filter(_.nonEmpty)
            .map(Paths.get(_))
            .toList
        case other =>
          classDirs += Paths.get(other)
      }
      i += 1
    }

    val srcRoot = sourceroot.getOrElse(
      throw new IllegalArgumentException("missing required flag: --sourceroot")
    )
    val tgtRoot = targetroot.getOrElse(
      throw new IllegalArgumentException("missing required flag: --targetroot")
    )
    val dirs = classDirs.result()
    require(dirs.nonEmpty, "missing class directories")

    val fullClasspath = (classpath.filterNot(dirs.contains) ++ jdkClasspath()).distinct
    val result = TastyToScip.convert(srcRoot, tgtRoot, dirs.asJava, fullClasspath.asJava, "")
    val warnings = result.warnings.asScala.toList
    if (warnings.nonEmpty) {
      warnings.foreach(w => System.err.println(s"warning: $w"))
      sys.exit(1)
    }
  }

  private def jdkClasspath(): List[Path] = {
    val modulesRoot = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("modules")
    Using.resource(Files.list(modulesRoot)) { stream =>
      stream.iterator().asScala.filter(Files.isDirectory(_)).toList
    }
  }
}
