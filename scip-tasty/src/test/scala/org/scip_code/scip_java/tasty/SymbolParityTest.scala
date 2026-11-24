package org.scip_code.scip_java.tasty

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The gate the whole TASTy front-end hangs on.
  *
  * Java-to-Scala references resolve by string equality, so a descriptor the two sides
  * spell differently is a reference that silently fails to resolve. This compiles a
  * fixture once with `-Xsemanticdb` and checks that the symbols the compiler recorded are
  * reproduced byte-for-byte from the `.tasty` beside it.
  */
class SymbolParityTest:

  /** No symbol the compiler recorded may be missing from, or differently spelled by, the
    * TASTy side. This is the direction that breaks navigation.
    */
  @Test
  def tastyReproducesEveryCompilerSymbol(): Unit =
    val (expected, obtained) = bothSides()
    val missing = (expected -- obtained).toList.sorted
    assertEquals(
      List.empty[String],
      missing,
      s"${missing.size} of ${expected.size} compiler symbols were not reproduced from TASTy",
    )

  /** The reverse direction is informational, not a failure.
    *
    * TASTy carries compiler-generated members the SemanticDB extractor never records --
    * `equals`, `hashCode`, `product*`, `writeReplace`, `fromProduct`, `MirroredMonoType`.
    * Nothing in source references them, so extra metadata costs index size rather than
    * resolution. What must hold is that none of them is source-positioned: if one were, it
    * would claim a definition occurrence the compiler does not give it.
    */
  @Test
  def extraSymbolsAreAllSynthetic(): Unit =
    val (expected, obtained) = bothSides()
    val extra = (obtained -- expected).toList.sorted
    val sourcePositioned = ParityHarness.sourcePositioned(compiled, extra.toSet)
    assertEquals(
      List.empty[String],
      sourcePositioned,
      "symbols absent from SemanticDB must not be source-positioned, or they would claim " +
        "definition occurrences the compiler does not emit",
    )
    println(s"[parity] ${expected.size} symbols agreed; ${extra.size} synthetic extras carried by TASTy")

  /** The hard disambiguator case: references to overloaded *Java* methods.
    *
    * A Java owner has no tree in tasty-query -- `setTree` is only called by the TASTy
    * reader -- so there is no declaration order to index into and `OverloadDisambiguator`
    * falls back to `()`. Every method the fixture calls is heavily overloaded in the JDK,
    * so this measures how far that fallback is from what the compiler wrote.
    */
  @Test
  def javaOwnerReferenceParity(): Unit =
    val expected = ParityHarness.semanticdbReferences(compiled.semanticdb, "java/")
    val obtained = ParityHarness.tastyReferences(compiled, "java/")
    val agreed = expected.intersect(obtained)
    val missing = (expected -- obtained).toList.sorted
    val extra = (obtained -- expected).toList.sorted

    println(s"""
      |=== java-owner reference parity ===
      |  agreed:  ${agreed.size} / ${expected.size}
      |  missing: ${missing.size}
      |  extra:   ${extra.size}
      |
      |--- missing (compiler emitted, TASTy did not) ---
      |${missing.mkString("\n")}
      |
      |--- extra (TASTy emitted, compiler did not) ---
      |${extra.mkString("\n")}
      |""".stripMargin)

    assert(expected.nonEmpty, "no java/ references in the compiler output -- fixture is wrong")

    // The assertion is scoped to method symbols, which is what the disambiguator governs.
    // Package-prefix occurrences (`java/lang/`) are a traversal gap, not a minting one:
    // a package qualifier in *type* position is not a `TermReferenceTree`, so this harness
    // never visits it. `TastySymbols` spells `PackageSymbol`s correctly.
    val missingMethods = missing.filter(_.endsWith(")."))
    assertEquals(
      List.empty[String],
      missingMethods,
      s"${missingMethods.size} java-owned method symbols were not reproduced; " +
        "a wrong overload disambiguator silently mis-resolves the reference",
    )
  end javaOwnerReferenceParity

  private def bothSides(): (Set[String], Set[String]) =
    val expected = ParityHarness.semanticdbSymbols(compiled.semanticdb)
    val obtained = ParityHarness.tastySymbols(compiled)
    assert(expected.nonEmpty, "the compiler produced no global symbols -- harness is broken")
    assert(obtained.nonEmpty, "TASTy produced no global symbols -- harness is broken")
    (expected, obtained)

  private lazy val compiled: ParityHarness.Compiled =
    val sourceroot = resourceDir("parity")
    val sources = Using.resource(Files.walk(sourceroot)) { s =>
      s.iterator().asScala.filter(_.getFileName.toString.endsWith(".scala")).toList
    }
    assert(sources.nonEmpty, s"no fixture sources under $sourceroot")
    ParityHarness.compile(sources, sourceroot, Files.createTempDirectory("scip-tasty-parity"))

  private def resourceDir(name: String): Path =
    val url = getClass.getClassLoader.getResource(name)
    assert(url != null, s"missing test resource: $name")
    Paths.get(url.toURI)
end SymbolParityTest
