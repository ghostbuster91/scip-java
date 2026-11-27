package org.scip_code.scip_java.aggregator;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Metadata;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.ProtocolVersion;
import org.scip_code.scip.Relationship;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.TextEncoding;
import org.scip_code.scip.ToolInfo;
import org.scip_code.scip_java.shared.ScipSymbols;

/**
 * Aggregates per-source SCIP shards (one {@link Index} per {@code *.scip} file emitted by the
 * compiler plugins) into a single SCIP index. The aggregator:
 *
 * <ul>
 *   <li>discovers shards under each requested target root,
 *   <li>resolves each shard's bare-descriptor symbols to fully-qualified SCIP symbols using a
 *       {@link PackageTable}, against the classpath of the project {@link ProjectOwners} finds owns
 *       the document,
 *   <li>optionally adds inverse-reference relationships across documents, and
 *   <li>emits a single {@link Index} with leading {@link Metadata}.
 * </ul>
 */
public class ScipAggregator {
  private static final PathMatcher JAR_PATTERN =
      FileSystems.getDefault().getPathMatcher("glob:**.jar");
  private static final PathMatcher SCIP_PATTERN =
      FileSystems.getDefault().getPathMatcher("glob:**.scip");

  private final ScipWriter writer;
  private final ScipAggregatorOptions options;

  public ScipAggregator(ScipWriter writer, ScipAggregatorOptions options) {
    this.writer = writer;
    this.options = options;
  }

  public static void run(ScipAggregatorOptions options) throws IOException {
    try (ScipWriter writer = new ScipWriter(options)) {
      new ScipAggregator(writer, options).run();
    }
  }

  private void run() throws IOException {
    PackageTable packages = new PackageTable(options);
    ProjectOwners owners = new ProjectOwners(options.projects());
    Rewriters rewriter = new Rewriters(packages, owners);
    List<Path> shards = findShards();
    Collections.sort(shards);
    if (options.reporter().hasErrors()) return;
    if (shards.isEmpty() && !options.allowEmptyIndex()) {
      options
          .reporter()
          .error(
              "No SCIP shards found. This typically means that `scip-java` is unable to"
                  + " automatically index this codebase. If you are using Gradle or Maven, please"
                  + " report an issue to https://github.com/scip-code/scip-java and include steps"
                  + " to reproduce. If you are using a different build tool, make sure that you"
                  + " have followed all of the manual configuration steps.");
      return;
    }
    options.reporter().startProcessing(shards.size());
    writer.emitTyped(metadataIndex());

    Map<String, List<String>> inverseReferences = computeInverseReferences(shards);
    Set<String> definedSymbols = ConcurrentHashMap.newKeySet();
    Map<String, SymbolInformation> externalSymbols = new ConcurrentHashMap<>();
    shardStream(shards)
        .forEach(
            shard ->
                processShard(shard, rewriter, inverseReferences, definedSymbols, externalSymbols));
    // Emit external symbols only once all document shards have been walked: per-project front-ends
    // (such as per-subproject TASTy conversion) each describe the symbols referenced outside their
    // own project, which both duplicates common dependencies across subprojects and describes
    // sibling-project definitions that another document shard already owns.
    List<SymbolInformation> uniqueExternal =
        externalSymbols.values().stream()
            .filter(info -> !definedSymbols.contains(info.getSymbol()))
            .sorted(Comparator.comparing(SymbolInformation::getSymbol))
            .toList();
    if (!uniqueExternal.isEmpty()) {
      writer.emitTyped(Index.newBuilder().addAllExternalSymbols(uniqueExternal).build());
    }
    writer.build();
    options.reporter().endProcessing();
    if (!owners.isEmpty()) options.reporter().info(owners.summary());
  }

  /**
   * One {@link SymbolRewriter} per project, created on first use, and the owner lookup that picks
   * between them. Its cache is per rewriter because a bare symbol can qualify differently per
   * project.
   */
  private static final class Rewriters {
    private final PackageTable packages;
    private final ProjectOwners owners;
    private final SymbolRewriter unowned;
    private final Map<String, SymbolRewriter> byProject = new ConcurrentHashMap<>();

    Rewriters(PackageTable packages, ProjectOwners owners) {
      this.packages = packages;
      this.owners = owners;
      this.unowned = new SymbolRewriter(packages, null);
    }

    SymbolRewriter forDocument(Path shard, Document document) {
      return forProject(owners.ownerOf(shard, document));
    }

    SymbolRewriter forShard(Path shard) {
      return forProject(owners.recordedOwner(shard).orElse(null));
    }

    private SymbolRewriter forProject(ScipProject project) {
      if (project == null) return unowned;
      return byProject.computeIfAbsent(project.key(), k -> new SymbolRewriter(packages, project));
    }
  }

  /**
   * Returns every {@code *.scip} shard under {@code options.targetroots}. A targetroot that happens
   * to be a {@code .jar} is included as-is so callers can pick out shards stored inside.
   */
  private List<Path> findShards() throws IOException {
    List<Path> shards = new ArrayList<>();
    SimpleFileVisitor<Path> visitor =
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (SCIP_PATTERN.matches(file)) shards.add(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            options.reporter().error(exc);
            return FileVisitResult.CONTINUE;
          }
        };
    for (Path root : options.targetroots()) {
      if (JAR_PATTERN.matches(root)) shards.add(root);
      else if (Files.isDirectory(root)) Files.walkFileTree(root, visitor);
      else
        options
            .reporter()
            .warning("ignoring target root that does not exist or is not a directory: " + root);
    }
    return shards;
  }

  private Index metadataIndex() {
    return Index.newBuilder()
        .setMetadata(
            Metadata.newBuilder()
                .setVersion(ProtocolVersion.UnspecifiedProtocolVersion)
                .setProjectRoot(options.sourceroot().toUri().toString())
                .setTextDocumentEncoding(TextEncoding.UTF8)
                .setToolInfo(
                    ToolInfo.newBuilder()
                        .setName(options.toolInfo().getName())
                        .setVersion(options.toolInfo().getVersion())
                        .addAllArguments(options.toolInfo().getArgumentsList())))
        .build();
  }

  private void processShard(
      Path shardPath,
      Rewriters rewriters,
      Map<String, List<String>> inverseReferences,
      Set<String> definedSymbols,
      Map<String, SymbolInformation> externalSymbols) {
    for (Index shard : readShard(shardPath)) {
      for (Document document : shard.getDocumentsList()) {
        SymbolRewriter rewriter = rewriters.forDocument(shardPath, document);
        Document rewritten = rewriteDocument(document, rewriter, inverseReferences);
        for (SymbolInformation info : rewritten.getSymbolsList()) {
          if (!info.getSymbol().isEmpty() && ScipSymbols.isGlobal(info.getSymbol())) {
            definedSymbols.add(info.getSymbol());
          }
        }
        writer.emitTyped(Index.newBuilder().addDocuments(rewritten).build());
        writer.flush();
        options.reporter().processedOneItem();
      }
      // A shard carrying external symbols describes definitions outside the indexed sources,
      // so it has no documents and no occurrences. Their symbols still need qualifying: they
      // are bare descriptors, and the package table resolves each to the dependency it came
      // from by classfile path, exactly as it does for a symbol referenced from Java.
      for (SymbolInformation info : rewriteExternalSymbols(shard, rewriters.forShard(shardPath))) {
        externalSymbols.putIfAbsent(info.getSymbol(), info);
      }
    }
  }

  private List<SymbolInformation> rewriteExternalSymbols(Index shard, SymbolRewriter rewriter) {
    List<SymbolInformation> result = new ArrayList<>();
    for (SymbolInformation info : shard.getExternalSymbolsList()) {
      SymbolInformation.Builder rebuilt =
          SymbolInformation.newBuilder(info).setSymbol(rewriter.rewrite(info.getSymbol()));
      if (!info.getEnclosingSymbol().isEmpty()) {
        rebuilt.setEnclosingSymbol(rewriter.rewrite(info.getEnclosingSymbol()));
      }
      rebuilt.clearRelationships();
      for (Relationship rel : info.getRelationshipsList()) {
        rebuilt.addRelationships(
            Relationship.newBuilder(rel).setSymbol(rewriter.rewrite(rel.getSymbol())));
      }
      result.add(rebuilt.build());
    }
    return result;
  }

  /**
   * Every {@link SymbolInformation} carried by the documents of {@code index}.
   *
   * <p>External symbols are deliberately excluded: they describe definitions outside the indexed
   * sources, so an override relationship on one would make "find references" on a dependency method
   * list implementors it has no way to know about.
   */
  private static List<SymbolInformation> documentSymbols(Index index) {
    List<SymbolInformation> result = new ArrayList<>();
    for (Document document : index.getDocumentsList()) result.addAll(document.getSymbolsList());
    return result;
  }

  private Document rewriteDocument(
      Document shard, SymbolRewriter rewriter, Map<String, List<String>> inverseReferences) {
    // Copy from `shard` so fields the aggregator does not rewrite (such as `position_encoding` and
    // `diagnostics`) survive unchanged rather than being silently stripped.
    Document.Builder out = Document.newBuilder(shard).clearOccurrences().clearSymbols();

    for (Occurrence occ : shard.getOccurrencesList()) {
      if (occ.getTypedRangeCase() == Occurrence.TypedRangeCase.TYPEDRANGE_NOT_SET) {
        throw new IllegalArgumentException("expected SCIP 0.9 typed occurrence range");
      }
      Occurrence.Builder rebuilt =
          Occurrence.newBuilder(occ).setSymbol(rewriter.rewrite(occ.getSymbol()));
      out.addOccurrences(rebuilt);
    }

    for (SymbolInformation info : shard.getSymbolsList()) {
      SymbolInformation.Builder rebuilt =
          SymbolInformation.newBuilder(info)
              .setSymbol(rewriter.rewrite(info.getSymbol()))
              .clearRelationships();
      if (!info.getEnclosingSymbol().isEmpty()) {
        rebuilt.setEnclosingSymbol(rewriter.rewrite(info.getEnclosingSymbol()));
      }
      for (Relationship rel : info.getRelationshipsList()) {
        rebuilt.addRelationships(
            Relationship.newBuilder(rel).setSymbol(rewriter.rewrite(rel.getSymbol())));
      }
      List<String> inverse = inverseReferences.get(info.getSymbol());
      if (inverse != null) {
        for (String overrider : inverse) {
          rebuilt.addRelationships(
              Relationship.newBuilder()
                  .setSymbol(rewriter.rewrite(overrider))
                  .setIsImplementation(true)
                  .setIsReference(true));
        }
      }
      out.addSymbols(rebuilt);
    }
    return out.build();
  }

  /**
   * Builds {@code overridden-symbol → [overriding-symbols]} for every method-style relationship.
   * Class/interface parent relationships are excluded because they shouldn't surface as
   * find-references results (TODO: drop once sourcegraph#50927 is fixed).
   */
  private Map<String, List<String>> computeInverseReferences(List<Path> shards) {
    if (!options.emitInverseRelationships()) return Collections.emptyMap();
    Map<String, List<String>> result = new HashMap<>();
    for (Path shard : shards) {
      for (Index index : readShard(shard)) {
        for (SymbolInformation info : documentSymbols(index)) {
          if (!supportsReferenceRelationship(info)) continue;
          if (info.getSymbol().isEmpty() || ScipSymbols.isLocal(info.getSymbol())) continue;
          for (Relationship rel : info.getRelationshipsList()) {
            if (!rel.getIsImplementation()) continue;
            if (ScipSymbols.isLocal(rel.getSymbol())) continue;
            if (isIgnoredOverriddenSymbol(rel.getSymbol())) continue;
            result.computeIfAbsent(rel.getSymbol(), k -> new ArrayList<>()).add(info.getSymbol());
          }
        }
      }
    }
    return result;
  }

  private static boolean supportsReferenceRelationship(SymbolInformation info) {
    return switch (info.getKind()) {
      case Class, Enum, Interface, Trait, Type, Object, PackageObject -> false;
      default -> true;
    };
  }

  private static boolean isIgnoredOverriddenSymbol(String symbol) {
    // Skip java/lang/Object# from cross-shard reference relationships; it's the parent
    // of every class and would dominate "find implementations" results.
    return symbol.endsWith("java/lang/Object#");
  }

  private Stream<Path> shardStream(List<Path> shards) {
    return options.parallel() ? shards.parallelStream() : shards.stream();
  }

  /**
   * Reads a shard as whole {@link Index} messages rather than just their documents, so that a shard
   * carrying only {@code external_symbols} is not silently dropped.
   */
  private Collection<Index> readShard(Path shardPath) {
    try {
      if (JAR_PATTERN.matches(shardPath)) return readShardsFromJar(shardPath);
      return List.of(Index.parseFrom(parseFromBytes(Files.readAllBytes(shardPath))));
    } catch (IOException e) {
      options.reporter().error("invalid SCIP shard: " + shardPath);
      options.reporter().error(e);
      return Collections.emptyList();
    }
  }

  private Collection<Index> readShardsFromJar(Path jarFile) throws IOException {
    List<Index> result = new ArrayList<>();
    try (JarFile jar = new JarFile(jarFile.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (!entry.getName().endsWith(".scip")) continue;
        byte[] bytes = InputStreamBytes.readAll(jar.getInputStream(entry));
        result.add(Index.parseFrom(parseFromBytes(bytes)));
      }
    }
    return result;
  }

  private static CodedInputStream parseFromBytes(byte[] bytes) {
    CodedInputStream in = CodedInputStream.newInstance(bytes);
    in.setRecursionLimit(1000);
    return in;
  }
}
