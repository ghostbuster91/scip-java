package org.scip_code.scip_java.tasty

import scala.jdk.CollectionConverters.*

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import org.scip_code.scip.SymbolInformation

/** A case class, its companion's `apply` and its `copy` all sit on one definition
  * occurrence -- the class name. Only one of them can own that range, so without
  * `is_reference` relationships tying them together, "find references" on any one of them
  * misses the occurrences attributed to the others.
  *
  * `SemanticdbToScip` builds these from SemanticDB's `definition_relationships`. The TASTy
  * side builds them from the positions directly, which is dotty's own `symsAtOffset`.
  */
class DefinitionCliqueTest {
  import OccurrenceParityTest.eachDocument

  private def symbols: List[SymbolInformation] = {
    val out = List.newBuilder[SymbolInformation]
    eachDocument(doc => out ++= doc.getSymbolsList.asScala)
    out.result()
  }

  private def referencesOf(symbol: String): Set[String] =
    symbols
      .filter(_.getSymbol == symbol)
      .flatMap(_.getRelationshipsList.asScala)
      .filter(_.getIsReference)
      .map(_.getSymbol)
      .toSet

  /** The generated members must be described even though they get no occurrence, or there
    * is nothing for a clique to relate.
    */
  @Test
  def generatedMembersAreDescribedWithoutOccurrences(): Unit = {
    val described: Set[String] = symbols.map(_.getSymbol).toSet
    assertTrue(
      described.contains("com/example/Greeting#copy()."),
      s"expected `copy` to be described; got:\n${described.toList.sorted.mkString("\n")}",
    )
    assertTrue(described.contains("com/example/Greeting.apply()."), "expected `apply` described")
  }

  /** `copy` and `apply` share the case class's own definition offset, so each should name
    * the others as references.
    */
  @Test
  def caseClassSharesACliqueWithCopyAndApply(): Unit = {
    val fromCopy: Set[String] = referencesOf("com/example/Greeting#copy().")
    assertTrue(
      fromCopy.contains("com/example/Greeting.apply()."),
      s"expected copy -> apply is_reference; got $fromCopy",
    )
  }

  /** The filter matters: an unrelated member that merely happens to start at the same
    * offset must not be dragged into the clique. Mirrors
    * `SemanticdbToScip.addCliqueReferences`, which relates another member only when it is
    * an `apply` or shares the display name.
    */
  @Test
  def cliquesDoNotRelateUnrelatedNames(): Unit = {
    val offenders: List[String] = symbols.flatMap { info =>
      info.getRelationshipsList.asScala
        // An override is also marked as a reference, and legitimately points at a name in a
        // supertype -- `Object#hashCode` -- so it is not a clique member.
        .filter(r => r.getIsReference && !r.getIsImplementation)
        .map(_.getSymbol)
        .filter(other => !other.endsWith(".apply().") && !sameName(info.getSymbol, other))
        .map(other => s"${info.getSymbol} -> $other")
    }
    assertEquals(List.empty[String], offenders, "clique relates symbols of differing names")
  }

  /** A local id says nothing on its own, so the enclosing global symbol is what tells a
    * reader which definition it belongs to. The SemanticDB path carries this; so must this
    * one, or the two front-ends describe locals differently.
    */
  @Test
  def localsCarryTheirEnclosingSymbol(): Unit = {
    val locals: List[SymbolInformation] = symbols.filter(_.getSymbol.startsWith("local"))
    assertTrue(locals.nonEmpty, "the fixture has a local val, so some locals are described")
    val missing: List[String] =
      locals.filter(_.getEnclosingSymbol.isEmpty).map(_.getSymbol).sorted
    assertEquals(List.empty[String], missing, "locals without an enclosing symbol")
  }

  private def sameName(a: String, b: String): Boolean = {
    def display(s: String): String =
      symbols.find(_.getSymbol == s).map(_.getDisplayName).getOrElse("")
    display(a) == display(b)
  }
}
