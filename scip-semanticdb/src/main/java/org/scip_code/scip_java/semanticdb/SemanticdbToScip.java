package org.scip_code.scip_java.semanticdb;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.scip_code.scip.Document;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.Relationship;
import org.scip_code.scip.Signature;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.SymbolRole;
import org.scip_code.scip_java.shared.ScipDocumentBuilder;
import org.scip_code.scip_java.shared.ScipRange;
import org.scip_code.scip_java.shared.ScipShardPaths;
import org.scip_code.scip_java.shared.ScipShardWriter;
import org.scip_code.scip_java.shared.SymbolDescriptor;

/**
 * Converts the SemanticDB files a compiler emitted into the {@code .scip} shards that {@code
 * scip-java aggregate} consumes. This is how Scala sources get indexed: the Scala 3 compiler
 * already emits SemanticDB with {@code -Xsemanticdb}, so scip-java only has to translate the
 * format.
 *
 * <p>Shards carry bare descriptor symbols, exactly like the ones the javac and kotlinc plugins
 * write — resolving them to fully qualified SCIP symbols is the aggregator's job. That is what lets
 * a Scala source and a Java source in the same build land in one index with cross-language
 * references intact.
 */
public final class SemanticdbToScip {

  /**
   * Outcome of a conversion run. Problems are returned rather than logged so that the caller owns
   * reporting, and they are warnings rather than errors by design: the worst any single one costs
   * is one source file's worth of index. Failing the whole run — and with it the index for every
   * other file — would be the less useful outcome.
   */
  public record Result(int shardsWritten, List<String> warnings) {}

  private static final PathMatcher SEMANTICDB_PATTERN =
      FileSystems.getDefault().getPathMatcher("glob:**.semanticdb");

  /**
   * Scala's own types bottom out in {@code java/lang/Object#}, which would make "find
   * implementations" on it list every class in the codebase.
   */
  private static final String IGNORED_OVERRIDDEN_SYMBOL = "java/lang/Object#";

  private final Path sourceroot;
  private final Path targetroot;
  private final List<String> warnings = new ArrayList<>();
  private final List<String> unformattableSignatures = new ArrayList<>();
  private final Set<String> unparseableSymbols = new LinkedHashSet<>();
  private int shardsWritten;

  public SemanticdbToScip(Path sourceroot, Path targetroot) {
    this.sourceroot = sourceroot;
    this.targetroot = targetroot;
  }

  /** Converts every {@code *.semanticdb} file found under each of {@code semanticdbRoots}. */
  public Result convert(List<Path> semanticdbRoots) {
    for (Path root : semanticdbRoots) {
      List<Path> files = findSemanticdbFiles(root);
      Collections.sort(files);
      for (Path file : files) {
        convertFile(file);
      }
    }
    if (!unparseableSymbols.isEmpty()) {
      warnings.add(
          unparseableSymbols.size()
              + " SemanticDB symbol(s) were not valid SCIP symbols and were left out, for example '"
              + unparseableSymbols.iterator().next()
              + "'");
    }
    if (!unformattableSignatures.isEmpty()) {
      // Summarized rather than listed one by one: the count is what tells you
      // whether this is a curiosity or a systematic gap, and one example is
      // enough to chase it down.
      warnings.add(
          unformattableSignatures.size()
              + " symbol(s) were indexed without a hover signature, for example '"
              + unformattableSignatures.get(0)
              + "'");
    }
    return new Result(shardsWritten, List.copyOf(warnings));
  }

  private List<Path> findSemanticdbFiles(Path root) {
    List<Path> result = new ArrayList<>();
    if (!Files.isDirectory(root)) return result;
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (SEMANTICDB_PATTERN.matches(file)) result.add(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              warnings.add("failed to read " + file + ": " + exc.getMessage());
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      warnings.add("failed to walk " + root + ": " + e.getMessage());
    }
    return result;
  }

  private void convertFile(Path file) {
    Semanticdb.TextDocuments documents;
    try {
      CodedInputStream in = CodedInputStream.newInstance(Files.readAllBytes(file));
      // Scala types nest far deeper than protobuf's default limit of 100.
      in.setRecursionLimit(1000);
      documents = Semanticdb.TextDocuments.parseFrom(in);
    } catch (IOException e) {
      warnings.add("invalid SemanticDB file " + file + ": " + e.getMessage());
      return;
    }
    for (Semanticdb.TextDocument semanticdb : documents.getDocumentsList()) {
      if (semanticdb.getOccurrencesCount() == 0) continue;
      // Java sources are indexed by the javac plugin, which has the full compiler
      // tree available and writes a shard to the very same path. Skipping them here
      // keeps whichever compiler also happens to emit SemanticDB for a .java file
      // from overwriting the richer shard.
      if (semanticdb.getLanguage() == Semanticdb.Language.JAVA) continue;
      try {
        writeShard(new ScipTextDocument(file, semanticdb, sourceroot));
      } catch (IOException | RuntimeException e) {
        // One source the converter cannot make sense of should cost that source,
        // not the whole index. Scala emits symbols from a long tail of language
        // features, so this is the difference between a partial index and none.
        warnings.add("failed to convert " + semanticdb.getUri() + ": " + e);
      }
    }
  }

  private void writeShard(ScipTextDocument doc) throws IOException {
    Path absolutePath = Paths.get(URI.create(doc.semanticdb.getUri()));
    Optional<Path> shardPath = ScipShardPaths.shardPath(targetroot, sourceroot, absolutePath);
    if (shardPath.isEmpty()) {
      // A source outside the sourceroot cannot be addressed by a relative path in
      // the index, so there is nothing useful to emit for it.
      return;
    }
    ScipShardWriter.writeShard(shardPath.get(), buildDocument(doc, absolutePath));
    shardsWritten++;
  }

  private Document buildDocument(ScipTextDocument doc, Path absolutePath) {
    ScipDocumentBuilder builder = new ScipDocumentBuilder();
    for (Semanticdb.SymbolOccurrence occ : doc.sortedSymbolOccurrences()) {
      scipOccurrence(occ).ifPresent(builder::addOccurrence);
    }
    Symtab symtab = new Symtab(doc.semanticdb);
    String language = language(doc.semanticdb);
    for (Semanticdb.SymbolInformation info : doc.semanticdb.getSymbolsList()) {
      if (!isEmittable(info.getSymbol())) continue;
      builder.addSymbol(scipSymbolInformation(info, doc, symtab, language));
    }
    return builder.build(
        language, ScipShardPaths.relativePath(sourceroot, absolutePath), doc.semanticdb.getText());
  }

  private static String language(Semanticdb.TextDocument semanticdb) {
    return semanticdb.getLanguage().toString().toLowerCase(Locale.ROOT);
  }

  /**
   * Whether {@code symbol} can be emitted as a SCIP symbol. Not everything in a SemanticDB document
   * is one: Scala 3 writes {@code <?>} where it could not determine a symbol. Emitting those would
   * hand the aggregator something it cannot resolve to a package, so anything that is neither a
   * local nor a parseable global symbol is dropped.
   */
  private boolean isEmittable(String symbol) {
    if (symbol.isEmpty()) return false;
    if (SemanticdbSymbols.isWithinLocal(symbol)) return true;
    try {
      SymbolDescriptor.parseFromSymbol(symbol);
      return true;
    } catch (RuntimeException e) {
      unparseableSymbols.add(symbol);
      return false;
    }
  }

  private Optional<Occurrence> scipOccurrence(Semanticdb.SymbolOccurrence occ) {
    if (!isEmittable(occ.getSymbol())) return Optional.empty();
    Occurrence.Builder out =
        Occurrence.newBuilder().setSymbol(SemanticdbSymbols.toScip(occ.getSymbol()));
    if (isDefinitionRole(occ.getRole())) out.setSymbolRoles(SymbolRole.Definition_VALUE);
    setRange(scipRange(occ.getRange()), out::setSingleLineRange, out::setMultiLineRange);
    if (occ.hasEnclosingRange()) {
      setRange(
          scipRange(occ.getEnclosingRange()),
          out::setSingleLineEnclosingRange,
          out::setMultiLineEnclosingRange);
    }
    return Optional.of(out.build());
  }

  private SymbolInformation scipSymbolInformation(
      Semanticdb.SymbolInformation info, ScipTextDocument doc, Symtab symtab, String language) {
    SymbolInformation.Builder out =
        SymbolInformation.newBuilder()
            .setSymbol(SemanticdbSymbols.toScip(info.getSymbol()))
            .setDisplayName(info.getDisplayName())
            .setKind(scipKind(info));
    if (isEmittable(info.getEnclosingSymbol())) {
      out.setEnclosingSymbol(SemanticdbSymbols.toScip(info.getEnclosingSymbol()));
    }

    // `semanticdb-scalac` emits SymbolInformation for synthetic symbols that have no
    // definition occurrence of their own (a case class `copy`, a companion `apply`).
    // ScipTextDocument points those at the definition they stand in for.
    for (String definitionSymbol : info.getDefinitionRelationshipsList()) {
      if (!isEmittable(definitionSymbol)) continue;
      Semanticdb.SymbolInformation definition = symtab.symbols.get(definitionSymbol);
      out.addRelationships(
          Relationship.newBuilder()
              .setSymbol(SemanticdbSymbols.toScip(definitionSymbol))
              .setIsDefinition(true)
              .setIsReference(
                  definition != null
                      && definition.getDisplayName().equals(info.getDisplayName())
                      && supportsReferenceRelationship(info)));
      addCliqueReferences(out, info, symtab, doc.definitionCliques.get(definitionSymbol));
    }
    addCliqueReferences(out, info, symtab, doc.definitionCliques.get(info.getSymbol()));

    for (String overriddenSymbol : info.getOverriddenSymbolsList()) {
      if (!isEmittable(overriddenSymbol) || overriddenSymbol.equals(IGNORED_OVERRIDDEN_SYMBOL)) {
        continue;
      }
      out.addRelationships(
          Relationship.newBuilder()
              .setSymbol(SemanticdbSymbols.toScip(overriddenSymbol))
              .setIsImplementation(true)
              .setIsReference(supportsReferenceRelationship(info)));
    }

    if (info.hasSignature()) {
      // Isolated: a formatting failure costs this one symbol its hover text and
      // nothing else. Scala reaches the formatter with a long tail of language
      // features, and a missing signature string is not worth dropping a source
      // file over, let alone the index.
      try {
        out.setSignatureDocumentation(
            Signature.newBuilder()
                .setLanguage(language)
                .setText(new SignatureFormatter(info, symtab).formatSymbol()));
      } catch (RuntimeException e) {
        unformattableSignatures.add(info.getSymbol());
      }
    }
    String documentation = info.getDocumentation().getMessage();
    if (!documentation.isEmpty()) out.addDocumentation(documentation);
    return out.build();
  }

  /**
   * Adds an {@code is_reference} relationship between every member of a "definition clique" — the
   * set of synthetic symbols that share one definition occurrence, such as a case class, its
   * companion's {@code apply} and its {@code copy}. Without this, "find references" on one of them
   * misses the occurrences attributed to the others.
   */
  private static void addCliqueReferences(
      SymbolInformation.Builder out,
      Semanticdb.SymbolInformation info,
      Symtab symtab,
      List<String> clique) {
    if (clique == null) return;
    for (String symbol : clique) {
      if (symbol.equals(info.getSymbol())) continue;
      Semanticdb.SymbolInformation other = symtab.symbols.get(symbol);
      if (other == null) continue;
      if (!symbol.endsWith(".apply().") && !other.getDisplayName().equals(info.getDisplayName())) {
        continue;
      }
      out.addRelationships(
          Relationship.newBuilder()
              .setSymbol(SemanticdbSymbols.toScip(symbol))
              .setIsReference(true));
    }
  }

  private static ScipRange scipRange(Semanticdb.Range range) {
    return ScipRange.range(
        range.getStartLine(),
        range.getStartCharacter(),
        range.getEndLine(),
        range.getEndCharacter());
  }

  private static void setRange(
      ScipRange range,
      java.util.function.Consumer<org.scip_code.scip.SingleLineRange> single,
      java.util.function.Consumer<org.scip_code.scip.MultiLineRange> multi) {
    if (range.isSingleLine()) single.accept(range.toSingleLineRange());
    else multi.accept(range.toMultiLineRange());
  }

  private static boolean isDefinitionRole(Semanticdb.SymbolOccurrence.Role role) {
    return role == Semanticdb.SymbolOccurrence.Role.DEFINITION
        || role == Semanticdb.SymbolOccurrence.Role.SYNTHETIC_DEFINITION;
  }

  /**
   * Types are excluded because an {@code is_reference} relationship on them makes "find references"
   * on a class also return every reference to its subclasses.
   */
  private static boolean supportsReferenceRelationship(Semanticdb.SymbolInformation info) {
    return switch (info.getKind()) {
      case INTERFACE, TYPE, CLASS, OBJECT, PACKAGE_OBJECT -> false;
      default -> true;
    };
  }

  private static SymbolInformation.Kind scipKind(Semanticdb.SymbolInformation info) {
    int properties = info.getProperties();
    boolean isStatic = (properties & Semanticdb.SymbolInformation.Property.STATIC_VALUE) > 0;
    boolean isAbstract = (properties & Semanticdb.SymbolInformation.Property.ABSTRACT_VALUE) > 0;
    boolean isEnum = (properties & Semanticdb.SymbolInformation.Property.ENUM_VALUE) > 0;

    return switch (info.getKind()) {
      case CLASS -> isEnum ? SymbolInformation.Kind.Enum : SymbolInformation.Kind.Class;
      case CONSTRUCTOR -> SymbolInformation.Kind.Constructor;
      case FIELD -> isStatic ? SymbolInformation.Kind.StaticField : SymbolInformation.Kind.Field;
      case INTERFACE -> SymbolInformation.Kind.Interface;
      case LOCAL ->
          isStatic ? SymbolInformation.Kind.StaticVariable : SymbolInformation.Kind.Variable;
      case MACRO -> SymbolInformation.Kind.Macro;
      case METHOD -> {
        if (isStatic) yield SymbolInformation.Kind.StaticMethod;
        else if (isAbstract) yield SymbolInformation.Kind.AbstractMethod;
        else yield SymbolInformation.Kind.Method;
      }
      case OBJECT -> SymbolInformation.Kind.Object;
      case PACKAGE -> SymbolInformation.Kind.Package;
      case PACKAGE_OBJECT -> SymbolInformation.Kind.PackageObject;
      case PARAMETER -> SymbolInformation.Kind.Parameter;
      case SELF_PARAMETER -> SymbolInformation.Kind.SelfParameter;
      case TRAIT -> SymbolInformation.Kind.Trait;
      case TYPE -> isEnum ? SymbolInformation.Kind.Enum : SymbolInformation.Kind.Type;
      case TYPE_PARAMETER -> SymbolInformation.Kind.TypeParameter;
      default -> SymbolInformation.Kind.UnspecifiedKind;
    };
  }
}
