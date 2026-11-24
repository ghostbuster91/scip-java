package org.scip_code.scip_java.tasty

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import org.scip_code.scip.{Index, Relationship, SymbolInformation}
import org.scip_code.scip_java.semanticdb.SemanticdbToScip

/** Compares what the two front-ends *say about* each symbol, not just which symbols they
  * name or where they place them.
  *
  * This test exists because of a blind spot it was written to close. The symbol-string and
  * occurrence comparisons both passed while the TASTy side was silently missing definition
  * cliques and `enclosing_symbol` -- neither of which is a symbol string or an occurrence,
  * so neither instrument could see them. Anything else absent from `SymbolInformation`
  * would have been equally invisible.
  *
  * Both front-ends run over the same compiled fixture, into separate target roots, and
  * their `SymbolInformation` is compared field by field.
  */
class SymbolInformationParityTest {
  import SymbolInformationParityTest.*

  /** Shared symbols must agree on display name, which is load-bearing rather than
    * cosmetic: `SemanticdbToScip.addCliqueReferences` compares display names to decide
    * whether two symbols belong in one clique.
    */
  @Test
  def displayNamesAgree(): Unit =
    assertEquals(List.empty[String], disagreements(_.getDisplayName, "display_name"))

  /** Kind drives the icon and the filtering in a client, so a mismatch is user-visible. */
  @Test
  def kindsAgree(): Unit =
    assertEquals(List.empty[String], disagreements(_.getKind.toString, "kind"))

  /** This is the field whose absence the suite could not see before. */
  @Test
  def enclosingSymbolsAgree(): Unit =
    assertEquals(List.empty[String], disagreements(_.getEnclosingSymbol, "enclosing_symbol"))

  /** The TASTy side must not invent an override the compiler does not report.
    *
    * Asserted one-directionally, because the two directions do different damage. A spurious
    * override puts a wrong entry in "find implementations" -- actively misleading. A missing
    * one only costs an entry, and `allOverriddenSymbols` does come up empty occasionally:
    * on this fixture it misses `Any#toString` for a generated `toString`, which is reported
    * by [[reportOverrideCoverage]] rather than failing the build.
    */
  @Test
  def noInventedOverrides(): Unit = {
    val invented = shared.toList.sorted.flatMap { symbol =>
      val expected = overrideTargets(fromSemanticdb(symbol))
      overrideTargets(fromTasty(symbol)).diff(expected).toList.sorted.map { target =>
        s"$symbol claims to override $target, which the compiler does not report"
      }
    }
    assertEquals(List.empty[String], invented)
  }

  /** Overrides the compiler reports that the TASTy side did not find. */
  @Test
  def reportOverrideCoverage(): Unit = {
    val missing = shared.toList.sorted.flatMap { symbol =>
      overrideTargets(fromSemanticdb(symbol))
        .diff(overrideTargets(fromTasty(symbol)))
        .toList
        .sorted
        .map(target => s"$symbol -> $target")
    }
    println(s"[overrides] ${missing.size} reported by the compiler and not found from TASTy")
    missing.foreach(m => println(s"  $m"))
  }

  /** Clique membership is reported rather than asserted, and deliberately so.
    *
    * SemanticDB's cliques are derived from `definition_relationships`, which the *compiler*
    * computes and writes. TASTy does not pickle them, so the TASTy side reconstructs cliques
    * from definition offsets instead. That recovers the same sets in the common cases but
    * cannot be made identical in general -- an attempt to force it produced rules fitted to
    * one fixture rather than to the language.
    *
    * What is asserted is the part that must hold regardless of how the sets are derived: a
    * clique never relates two symbols with different display names, and never points at a
    * symbol nothing describes. Those are the ways a wrong clique does damage -- by sending
    * "find references" somewhere unrelated, or nowhere at all.
    */
  @Test
  def cliquesAreWellFormed(): Unit = {
    val described: Set[String] = tastySymbols.keySet
    val problems = tastySymbols.toList.sortBy(_._1).flatMap { case (symbol, info) =>
      info.getRelationshipsList.asScala.toList.filter(_.getIsReference).flatMap { r =>
        val target = r.getSymbol
        if (target == symbol) Some(s"$symbol references itself")
        else if (r.getIsImplementation) None // an override, not a clique member
        else if (!described.contains(target) && !target.startsWith("java/")) {
          Some(s"$symbol -> $target: nothing describes the target")
        } else None
      }
    }
    assertEquals(List.empty[String], problems, "malformed clique relationships")
  }

  /** How far the reconstructed cliques are from the compiler's, as a number worth watching. */
  @Test
  def reportCliqueDifferences(): Unit = {
    val differing = disagreements(info => renderRelationships(info), "relationships")
    println(s"[cliques] ${differing.size} of ${shared.size} shared symbols differ in relationships")
    differing.foreach(d => println(s"  $d"))
  }

  /** Hover text is deliberately *not* compared for equality.
    *
    * The SemanticDB path renders it with `SignatureFormatter`; the TASTy path has only
    * `showBasic`, since tasty-query keeps `Printers` private. The strings differ by design.
    * What is worth holding is that a symbol described by one side is not left without any
    * hover by the other.
    */
  @Test
  def reportSignatureCoverage(): Unit = {
    val both = shared
    val onlySemanticdb = both.filter { s =>
      fromSemanticdb(s).hasSignatureDocumentation && !fromTasty(s).hasSignatureDocumentation
    }
    val onlyTasty = both.filter { s =>
      !fromSemanticdb(s).hasSignatureDocumentation && fromTasty(s).hasSignatureDocumentation
    }
    println(s"""
      |=== SymbolInformation parity ===
      |  described by both:   ${both.size}
      |  only in SemanticDB:  ${(semanticdbSymbols.keySet -- tastySymbols.keySet).size}
      |  only in TASTy:       ${(tastySymbols.keySet -- semanticdbSymbols.keySet).size}
      |  hover only SemanticDB: ${onlySemanticdb.size}
      |  hover only TASTy:      ${onlyTasty.size}
      |
      |--- described only by SemanticDB ---
      |${(semanticdbSymbols.keySet -- tastySymbols.keySet).toList.sorted.mkString("\n")}
      |
      |--- described only by TASTy ---
      |${(tastySymbols.keySet -- semanticdbSymbols.keySet).toList.sorted.mkString("\n")}
      |
      |--- hover text missing on the TASTy side ---
      |${onlySemanticdb.toList.sorted.mkString("\n")}
      |""".stripMargin)
    assertTrue(both.nonEmpty, "the two front-ends describe no symbol in common")
  }

  private def disagreements(field: SymbolInformation => String, label: String): List[String] =
    shared.toList.sorted.flatMap { symbol =>
      val expected = field(fromSemanticdb(symbol))
      val obtained = field(fromTasty(symbol))
      Option.when(expected != obtained)(
        s"$symbol: $label semanticdb=<$expected> tasty=<$obtained>"
      )
    }

  private def shared: Set[String] = semanticdbSymbols.keySet.intersect(tastySymbols.keySet)
  private def fromSemanticdb(symbol: String): SymbolInformation = semanticdbSymbols(symbol)
  private def fromTasty(symbol: String): SymbolInformation = tastySymbols(symbol)
}

object SymbolInformationParityTest {

  /** The symbols `info` claims to override. */
  private def overrideTargets(info: SymbolInformation): Set[String] =
    info.getRelationshipsList.asScala.toList.filter(_.getIsImplementation).map(_.getSymbol).toSet

  private def renderRelationships(info: SymbolInformation): String =
    info.getRelationshipsList.asScala.toList
      .map { (r: Relationship) =>
        val flags = List(
          Option.when(r.getIsReference)("ref"),
          Option.when(r.getIsImplementation)("impl"),
          Option.when(r.getIsDefinition)("def"),
          Option.when(r.getIsTypeDefinition)("typedef"),
        ).flatten
        s"${r.getSymbol}[${flags.mkString(",")}]"
      }
      .sorted
      .mkString(" ")

  /** Local symbols are excluded: the two front-ends number them independently, so a
    * `local 3` on one side is not the `local 3` on the other and comparing them by name is
    * meaningless. Their *structure* is covered by the occurrence tests instead.
    */
  private def isComparable(symbol: String): Boolean =
    symbol.nonEmpty && !symbol.startsWith("local") && !symbol.contains("<?>")

  lazy val tastySymbols: Map[String, SymbolInformation] = {
    OccurrenceParityTest.shards
    readSymbols(OccurrenceParityTest.targetRootForTests)
  }

  lazy val semanticdbSymbols: Map[String, SymbolInformation] = {
    val out = Files.createTempDirectory("scip-semanticdb-shards")
    val result = SemanticdbToScip(sourceRoot, out).convert(List(compiledSemanticdb).asJava)
    result.warnings.asScala.foreach(w => println(s"[semanticdb warning] $w"))
    assert(result.shardsWritten > 0, s"SemanticdbToScip wrote no shards from $compiledSemanticdb")
    readSymbols(out)
  }

  /** The same compilation both other suites use, so the fixture is built once. */
  private def compiledSemanticdb: Path = {
    OccurrenceParityTest.shards
    OccurrenceParityTest.semanticdbRootForTests
  }

  private def readSymbols(targetRoot: Path): Map[String, SymbolInformation] = {
    val out = Map.newBuilder[String, SymbolInformation]
    Using.resource(Files.walk(targetRoot)) { stream =>
      stream
        .iterator()
        .asScala
        .filter(_.getFileName.toString.endsWith(".scip"))
        .foreach { file =>
          Index.parseFrom(Files.readAllBytes(file)).getDocumentsList.asScala.foreach { doc =>
            doc.getSymbolsList.asScala.foreach { info =>
              if (isComparable(info.getSymbol)) out += (info.getSymbol -> info)
            }
          }
        }
    }
    out.result()
  }

  private def sourceRoot: Path = {
    val url = getClass.getClassLoader.getResource("parity")
    assert(url != null, "missing test resource: parity")
    Paths.get(url.toURI)
  }
}
