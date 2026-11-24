package org.scip_code.scip_java.tasty

import org.scip_code.scip_java.shared.ScipSymbols
import org.scip_code.scip_java.shared.ScipSymbols.Descriptor
import tastyquery.Contexts.Context
import tastyquery.Modifiers.TermSymbolKind
import tastyquery.SourceLanguage
import tastyquery.Symbols.*

/** Mints bare-descriptor SCIP symbols for symbols read out of TASTy.
  *
  * Transcribed from `dotty.tools.dotc.semanticdb.SemanticSymbolBuilder.addSymName` /
  * `addDescriptor` and `Scala3.isGlobal`, at dotty tag 3.7.4. The output has to agree
  * byte for byte with what the compiler writes, because Java-to-Scala references resolve
  * by string equality alone.
  *
  * Encoding is delegated to [[ScipSymbols.Descriptor]] rather than reimplemented: its
  * backtick rule is already `isJavaIdentifierStart`/`Part`, the same test as dotty's
  * `isJavaIdent`.
  *
  * Known approximations, each of which the parity harness is expected to expose rather
  * than hide:
  *   - `StableRealizable` is not exposed by tasty-query (`Flags` is `private[tastyquery]`),
  *     so the `!sym.is(StableRealizable) || sym.isConstructor` guard on method
  *     descriptors is not applied.
  *   - `isSelfSym` is likewise unavailable; self symbols are not enumerated as
  *     declarations, so the `!sym.isSelfSym` conjunct in `isGlobal` is dropped.
  *   - Overload disambiguators for Java owners come from the classfile `methods[]` table
  *     via [[JavaMethodOrder]], since a Java symbol has no tree to read order from.
  *   - dotty applies `unescapeUnicode` to names before encoding; this does not.
  */
object TastySymbols:

  /** The bare-descriptor symbol for `sym`, or `None` if it is not a global symbol.
    *
    * Locals deliberately return `None`: dotty numbers them by `(definition offset, name)`
    * in traversal order, which is a document-scoped scheme this cannot and need not
    * reproduce. The indexer assigns its own local ids.
    */
  def globalSymbol(sym: Symbol)(using Context, JavaOrderLookup): Option[String] =
    if isGlobal(sym) then Some(symbolName(sym)) else None

  private def symbolName(sym: Symbol)(using Context, JavaOrderLookup): String =
    ScipSymbols.global(ownerPrefix(sym), descriptor(sym))

  /** dotty: `if !owner.isRoot then addSymName(b, owner)` -- the root contributes nothing. */
  private def ownerPrefix(sym: Symbol)(using Context, JavaOrderLookup): String =
    sym.owner match
      case null                           => ScipSymbols.ROOT_PACKAGE
      case owner if isRootPackage(owner)  => ScipSymbols.ROOT_PACKAGE
      case owner                          => symbolName(owner)

  private def descriptor(sym: Symbol)(using Context, JavaOrderLookup): Descriptor =
    sym match
      // A Java class's statics live in a module class, but dotty spells them with the
      // *class* descriptor: `isAllOf(JavaModule)` takes the `#` branch, so a static is
      // `java/lang/Math#max().` and not `java/lang/Math.max().`. Detected via
      // `sourceLanguage`, since the `JavaModule` flag itself is not exposed.
      case cls: ClassSymbol if cls.isModuleClass && cls.sourceLanguage == SourceLanguage.Java =>
        Descriptor(Descriptor.Kind.Type, moduleClassName(cls))
      // dotty: a Scala ModuleClass delegates to its source module, so `object Foo` is
      // spelled `Foo.` and not `Foo#`.
      case cls: ClassSymbol if cls.isModuleClass =>
        cls.moduleValue.fold(Descriptor(Descriptor.Kind.Type, moduleClassName(cls)))(descriptor)
      case tp: TypeParamSymbol =>
        Descriptor(Descriptor.Kind.TypeParameter, tp.name.toString)
      case pkg: PackageSymbol =>
        Descriptor(Descriptor.Kind.Package, pkg.name.toString)
      case term: TermSymbol if isParam(term) =>
        Descriptor(Descriptor.Kind.Parameter, term.name.toString)
      // The *value* side of a Java statics holder, which is what the qualifier of
      // `Math.max(..)` resolves to. Same `JavaModule` rule as the module class above: the
      // compiler spells it `java/lang/Math#`, not `java/lang/Math.`.
      case term: TermSymbol
          if term.kind == TermSymbolKind.Module && term.sourceLanguage == SourceLanguage.Java =>
        Descriptor(Descriptor.Kind.Type, term.name.toString)
      case term: TermSymbol if term.isMethod || term.kind == TermSymbolKind.Var =>
        // The disambiguator already carries its own parentheses.
        Descriptor(Descriptor.Kind.Method, term.name.toString, OverloadDisambiguator.of(term))
      case term: TermSymbol =>
        Descriptor(Descriptor.Kind.Term, term.name.toString)
      case tpe: TypeSymbol =>
        Descriptor(Descriptor.Kind.Type, tpe.name.toString)
      case other =>
        Descriptor(Descriptor.Kind.Term, other.name.toString)

  /** dotty `Scala3.isGlobal`:
    * {{{
    * sym.is(Package) || !sym.isSelfSym && (sym.is(Param) || sym.owner.isClass) && sym.owner.isGlobal
    * }}}
    */
  private def isGlobal(sym: Symbol)(using Context): Boolean =
    sym match
      case _: PackageSymbol => true
      case _ =>
        sym.owner match
          case null  => false
          case owner => (isParam(sym) || isClassLike(owner)) && isGlobal(owner)

  /** dotty's `sym.owner.isClass`.
    *
    * Packages count: dotty models them as `PackageClassSymbol`, so `isClass` holds for
    * the owner of any top-level definition. tasty-query keeps `PackageSymbol` outside the
    * `ClassSymbol` hierarchy, so the package case has to be added back explicitly --
    * without it every top-level symbol is misclassified as local.
    */
  private def isClassLike(sym: Symbol): Boolean =
    sym.isInstanceOf[ClassSymbol] || sym.isInstanceOf[PackageSymbol]

  /** dotty's `sym.is(Param)`. tasty-query has no such predicate, so it is recovered from
    * the owning method's parameter lists.
    */
  private def isParam(sym: Symbol)(using Context): Boolean =
    sym.owner match
      case owner: TermSymbol if owner.isMethod =>
        owner.paramSymss.exists {
          case Left(termParams)  => termParams.exists(_ eq sym)
          case Right(typeParams) => typeParams.exists(_ eq sym)
        }
      case _ => false

  /** The module class name without its trailing `$`, e.g. `Math$` -> `Math`. */
  private def moduleClassName(cls: ClassSymbol)(using Context): String =
    cls.moduleValue.map(_.name.toString).getOrElse {
      val n = cls.name.toString
      if n.endsWith("$") then n.dropRight(1) else n
    }

  private def isRootPackage(sym: Symbol)(using Context): Boolean =
    sym match
      case pkg: PackageSymbol => pkg.owner == null
      case _                  => false
end TastySymbols
