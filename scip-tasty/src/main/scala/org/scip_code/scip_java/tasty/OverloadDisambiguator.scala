package org.scip_code.scip_java.tasty

import tastyquery.Contexts.Context
import tastyquery.Modifiers.TermSymbolKind
import tastyquery.Names
import tastyquery.Signatures
import tastyquery.SourceLanguage
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
  * The strings this produces have to match the compiler's byte for byte, because a Scala
  * definition referenced from Java resolves by string equality alone.
  */
object OverloadDisambiguator {

  /** The disambiguator for `sym`, including the enclosing parentheses: `()` or `(+2)`.
    *
    * The [[JavaOrderLookup]] supplies declaration order for Java owners, which have no
    * tree. Without it every Java overload collapses to `()`, which silently mis-resolves
    * references to all but the first alternative of a name like `java.lang.Math.max`.
    */
  def of(sym: TermSymbol)(using Context, JavaOrderLookup): String =
    alternatives(sym) match {
      case Some(alts) => "(" + suffix(sym, alts) + ")"
      case None       => "(" + javaSuffix(sym) + ")"
    }

  /** dotty numbers a Java owner's overloads in classfile `methods[]` order. */
  private def javaSuffix(sym: TermSymbol)(using Context, JavaOrderLookup): String = {
    val owner: Option[ClassSymbol] = sym.owner match {
      case cls: ClassSymbol => Some(cls)
      case _                => None
    }
    val resolved: Option[String] = for {
      cls <- owner
      order <- summon[JavaOrderLookup].order
      // `staticsFirst` mirrors dotty's `JavaModule` case, which concatenates the statics
      // holder's declarations ahead of the companion class's.
      symIsStatic = isStaticsHolder(cls)
      rows <- order.sameNamed(cls, sym.name.toString, staticsFirst = symIsStatic)
      idx = indexOfJava(sym, symIsStatic, rows)
      if idx >= 0
    } yield if (idx == 0) "" else "+" + idx
    resolved.getOrElse("")
  }

  /** Position of `sym` among the same-named classfile rows, or -1 if not identifiable.
    *
    * tasty-query offers no link from a `TermSymbol` back to its `methods[]` row, so the
    * row is identified by its **erased parameter types** and staticness, which uniquely
    * distinguish overloads within a class. Both sides spell them the same way:
    * `Signature.paramsSig` yields erased names and [[JvmDescriptor]] decodes the descriptor
    * into that same spelling -- including that a primitive erases to `scala.Int`, not `int`.
    *
    * `ParamSig.TypeLen` entries are dropped: they record a type-parameter count, which a
    * JVM descriptor does not carry.
    */
  private def indexOfJava(
    sym: TermSymbol,
    isStatic: Boolean,
    rows: Vector[JavaMethodOrderMethod],
  )(using Context): Int =
    erasedParams(sym) match {
      case None         => -1
      case Some(params) => rows.indexWhere(r => r.isStatic == isStatic && r.paramTypes == params)
    }

  /** `sym.signature` throws when tasty-query cannot erase the type, which it cannot do at
    * all unless scala-library is on the classpath -- even for a pure-Java symbol.
    */
  private def erasedParams(sym: TermSymbol)(using Context): Option[Vector[String]] =
    scala.util.Try(sym.signature).toOption.map { sig =>
      sig.paramsSig.collect { case Signatures.ParamSig.Term(tpe) => tpe.toString }.toVector
    }

  private def isStaticsHolder(cls: ClassSymbol)(using Context): Boolean =
    cls.isModuleClass && cls.sourceLanguage == SourceLanguage.Java

  private def suffix(sym: TermSymbol, alts: List[TermSymbol])(using Context): String = {
    val sig: Option[Signatures.Signature] = scala.util.Try(sym.signature).toOption
    val target: Names.UnsignedTermName = sym.targetName
    def matches(c: TermSymbol): Boolean =
      (c eq sym) || (c.targetName == target && sig.isDefined && scala.util.Try(c.signature).toOption == sig)
    alts match {
      // dotty: if the head already matches, no suffix is appended at all. Otherwise the
      // suffix counts from the *tail*, plus one -- so first => "", second => "+1".
      case head :: rest if !matches(head) =>
        val idx: Int = rest.indexWhere(matches)
        if (idx >= 0) "+" + (idx + 1) else ""
      case _ => ""
    }
  }

  /** The candidate list, in the order dotty numbers it, or `None` if unavailable.
    *
    * dotty reads `owner.info.decls.lookupAll(name).toList.reverse`, the `reverse` undoing
    * the fact that its scopes enumerate most-recent-first. tasty-query has no ordered
    * equivalent -- `declarations` and `getAllOverloadedDecls` are backed by a
    * `HashMap[Name, HashSet[Symbol]]` -- so the order comes from the class body instead,
    * which TASTy pickles in source order and which already contains the synthesized
    * case-class members.
    */
  private def alternatives(sym: TermSymbol)(using Context): Option[List[TermSymbol]] =
    sym.owner match {
      case cls: ClassSymbol => declaredInOrder(cls).map(ordered(sym, _))
      case _                => None
    }

  private def ordered(sym: TermSymbol, candidates: Vector[TermSymbol])(using
    Context
  ): List[TermSymbol] = {
    // dotty starts from `lookupAll(sym.name)`, so only same-named declarations are
    // candidates -- the index is per-name, not a position within the class.
    val sameName: Vector[TermSymbol] = candidates.filter(_.name == sym.name)
    // Then it filters to `Method | Mutable` -- a `var` occupies an index too -- and
    // partitions non-synthetic before synthetic, independently of source order.
    val (plain, synthetic) =
      sameName.filter(c => c.isMethod || c.kind == TermSymbolKind.Var).partition(!_.isSynthetic)
    (plain ++ synthetic).toList
  }

  /** The members `cls` declares, in source order, or `None` when it has no tree.
    *
    * Java and Scala 2 symbols always return `None`: tasty-query only ever calls `setTree`
    * from the TASTy reader, so they carry no tree and hence no order.
    */
  def declaredInOrder(cls: ClassSymbol)(using Context): Option[Vector[TermSymbol]] =
    cls.tree.map { classDef =>
      val template: Template = classDef.rhs
      (template.constr +: template.body).toVector.collect {
        case d: DefDef => d.symbol
        case v: ValDef => v.symbol
      }
    }
}
