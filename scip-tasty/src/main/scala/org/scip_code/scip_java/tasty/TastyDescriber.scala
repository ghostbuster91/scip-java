package org.scip_code.scip_java.tasty

import org.scip_code.scip.{Relationship, Signature, SymbolInformation}
import tastyquery.Contexts.Context
import tastyquery.Modifiers.TermSymbolKind
import tastyquery.SourceLanguage
import tastyquery.Symbols.*
import tastyquery.Symbols.TypeMemberDefinition

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

  /** Returns a builder rather than a finished message, so a caller can still add the
    * `is_reference` relationships that tie a definition clique together -- those are only
    * known once a whole document has been walked.
    *
    * `displayName` is load-bearing rather than cosmetic: `SemanticdbToScip.addCliqueReferences`
    * compares display names to decide whether two symbols belong in one clique.
    */
  def describe(sym: Symbol, symbol: String, displayName: String)(using
    Context,
    JavaOrderLookup,
  ): SymbolInformation.Builder = {
    val info = SymbolInformation.newBuilder().setSymbol(symbol).setDisplayName(displayName)
    kindOf(sym).foreach(info.setKind)
    signatureOf(sym).foreach { text =>
      info.setSignatureDocumentation(
        Signature.newBuilder().setLanguage(languageOf(sym)).setText(text)
      )
    }
    overrides(sym).foreach(info.addRelationships)
    info
  }

  /** The name a client shows for `sym`, stripping synthetic module `$` and mapping a file's
    * synthetic `Foo$package` wrapper to its package name.
    */
  def displayNameOf(sym: Symbol)(using Context): String =
    displayNameOf(sym, sourceName(sym))

  /** The name a client shows for `sym` given the name matched in source.
    *
    * A file's top-level definitions are wrapped in a synthetic `Foo$package` module, which
    * the compiler reports as a package object named after its *package* -- `example`, not
    * `Fixture$package`. The two names have to stay separate: matching "example" against the
    * source text would find it in the `package com.example` clause and wrongly claim a range
    * there.
    */
  def displayNameOf(sym: Symbol, matched: String)(using Context): String =
    if (isPackageWrapper(sym)) Option(sym.owner).map(_.name.toString).getOrElse(matched)
    else matched

  /** The synthetic module a file's top-level definitions live in.
    *
    * The module class is named `Foo$package$` and the module value `Foo$package`, so the
    * trailing object marker comes off before the test.
    */
  def isPackageWrapper(sym: Symbol): Boolean =
    sym.name.toString.stripSuffix("$").endsWith("$package")

  /** The name as it is written in source.
    *
    * A module class is pickled as `Foo$`, which never appears in source; the name to look
    * for is the module value's. Without this every `object` loses its definition occurrence.
    */
  def sourceName(sym: Symbol)(using Context): String =
    sym match {
      case cls: ClassSymbol if cls.isModuleClass =>
        cls.moduleValue.map(_.name.toString).getOrElse {
          val n: String = cls.name.toString
          if (n.endsWith("$")) n.dropRight(1) else n
        }
      case _ => sym.name.toString
    }

  private def languageOf(sym: Symbol)(using Context): String =
    sym match {
      case t: TermOrTypeSymbol if t.sourceLanguage == SourceLanguage.Java => "java"
      case _                                                              => "scala"
    }

  /** `java/lang/Object#` and anything declared in it.
    *
    * Every class descends from `Object`, so letting its members through would make "find
    * implementations" on `Object#toString` list every class in the codebase -- which is the
    * reason `SemanticdbToScip` and `ScipAggregator` filter it too.
    *
    * The prefix matters. Matching only the class symbol lets a *member* through, and
    * tasty-query does report them: it names `java/lang/Object#toString()` as overridden
    * where the compiler names `scala/Any#toString()`. Both are true of the JVM, but a Scala
    * index should say what the compiler says, and the Scala-level declaration is `Any`.
    */
  private val IgnoredOverriddenPrefix: String = "java/lang/Object#"

  private def overrides(sym: Symbol)(using Context, JavaOrderLookup): List[Relationship] =
    sym match {
      case t: TermOrTypeSymbol =>
        // An override is also a reference, so that "find references" on the overridden
        // member lists its implementations -- except for the kinds where that would be
        // noise rather than navigation. `SemanticdbToScip.supportsReferenceRelationship`
        // draws the same line.
        val alsoReference: Boolean = supportsReferenceRelationship(sym)
        scala.util.Try(t.allOverriddenSymbols.toList).getOrElse(Nil).flatMap { parent =>
          TastySymbols
            .globalSymbol(parent)
            .filterNot(_.startsWith(IgnoredOverriddenPrefix))
            .map(s =>
              Relationship
                .newBuilder()
                .setSymbol(s)
                .setIsImplementation(true)
                .setIsReference(alsoReference)
                .build()
            )
        }
      case _ => Nil
    }

  /** Mirrors `SemanticdbToScip.supportsReferenceRelationship`: a type-like symbol's
    * override relationship is not also a reference, or "find references" on a class or trait
    * would list every subclass as a use of it.
    */
  private def supportsReferenceRelationship(sym: Symbol)(using Context): Boolean =
    kindOf(sym) match {
      case Some(SymbolInformation.Kind.Interface) | Some(SymbolInformation.Kind.Trait) |
          Some(SymbolInformation.Kind.Enum) | Some(SymbolInformation.Kind.Type) |
          Some(SymbolInformation.Kind.Class) | Some(SymbolInformation.Kind.Object) |
          Some(SymbolInformation.Kind.PackageObject) =>
        false
      case _ => true
    }

  /** A rendering of the symbol's type, for hover.
    *
    * `showBasic` is all tasty-query offers -- `Printers` is `private[tastyquery]`, so the
    * formatting cannot be controlled -- and it throws on types it cannot render, hence the
    * guard. Scaladoc is not available at all: TASTy has a `Comments` section but
    * tasty-query never unpickles it.
    */
  private def signatureOf(sym: Symbol)(using Context): Option[String] =
    scala.util
      .Try(sym match {
        case t: TermSymbol => t.declaredType.showBasic
        // A type member shows what it is defined as, which is the whole of its signature.
        case t: TypeMemberSymbol =>
          t.typeDef match {
            case TypeMemberDefinition.TypeAlias(alias)          => s"= ${alias.showBasic}"
            case TypeMemberDefinition.AbstractType(bounds)      => bounds.showBasic
            case TypeMemberDefinition.OpaqueTypeAlias(_, alias) => s"= ${alias.showBasic}"
          }
        // A class shows what it extends. `Object` and `Matchable` are dropped: every class
        // has them, so listing them is noise in a hover rather than information.
        case cls: ClassSymbol =>
          cls.parents
            .map(_.showBasic)
            .filterNot(p => p == "java.lang.Object" || p == "scala.Matchable")
            .mkString("extends ", " with ", "")
        case _ => ""
      })
      .toOption
      .filter(s => s.nonEmpty && s != "extends ")

  /** The SCIP kind, mirroring `SemanticdbToScip.scipKind`.
    *
    * The non-obvious part is that a Scala `val` or `var` member is **not** a field. The
    * compiler reports it to SemanticDB as a `METHOD`, because that is what it is -- an
    * accessor -- and `scipKind` then maps `METHOD` to `Method`, or to `AbstractMethod` or
    * `StaticMethod` depending on the symbol's properties. Mapping `Val` to `Field` here
    * disagreed with the SemanticDB front-end on 96 symbols of the parity fixture, which is
    * user-visible: the kind drives a client's icons and filtering.
    *
    * `Field` is therefore reserved for a Java field, which reaches this through
    * `sourceLanguage`.
    */
  private def kindOf(sym: Symbol)(using Context): Option[SymbolInformation.Kind] =
    sym match {
      case cls: ClassSymbol =>
        // A file's top-level definitions live in a synthetic `Foo$package` module, which the
        // compiler reports as a package object rather than a plain one.
        if (isPackageWrapper(cls)) Some(SymbolInformation.Kind.PackageObject)
        else if (cls.isModuleClass) Some(SymbolInformation.Kind.Object)
        else if (cls.isTrait) Some(SymbolInformation.Kind.Trait)
        else if (cls.isEnum) Some(SymbolInformation.Kind.Enum)
        else Some(SymbolInformation.Kind.Class)
      case _: TypeParamSymbol => Some(SymbolInformation.Kind.TypeParameter)
      case _: TypeSymbol      => Some(SymbolInformation.Kind.Type)
      case t: TermSymbol      => Some(termKind(t))
      case _: PackageSymbol   => Some(SymbolInformation.Kind.Package)
    }

  private def termKind(t: TermSymbol)(using Context): SymbolInformation.Kind =
    if (TastySymbols.isParam(t)) SymbolInformation.Kind.Parameter
    else if (TastyExclusions.isConstructor(t)) SymbolInformation.Kind.Constructor
    else if (t.kind == TermSymbolKind.Module)
      if (isPackageWrapper(t)) SymbolInformation.Kind.PackageObject
      else SymbolInformation.Kind.Object
    // An enum case compiles to a static member of the enum class, which is what the
    // compiler reports as `METHOD | STATIC`.
    else if (t.isEnumCase) SymbolInformation.Kind.StaticMethod
    else if (t.sourceLanguage == SourceLanguage.Java)
      if (t.kind != TermSymbolKind.Def)
        if (isJavaStatic(t)) SymbolInformation.Kind.StaticField else SymbolInformation.Kind.Field
      else if (isJavaStatic(t)) SymbolInformation.Kind.StaticMethod
      else if (t.isAbstractMember) SymbolInformation.Kind.AbstractMethod
      else SymbolInformation.Kind.Method
    else if (!TastySymbols.isGlobal(t) && t.kind != TermSymbolKind.Def)
      SymbolInformation.Kind.Variable
    else if (t.isAbstractMember) SymbolInformation.Kind.AbstractMethod
    else SymbolInformation.Kind.Method

  /** A Java class's statics live in its synthetic module class (`JavaModule`). */
  private def isJavaStatic(t: TermSymbol)(using Context): Boolean =
    t.owner match {
      case cls: ClassSymbol => cls.isModuleClass && cls.sourceLanguage == SourceLanguage.Java
      case _                => false
    }
}
