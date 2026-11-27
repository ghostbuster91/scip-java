package org.scip_code.scip_java.aggregator;

import java.util.concurrent.ConcurrentHashMap;
import org.scip_code.scip_java.shared.ScipSymbols;

/**
 * Rewrites bare descriptor symbols emitted by the compiler plugins (e.g. {@code
 * _root_/com/example/Foo#}) into fully-qualified SCIP symbols (e.g. {@code scip-java maven
 * com.example:my-lib 1.0 _root_/com/example/Foo#}) by looking up the descriptor's owning package in
 * a {@link PackageTable}.
 *
 * <p>Local symbols (those starting with {@code "local "}) are passed through unchanged.
 *
 * <p>One rewriter serves the documents of one project, because the same bare symbol can belong to
 * different packages depending on which project's classpath it was compiled against; {@code
 * project} is {@code null} for documents no project was found to own.
 */
final class SymbolRewriter {
  private final PackageTable packages;
  private final ScipProject project;
  private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

  SymbolRewriter(PackageTable packages, ScipProject project) {
    this.packages = packages;
    this.project = project;
  }

  /** Rewrites {@code symbol}, or returns {@code symbol} unchanged when no rewriting applies. */
  String rewrite(String symbol) {
    if (symbol == null || symbol.isEmpty()) return symbol;
    if (ScipSymbols.isLocal(symbol)) return symbol;
    return cache.computeIfAbsent(
        symbol,
        s -> {
          Package pkg = packages.packageForSymbol(s, project).orElse(Package.EMPTY);
          return "scip-java " + pkg.scipTypedEncoding() + " " + s;
        });
  }
}
