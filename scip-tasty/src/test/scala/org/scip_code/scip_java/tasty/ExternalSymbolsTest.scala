package org.scip_code.scip_java.tasty

import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import org.scip_code.scip.{Index, SymbolInformation}

/** External symbols describe definitions that live outside the indexed sources.
  *
  * This is the capability TASTy makes possible and SemanticDB cannot: SemanticDB is never
  * published inside a jar, so nothing can be said about a dependency's symbols without
  * indexing the dependency itself. TASTy ships inside every published `_3` artifact.
  */
class ExternalSymbolsTest {
  import OccurrenceParityTest.shards

  /** Only symbols no indexed source defines may be described as external.
    *
    * A project symbol appearing here would be described twice, once with a definition
    * occurrence in its document and once as an external symbol with none.
    */
  @Test
  def externalSymbolsExcludeProjectDefinitions(): Unit = {
    val projectSymbols: Set[String] =
      OccurrenceParity.fromShards(targetRootOf).map { case (sym, _, _, _, _) => sym }
    val overlap: List[String] =
      external.map(_.getSymbol).filter(projectSymbols.contains).sorted
    assertTrue(external.nonEmpty, "the fixture references library symbols, so some are external")
    println(s"[external] ${external.size} symbols described; ${external.map(_.getSymbol).toSet.size} distinct")
    // A project symbol is referenced *and* defined, so it must not appear as external.
    assertEquals(
      List.empty[String],
      overlap.filter(_.startsWith("com/example/")),
      "project definitions must not be described as external symbols",
    )
  }

  /** The fixture calls `String.valueOf`, `Math.max` and `StringBuilder.append`, so the JDK
    * symbols it resolved are exactly what an external description is for.
    */
  @Test
  def describesTheLibrarySymbolsTheFixtureUses(): Unit = {
    val symbols: Set[String] = external.map(_.getSymbol).toSet
    List("java/lang/Math#max().", "java/lang/String#valueOf().").foreach { expected =>
      assertTrue(
        symbols.contains(expected),
        s"expected an external description of $expected, got:\n${symbols.toList.sorted.mkString("\n")}",
      )
    }
  }

  /** Every description must carry the fields a reader actually shows. */
  @Test
  def externalSymbolsAreWellFormed(): Unit = {
    val problems = List.newBuilder[String]
    external.foreach { info =>
      if (info.getSymbol.isEmpty) problems += "an external symbol has no symbol"
      if (info.getDisplayName.isEmpty) problems += s"${info.getSymbol}: no display name"
      // `SemanticdbToScip.addCliqueReferences` compares display names, so an empty one
      // silently drops the symbol out of any reference clique.
      if (info.getSymbol.startsWith("local")) problems += s"${info.getSymbol}: local symbol"
    }
    assertEquals(List.empty[String], problems.result(), "malformed external symbols")
  }

  private def external: List[SymbolInformation] = {
    shards // force the conversion
    val out = List.newBuilder[SymbolInformation]
    Using.resource(Files.walk(targetRootOf)) { stream =>
      stream
        .iterator()
        .asScala
        .filter(_.getFileName.toString.endsWith(".scip"))
        .foreach { file =>
          out ++= Index
            .parseFrom(Files.readAllBytes(file))
            .getExternalSymbolsList
            .asScala
        }
    }
    out.result()
  }

  private def targetRootOf: java.nio.file.Path = {
    shards
    OccurrenceParityTest.targetRootForTests
  }
}
