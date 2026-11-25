package org.scip_code.scip_java.tasty

import scala.jdk.CollectionConverters.*

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import org.scip_code.scip.{Document, Occurrence, SymbolRole}

/** Backticked identifiers and import selectors, which `nameRange` and the traverser used to
  * have no case for at all -- a backticked def's own definition range was shifted onto its
  * opening quote, and an import clause (aliased or not) produced no occurrence whatsoever.
  */
class BacktickAndImportParityTest {

  @Test
  def backtickedDefinitionAndReferenceShareOneSymbol(): Unit = {
    val occs = occurrencesNamed("type")
    val (definitions, references) = occs.partition(isDefinition)
    assertEquals(1, definitions.size, s"expected one definition of `type`: $occs")
    assertEquals(1, references.size, s"expected one reference to `type`: $occs")
    assertEquals(definitions.head.getSymbol, references.head.getSymbol)
  }

  @Test
  def aliasedImportReferencesTheOriginalName(): Unit = {
    // One from the import clause's selector itself, one from the plain `Greeting.default`
    // the method body reads instead of the alias (see the fixture for why).
    val refs = occurrencesNamed("default").filterNot(isDefinition)
    assertEquals(2, refs.size, s"expected two references to `default`: $refs")
    refs.foreach { ref =>
      assertTrue(ref.getSymbol.endsWith("Greeting.default."), ref.getSymbol)
    }
  }

  @Test
  def plainImportReferencesTheImportedTypeAndItsCompanion(): Unit = {
    // `Try` names both a class and its companion object's value; the import selector must
    // resolve to both, not just whichever one a name lookup happens to find first.
    val symbols = occurrencesNamed("Try").filterNot(isDefinition).map(_.getSymbol).toSet
    assertEquals(Set("scala/util/Try#", "scala/util/Try."), symbols, symbols.toString)
    // Package segments in `import scala.util.{Try => Attempt}` must not emit occurrences
    // (falling through to `super.traverse` used to emit both `scala/` and `scala/util/`).
    assertEquals(
      List.empty[Occurrence],
      occurrencesNamed("util").filter(_.getSymbol == "scala/util/"),
      "package qualifier in import clause must not emit an occurrence",
    )
  }

  @Test
  def valueImportReferencesTheMemberOfTheImportedValue(): Unit = {
    // `import g.greet` imports from a method parameter (not a singleton object); both the
    // import selector and the body reference must resolve to `Greeting#greet().`.
    val refs = occurrencesNamed("greet").filterNot(isDefinition)
    assertEquals(2, refs.size, s"expected two references to `greet`: $refs")
    refs.foreach { ref =>
      assertTrue(ref.getSymbol.endsWith("Greeting#greet()."), ref.getSymbol)
    }
  }

  private def isDefinition(occ: Occurrence): Boolean =
    (occ.getSymbolRoles & SymbolRole.Definition_VALUE) != 0

  private def occurrencesNamed(name: String): List[Occurrence] = {
    val builder = List.newBuilder[Occurrence]
    OccurrenceParityTest.eachDocument { doc =>
      if (doc.getRelativePath == "com/example/Fixture.scala") {
        doc.getOccurrencesList.asScala.foreach { occ =>
          if (isAtName(doc, occ, name)) builder += occ
        }
      }
    }
    builder.result()
  }

  /** Whether `occ`'s range covers `name`, backticks and all if it is quoted -- the
    * occurrence range includes the quotes, matching the compiler's own convention.
    */
  private def isAtName(doc: Document, occ: Occurrence, name: String): Boolean = {
    val r = org.scip_code.scip_java.shared.ScipRange.from(occ)
    r.startLine == r.endLine && {
      val line: String = doc.getText.linesIterator.drop(r.startLine).next()
      r.startCharacter >= 0 &&
      r.endCharacter <= line.length && {
        val text = line.substring(r.startCharacter, r.endCharacter)
        text == name || text == s"`$name`"
      }
    }
  }
}
