package org.scip_code.scip_java.tasty

import tastyquery.Contexts.Context
import tastyquery.Modifiers.TermSymbolKind
import tastyquery.Signatures
import tastyquery.Symbols.*
import tastyquery.Trees.*

/** Reproduces the SCIP/SemanticDB method disambiguator -- the `()`, `(+1)`, `(+2)` in
  * `com/Foo#bar(+1).` -- exactly as the Scala 3 compiler mints it.
  *
  * Transcribed from `dotty.tools.dotc.semanticdb.SemanticSymbolBuilder.addOverloadIdx`
  * at dotty tag 3.7.4. It is deliberately a transcription rather than a reading of the
  * SemanticDB spec: the suffix is not a plain declaration index, and the spec prose and
  * the compiler disagree (see the same note in scip-javac's `GlobalSymbolsCache`).
  *
  * The strings this produces have to match the compiler's byte for byte. A Scala
  * definition is referenced from Java by string equality alone, and under per-file
  * fallback a TASTy-indexed document and a SemanticDB-indexed document sit in the same
  * index and must resolve into each other.
  */
object OverloadDisambiguator:

  /** The disambiguator for `sym`, including the enclosing parentheses: `()` or `(+2)`.
    *
    * `javaOrder` supplies declaration order for Java owners, which have no tree. Without
    * it every Java overload collapses to `()`, which silently mis-resolves references to
    * all but the first alternative of a name like `java.lang.Math.max`.
    */
  def of(sym: TermSymbol)(using Context, JavaOrderLookup): String =
    alternatives(sym) match
      case Some(alts) => "(" + suffix(sym, alts) + ")"
      case None       => "(" + javaSuffix(sym) + ")"

  /** dotty numbers a Java owner's overloads in classfile `methods[]` order. */
  private def javaSuffix(sym: TermSymbol)(using Context, JavaOrderLookup): String =
    val owner = sym.owner match
      case cls: ClassSymbol => Some(cls)
      case _                => None
    val resolved = for
      cls <- owner
      order <- summon[JavaOrderLookup].order
      // `staticsFirst` mirrors dotty's `JavaModule` case, which concatenates the statics
      // holder's declarations ahead of the companion class's.
      rows <- order.sameNamed(cls, sym.name.toString, staticsFirst = isStaticsHolder(cls))
      idx = indexOfJava(sym, rows)
      if idx >= 0
    yield if idx == 0 then "" else "+" + idx
    resolved.getOrElse("")

  /** Position of `sym` among the same-named classfile rows, or -1 if not identifiable.
    *
    * tasty-query offers no link from a `TermSymbol` back to its `methods[]` row, so the
    * row is identified by its **erased parameter types**, which uniquely distinguish
    * overloads within a class. Both sides spell them the same way: `Signature.paramsSig`
    * yields erased names (`int`, `java.lang.String`, `int[]`) and [[JvmDescriptor]] decodes
    * the descriptor into that same spelling.
    *
    * `ParamSig.TypeLen` entries are dropped -- they record a type-parameter count, which a
    * JVM descriptor does not carry.
    */
  private def indexOfJava(sym: TermSymbol, rows: Vector[JavaMethodOrderMethod])(using
    Context
  ): Int =
    erasedParams(sym) match
      case None         => -1
      case Some(params) => rows.indexWhere(_.paramTypes == params)

  private def erasedParams(sym: TermSymbol)(using Context): Option[Vector[String]] =
    scala.util.Try(sym.signature).toOption.map { sig =>
      sig.paramsSig.collect { case Signatures.ParamSig.Term(tpe) => tpe.toString }.toVector
    }

  private def isStaticsHolder(cls: ClassSymbol)(using Context): Boolean =
    cls.isModuleClass && cls.sourceLanguage == tastyquery.SourceLanguage.Java

  private def suffix(sym: TermSymbol, alts: List[TermSymbol])(using Context): String =
    val sig = sym.signature
    val target = sym.targetName
    def matches(c: TermSymbol): Boolean =
      c.signature == sig && c.targetName == target
    alts match
      // dotty: if the head already matches, no suffix is appended at all. Otherwise the
      // suffix counts from the *tail*, plus one -- so first => "", second => "+1".
      case head :: rest if !matches(head) =>
        val idx = rest.indexWhere(matches)
        if idx >= 0 then "+" + (idx + 1) else ""
      case _ => ""

  /** The candidate list, in the order dotty numbers it, or `None` if unavailable.
    *
    * dotty reads `owner.info.decls.lookupAll(name).toList.reverse`, the `reverse`
    * undoing the fact that its scopes enumerate most-recent-first. tasty-query has no
    * ordered equivalent -- `declarations` and `getAllOverloadedDecls` are backed by a
    * `HashMap[Name, HashSet[Symbol]]` -- so the order comes from the class body instead,
    * which TASTy pickles in source order and which already contains the synthesized
    * case-class members.
    */
  private def alternatives(sym: TermSymbol)(using Context): Option[List[TermSymbol]] =
    sym.owner match
      case cls: ClassSymbol => declaredInOrder(cls).map(ordered(sym, _))
      case _                => None

  private def ordered(sym: TermSymbol, candidates: Vector[TermSymbol])(using
    Context
  ): List[TermSymbol] =
    // dotty starts from `lookupAll(sym.name)`, so only same-named declarations are
    // candidates -- the index is per-name, not a position within the class.
    val sameName = candidates.filter(_.name == sym.name)
    // Then it filters to `Method | Mutable` -- a `var` occupies an index too -- and
    // partitions non-synthetic before synthetic, independently of source order.
    val (plain, synthetic) =
      sameName.filter(c => c.isMethod || c.kind == TermSymbolKind.Var).partition(!_.isSynthetic)
    (plain ++ synthetic).toList

  /** The members `cls` declares, in source order, or `None` when it has no tree.
    *
    * Java and Scala 2 symbols always return `None`: tasty-query only ever calls
    * `setTree` from the TASTy reader, so they carry no tree and hence no order.
    */
  def declaredInOrder(cls: ClassSymbol)(using Context): Option[Vector[TermSymbol]] =
    cls.tree.map: classDef =>
      val template = classDef.rhs
      (template.constr +: template.body).toVector.collect {
        case d: DefDef => d.symbol
        case v: ValDef => v.symbol
      }
end OverloadDisambiguator
