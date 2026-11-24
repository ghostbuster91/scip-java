package org.scip_code.scip_java.tasty

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.{util as ju}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import org.scip_code.scip_java.shared.{ScipShardPaths, ScipShardWriter}
import tastyquery.Classpaths.{Classpath, ClasspathEntry}
import tastyquery.Contexts
import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.jdk.ClasspathLoaders

/** Converts Scala 3 TASTy into SCIP shards, one per source file.
  *
  * The shards carry bare descriptor symbols in the same layout the javac and kotlinc
  * plugins write (`<targetroot>/META-INF/scip/<relative-source>.scip`), so `scip-aggregator`
  * qualifies them and resolves references across languages exactly as it already does. This
  * deliberately adds no second aggregation entry point.
  *
  * The entry point is shaped like `SemanticdbToScip` so Kotlin callers never touch Scala
  * types, and it follows the same rule that per-file failures are warnings rather than
  * errors: the worst a bad file should cost is its own document, not the whole index.
  */
object TastyToScip:

  /** What one conversion produced. `coveredSources` are source-root-relative paths, which is
    * what `ScipShardPaths.shardPathForRelativeSource` keys on -- a second writer for the
    * same relative path would silently overwrite the shard.
    */
  final case class Result(
    shardsWritten: Int,
    warnings: ju.List[String],
    coveredSources: ju.Set[String],
  )

  /** Indexes every Scala 3 class found in `classDirs`.
    *
    * `classpath` must be the full compile classpath, including the JRE: TASTy cannot be
    * made sense of without it. `sourceRoot` is where the paths pickled into TASTy are
    * resolved from, so it must be the `-sourceroot` the project was compiled with.
    */
  def convert(
    sourceRoot: Path,
    targetRoot: Path,
    classDirs: ju.List[Path],
    classpath: ju.List[Path],
  ): Result =
    val warnings = mutable.ListBuffer.empty[String]
    val covered = mutable.LinkedHashSet.empty[String]
    var written = 0

    val projectEntries = classDirs.asScala.toList
    val entries = ClasspathLoaders.read(projectEntries ++ classpath.asScala.toList)
    // `findSymbolsByClasspathEntry` compares entries by `equals`, so the instances handed to
    // `initialize` are the ones that must be passed back in.
    val projectCount = projectEntries.size
    given Context = Contexts.Context.initialize(entries)
    given JavaOrderLookup = JavaOrderLookup(Some(JavaMethodOrder(entries)))

    for (sourcePath, classes) <- groupBySource(entries.take(projectCount), warnings) do
      try
        readSource(sourceRoot, sourcePath) match
          case None =>
            warnings += s"skipping $sourcePath: not found under source root $sourceRoot"
          case Some(text) =>
            val indexer = TastyDocumentIndexer(sourcePath, text)
            classes.foreach(cls => cls.tree.foreach(indexer.index))
            val document = indexer.result()
            if document.getOccurrencesCount > 0 then
              val shard = ScipShardPaths.shardPathForRelativeSource(targetRoot, Path.of(sourcePath))
              Files.createDirectories(shard.getParent)
              ScipShardWriter.writeShard(shard, document)
              covered += sourcePath
              written += 1
      catch
        case e: IOException            => warnings += s"failed to write a shard for $sourcePath: $e"
        case e: RuntimeException       => warnings += s"failed to index $sourcePath: $e"
        case e: StackOverflowError     => warnings += s"failed to index $sourcePath: $e"

    Result(written, warnings.toList.asJava, covered.asJava)
  end convert

  /** Top-level classes grouped by the source file their tree came from.
    *
    * A file usually defines several top-level classes, and one document has to describe all
    * of them. The grouping key is `SourceFile.path`, which is what TASTy pickled relative to
    * the compile-time `-sourceroot`.
    */
  private def groupBySource(
    projectEntries: List[ClasspathEntry],
    warnings: mutable.ListBuffer[String],
  )(using Context): List[(String, List[ClassSymbol])] =
    val grouped = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[ClassSymbol]]
    for entry <- projectEntries do
      val roots =
        try summon[Context].findSymbolsByClasspathEntry(entry).toList
        catch
          case e: RuntimeException =>
            warnings += s"failed to enumerate $entry: $e"
            Nil
      for case cls: ClassSymbol <- roots do
        try
          cls.tree.foreach { tree =>
            val path = tree.pos.sourceFile.path
            if path.nonEmpty then grouped.getOrElseUpdate(path, mutable.ListBuffer.empty) += cls
          }
        catch
          case e: RuntimeException =>
            // A TASTy version mismatch surfaces here, lazily, rather than from
            // `ClasspathLoaders.read`. Report it against the class, not the run.
            warnings += s"failed to read the tree of ${cls.displayFullName}: $e"
    grouped.view.mapValues(_.toList).toList
  end groupBySource

  private def readSource(sourceRoot: Path, relative: String): Option[String] =
    val file = sourceRoot.resolve(relative)
    if Files.isRegularFile(file) then
      Some(String(Files.readAllBytes(file), StandardCharsets.UTF_8))
    else None
end TastyToScip
