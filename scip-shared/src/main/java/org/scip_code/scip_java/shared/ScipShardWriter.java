package org.scip_code.scip_java.shared;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.SymbolInformation;

/** Serializes SCIP shards on disk: a single {@link Document}, or a set of external symbols. */
public final class ScipShardWriter {
  private ScipShardWriter() {}

  /**
   * Wraps {@code document} in a singleton {@link Index} message (no {@code Metadata}) and writes it
   * to {@code output}, creating parent directories as needed. The aggregator owns the per-index
   * metadata, so per-source shards intentionally omit it.
   */
  public static void writeShard(Path output, Document document) throws IOException {
    byte[] bytes = Index.newBuilder().addDocuments(document).build().toByteArray();
    Files.createDirectories(output.getParent());
    Files.write(output, bytes);
  }

  /**
   * Writes {@code symbols} as an {@link Index} carrying only {@code external_symbols}.
   *
   * <p>External symbols describe definitions that live outside the indexed sources — a method in a
   * dependency, say — so that hovering one shows its signature without the dependency having been
   * indexed itself. They carry no occurrences and no document, because there is no source file in
   * this repository to attach a range to.
   */
  public static void writeExternalSymbols(Path output, List<SymbolInformation> symbols)
      throws IOException {
    byte[] bytes = Index.newBuilder().addAllExternalSymbols(symbols).build().toByteArray();
    Files.createDirectories(output.getParent());
    Files.write(output, bytes);
  }
}
