package org.scip_code.scip_java.tasty

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import org.scip_code.scip.{Index, Occurrence}
import org.scip_code.scip_java.shared.ScipRange

/** Drives `TastyToScip` end to end and compares its occurrences against the compiler's.
  *
  * Symbol parity is necessary but not sufficient: a correct symbol at the wrong range is
  * still a broken "go to definition". This is the test that exercises the range handling --
  * name ranges derived from `pointOffset` and checked against the source text, and the
  * per-document filter that stops an `Inlined` body attributing occurrences to the file it
  * was defined in rather than the file being indexed.
  */
class OccurrenceParityTest:
  import OccurrenceParityTest.*

  /** Every source-named occurrence the compiler emits must be reproduced, range included.
    *
    * Scoped to what the implementation actually claims to cover, so the test fails on a
    * regression rather than standing permanently red. Three categories are excluded and
    * tracked as known gaps by [[reportKnownGaps]]:
    *   - package references, because the `package` clause lives in a `PackageDef` that
    *     tasty-query only exposes through `private[tastyquery] topLevelTasty`;
    *   - zero-extent occurrences, which the compiler emits as markers for constructors --
    *     `<init>` never appears in source, so there is no name to anchor a range to;
    *   - constructors, for the same reason.
    */
  @Test
  def sourceNamedOccurrencesMatchTheCompiler(): Unit =
    val missing = (expected -- obtained).filter(isSourceNamed).toList.sorted
    assertEquals(
      List.empty[String],
      missing.map(render),
      s"${missing.size} source-named occurrences were not reproduced",
    )

  /** The known gaps, reported rather than asserted, so their size stays visible. */
  @Test
  def reportKnownGaps(): Unit =
    val missing = (expected -- obtained).toList.sorted
    val extra = (obtained -- expected).toList.sorted
    println(s"""
      |=== occurrence parity (symbol + range) ===
      |  agreed:  ${expected.intersect(obtained).size} / ${expected.size}
      |  missing: ${missing.size} (${missing.count(isSourceNamed)} source-named)
      |  extra:   ${extra.size}
      |
      |--- missing ---
      |${missing.map(render).mkString("\n")}
      |
      |--- extra ---
      |${extra.map(render).mkString("\n")}
      |""".stripMargin)
    assertTrue(expected.nonEmpty, "the compiler produced no global occurrences")
    assertTrue(obtained.nonEmpty, "TastyToScip produced no global occurrences")

  /** No occurrence may point outside the document holding it.
    *
    * This is the invariant the per-file filter exists for. A violation means a position from
    * another source file leaked in -- most likely through an `Inlined` body -- which would
    * send navigation to an unrelated line of an unrelated file.
    */
  @Test
  def occurrencesStayInsideTheirDocument(): Unit =
    val offenders = List.newBuilder[String]
    eachDocument { doc =>
      val lineCount = doc.getText.linesIterator.size
      doc.getOccurrencesList.asScala.foreach { occ =>
        val r = ScipRange.from(occ)
        if r.startLine < 0 || r.endLine > lineCount then
          offenders += s"${doc.getRelativePath}: ${occ.getSymbol} at ${r.startLine}..${r.endLine} of $lineCount lines"
      }
    }
    assertEquals(List.empty[String], offenders.result(), "occurrences outside their document")

  /** Document shape the aggregator and any cross-front-end comparison depend on. */
  @Test
  def documentsAreWellFormed(): Unit =
    val problems = List.newBuilder[String]
    eachDocument { doc =>
      // `SemanticdbToScip` derives this as `Language.SCALA.name.toLowerCase`; a mismatch
      // silently splits the two front-ends in any diff.
      if doc.getLanguage != "scala" then problems += s"${doc.getRelativePath}: language=${doc.getLanguage}"
      if doc.getText.isEmpty then problems += s"${doc.getRelativePath}: empty text"
      if doc.getRelativePath.isEmpty then problems += "a document has no relative path"
      doc.getOccurrencesList.asScala.foreach { occ =>
        // `ScipRange.from` and `ScipAggregator.rewriteDocument` both throw on this.
        if occ.getTypedRangeCase == Occurrence.TypedRangeCase.TYPEDRANGE_NOT_SET then
          problems += s"${doc.getRelativePath}: ${occ.getSymbol} has no typed range"
      }
    }
    assertEquals(List.empty[String], problems.result(), "malformed documents")

  /** Shards must land where the aggregator looks for them, one per source file. */
  @Test
  def shardsAreWrittenWhereTheAggregatorLooks(): Unit =
    assertEquals(2, shards.shardsWritten, "expected one shard per fixture source")
    assertEquals(
      Set("com/example/Fixture.scala", "com/example/JavaInterop.scala"),
      shards.coveredSources.asScala.toSet,
      "coveredSources must be source-root-relative, which is what the shard path keys on",
    )
    assertEquals(List.empty[String], shards.warnings.asScala.toList, "conversion warnings")
end OccurrenceParityTest

object OccurrenceParityTest:

  /** Compiled and indexed once: JUnit builds a fresh test instance per method, so holding
    * these on the instance would recompile the fixture for every test.
    */
  private lazy val compiled: ParityHarness.Compiled =
    val sources = Using.resource(Files.walk(sourceRoot)) { s =>
      s.iterator().asScala.filter(_.getFileName.toString.endsWith(".scala")).toList
    }
    ParityHarness.compile(sources, sourceRoot, Files.createTempDirectory("scip-tasty-occ"))

  lazy val shards: TastyToScip.Result =
    TastyToScip.convert(
      sourceRoot,
      targetRoot,
      List(compiled.classes).asJava,
      (compiled.classpath ++ ParityHarness.jdkPaths).asJava,
    )

  private lazy val targetRoot: Path = Files.createTempDirectory("scip-tasty-shards")

  lazy val expected: Set[OccurrenceParity.Key] = OccurrenceParity.fromSemanticdb(compiled.semanticdb)

  lazy val obtained: Set[OccurrenceParity.Key] =
    shards // force the conversion before reading it back
    OccurrenceParity.fromShards(targetRoot)

  def eachDocument(f: org.scip_code.scip.Document => Unit): Unit =
    shards
    Using.resource(Files.walk(targetRoot)) { stream =>
      stream.iterator().asScala
        .filter(_.getFileName.toString.endsWith(".scip"))
        .foreach { file =>
          Index.parseFrom(Files.readAllBytes(file)).getDocumentsList.asScala.foreach(f)
        }
    }

  /** Whether an occurrence anchors to a name actually written in the source. */
  def isSourceNamed(key: OccurrenceParity.Key): Boolean =
    val (sym, sl, sc, el, ec) = key
    val zeroExtent = sl == el && sc == ec
    !zeroExtent && !sym.endsWith("/") && !sym.contains("`<init>`")

  def render(key: OccurrenceParity.Key): String =
    val (sym, sl, sc, el, ec) = key
    f"$sl%3d:$sc%-3d..$el%3d:$ec%-3d $sym"

  private def sourceRoot: Path =
    val url = getClass.getClassLoader.getResource("parity")
    assert(url != null, "missing test resource: parity")
    Paths.get(url.toURI)
end OccurrenceParityTest
