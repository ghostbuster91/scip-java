package org.scip_code.scip_java.shared;

import java.util.Optional;
import org.scip_code.scip_java.shared.ScipSymbols.Descriptor;
import org.scip_code.scip_java.shared.ScipSymbols.Descriptor.Kind;

public record SymbolDescriptor(ScipSymbols.Descriptor descriptor, String owner) {

  public static SymbolDescriptor NONE = new SymbolDescriptor(Descriptor.NONE, ScipSymbols.NONE);

  public static SymbolDescriptor parseFromSymbol(String symbol) {
    return new Parser(symbol).entryPoint();
  }

  /**
   * Returns the outermost non-package descriptor of {@code symbol}, or empty when there is none to
   * find. A symbol that does not parse yields empty rather than throwing: this is reached for every
   * symbol in an index, so one malformed symbol out of a compiler plugin should cost that symbol
   * its package rather than the whole index.
   */
  public static Optional<SymbolDescriptor> toplevel(String symbol) {
    if (symbol == null || symbol.isEmpty()) {
      return Optional.empty();
    }
    if (ScipSymbols.isLocal(symbol)) {
      return Optional.empty();
    }
    Optional<SymbolDescriptor> parsed = parseOrEmpty(symbol);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    SymbolDescriptor current = parsed.get();
    if (current.descriptor().kind() == Kind.Package) {
      return Optional.empty();
    }
    Optional<SymbolDescriptor> owner = parseOrEmpty(current.owner());
    if (owner.isEmpty()) {
      return Optional.empty();
    }
    if (owner.get().descriptor().kind() == Kind.Package) {
      return Optional.of(current);
    } else {
      return toplevel(current.owner());
    }
  }

  private static Optional<SymbolDescriptor> parseOrEmpty(String symbol) {
    try {
      return Optional.of(parseFromSymbol(symbol));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  public static class Parser {

    private int i;
    private char currChar = EOF;
    private final String symbol;

    private static final char BOF = '\u0000';
    private static final char EOF = '\u001A';

    public Parser(String symbol) {
      i = symbol.length();
      this.symbol = symbol;
    }

    public SymbolDescriptor entryPoint() {
      if (ScipSymbols.isLocal(symbol)) {
        return new SymbolDescriptor(Descriptor.local(symbol), ScipSymbols.NONE);
      }
      if (ScipSymbols.NONE.equals(symbol)) {
        return SymbolDescriptor.NONE;
      }
      readChar();
      ScipSymbols.Descriptor descriptor = parseDescriptor();

      String owner = i < 0 ? ScipSymbols.ROOT_PACKAGE : symbol.substring(0, i + 1);
      return new SymbolDescriptor(descriptor, owner);
    }

    public IllegalArgumentException fail() {
      StringBuilder b = new StringBuilder();
      for (int j = 0; j < i; j++) {
        b.append(' ');
      }
      b.append('^');
      return new IllegalArgumentException("invalid symbol format\n" + symbol + "\n" + b.toString());
    }

    public char readChar() {
      if (i < 0) throw fail();

      if (i == 0) {
        i--;
        currChar = BOF;
        return currChar;
      }

      i--;
      currChar = symbol.charAt(i);
      return currChar;
    }

    public String parseValue() {
      if (currChar == '`') {
        int end = i;
        while (readChar() != '`') {}
        readChar();
        return symbol.substring(i + 2, end);
      } else {
        int end = i + 1;
        if (!Character.isJavaIdentifierPart(currChar)) throw fail();

        while (Character.isJavaIdentifierPart(readChar()) && currChar != BOF) {}
        return symbol.substring(i + 1, end);
      }
    }

    public String parseDisambiguator() {
      int end = i + 1;
      if (currChar != ')') throw fail();
      while (readChar() != '(') {}
      readChar();
      return symbol.substring(i + 1, end);
    }

    public ScipSymbols.Descriptor parseDescriptor() {
      String value;
      switch (currChar) {
        case '.':
          readChar();
          if (currChar == ')') {
            String disamiguator = parseDisambiguator();
            value = parseValue();
            return new ScipSymbols.Descriptor(Kind.Method, value, disamiguator);
          } else {
            return new ScipSymbols.Descriptor(Kind.Term, parseValue());
          }
        case '#':
          readChar();
          return new ScipSymbols.Descriptor(Kind.Type, parseValue());
        case '/':
          readChar();
          return new ScipSymbols.Descriptor(Kind.Package, parseValue());
        case ')':
          readChar();
          value = parseValue();
          if (currChar != '(') throw fail();
          readChar();
          return new ScipSymbols.Descriptor(Kind.Parameter, value);
        case ']':
          readChar();
          value = parseValue();
          if (currChar != '[') throw fail();
          readChar();
          return new ScipSymbols.Descriptor(Kind.TypeParameter, value);
        default:
          throw fail();
      }
    }
  }
}
