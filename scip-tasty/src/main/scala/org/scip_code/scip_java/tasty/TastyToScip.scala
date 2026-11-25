package org.scip_code.scip_java.tasty

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.{util as ju}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import org.scip_code.scip.{Document, SymbolInformation}
import org.scip_code.scip_java.shared.{ScipShardPaths, ScipShardWriter}
import tastyquery.Classpaths.{Classpath, ClasspathEntry}
import tastyquery.Contexts
import tastyquery.Contexts.Context
import tastyquery.Exceptions.MemberNotFoundException
import tastyquery.Symbols.*
import tastyquery.jdk.ClasspathLoaders

/** Converts Scala 3 TASTy into SCIP shards, one per source file.
  *
  * The shards carry bare descriptor symbols in the same layout the javac and kotlinc
  * plugins write (`<targetroot>/META-INF/scip/<relative-source>.scip`), so `scip-aggregator`
  * qualifies them and resolves references across languages exactly as it already does. This
  * deliberately adds no second aggregation entry point.
  *
  * The entry point is shaped like `SemanticdbToScip`, and follows the same rule that
  * failures are warnings rather than errors: the worst a bad file should cost is its own
  * document, not the whole index.
  */
object TastyToScip {

  /** What one conversion produced.
    *
    * `coveredSources` are source-root-relative paths, which is what
    * `ScipShardPaths.shardPathForRelativeSource` keys on -- a second writer for the same
    * relative path would silently overwrite the shard.
    *
    * Deliberately a plain class rather than a `case class`: a case class extends
    * `scala.Product`, which Kotlin cannot resolve without a scala-library dependency of its
    * own, so returning one would force Scala onto every caller's compile classpath -- the
    * very leak this facade exists to prevent. Every member type here is a Java one.
    */
  final class Result(
    val shardsWritten: Int,
    val warnings: ju.List[String],
    val coveredSources: ju.Set[String],
  )

  /** Indexes every Scala 3 class found in `classDirs`.
    *
    * `classpath` must be the full compile classpath, including the JRE: TASTy cannot be
    * made sense of without it. `sourceRoot` is where the paths pickled into TASTy are
    * resolved from, so it must be the `-sourceroot` the project was compiled with.
    *
    * `shardDiscriminator` names the external-symbols shard this call writes. A caller
    * converting one project at a time -- one subproject of a multi-module sbt build, say --
    * must pass a discriminator unique to that project, or each call's shard overwrites the
    * last one's.
    */
  def convert(
    sourceRoot: Path,
    targetRoot: Path,
    classDirs: ju.List[Path],
    classpath: ju.List[Path],
    shardDiscriminator: String,
  ): Result = {
    val warnings = mutable.ListBuffer.empty[String]
    val covered = mutable.LinkedHashSet.empty[String]

    val written: Int =
      try
        convertInto(
          sourceRoot,
          targetRoot,
          classDirs,
          classpath,
          shardDiscriminator,
          warnings,
          covered,
        )
      catch {
        // Reading the classpath and initializing the context can both fail -- an unreadable
        // jar, or running out of memory on a very large classpath. Scala indexing failing
        // wholesale must still leave the Java and Kotlin shards already on disk to be
        // aggregated into an index.
        //
        // `NonFatal` rather than `Exception`, because tasty-query reports some malformed
        // input with `assert`, which throws `AssertionError` -- an `Error`, not an
        // `Exception`. Indexing tapir hit exactly that while reading an annotation, and an
        // `Exception` handler let it escape and take the whole run down.
        case NonFatal(e) =>
          warnings += s"TASTy indexing failed, leaving Scala sources to SemanticDB: $e"
          0
        case e: StackOverflowError =>
          warnings += s"TASTy indexing failed, leaving Scala sources to SemanticDB: $e"
          0
      }

    new Result(written, warnings.toList.asJava, covered.asJava)
  }

  private def convertInto(
    sourceRoot: Path,
    targetRoot: Path,
    classDirs: ju.List[Path],
    classpath: ju.List[Path],
    shardDiscriminator: String,
    warnings: mutable.ListBuffer[String],
    covered: mutable.LinkedHashSet[String],
  ): Int = {
    var written: Int = 0
    val defined = mutable.HashSet.empty[String]
    val referenced = mutable.LinkedHashMap.empty[String, Symbol]
    val projectEntries: List[Path] = classDirs.asScala.toList
    val entries: Classpath = ClasspathLoaders.read(projectEntries ++ classpath.asScala.toList)
    // `findSymbolsByClasspathEntry` compares entries by `equals`, so the instances handed to
    // `initialize` are the ones that must be passed back in. `read` preserves input order,
    // so the project entries are the first `classDirs.size`.
    val projectCount: Int = projectEntries.size
    given Context = Contexts.Context.initialize(entries)
    given JavaOrderLookup = JavaOrderLookup(Some(new JavaMethodOrder(entries)))

    for ((pickledPath, classes) <- groupBySource(entries.take(projectCount), warnings)) {
      try {
        relativize(sourceRoot, pickledPath) match {
          case None =>
            warnings += s"skipping $pickledPath: outside the source root $sourceRoot"
          case Some(relativePath) =>
            readSource(sourceRoot, relativePath) match {
              case None =>
                warnings += s"skipping $relativePath: no such file under $sourceRoot"
              case Some(text) =>
                val indexer = new TastyDocumentIndexer(pickledPath, relativePath, text)
                classes.foreach(cls => cls.tree.foreach(indexer.index))
                val document: Document = indexer.result()
                defined ++= indexer.definedSymbols
                indexer.referencedSymbols.foreach { case (symbol, sym) =>
                  referenced.getOrElseUpdate(symbol, sym)
                }
                if (document.getOccurrencesCount > 0) {
                  val shard: Path =
                    ScipShardPaths.shardPathForRelativeSource(targetRoot, Path.of(relativePath))
                  Files.createDirectories(shard.getParent)
                  ScipShardWriter.writeShard(shard, document)
                  covered += relativePath
                  written += 1
                }
            }
        }
      } catch {
        case e: IOException        => warnings += s"failed to write a shard for $pickledPath: $e"
        // `NonFatal` covers `AssertionError`, which tasty-query throws on some malformed
        // input; an `Exception` handler would let it escape and end the run.
        case NonFatal(e)           => warnings += s"failed to index $pickledPath: $e"
        case e: StackOverflowError => warnings += s"failed to index $pickledPath: $e"
      }
    }

    writeExternalSymbols(targetRoot, shardDiscriminator, defined.toSet, referenced.toList, warnings)
    written
  }

  /** Describes the referenced symbols that no indexed source defines.
    *
    * Scoped to what the sources actually reference, rather than everything the dependency
    * jars contain. Describing whole jars would be enormous -- scala-library alone has tens
    * of thousands of public symbols -- and almost none of it would ever be looked at. What
    * a reader needs is the signature of the library symbol under the cursor, and that is
    * exactly this set.
    *
    * Written as one shard rather than per-source, because an external symbol belongs to no
    * document. `ScipWriter.emitTyped` concatenates `Index` messages, so a chunk carrying
    * only `external_symbols` composes with the per-document ones.
    */
  private def writeExternalSymbols(
    targetRoot: Path,
    shardDiscriminator: String,
    defined: Set[String],
    referenced: List[(String, Symbol)],
    warnings: mutable.ListBuffer[String],
  )(using Context, JavaOrderLookup): Unit = {
    // No clique relationships here: a clique ties together symbols sharing one definition
    // occurrence, and an external symbol has no occurrence in this repository at all.
    val external: List[SymbolInformation] = referenced.collect {
      case (symbol, sym) if !defined.contains(symbol) =>
        scala.util
          .Try(TastyDescriber.describe(sym, symbol, TastyDescriber.displayNameOf(sym)).build())
          .toOption
    }.flatten

    if (external.nonEmpty) {
      // Discriminated by project: a caller converting one project per call -- one
      // subproject of a multi-module sbt build -- would otherwise have each call's shard
      // overwrite the last one's, silently dropping every earlier project's external
      // symbols.
      val suffix: String = if (shardDiscriminator.isEmpty) "" else s"-$shardDiscriminator"
      val shard: Path = targetRoot
        .resolve(ScipShardPaths.SCIP_ROOT)
        .resolve(s"external-symbols-tasty$suffix.scip")
      try {
        Files.createDirectories(shard.getParent)
        ScipShardWriter.writeExternalSymbols(shard, external.asJava)
      } catch {
        case e: IOException => warnings += s"failed to write external symbols: $e"
      }
    }
  }

  /** Top-level classes grouped by the source file their tree came from.
    *
    * A file usually defines several top-level classes, and one document has to describe all
    * of them. The grouping key is `SourceFile.path`, which is what TASTy pickled relative to
    * the compile-time `-sourceroot`.
    */
  private def groupBySource(
    projectEntries: List[ClasspathEntry],
    warnings: mutable.ListBuffer[String],
  )(using Context): List[(String, List[ClassSymbol])] = {
    val grouped = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[ClassSymbol]]
    // An entry contributing no packages -- an empty `test-classes` directory, say -- is never
    // registered with the loader, and `findSymbolsByClasspathEntry` throws
    // `UnknownClasspathEntry` for it rather than returning nothing. Skipped silently: a
    // project with no test sources is entirely normal and not worth a warning.
    for (entry <- projectEntries if entry.listAllPackages().nonEmpty) {
      eachRoot(entry, warnings).foreach { cls =>
        try
          cls.tree.foreach { tree =>
            val path: String = tree.pos.sourceFile.path
            if (path.nonEmpty) grouped.getOrElseUpdate(path, mutable.ListBuffer.empty) += cls
          }
        catch {
          case NonFatal(e) =>
            // A TASTy version mismatch surfaces here, lazily, rather than from
            // `ClasspathLoaders.read`. Report it against the class, not the run.
            warnings += s"failed to read the tree of ${cls.displayFullName}: $e"
        }
      }
    }
    grouped.view.mapValues(_.toList).toList
  }

  /** The top-level TASTy classes of one classpath entry, isolating failures to one class.
    *
    * Looks up each `.tasty` file individually via `entry.listAllPackages()` rather than
    * iterating `findSymbolsByClasspathEntry(entry)`: the latter shares one iterator across
    * the entire entry, so a single `AssertionError` from tasty-query aborts enumeration and
    * loses every remaining class in that module. Looking up each `ClassData` with a `.tasty`
    * file independently keeps every sibling class intact and names the failing class in the
    * warning.
    */
  private def eachRoot(
    entry: ClasspathEntry,
    warnings: mutable.ListBuffer[String],
  )(using Context): List[ClassSymbol] = {
    val ctx: Context = summon[Context]
    val roots = mutable.LinkedHashSet.empty[ClassSymbol]
    for {
      pkg <- entry.listAllPackages()
      cd <- pkg.listAllClassDatas() if cd.hasTastyFile
    } {
      val baseName: String = cd.binaryName.stripSuffix("$")
      val fullName: String =
        if (pkg.dotSeparatedName.isEmpty) baseName
        else s"${pkg.dotSeparatedName}.$baseName"
      def lookup(find: String => ClassSymbol): Unit =
        try roots += find(fullName)
        catch {
          case _: MemberNotFoundException => ()
          case NonFatal(e) =>
            warnings += s"failed to read $fullName: $e"
        }
      lookup(ctx.findTopLevelClass)
      lookup(ctx.findTopLevelModuleClass)
    }
    roots.toList
  }

  /** The source-root-relative path for a path as pickled into TASTy, if it is under the root.
    *
    * dotty relativizes source paths against `-sourceroot`, whose default is `"."`, so under
    * sbt -- which compiles with the build root as its working directory -- the pickled path
    * is already build-root-relative. That is not guaranteed though: a build that sets
    * `-sourceroot` elsewhere, or a source outside it, yields an absolute path. Both shapes
    * are accepted here rather than relying on the common case.
    *
    * A source outside the root yields `None`, matching `ScipShardPaths.shardPath`, which
    * returns empty for exactly that case. There is no sensible shard path for such a file.
    */
  private[tasty] def relativize(sourceRoot: Path, pickled: String): Option[String] = {
    val normalizedRoot: Path = sourceRoot.toAbsolutePath.normalize
    val candidate: Path = Path.of(pickled)
    val absolute: Path =
      if (candidate.isAbsolute) candidate.normalize
      else normalizedRoot.resolve(candidate).normalize
    if (!absolute.startsWith(normalizedRoot)) None
    else {
      // Shard paths and `Document.relative_path` are both unix-style regardless of platform.
      val relative: Path = normalizedRoot.relativize(absolute)
      Some((0 until relative.getNameCount).map(relative.getName(_).toString).mkString("/"))
        .filter(_.nonEmpty)
    }
  }

  private def readSource(sourceRoot: Path, relative: String): Option[String] = {
    val file: Path = sourceRoot.resolve(relative)
    if (Files.isRegularFile(file)) Some(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
    else None
  }
}
