package org.scip_code.scip_java.tasty

import scala.collection.mutable

import org.scip_code.scip.{Document, Occurrence, Relationship, SymbolRole}
import org.scip_code.scip_java.shared.{ScipDocumentBuilder, ScipRange, ScipSymbols}
import tastyquery.Contexts.Context
import tastyquery.SourcePosition
import tastyquery.Symbols.*
import tastyquery.Traversers
import tastyquery.Trees.*
import tastyquery.Types.{AppliedType, PackageRef, TermRef, TermType, TypeRef}

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
  private val offsetsOfSymbol = mutable.HashMap.empty[String, mutable.LinkedHashSet[Int]]

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
      //
      // `Import` is also handled without falling through to `super.traverse`: otherwise
      // `super.traverse` visits `imp.expr` as an ordinary `TermReferenceTree` and emits
      // package occurrences for `import scala.util.Try` that the compiler does not emit (and
      // visits non-package qualifiers twice).
      val handledChildren: Boolean = tree match {
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
        case imp: Import =>
          indexImport(imp)
          true
        case _ => false
      }
      if (!handledChildren) {
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
          //
          // A constructor call is the exception: it is pickled as `Select(New(tpt), "<init>")`,
          // and `<init>` is never written in source, so matching the resolved symbol's own
          // name would always miss. The class name the `New` targets is what the compiler's
          // own extractor anchors the constructor reference to instead.
          case ref: TermReferenceTree =>
            resolved(ref).foreach { sym =>
              constructorAnchor(ref) match {
                case Some((pos, name)) => reference(pos, sym, name)
                case None              => reference(ref.pos, sym)
              }
            }
          case ti: TypeIdent          => typeSymbol(ti).foreach(reference(ti.pos, _))
          case st: SelectTypeTree     => typeSymbol(st).foreach(reference(st.pos, _))
          // The package part of a qualified type name -- the `lang` of
          // `new java.lang.StringBuilder`. It is a `TypeTree` rather than a
          // `TermReferenceTree`, so it needs its own case or the prefix goes unindexed.
          case trt: TermRefTypeTree   => prefixSymbol(trt).foreach(reference(trt.pos, _))
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

  /** The symbol a type-position *prefix* refers to, which is usually a package. */
  private def prefixSymbol(tree: TypeTree): Option[Symbol] =
    scala.util.Try(tree.toPrefix).toOption.flatMap {
      case p: PackageRef => scala.util.Try(p.symbol).toOption
      case t: TermRef    => scala.util.Try(t.symbol).toOption
      case tr: TypeRef   => tr.optSymbol
      case _             => None
    }

  /** Indexes an `import foo.{bar => baz}` clause without emitting bare package qualifiers.
    *
    * `ImportIdent` carries no type of its own (it is not a `TermReferenceTree`), so the
    * member it names is resolved from the qualifier's members. Only the original name (`bar`)
    * is anchored here -- an alias (`baz`) introduces a new local binding rather than
    * referencing one. Non-package segments of the qualifier (`Greeting` in
    * `import com.example.Greeting.default`, or a local value in `import g.greet`) are
    * indexed recursively while package prefixes are skipped.
    */
  private def indexImport(imp: Import): Unit = {
    indexImportQualifier(imp.expr)
    imp.selectors.foreach { sel =>
      if (!sel.isWildcard) {
        importSelectorSymbols(imp.expr, sel.imported.name.toString)
          .foreach(reference(sel.imported.pos, _))
      }
    }
  }

  private def indexImportQualifier(expr: TermTree): Unit =
    expr match {
      case ref: TermReferenceTree =>
        resolved(ref)
          .filterNot(_.isInstanceOf[PackageSymbol])
          .foreach(reference(ref.pos, _))
        ref match {
          case Select(qual, _) => indexImportQualifier(qual)
          case _               => ()
        }
      case _ => ()
    }

  /** The symbols an import selector names, found by hand: `ImportIdent` carries no type of
    * its own, unlike every other reference here, so there is no `.symbol` to read off the
    * tree -- the qualifier's members have to be searched by name instead.
    *
    * More than one symbol can share the selected name -- `Try` is both a class and its
    * companion object's value -- and the compiler describes the import as referencing all
    * of them, not just whichever one a name lookup happens to find first.
    */
  private def importSelectorSymbols(expr: TermTree, name: String): List[Symbol] =
    scala.util.Try(expr.tpe).toOption match {
      case Some(p: PackageRef) =>
        scala.util.Try(p.symbol).toOption.toList
          .flatMap(_.declarations)
          .filter(_.name.toString == name)
      case Some(tpe) =>
        classSymbolOf(tpe).toList.flatMap { c =>
          scala.util
            .Try(c.linearization)
            .getOrElse(List(c))
            .iterator
            .map(_.declarations.filter(_.name.toString == name))
            .find(_.nonEmpty)
            .getOrElse(Nil)
        }
      case None => Nil
    }

  private def classSymbolOf(tpe: TermType): Option[ClassSymbol] =
    tpe match {
      case t: TermRef =>
        scala.util
          .Try(t.symbol)
          .toOption
          .flatMap(_.moduleClass)
          .orElse(scala.util.Try(t.widenTermRef).toOption.flatMap(classSymbolOf))
      case tr: TypeRef    => tr.optSymbol.collect { case cls: ClassSymbol => cls }
      case at: AppliedType => classSymbolOf(at.tycon)
      case _              => None
    }

  private def definition(tree: DefTree, sym: Symbol, name: String): Unit =
    if (TastyExclusions.isConstructor(sym)) {
      // A constructor with no explicit parameter list -- `class Overloads:`, say -- has a
      // zero-extent position, which `excludeDefinition` would otherwise treat as "not
      // present in source" and skip entirely. That is right for the *occurrence* (there is
      // nothing to anchor a range to), but wrong for *ownership*: the constructor is still
      // a symbol this project defines, and `new Overloads(...)` still has to resolve to
      // something the index knows is not external.
      describeWithoutOccurrence(tree, sym, name)
      constructorMarker(tree, sym)
    } else if (!TastyExclusions.excludeDefinition(tree)) {
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
          // No range to claim: the name is not written at this position. A module class, a
          // setter, a `given` -- all real symbols that the compiler describes without an
          // occurrence, and all of them clique members.
          describeWithoutOccurrence(tree, sym, name)
      }
    }

  /** A zero-width definition occurrence for a constructor, at the end of the class name.
    *
    * `<init>` is never written in source, so there is no name to anchor a range to -- but
    * without *some* definition occurrence, a `new Greeting(...)` reference resolves to a
    * symbol that nothing in the index defines, and "go to definition" on it leads nowhere.
    * The compiler solves this the same way, emitting a marker where the class name ends.
    */
  private def constructorMarker(tree: DefTree, ctor: Symbol): Unit =
    if (!tree.pos.isUnknown && tree.pos.sourceFile.path == pickledPath) {
      TastySymbols.globalSymbol(ctor).foreach { symbol =>
        // The constructor's own span start, which is where the compiler puts its marker.
        // That is the start of the parameter list for `case class Greeting(..)` and the
        // start of the body for a `class Overloads:` that declares none -- so deriving it
        // from the class *name* instead gets the second case wrong.
        val offset: Int = tree.pos.startOffset
        if (lines.contains(offset)) {
          val line: Int = lines.lineOf(offset)
          val column: Int = lines.columnOf(offset)
          emit(
            symbol,
            ScipRange.singleLine(line, column, column),
            Occurrence.newBuilder().setSymbolRoles(SymbolRole.Definition_VALUE),
          )
          defined += symbol
        }
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

  /** Where and what to match for a `new Foo(...)` constructor call, if `ref` is one.
    *
    * `Foo` is what is actually written at the call site; `tpt` may itself be wrapped (`new
    * Foo[Int]()`), so the applied type's constructor is unwrapped down to the named class.
    */
  private def constructorAnchor(ref: TermReferenceTree): Option[(SourcePosition, String)] =
    ref match {
      case Select(nw: New, _) => classNameAndPos(nw.tpt)
      case _                  => None
    }

  private def classNameAndPos(tpt: TypeTree): Option[(SourcePosition, String)] =
    tpt match {
      case ti: TypeIdent       => Some((ti.pos, ti.name.toString))
      case st: SelectTypeTree  => Some((st.pos, st.name.toString))
      case at: AppliedTypeTree => classNameAndPos(at.tycon)
      case _                   => None
    }

  private def reference(pos: SourcePosition, sym: Symbol): Unit =
    reference(pos, sym, sym.name.toString)

  private def reference(pos: SourcePosition, sym: Symbol, name: String): Unit = {
    nameRange(pos, name).foreach { range =>
      symbolFor(sym, definitionPos(sym), name).foreach { symbol =>
        emit(symbol, range, Occurrence.newBuilder())
        // Recorded whether or not it turns out to be external; only the caller, which sees
        // every document, knows which referenced symbols are defined nowhere in the sources.
        if (ScipSymbols.isGlobal(symbol)) referenced.getOrElseUpdate(symbol, sym)
      }
    }
  }

  /** Where `sym` itself is defined, not where this reference to it occurs.
    *
    * `symbolFor` keys a local id on `(definition offset, name)`; a reference must look up
    * that key the same way `definition` populated it, or every use of a local gets a fresh
    * id that never matches its definition.
    */
  private def definitionPos(sym: Symbol): SourcePosition =
    sym.tree.map(_.pos).getOrElse(SourcePosition.NoPosition)

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
  private def recordAtOffset(offset: Int, symbol: String): Unit = {
    symbolsAtOffset.getOrElseUpdate(offset, mutable.LinkedHashSet.empty) += symbol
    offsetsOfSymbol.getOrElseUpdate(symbol, mutable.LinkedHashSet.empty) += offset
  }

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
    offsetsOfSymbol
      .get(symbol)
      .toList
      .flatMap(_.iterator.flatMap(offset => symbolsAtOffset.getOrElse(offset, Nil)))
      .filter(_ != symbol)
      .filter { other =>
        other.endsWith(".apply().") || descriptions.get(other).exists(_._2 == displayName)
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
    else candidates(pos).flatMap(candidateRange(_, name)).headOption

  private def candidates(pos: SourcePosition): List[Int] =
    // `Select` points at the selected name; `Ident` and most definitions start on it.
    List(pos.pointOffset, pos.startOffset).distinct

  /** The range at `candidate`, if `name` is written there.
    *
    * A backticked identifier -- `` `type` ``, say, naming a reserved word -- is pickled at
    * the position of the backtick itself, not the name inside it, so the name has to be
    * checked one character further in. The range itself includes the backticks, matching
    * the compiler's own convention of quoting the whole occurrence, not just the name.
    */
  private def candidateRange(candidate: Int, name: String): Option[ScipRange] = {
    val quoted: Boolean = candidate >= 0 && candidate < text.length && text.charAt(candidate) == '`'
    val nameStart: Int = if (quoted) candidate + 1 else candidate
    if (!matchesAt(nameStart, name)) None
    else if (quoted) {
      val closeQuote: Int = nameStart + name.length
      if (closeQuote < text.length && text.charAt(closeQuote) == '`')
        Some(rangeOf(candidate, name.length + 2))
      else None
    } else Some(rangeOf(candidate, name.length))
  }

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

  private def displayNameOf(sym: Symbol, matched: String): String =
    TastyDescriber.displayNameOf(sym, matched)

  private def sourceName(sym: Symbol): String =
    TastyDescriber.sourceName(sym)
}
