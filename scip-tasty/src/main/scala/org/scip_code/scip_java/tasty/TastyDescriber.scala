package org.scip_code.scip_java.tasty

import org.scip_code.scip.{Relationship, Signature, SymbolInformation}
import tastyquery.Contexts.Context
import tastyquery.Modifiers.TermSymbolKind
import tastyquery.Symbols.*

/** Builds the `SymbolInformation` that describes a symbol: its kind, its signature and what
  * it overrides.
  *
  * Shared by both halves of the front-end. A symbol defined in the indexed sources is
  * described inside its own document; a symbol referenced from a dependency is described as
  * an external symbol, with no document and no occurrence because there is no source file
  * here to attach a range to. The description itself is the same either way, which is why
  * this is one place rather than two.
  */
object TastyDescriber {

  /** `display_name` is load-bearing, not cosmetic: `SemanticdbToScip.addCliqueReferences`
    * compares display names to decide whether to add an `is_reference` relationship.
    */
  def describe(sym: Symbol, symbol: String, displayName: String)(using
    Context,
    JavaOrderLookup,
  ): SymbolInformation = {
    val info = SymbolInformation.newBuilder().setSymbol(symbol).setDisplayName(displayName)
    kindOf(sym).foreach(info.setKind)
    signatureOf(sym).foreach { text =>
      info.setSignatureDocumentation(Signature.newBuilder().setLanguage("scala").setText(text))
    }
    overrides(sym).foreach(info.addRelationships)
    info.build()
  }

  /** Every method overrides something on `Object`; both `SemanticdbToScip` and
    * `ScipAggregator` filter those out, so it is done at the source here too -- otherwise
    * "find implementations" on `Object#toString` lists every class in the codebase.
    */
  private val IgnoredOverriddenSymbol: String = "java/lang/Object#"

  private def overrides(sym: Symbol)(using Context, JavaOrderLookup): List[Relationship] =
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

  /** A rendering of the symbol's type, for hover.
    *
    * `showBasic` is all tasty-query offers -- `Printers` is `private[tastyquery]`, so the
    * formatting cannot be controlled -- and it throws on types it cannot render, hence the
    * guard. Scaladoc is not available at all: TASTy has a `Comments` section but
    * tasty-query never unpickles it.
    */
  private def signatureOf(sym: Symbol)(using Context): Option[String] =
    sym match {
      case t: TermSymbol =>
        scala.util.Try(t.declaredType.showBasic).toOption.filter(_.nonEmpty)
      case _ => None
    }

  private def kindOf(sym: Symbol)(using Context): Option[SymbolInformation.Kind] =
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
}
