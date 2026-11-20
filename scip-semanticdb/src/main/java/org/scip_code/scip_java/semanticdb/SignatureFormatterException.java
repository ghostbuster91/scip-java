package org.scip_code.scip_java.semanticdb;

public class SignatureFormatterException extends RuntimeException {
  public SignatureFormatterException(
      Semanticdb.SymbolInformation symbolInformation, Throwable cause) {
    super(
        String.format(
            "failed to format symbol '%s'\n%s", symbolInformation.getSymbol(), symbolInformation),
        cause);
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    // This exception doesn't have a relevant stack trace. The cause exception
    // already points to the culprit filename and line number.
    return this;
  }
}
