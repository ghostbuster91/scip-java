package org.scip_code.scip_java.semanticdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemanticdbSymbolsTest {

  @Test
  void translatesLocalSymbols() {
    assertEquals("local 3", SemanticdbSymbols.toScip("local3"));
    assertEquals("local 42", SemanticdbSymbols.toScip("local42"));
  }

  /**
   * Everything declared inside a method body is local too, down to the parameters of a method
   * declared in another method's body.
   */
  @Test
  void translatesSymbolsOwnedByALocal() {
    assertTrue(SemanticdbSymbols.isWithinLocal("local15(name)"));
    assertEquals("local 15(name)", SemanticdbSymbols.toScip("local15(name)"));
    assertEquals("local 15(name).", SemanticdbSymbols.toScip("local15(name)."));
  }

  @Test
  void leavesGlobalSymbolsAlone() {
    assertEquals("com/example/Foo#", SemanticdbSymbols.toScip("com/example/Foo#"));
    assertEquals("com/example/Foo#bar().", SemanticdbSymbols.toScip("com/example/Foo#bar()."));
  }

  /**
   * A package named `local` shares the prefix with a local symbol, so matching on the prefix alone
   * would rewrite a global symbol into the local namespace.
   */
  @Test
  void aPackageNamedLocalIsGlobal() {
    assertFalse(SemanticdbSymbols.isWithinLocal("local/Foo#"));
    assertFalse(SemanticdbSymbols.isWithinLocal("local"));
    assertFalse(SemanticdbSymbols.isWithinLocal("locale/Foo#"));
    assertEquals("local/Foo#", SemanticdbSymbols.toScip("local/Foo#"));
  }

  @Test
  void recognizesLocalSymbols() {
    assertTrue(SemanticdbSymbols.isWithinLocal("local0"));
    assertTrue(SemanticdbSymbols.isWithinLocal("local123"));
    // scip-javac's own spelling for a Java wildcard is not a numbered local.
    assertFalse(SemanticdbSymbols.isWithinLocal("local_wildcard"));
  }
}
