package org.scip_code.scip_java.aggregator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Diagnostic;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.PositionEncoding;
import org.scip_code.scip.Relationship;
import org.scip_code.scip.Severity;
import org.scip_code.scip.SingleLineRange;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.SymbolRole;
import org.scip_code.scip.SyntaxKind;
import org.scip_code.scip.ToolInfo;
import org.scip_code.scip_java.shared.ScipShardPaths;
import org.scip_code.scip_java.shared.ScipShardWriter;

/**
 * Covers aggregator field preservation, Trait inverse-relationship exclusion, and writer cleanup.
 */
class ScipAggregatorHygieneTest {

  @Test
  void preservesExtraScipFieldsAndSkipsTraitInverseReferences(@TempDir Path tmp)
      throws IOException {
    Path targetroot = Files.createDirectories(tmp.resolve("targetroot"));
    Path scipRoot = targetroot.resolve(ScipShardPaths.SCIP_ROOT);

    Diagnostic diag =
        Diagnostic.newBuilder().setSeverity(Severity.Warning).setMessage("sample").build();
    Occurrence occ =
        Occurrence.newBuilder()
            .setSymbol("com/example/MyTrait#")
            .setSymbolRoles(SymbolRole.Definition_VALUE)
            .setSingleLineRange(
                SingleLineRange.newBuilder().setLine(0).setStartCharacter(6).setEndCharacter(13))
            .setSyntaxKind(SyntaxKind.IdentifierType)
            .addOverrideDocumentation("override doc")
            .addDiagnostics(diag)
            .build();
    SymbolInformation traitInfo =
        SymbolInformation.newBuilder()
            .setSymbol("com/example/MyTrait#")
            .setDisplayName("MyTrait")
            .setKind(SymbolInformation.Kind.Trait)
            .build();
    SymbolInformation subTraitInfo =
        SymbolInformation.newBuilder()
            .setSymbol("com/example/SubTrait#")
            .setDisplayName("SubTrait")
            .setKind(SymbolInformation.Kind.Trait)
            .addRelationships(
                Relationship.newBuilder()
                    .setSymbol("com/example/MyTrait#")
                    .setIsImplementation(true))
            .build();
    Document doc =
        Document.newBuilder()
            .setLanguage("scala")
            .setRelativePath("com/example/MyTrait.scala")
            .setPositionEncoding(PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
            .addOccurrences(occ)
            .addSymbols(traitInfo)
            .addSymbols(subTraitInfo)
            .build();

    ScipShardWriter.writeShard(scipRoot.resolve("com/example/MyTrait.scala.scip"), doc);

    Path output = tmp.resolve("index.scip");
    ScipAggregator.run(
        new ScipAggregatorOptions(
            List.of(targetroot),
            output,
            tmp,
            new ScipAggregatorReporter() {},
            ToolInfo.newBuilder().setName("test").setVersion("0").build(),
            /* parallel= */ true,
            List.of(),
            /* emitInverseRelationships= */ true,
            /* allowEmptyIndex= */ false,
            /* allowExportingGlobalSymbolsFromDirectoryEntries= */ false));

    CodedInputStream in = CodedInputStream.newInstance(Files.readAllBytes(output));
    in.setRecursionLimit(1000);
    Index index = Index.parseFrom(in);
    assertEquals(1, index.getDocumentsCount());
    Document outDoc = index.getDocuments(0);
    assertEquals(PositionEncoding.UTF16CodeUnitOffsetFromLineStart, outDoc.getPositionEncoding());
    Occurrence outOcc = outDoc.getOccurrences(0);
    assertEquals(SyntaxKind.IdentifierType, outOcc.getSyntaxKind());
    assertEquals(List.of("override doc"), outOcc.getOverrideDocumentationList());
    assertEquals(1, outOcc.getDiagnosticsCount());
    assertEquals("sample", outOcc.getDiagnostics(0).getMessage());

    // `SubTrait` has Kind.Trait, so it must not attach an inverse reference onto `MyTrait`.
    SymbolInformation outTrait =
        outDoc.getSymbolsList().stream()
            .filter(s -> s.getSymbol().endsWith("com/example/MyTrait#"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        outTrait.getRelationshipsList().isEmpty(),
        "Trait subtype must not produce inverse reference relationship: "
            + outTrait.getRelationshipsList());
  }

  @Test
  void deletesTempFileWhenClosedWithoutBuild(@TempDir Path tmp) throws IOException {
    Path output = tmp.resolve("index.scip");
    ScipAggregatorOptions options =
        new ScipAggregatorOptions(
            List.of(),
            output,
            tmp,
            new ScipAggregatorReporter() {},
            ToolInfo.newBuilder().setName("test").setVersion("0").build(),
            /* parallel= */ false,
            List.of(),
            /* emitInverseRelationships= */ false,
            /* allowEmptyIndex= */ false,
            /* allowExportingGlobalSymbolsFromDirectoryEntries= */ false);

    // Calling run with no shards and allowEmptyIndex=false returns early without calling build();
    // try-with-resources must close the stream and not leave output behind.
    ScipAggregator.run(options);
    assertFalse(Files.exists(output));
  }
}
