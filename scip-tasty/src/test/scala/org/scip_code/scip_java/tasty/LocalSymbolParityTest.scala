package org.scip_code.scip_java.tasty

import scala.jdk.CollectionConverters.*

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import org.scip_code.scip.{Document, Occurrence, SymbolRole}
import org.scip_code.scip_java.shared.ScipSymbols

/** A local's definition and every reference to it must share one id.
  *
  * `TastyDocumentIndexer.symbolFor` keys `local N` ids by `(definition offset, name)`. A
  * reference has to look that key up using the *symbol's* definition position, not the
  * position of the reference occurrence itself -- otherwise every use mints a fresh id that
  * never matches the local it refers to, and "go to definition" / "find references" on a
  * local breaks silently (locals are excluded from the cross-front-end parity comparison,
  * so nothing else here would catch it).
  */
class LocalSymbolParityTest {

  /** `JavaInterop.build` assigns `val sb` once and reads it four times, so its local id
    * must appear on exactly one definition occurrence and at least three reference
    * occurrences, all carrying the same symbol.
    */
  @Test
  def localDefinitionMatchesItsReferences(): Unit = {
    val doc: Document = javaInteropDocument

    val sbOccurrences: List[Occurrence] =
      doc.getOccurrencesList.asScala.toList.filter { occ =>
        ScipSymbols.isLocal(occ.getSymbol) && isAtName(doc, occ, "sb")
      }

    val (definitions, references) =
      sbOccurrences.partition(occ => isDefinition(occ))

    assertEquals(1, definitions.size, s"expected exactly one definition of 'sb': $sbOccurrences")
    assertTrue(references.size >= 3, s"expected at least three references to 'sb': $sbOccurrences")

    val definitionSymbol: String = definitions.head.getSymbol
    references.foreach { ref =>
      assertEquals(
        definitionSymbol,
        ref.getSymbol,
        "a reference to 'sb' must carry the same local id as its definition",
      )
    }
  }

  private def isDefinition(occ: Occurrence): Boolean =
    (occ.getSymbolRoles & SymbolRole.Definition_VALUE) != 0

  /** Whether `occ`'s range covers exactly the text `name`, so unrelated locals sharing a
    * document don't get swept into the comparison.
    */
  private def isAtName(doc: Document, occ: Occurrence, name: String): Boolean = {
    val r = org.scip_code.scip_java.shared.ScipRange.from(occ)
    if (r.startLine != r.endLine) false
    else {
      val line: String = doc.getText.linesIterator.drop(r.startLine).next()
      r.startCharacter >= 0 &&
      r.endCharacter <= line.length &&
      line.substring(r.startCharacter, r.endCharacter) == name
    }
  }

  private def javaInteropDocument: Document = {
    val builder = List.newBuilder[Document]
    OccurrenceParityTest.eachDocument { doc =>
      if (doc.getRelativePath == "com/example/JavaInterop.scala") builder += doc
    }
    val docs = builder.result()
    assertEquals(1, docs.size, "expected one JavaInterop.scala document")
    docs.head
  }
}
