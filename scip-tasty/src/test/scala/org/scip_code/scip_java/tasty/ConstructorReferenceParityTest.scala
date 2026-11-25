package org.scip_code.scip_java.tasty

import scala.jdk.CollectionConverters.*

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import org.scip_code.scip.{Document, Occurrence, SymbolRole}

/** `new Foo(...)` must carry a reference to the constructor it calls, not just to the class.
  *
  * `<init>` is never written in source, so `reference` cannot match the resolved symbol's own
  * name the way it does for everything else; it has to be anchored to the class name the `New`
  * targets instead. Without that, "find references" on a constructor never finds its call sites.
  */
class ConstructorReferenceParityTest {

  @Test
  def noArgConstructorCallIsReferenced(): Unit = {
    val refs = constructorReferences("Overloads")
    assertEquals(1, refs.size, s"expected one constructor reference to Overloads: $refs")
    assertTrue(refs.head.getSymbol.endsWith("Overloads#`<init>`()."), refs.head.getSymbol)
  }

  @Test
  def parameterizedConstructorCallIsReferenced(): Unit = {
    val refs = constructorReferences("Greeting")
    assertEquals(1, refs.size, s"expected one constructor reference to Greeting: $refs")
    assertTrue(refs.head.getSymbol.endsWith("Greeting#`<init>`()."), refs.head.getSymbol)
  }

  private def constructorReferences(className: String): List[Occurrence] = {
    val builder = List.newBuilder[Occurrence]
    OccurrenceParityTest.eachDocument { doc =>
      if (doc.getRelativePath == "com/example/Fixture.scala") {
        doc.getOccurrencesList.asScala.foreach { occ =>
          val isReference: Boolean = (occ.getSymbolRoles & SymbolRole.Definition_VALUE) == 0
          if (isReference && occ.getSymbol.contains(s"$className#`<init>`")) builder += occ
        }
      }
    }
    builder.result()
  }
}
