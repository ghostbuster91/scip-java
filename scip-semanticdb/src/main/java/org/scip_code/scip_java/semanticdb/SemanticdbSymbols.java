package org.scip_code.scip_java.semanticdb;

/**
 * The one place the two symbol formats disagree. SemanticDB and SCIP spell global symbols
 * identically — {@code com/example/Foo#bar().} — which is what lets a Scala source and a Java
 * source cross-reference each other. They differ on locals: SemanticDB writes {@code local8} where
 * SCIP writes {@code local 8}.
 */
final class SemanticdbSymbols {
  private static final String LOCAL_PREFIX = "local";

  private SemanticdbSymbols() {}

  /**
   * Whether {@code symbol} is local, or is owned by something local. A definition inside a method
   * body is local, and so is everything nested under it: the parameter of a local method is spelled
   * {@code local15(name)}.
   *
   * <p>The prefix has to be followed by at least one digit, because a package named {@code local}
   * shares the prefix but is a global symbol.
   */
  static boolean isWithinLocal(String symbol) {
    if (!symbol.startsWith(LOCAL_PREFIX)) return false;
    int i = LOCAL_PREFIX.length();
    while (i < symbol.length() && Character.isDigit(symbol.charAt(i))) i++;
    return i > LOCAL_PREFIX.length();
  }

  /**
   * Translates a SemanticDB symbol into its SCIP spelling. Global symbols pass through unchanged.
   *
   * <p>Translating locals matters beyond spelling: the aggregator qualifies every symbol it does
   * not recognize as local with the enclosing package, so an untranslated {@code local8} would land
   * in the global namespace and collide with the locals of every other document. SCIP takes the
   * part after {@code "local "} as an opaque id, so a nested local keeps its descriptors.
   */
  static String toScip(String symbol) {
    if (!isWithinLocal(symbol)) return symbol;
    return LOCAL_PREFIX + " " + symbol.substring(LOCAL_PREFIX.length());
  }
}
