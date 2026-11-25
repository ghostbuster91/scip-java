package org.scip_code.scip_java.semanticdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.scip_code.scip_java.semanticdb.Semanticdb.AnnotationTree;
import org.scip_code.scip_java.semanticdb.Semanticdb.ClassSignature;
import org.scip_code.scip_java.semanticdb.Semanticdb.Constant;
import org.scip_code.scip_java.semanticdb.Semanticdb.ExistentialType;
import org.scip_code.scip_java.semanticdb.Semanticdb.IntConstant;
import org.scip_code.scip_java.semanticdb.Semanticdb.Language;
import org.scip_code.scip_java.semanticdb.Semanticdb.LiteralTree;
import org.scip_code.scip_java.semanticdb.Semanticdb.Signature;
import org.scip_code.scip_java.semanticdb.Semanticdb.SymbolInformation;
import org.scip_code.scip_java.semanticdb.Semanticdb.SymbolInformation.Kind;
import org.scip_code.scip_java.semanticdb.Semanticdb.SymbolInformation.Property;
import org.scip_code.scip_java.semanticdb.Semanticdb.TextDocument;
import org.scip_code.scip_java.semanticdb.Semanticdb.Tree;
import org.scip_code.scip_java.semanticdb.Semanticdb.Type;
import org.scip_code.scip_java.semanticdb.Semanticdb.TypeRef;
import org.scip_code.scip_java.semanticdb.Semanticdb.UnaryOperator;
import org.scip_code.scip_java.semanticdb.Semanticdb.UnaryOperatorTree;
import org.scip_code.scip_java.semanticdb.Semanticdb.UnionType;
import org.scip_code.scip_java.semanticdb.Semanticdb.ValueSignature;

/**
 * Colocated unit test for {@link SignatureFormatter}, pinning down formatting regressions for
 * function types, union types, class parents, existential brackets, and unary operators.
 */
class SignatureFormatterTest {

  private static final Symtab EMPTY_SYMTAB = new Symtab(TextDocument.getDefaultInstance());

  private static Type typeRef(String symbol, Type... typeArguments) {
    TypeRef.Builder builder = TypeRef.newBuilder().setSymbol(symbol);
    for (Type typeArgument : typeArguments) builder.addTypeArguments(typeArgument);
    return Type.newBuilder().setTypeRef(builder).build();
  }

  private static String format(SymbolInformation info) {
    return new SignatureFormatter(info, EMPTY_SYMTAB).formatSymbol();
  }

  private static SymbolInformation scalaValWithType(Type type) {
    return SymbolInformation.newBuilder()
        .setSymbol("_empty_/foo.")
        .setDisplayName("foo")
        .setLanguage(Language.SCALA)
        .setSignature(
            Signature.newBuilder().setValueSignature(ValueSignature.newBuilder().setTpe(type)))
        .build();
  }

  /**
   * `Function0[Int]` is `() => Int`, `Function1[String, Int]` is `String => Int` (no parens around
   * the single parameter), and `Function2[String, Boolean, Int]` is `(String, Boolean) => Int`.
   */
  @Test
  void formatsScalaFunctionTypesByArity() {
    assertEquals(
        "foo: () => Int ",
        format(scalaValWithType(typeRef("scala/Function0#", typeRef("scala/Int#")))));
    assertEquals(
        "foo: String => Int ",
        format(
            scalaValWithType(
                typeRef("scala/Function1#", typeRef("java/lang/String#"), typeRef("scala/Int#")))));
    assertEquals(
        "foo: (String, Boolean) => Int ",
        format(
            scalaValWithType(
                typeRef(
                    "scala/Function2#",
                    typeRef("java/lang/String#"),
                    typeRef("scala/Boolean#"),
                    typeRef("scala/Int#")))));
  }

  /** Scala 3 union types must read from `getUnionType()`, not `getIntersectionType()`. */
  @Test
  void formatsScalaUnionTypes() {
    Type union =
        Type.newBuilder()
            .setUnionType(
                UnionType.newBuilder()
                    .addTypes(typeRef("scala/Int#"))
                    .addTypes(typeRef("java/lang/String#")))
            .build();

    assertEquals("foo: Int | String ", format(scalaValWithType(union)));
  }

  /**
   * Non-case classes keep their explicit parents; case classes drop only synthetic
   * Product/Serializable.
   */
  @Test
  void preservesNonCaseClassParentsAndFiltersSyntheticCaseClassParents() {
    SymbolInformation plainClass =
        SymbolInformation.newBuilder()
            .setSymbol("com/example/Sub#")
            .setDisplayName("Sub")
            .setKind(Kind.CLASS)
            .setLanguage(Language.SCALA)
            .setSignature(
                Signature.newBuilder()
                    .setClassSignature(
                        ClassSignature.newBuilder().addParents(typeRef("com/example/Base#"))))
            .build();

    assertEquals("class Sub extends Base", format(plainClass));

    SymbolInformation caseClass =
        SymbolInformation.newBuilder()
            .setSymbol("com/example/Point#")
            .setDisplayName("Point")
            .setKind(Kind.CLASS)
            .setProperties(Property.CASE_VALUE)
            .setLanguage(Language.SCALA)
            .setSignature(
                Signature.newBuilder()
                    .setClassSignature(
                        ClassSignature.newBuilder()
                            .addParents(typeRef("com/example/Base#"))
                            .addParents(typeRef("scala/Product#"))
                            .addParents(typeRef("scala/Serializable#"))))
            .build();

    assertEquals("case class Point extends Base", format(caseClass));
  }

  /** A Scala existential type argument list must close with `]`, not re-open with `[`. */
  @Test
  void closesAScalaExistentialTypeWithAMatchingBracket() {
    SymbolInformation info =
        SymbolInformation.newBuilder()
            .setSymbol("_empty_/foo.")
            .setDisplayName("foo")
            .setLanguage(Language.SCALA)
            .setSignature(
                Signature.newBuilder()
                    .setValueSignature(
                        ValueSignature.newBuilder()
                            .setTpe(
                                Type.newBuilder()
                                    .setExistentialType(
                                        ExistentialType.newBuilder()
                                            .setTpe(
                                                typeRef(
                                                    "scala/collection/Seq#",
                                                    typeRef("scala/Int#")))))))
            .build();

    assertEquals("foo: Seq[Int] ", format(info));
  }

  /** `UNARY_PLUS` must render as `+`, not `-` (a copy-paste of the `UNARY_MINUS` case above it). */
  @Test
  void rendersUnaryPlusAsPlus() {
    Tree five =
        Tree.newBuilder()
            .setLiteralTree(
                LiteralTree.newBuilder()
                    .setConstant(
                        Constant.newBuilder().setIntConstant(IntConstant.newBuilder().setValue(5))))
            .build();
    Tree plusFive =
        Tree.newBuilder()
            .setUnaryopTree(
                UnaryOperatorTree.newBuilder().setOp(UnaryOperator.UNARY_PLUS).setTree(five))
            .build();
    SymbolInformation info =
        SymbolInformation.newBuilder()
            .setSymbol("_empty_/foo.")
            .setDisplayName("foo")
            .setLanguage(Language.SCALA)
            .addAnnotations(
                AnnotationTree.newBuilder()
                    .setTpe(typeRef("com/example/Ann#"))
                    .addParameters(plusFive))
            .setSignature(
                Signature.newBuilder()
                    .setValueSignature(ValueSignature.newBuilder().setTpe(typeRef("scala/Int#"))))
            .build();

    assertTrue(format(info).contains("@Ann(+5)"), format(info));
  }
}
