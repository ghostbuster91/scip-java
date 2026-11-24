package org.scip_code.scip_java.tasty

import scala.collection.mutable

import org.scip_code.scip.{Document, Occurrence, Relationship, SymbolInformation, SymbolRole}
import org.scip_code.scip_java.shared.{ScipDocumentBuilder, ScipRange, ScipSymbols}
import tastyquery.Contexts.Context
import tastyquery.Modifiers.TermSymbolKind
import tastyquery.SourcePosition
import tastyquery.Symbols.*
import tastyquery.Traversers
import tastyquery.Trees.*
import tastyquery.Types.TypeRef

/** Turns the TASTy trees of a single source file into SCIP occurrences.
  *
  * One instance per document. Occurrences for any other file are dropped: an `Inlined`
  * body carries positions in the file it was *defined* in, so a tree reached from this
  * file's trees can legitimately point into another source, and emitting it here would
  * describe the wrong document.
  */
final class TastyDocumentIndexer(
  /** The path as pickled into TASTy. Occurrences are filtered against this. */
  pickledPath: String,
  /** The source-root-relative path, which `Document.relative_path` carries and which the
    * shard path is derived from.
    */
  relativePath: String,
  text: String,
)(using Context, JavaOrderLookup) {

  private val lines: LineIndex = LineIndex.of(text)
  private val builder: ScipDocumentBuilder = new ScipDocumentBuilder()
  private val describedSymbols = mutable.HashSet.empty[String]

  /** Every method overrides something on `Object`; both `SemanticdbToScip` and
    * `ScipAggregator` filter those out, so it is done at the source here too.
    */
  private val IgnoredOverriddenSymbol: String = "java/lang/Object#"

  /** Local ids, bucketed the way dotty buckets them.
    *
    * `SemanticSymbolBuilder.localIdx` keys on `(definition offset, name)`, so a local
    * `var`'s field, getter and setter collapse onto one `localN`. The numbers need not
    * match the compiler's -- locals are document-scoped and `SymbolRewriter` passes them
    * through untouched -- but the *bucketing* must, or a local definition and its
    * references get different ids.
    */
  private val localIds = mutable.HashMap.empty[(Int, String), Int]
  private var nextLocalId: Int = 0

  def result(): Document = builder.build("scala", relativePath, text)

  def index(tree: Tree): Unit = traverser.traverse(tree)

  private val traverser = new Traversers.TreeTraverser {
    override def traverse(tree: Tree): Unit = {
      // For a compiler-generated class member, skip the signature but keep the body. A case
      // class's `copy` and `apply` take their parameters' positions from the class's own
      // parameters, so descending into them puts three definition occurrences on one range
      // where the compiler emits only the constructor's. The right-hand side is a different
      // matter: a `given` is generated but its body is written by hand, and pruning that
      // loses every reference in it.
      val generated: Boolean = tree match {
        case dd: DefDef if isGeneratedMember(dd.symbol) =>
          traverse(dd.rhs)
          true
        case _ => false
      }
      if (!generated) {
        tree match {
          case cd: ClassDef =>
            definition(cd, cd.symbol, sourceName(cd.symbol))
            cd.symbol.typeParams.foreach { tp =>
              tp.tree.foreach(t => definition(t, tp, tp.name.toString))
            }
          case dd: DefDef     => definition(dd, dd.symbol, dd.name.toString)
          case vd: ValDef     => definition(vd, vd.symbol, vd.name.toString)
          case tm: TypeMember => definition(tm, tm.symbol, tm.name.toString)
          // A constructor carries its own copies of the class type parameters; dotty
          // attributes them to the class, so the copies would invent a second spelling.
          case tp: TypeParam if !TastyExclusions.isConstructor(tp.symbol.owner) =>
            definition(tp, tp.symbol, tp.name.toString)
          case b: Bind => definition(b, b.symbol, b.name.toString)
          // The name to look for in the source comes from the resolved symbol, not the
          // tree: a `Select` of an overloaded method carries a `SignedName`, whose
          // `toString` includes the erased signature and would never match the source.
          case ref: TermReferenceTree => resolved(ref).foreach(reference(ref.pos, _))
          case ti: TypeIdent          => typeSymbol(ti).foreach(reference(ti.pos, _))
          case st: SelectTypeTree     => typeSymbol(st).foreach(reference(st.pos, _))
          case _                      => ()
        }
        super.traverse(tree)
      }
    }
  }

  /** `.symbol` and `.toType` can throw on references tasty-query cannot resolve. One bad
    * reference should cost that occurrence, not the document.
    */
  private def resolved(ref: TermReferenceTree): Option[Symbol] =
    scala.util.Try(ref.symbol).toOption

  private def typeSymbol(tree: TypeTree): Option[Symbol] =
    scala.util.Try(tree.toType).toOption.collect { case tr: TypeRef => tr.optSymbol }.flatten

  private def definition(tree: DefTree, sym: Symbol, name: String): Unit = {
    // An auto-generated companion -- the one an `enum` or `case class` gets for free --
    // sits at the same position as the class itself. Emitting it would put both `Color#`
    // and `Color.` on the name `Color`, where the compiler emits only the class.
    val skip: Boolean =
      TastyExclusions.excludeDefinition(tree) || TastyExclusions.isGeneratedCompanion(sym)
    if (!skip) {
      nameRange(tree.pos, name).foreach { range =>
        symbolFor(sym, tree.pos, name).foreach { symbol =>
          emit(symbol, range, Occurrence.newBuilder().setSymbolRoles(SymbolRole.Definition_VALUE))
          describe(sym, symbol, name)
        }
      }
    }
  }

  private def reference(pos: SourcePosition, sym: Symbol): Unit = {
    val name: String = sym.name.toString
    nameRange(pos, name).foreach { range =>
      symbolFor(sym, pos, name).foreach { symbol =>
        emit(symbol, range, Occurrence.newBuilder())
      }
    }
  }

  private def emit(symbol: String, range: ScipRange, b: Occurrence.Builder): Unit = {
    b.setSymbol(symbol)
    // `ScipRange.from` and `ScipAggregator.rewriteDocument` both reject an occurrence with
    // no typed range, so the oneof must always be set.
    if (range.isSingleLine) b.setSingleLineRange(range.toSingleLineRange)
    else b.setMultiLineRange(range.toMultiLineRange)
    builder.addOccurrence(b.build())
  }

  /** The bare-descriptor symbol, or a document-local id for anything not global. */
  private def symbolFor(sym: Symbol, pos: SourcePosition, name: String): Option[String] =
    TastySymbols.globalSymbol(sym).orElse {
      if (pos.isUnknown) None
      else {
        val key: (Int, String) = (pos.startOffset, name)
        val id: Int = localIds.getOrElseUpdate(key, { val i = nextLocalId; nextLocalId += 1; i })
        Some(ScipSymbols.local(id))
      }
    }

  private def describe(sym: Symbol, symbol: String, name: String): Unit =
    if (describedSymbols.add(symbol)) {
      val info = SymbolInformation.newBuilder().setSymbol(symbol).setDisplayName(name)
      kindOf(sym).foreach(info.setKind)
      overrides(sym).foreach(info.addRelationships)
      builder.addSymbol(info.build())
    }

  private def overrides(sym: Symbol): List[Relationship] =
    sym match {
      case t: TermOrTypeSymbol =>
        scala.util.Try(t.allOverriddenSymbols.toList).getOrElse(Nil).flatMap { parent =>
          TastySymbols
            .globalSymbol(parent)
            .filter(_ != IgnoredOverriddenSymbol)
            .map(s => Relationship.newBuilder().setSymbol(s).setIsImplementation(true).build())
        }
      case _ => Nil
    }

  private def kindOf(sym: Symbol): Option[SymbolInformation.Kind] =
    sym match {
      case cls: ClassSymbol =>
        if (cls.isModuleClass) Some(SymbolInformation.Kind.Object)
        else if (cls.isTrait) Some(SymbolInformation.Kind.Trait)
        else if (cls.isEnum) Some(SymbolInformation.Kind.Enum)
        else Some(SymbolInformation.Kind.Class)
      case _: TypeParamSymbol => Some(SymbolInformation.Kind.TypeParameter)
      case _: TypeSymbol      => Some(SymbolInformation.Kind.Type)
      case t: TermSymbol =>
        t.kind match {
          case TermSymbolKind.Module  => Some(SymbolInformation.Kind.Object)
          case TermSymbolKind.Var     => Some(SymbolInformation.Kind.Variable)
          case TermSymbolKind.Val     => Some(SymbolInformation.Kind.Field)
          case TermSymbolKind.LazyVal => Some(SymbolInformation.Kind.Field)
          case TermSymbolKind.Def     => Some(SymbolInformation.Kind.Method)
        }
      case _ => None
    }

  /** The range covering just the identifier.
    *
    * TASTy has no name-only position: `ClassDef.pos` spans the whole class and `DefDef.pos`
    * the whole definition. The `point` is dotty's `^` location and sits on the name, so the
    * range is `[point, point + name.length)`.
    *
    * The name is then checked against the source text, which is what dotty's
    * `namePresentInSource` does. That guard is what keeps compiler-generated members out:
    * a synthetic `copy` positioned on the case class name, or a mangled `copy$default$1`,
    * does not spell its own name at its point and so gets no occurrence.
    */
  private def nameRange(pos: SourcePosition, name: String): Option[ScipRange] =
    if (pos.isUnknown || pos.sourceFile.path != pickledPath) None
    else candidates(pos).find(at => matchesAt(at, name)).map(start => rangeOf(start, name.length))

  private def candidates(pos: SourcePosition): List[Int] =
    // `Select` points at the selected name; `Ident` and most definitions start on it.
    List(pos.pointOffset, pos.startOffset).distinct

  private def matchesAt(start: Int, name: String): Boolean =
    start >= 0 &&
      start + name.length <= text.length &&
      text.regionMatches(start, name, 0, name.length)

  private def rangeOf(start: Int, len: Int): ScipRange = {
    val end: Int = start + len
    val startLine: Int = lines.lineOf(start)
    val endLine: Int = lines.lineOf(end)
    if (startLine == endLine)
      ScipRange.singleLine(startLine, lines.columnOf(start), lines.columnOf(end))
    else ScipRange.range(startLine, lines.columnOf(start), endLine, lines.columnOf(end))
  }

  /** A synthetic method that is a class member, i.e. one the compiler added to a class.
    *
    * Deliberately narrower than `isSynthetic`. A lambda's `$anonfun` is synthetic too, but
    * its parameters are written by the author -- `(a: Int) => ...` -- so pruning them loses
    * the `Int`. Anonymous functions are owned by a term, not a class, which separates the
    * two cases without matching on mangled names.
    */
  private def isGeneratedMember(sym: TermSymbol): Boolean =
    sym.isSynthetic && sym.owner.isInstanceOf[ClassSymbol]

  /** The name as it is written in source.
    *
    * A module class is pickled as `Foo$`, which never appears in source; the name to look
    * for is the module value's. Without this every `object` loses its definition occurrence.
    */
  private def sourceName(sym: Symbol): String =
    sym match {
      case cls: ClassSymbol if cls.isModuleClass =>
        cls.moduleValue.map(_.name.toString).getOrElse {
          val n: String = cls.name.toString
          if (n.endsWith("$")) n.dropRight(1) else n
        }
      case _ => sym.name.toString
    }
}
