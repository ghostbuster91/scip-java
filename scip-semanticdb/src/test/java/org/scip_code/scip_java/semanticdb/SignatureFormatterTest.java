package org.scip_code.scip_java.semanticdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.scip_code.scip_java.semanticdb.Semanticdb.AnnotationTree;
import org.scip_code.scip_java.semanticdb.Semanticdb.Constant;
import org.scip_code.scip_java.semanticdb.Semanticdb.ExistentialType;
import org.scip_code.scip_java.semanticdb.Semanticdb.IntConstant;
import org.scip_code.scip_java.semanticdb.Semanticdb.Language;
import org.scip_code.scip_java.semanticdb.Semanticdb.LiteralTree;
import org.scip_code.scip_java.semanticdb.Semanticdb.Signature;
import org.scip_code.scip_java.semanticdb.Semanticdb.SymbolInformation;
import org.scip_code.scip_java.semanticdb.Semanticdb.TextDocument;
import org.scip_code.scip_java.semanticdb.Semanticdb.Tree;
import org.scip_code.scip_java.semanticdb.Semanticdb.Type;
import org.scip_code.scip_java.semanticdb.Semanticdb.TypeRef;
import org.scip_code.scip_java.semanticdb.Semanticdb.UnaryOperator;
import org.scip_code.scip_java.semanticdb.Semanticdb.UnaryOperatorTree;
import org.scip_code.scip_java.semanticdb.Semanticdb.ValueSignature;

/**
 * Colocated unit test for {@link SignatureFormatter}, which until now had no coverage at all. Each
 * case below pins down a one-line regression found by reading the code: a stray {@code s} instead
 * of {@code b} that routed a nullary function type's argument into the wrong accumulator, a Scala
 * existential type closing with the wrong bracket, and a unary {@code +} that rendered as {@code
 * -}.
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

  /**
   * `formatType`'s nullary-function branch (`scala/Function0[Int]`, i.e. `=> Int`) used to append
   * its argument into the formatter's whole-signature field instead of the local accumulator the
   * branch is building -- invisible in the branch's own return value, and spliced into the
   * signature one call too early, swallowing the space before {@code =>}.
   */
  @Test
  void formatsANullaryFunctionTypeWithoutLosingASpace() {
    SymbolInformation info =
        SymbolInformation.newBuilder()
            .setSymbol("_empty_/foo.")
            .setDisplayName("foo")
            .setLanguage(Language.SCALA)
            .setSignature(
                Signature.newBuilder()
                    .setValueSignature(
                        ValueSignature.newBuilder()
                            .setTpe(typeRef("scala/Function0#", typeRef("scala/Int#")))))
            .build();

    assertEquals("foo: Int => Int ", format(info));
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
