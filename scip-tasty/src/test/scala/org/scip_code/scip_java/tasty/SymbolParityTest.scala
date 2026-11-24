package org.scip_code.scip_java.tasty

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test

/** The gate the whole TASTy front-end hangs on.
  *
  * Java-to-Scala references resolve by string equality, so a descriptor the two sides spell
  * differently is a reference that silently fails to resolve. This compiles a fixture once
  * with `-Xsemanticdb` and checks that the symbols the compiler recorded are reproduced
  * byte-for-byte from the `.tasty` beside it.
  */
class SymbolParityTest {
  import SymbolParityTest.*

  /** No symbol the compiler recorded may be missing from, or differently spelled by, the
    * TASTy side. This is the direction that breaks navigation.
    */
  @Test
  def tastyReproducesEveryCompilerSymbol(): Unit = {
    val missing: List[String] = (expected -- obtained).toList.sorted
    assertEquals(
      List.empty[String],
      missing,
      s"${missing.size} of ${expected.size} compiler symbols were not reproduced from TASTy",
    )
  }

  /** The reverse direction is informational, not a failure.
    *
    * TASTy carries compiler-generated members the SemanticDB extractor never records --
    * `equals`, `hashCode`, `product*`, `writeReplace`, `fromProduct`, `MirroredMonoType`.
    * Nothing in source references them, so extra metadata costs index size rather than
    * resolution. What must hold is that none of them is source-positioned: if one were, it
    * would claim a definition occurrence the compiler does not give it.
    */
  @Test
  def extraSymbolsAreAllSynthetic(): Unit = {
    val extra: List[String] = (obtained -- expected).toList.sorted
    val sourcePositioned: List[String] = ParityHarness.sourcePositioned(compiled, extra.toSet)
    assertEquals(
      List.empty[String],
      sourcePositioned,
      "symbols absent from SemanticDB must not be source-positioned, or they would claim " +
        "definition occurrences the compiler does not emit",
    )
    println(
      s"[parity] ${expected.size} symbols agreed; ${extra.size} synthetic extras carried by TASTy"
    )
  }

  /** The hard disambiguator case: references to overloaded *Java* methods.
    *
    * A Java owner has no tree in tasty-query -- `setTree` is only called by the TASTy
    * reader -- so there is no declaration order to index into, and the order has to come
    * from the classfile `methods[]` table instead. Every method the fixture calls is
    * heavily overloaded in the JDK, so a wrong order shows up immediately.
    */
  @Test
  def javaOwnerReferenceParity(): Unit = {
    val expectedRefs: Set[String] = ParityHarness.semanticdbReferences(compiled.semanticdb, "java/")
    val obtainedRefs: Set[String] = ParityHarness.tastyReferences(compiled, "java/")
    val missing: List[String] = (expectedRefs -- obtainedRefs).toList.sorted
    val extra: List[String] = (obtainedRefs -- expectedRefs).toList.sorted

    println(s"""
      |=== java-owner reference parity ===
      |  agreed:  ${expectedRefs.intersect(obtainedRefs).size} / ${expectedRefs.size}
      |  missing: ${missing.size}
      |  extra:   ${extra.size}
      |
      |--- missing (compiler emitted, TASTy did not) ---
      |${missing.mkString("\n")}
      |
      |--- extra (TASTy emitted, compiler did not) ---
      |${extra.mkString("\n")}
      |""".stripMargin)

    assertTrue(expectedRefs.nonEmpty, "no java/ references in the compiler output")

    // Scoped to method symbols, which is what the disambiguator governs. Package-prefix
    // occurrences (`java/lang/`) are a traversal gap, not a minting one: a package
    // qualifier in *type* position is not a `TermReferenceTree`, so this harness never
    // visits it. `TastySymbols` spells `PackageSymbol`s correctly.
    val missingMethods: List[String] = missing.filter(_.endsWith(")."))
    assertEquals(
      List.empty[String],
      missingMethods,
      s"${missingMethods.size} java-owned method symbols were not reproduced; " +
        "a wrong overload disambiguator silently mis-resolves the reference",
    )
  }
}

object SymbolParityTest {

  /** Compiled once: JUnit builds a fresh test instance per method. */
  lazy val compiled: ParityHarness.Compiled = {
    val sources: List[Path] = Using.resource(Files.walk(sourceRoot)) { s =>
      s.iterator().asScala.filter(_.getFileName.toString.endsWith(".scala")).toList
    }
    assert(sources.nonEmpty, s"no fixture sources under $sourceRoot")
    ParityHarness.compile(sources, sourceRoot, Files.createTempDirectory("scip-tasty-parity"))
  }

  lazy val expected: Set[String] = {
    val symbols: Set[String] = ParityHarness.semanticdbSymbols(compiled.semanticdb)
    assert(symbols.nonEmpty, "the compiler produced no global symbols -- harness is broken")
    symbols
  }

  lazy val obtained: Set[String] = {
    val symbols: Set[String] = ParityHarness.tastySymbols(compiled)
    assert(symbols.nonEmpty, "TASTy produced no global symbols -- harness is broken")
    symbols
  }

  private def sourceRoot: Path = {
    val url = getClass.getClassLoader.getResource("parity")
    assert(url != null, "missing test resource: parity")
    Paths.get(url.toURI)
  }
}
