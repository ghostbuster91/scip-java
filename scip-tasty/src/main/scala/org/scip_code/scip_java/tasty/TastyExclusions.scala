package org.scip_code.scip_java.tasty

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*

/** Decides which definitions get a *definition occurrence* in the index.
  *
  * This mirrors the gate in `ExtractSemanticDB.Extractor.traverse`, which registers a
  * definition only when
  * {{{
  * !excludeDef(tree.symbol) && (tree.span.hasLength || tree.symbol.isAnonymousClass)
  * }}}
  * and otherwise falls through to recording symbol metadata with no occurrence. The
  * `span.hasLength` half is the one that matters in practice, and it is reproducible from
  * tasty-query; `excludeDef`'s blanket `sym.is(Synthetic)` test is not, because `Flags` is
  * `private[tastyquery]`.
  *
  * Measured against a fixture covering case classes, enums, givens, extension methods and
  * top-level definitions: of the 44 symbols TASTy carries that the compiler's SemanticDB
  * does not record -- `equals`, `hashCode`, `productArity`/`productElement`/`productPrefix`,
  * `canEqual`, `writeReplace`, `fromProduct`, `MirroredMonoType`, module-class constructors
  * -- 43 are synthetic with a zero-extent span, so this rule gives them no occurrence.
  *
  * Note what this is *not*. Two tempting rules were tested and falsified:
  *   - a flag-based "drop synthetics" rule is wrong, because `copy`, `apply` and `unapply`
  *     are synthetic yet the compiler does emit them, positioned on the case class name;
  *   - "only emit what appears as a tree node" is also wrong, because the unwanted members
  *     are tree nodes in the pickled `Template` body too.
  * The compiler's exact `SymbolInformation` set depends on where `ExtractSemanticDB` sits
  * relative to synthetic-member generation, which a TASTy reader cannot observe. That is
  * acceptable: those symbols are never referenced from source, so carrying extra metadata
  * for them costs index size, not resolution.
  */
object TastyExclusions:

  /** True when `tree` should not produce a definition occurrence. */
  def excludeDefinition(tree: DefTree)(using Context): Boolean =
    val pos = tree.pos
    // dotty's `tree.span.hasLength`: a zero-extent or absent span means the definition is
    // not present in source, so there is no range to point at.
    pos.isUnknown || pos.isZeroExtent

  /** True when `sym` is a primary/secondary constructor.
    *
    * tasty-query keeps `Symbol.isConstructor` private, so this goes by name, which is what
    * dotty's `nme.CONSTRUCTOR` amounts to anyway.
    */
  def isConstructor(sym: Symbol): Boolean = sym.name.toString == "<init>"

  /** True when a method's type parameters must not be emitted.
    *
    * A class's constructor carries its own copies of the class type parameters. dotty
    * attributes them to the class -- `Show#[A]` -- so emitting the constructor's copies
    * would add a second, differently-owned spelling (`Show#`<init>`().[A]`) that the
    * compiler never produces. The class's own `typeParams` are emitted separately.
    */
  def skipTypeParams(method: Symbol): Boolean = isConstructor(method)
end TastyExclusions
