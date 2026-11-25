package org.scip_code.scip_java.aggregator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Index;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.ToolInfo;
import org.scip_code.scip_java.shared.ScipShardPaths;
import org.scip_code.scip_java.shared.ScipShardWriter;

/**
 * A shard of {@code external_symbols} describes definitions outside the indexed sources, so it
 * carries no documents at all.
 *
 * <p>That is the whole risk being tested: shard reading used to return only the documents of each
 * {@link Index}, which discarded such a shard silently -- no error, no warning, and nothing in the
 * output to show it had happened.
 */
class ExternalSymbolsAggregationTest {

  @Test
  void carriesExternalSymbolsThroughToTheIndex(@TempDir Path tmp) throws IOException {
    Path targetroot = Files.createDirectories(tmp.resolve("targetroot"));
    Path shard =
        targetroot.resolve(ScipShardPaths.SCIP_ROOT).resolve("external-symbols-tasty.scip");
    ScipShardWriter.writeExternalSymbols(
        shard,
        List.of(
            SymbolInformation.newBuilder()
                .setSymbol("java/lang/Math#max().")
                .setDisplayName("max")
                .setKind(SymbolInformation.Kind.Method)
                .build()));

    List<SymbolInformation> external = aggregate(tmp, targetroot);

    assertEquals(1, external.size(), "the external-symbols shard must not be dropped");
    assertEquals("max", external.get(0).getDisplayName());
    // Unqualified here because the fixture declares no packages, so the package table cannot
    // attribute the symbol to a dependency. What matters is that it survived aggregation.
    assertTrue(
        external.get(0).getSymbol().endsWith("java/lang/Math#max()."), external.get(0).getSymbol());
  }

  private List<SymbolInformation> aggregate(Path tmp, Path targetroot) throws IOException {
    Path output = tmp.resolve("index.scip");
    ScipAggregator.run(
        new ScipAggregatorOptions(
            List.of(targetroot),
            output,
            tmp,
            new ScipAggregatorReporter() {},
            ToolInfo.newBuilder().setName("test").setVersion("0").build(),
            /* parallel= */ false,
            List.of(),
            /* emitInverseRelationships= */ false,
            /* allowEmptyIndex= */ true,
            /* allowExportingGlobalSymbolsFromDirectoryEntries= */ false));

    // `ScipWriter.emitTyped` concatenates serialized `Index` messages with no delimiters.
    // Concatenating messages of one type is equivalent to a single message whose repeated
    // fields are the concatenation, so the whole file parses as one `Index` -- which is also
    // why appending a chunk carrying only `external_symbols` composes with the document ones.
    CodedInputStream in = CodedInputStream.newInstance(Files.readAllBytes(output));
    in.setRecursionLimit(1000);
    return new ArrayList<>(Index.parseFrom(in).getExternalSymbolsList());
  }
}
