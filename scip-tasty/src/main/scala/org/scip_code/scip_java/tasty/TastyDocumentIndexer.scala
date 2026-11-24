package org.scip_code.scip_java.tasty

import scala.collection.mutable

import org.scip_code.scip.{Document, Occurrence, Relationship, SymbolRole}
import org.scip_code.scip_java.shared.{ScipDocumentBuilder, ScipRange, ScipSymbols}
import tastyquery.Contexts.Context
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
  private val defined = mutable.HashSet.empty[String]
  private val referenced = mutable.HashMap.empty[String, Symbol]

  /** Symbol descriptions, built only once the whole document has been walked.
    *
    * Deferred because a description carries the `is_reference` relationships of its
    * definition clique, and a clique is not known until every definition has been seen.
    */
  private val descriptions = mutable.LinkedHashMap.empty[String, (Symbol, String)]

  /** Symbols sharing one definition offset -- dotty's `symsAtOffset`.
    *
    * A case class, its companion's `apply` and its `copy` are all positioned on the class
    * name, and a `var`'s field, getter and setter all on the `var`. Grouping by offset is
    * what recovers those sets; see [[cliqueRelationships]] for what is done with them.
    */
  private val symbolsAtOffset = mutable.LinkedHashMap.empty[Int, mutable.LinkedHashSet[String]]

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

  def result(): Document = {
    // Descriptions are emitted here, not as each definition is met, because every one of
    // them carries the `is_reference` relationships of its clique and those depend on the
    // whole document having been walked.
    descriptions.foreach { case (symbol, (sym, name)) =>
      val info = TastyDescriber.describe(sym, symbol, name)
      cliqueRelationships(symbol, name).foreach(info.addRelationships)
      // Only locals carry this, matching the SemanticDB path: it is what lets a reader tell
      // which method a `local 4` belongs to, since the id itself says nothing.
      if (ScipSymbols.isLocal(symbol)) enclosingGlobalSymbol(sym).foreach(info.setEnclosingSymbol)
      builder.addSymbol(info.build())
    }
    builder.build("scala", relativePath, text)
  }

  /** Global symbols this document defines. */
  def definedSymbols: Set[String] = defined.toSet

  /** Global symbols this document references, paired with the symbol they resolved to.
    *
    * The caller subtracts the definitions found across every document to get the ones that
    * live outside the indexed sources, which are the ones worth describing as external.
    */
  def referencedSymbols: Map[String, Symbol] = referenced.toMap

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
          describeWithoutOccurrence(dd, dd.symbol, sourceName(dd.symbol))
          dd.symbol.paramSymss.foreach {
            case Left(termParams) =>
              termParams.foreach(p =>
                p.tree.foreach(t => describeWithoutOccurrence(t, p, p.name.toString))
              )
            case Right(_) => ()
          }
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

  private def definition(tree: DefTree, sym: Symbol, name: String): Unit =
    if (!TastyExclusions.excludeDefinition(tree)) {
      // An auto-generated companion -- the one an `enum` or `case class` gets for free --
      // sits at the same position as the class itself, so it must not claim an occurrence:
      // that would put both `Color#` and `Color.` on the name `Color`, where the compiler
      // emits only the class. It is still described, because it is a real symbol and the
      // class names it as part of its clique.
      val mayOwnRange: Boolean = !TastyExclusions.isGeneratedCompanion(sym)
      nameRange(tree.pos, name).filter(_ => mayOwnRange) match {
        case Some(range) =>
          symbolFor(sym, tree.pos, name).foreach { symbol =>
            emit(symbol, range, Occurrence.newBuilder().setSymbolRoles(SymbolRole.Definition_VALUE))
            defined += symbol
            describe(sym, symbol, displayNameOf(sym, name))
            recordAtOffset(tree.pos.startOffset, symbol)
          }
        case None =>
          // No range to claim: the name is not written at this position. A constructor, a
          // module class, a setter, a `given` -- all real symbols that the compiler
          // describes without an occurrence, and all of them clique members.
          describeWithoutOccurrence(tree, sym, name)
      }
    }

  /** Describes a definition and records its offset, without claiming an occurrence.
    *
    * This is the shape the compiler's extractor uses for a generated member: `copy`,
    * `apply` and `unapply` get `SymbolInformation` so that hovering them shows something,
    * but no definition occurrence, because the range they would claim belongs to the class
    * name. Registering the offset is what later ties them to the class as a clique.
    */
  private def describeWithoutOccurrence(tree: DefTree, sym: Symbol, name: String): Unit =
    if (!tree.pos.isUnknown && tree.pos.sourceFile.path == pickledPath) {
      TastySymbols.globalSymbol(sym).foreach { symbol =>
        describe(sym, symbol, displayNameOf(sym, name))
        recordAtOffset(tree.pos.startOffset, symbol)
      }
    }

  private def reference(pos: SourcePosition, sym: Symbol): Unit = {
    val name: String = sym.name.toString
    nameRange(pos, name).foreach { range =>
      symbolFor(sym, pos, name).foreach { symbol =>
        emit(symbol, range, Occurrence.newBuilder())
        // Recorded whether or not it turns out to be external; only the caller, which sees
        // every document, knows which referenced symbols are defined nowhere in the sources.
        if (ScipSymbols.isGlobal(symbol)) referenced.getOrElseUpdate(symbol, sym)
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
    descriptions.getOrElseUpdate(symbol, (sym, name))

  /** The nearest enclosing symbol that is global, if any.
    *
    * `SymbolInformation.enclosing_symbol` is passed through `SymbolRewriter.rewrite` by the
    * aggregator, so it has to be a bare descriptor like any other symbol here.
    */
  private def enclosingGlobalSymbol(sym: Symbol): Option[String] = {
    def loop(current: Symbol): Option[String] =
      current.owner match {
        case null  => None
        case owner => TastySymbols.globalSymbol(owner).orElse(loop(owner))
      }
    loop(sym)
  }

  /** Records that `symbol` is defined at `offset`, for clique grouping. */
  private def recordAtOffset(offset: Int, symbol: String): Unit =
    symbolsAtOffset.getOrElseUpdate(offset, mutable.LinkedHashSet.empty) += symbol

  /** `is_reference` relationships tying `symbol` to the rest of its definition clique.
    *
    * A clique is the set of symbols sharing one definition occurrence -- a case class, its
    * companion's `apply`, its `copy`. Without these, "find references" on one of them misses
    * the occurrences attributed to the others, because only one of them owns the range.
    *
    * The filter mirrors `SemanticdbToScip.addCliqueReferences`: relate another member only
    * when it is an `apply` or shares this symbol's display name. That is what keeps a
    * case class's `copy` tied to the class while leaving unrelated members that merely
    * happen to start at the same offset alone.
    */
  private def cliqueRelationships(symbol: String, displayName: String): List[Relationship] =
    symbolsAtOffset.values.toList
      .filter(_.contains(symbol))
      .flatMap { clique =>
        clique.toList.filter(_ != symbol).filter { other =>
          other.endsWith(".apply().") || descriptions.get(other).exists(_._2 == displayName)
        }
      }
      .distinct
      .map(other => Relationship.newBuilder().setSymbol(other).setIsReference(true).build())

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

  /** The name a client shows for `sym`, which is not always the name matched in source.
    *
    * A file's top-level definitions are wrapped in a synthetic `Foo$package` module, which
    * the compiler reports as a package object named after its *package* -- `example`, not
    * `Fixture$package`. The two names have to stay separate: matching "example" against the
    * source text would find it in the `package com.example` clause and wrongly claim a range
    * there.
    */
  private def displayNameOf(sym: Symbol, matched: String): String =
    if (isPackageWrapper(sym)) Option(sym.owner).map(_.name.toString).getOrElse(matched)
    else matched

  /** The synthetic module a file's top-level definitions live in.
    *
    * The module class is named `Foo$package$` and the module value `Foo$package`, so the
    * trailing object marker comes off before the test.
    */
  private def isPackageWrapper(sym: Symbol): Boolean =
    sym.name.toString.stripSuffix("$").endsWith("$package")

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
