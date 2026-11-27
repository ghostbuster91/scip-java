package org.scip_code.scip_java.aggregator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.SingleLineRange;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.SymbolRole;
import org.scip_code.scip.ToolInfo;
import org.scip_code.scip_java.shared.ScipShardPaths;
import org.scip_code.scip_java.shared.ScipShardWriter;

/**
 * Each document is attributed against the classpath of the project that compiled it.
 *
 * <p>The fixture is the shape that broke on a real build: two projects put different versions of
 * one library on their classpaths, so the same bare reference qualifies to {@code 1.0} from one
 * project and {@code 2.0} from the other. Attributing against the whole build gives every document
 * the same answer, and so is wrong for one of them whichever it picks.
 */
class ProjectAttributionTest {

  private static final String SHARED_REFERENCE = "com/example/Shared#run().";

  @Test
  void attributesEachDocumentAgainstItsOwnProjectsClasspath(@TempDir Path tmp) throws IOException {
    Fixture fixture = new Fixture(tmp);
    // Recorded by a front-end as written by `old`.
    Path recorded = fixture.shard("old/A.scala", "com/old/A#");
    // Recorded by nobody, but `com/fresh/B.class` is in `fresh`'s class directory.
    fixture.shard("fresh/B.java", "com/fresh/B#");
    fixture.classfile(fixture.freshClasses, "com/fresh/B.class");
    // Owned by no project at all.
    fixture.shard("orphan/C.java", "com/orphan/C#");

    Map<String, String> versions =
        fixture.aggregate(List.of(fixture.project("old", Set.of(recorded)), fixture.fresh()));

    assertEquals("1.0", versions.get("old/A.scala"), "a recorded owner's classpath decides");
    assertEquals("2.0", versions.get("fresh/B.java"), "a class directory identifies the owner");
    // Neither version is listed by more projects, so the build-wide rule falls back to the one
    // listed first -- `old`'s, which is exactly the answer `fresh` must not get.
    assertEquals("1.0", versions.get("orphan/C.java"), "an unowned document keeps the build rule");
  }

  @Test
  void aSourceSeveralProjectsCompileGoesToTheProjectListedFirst(@TempDir Path tmp)
      throws IOException {
    Fixture fixture = new Fixture(tmp);
    fixture.shard("shared/D.java", "com/shared/D#");
    fixture.classfile(fixture.oldClasses, "com/shared/D.class");
    fixture.classfile(fixture.freshClasses, "com/shared/D.class");

    assertEquals(
        "2.0",
        fixture
            .aggregate(List.of(fixture.fresh(), fixture.project("old", Set.of())))
            .get("shared/D.java"));
    assertEquals(
        "1.0",
        fixture
            .aggregate(List.of(fixture.project("old", Set.of()), fixture.fresh()))
            .get("shared/D.java"));
  }

  /** Two projects, `old` on `lib 1.0` and `fresh` on `lib 2.0`, each with its own output. */
  private static final class Fixture {
    final Path tmp;
    final Path targetroot;
    final Path oldClasses;
    final Path freshClasses;
    final MavenPackage lib1;
    final MavenPackage lib2;

    Fixture(Path tmp) throws IOException {
      this.tmp = tmp;
      this.targetroot = Files.createDirectories(tmp.resolve("targetroot"));
      this.oldClasses = Files.createDirectories(tmp.resolve("old-classes"));
      this.freshClasses = Files.createDirectories(tmp.resolve("fresh-classes"));
      this.lib1 = jar("lib-1.0.jar", "1.0");
      this.lib2 = jar("lib-2.0.jar", "2.0");
    }

    ScipProject project(String key, Set<Path> shards) {
      Set<Path> classpath = key.equals("old") ? Set.of(lib1.jar) : Set.of(lib2.jar);
      Path classes = key.equals("old") ? oldClasses : freshClasses;
      return new ScipProject(key, classpath, List.of(classes), shards);
    }

    ScipProject fresh() {
      return project("fresh", Set.of());
    }

    void classfile(Path directory, String classfile) throws IOException {
      Path file = directory.resolve(classfile);
      Files.createDirectories(file.getParent());
      Files.write(file, new byte[0]);
    }

    /** A shard for {@code relativePath} defining {@code definition} and referencing the library. */
    Path shard(String relativePath, String definition) throws IOException {
      Path shard = ScipShardPaths.shardPathForRelativeSource(targetroot, Path.of(relativePath));
      Document document =
          Document.newBuilder()
              .setLanguage("java")
              .setRelativePath(relativePath)
              .addSymbols(SymbolInformation.newBuilder().setSymbol(definition))
              .addOccurrences(occurrence(definition, SymbolRole.Definition_VALUE, 0))
              .addOccurrences(occurrence(SHARED_REFERENCE, 0, 1))
              .build();
      Files.createDirectories(shard.getParent());
      ScipShardWriter.writeShard(shard, document);
      return shard;
    }

    /** The version each document's library reference was qualified with, by relative path. */
    Map<String, String> aggregate(List<ScipProject> projects) throws IOException {
      Path output = tmp.resolve("index.scip");
      ScipAggregator.run(
          new ScipAggregatorOptions(
              List.of(targetroot),
              output,
              tmp,
              new ScipAggregatorReporter() {},
              ToolInfo.newBuilder().setName("test").setVersion("0").build(),
              /* parallel= */ true,
              // `old`'s packages first, so the build-wide rule's last tiebreak picks 1.0.
              List.of(lib1, lib2),
              /* emitInverseRelationships= */ false,
              /* allowEmptyIndex= */ false,
              /* allowExportingGlobalSymbolsFromDirectoryEntries= */ true,
              projects));
      CodedInputStream in = CodedInputStream.newInstance(Files.readAllBytes(output));
      in.setRecursionLimit(1000);
      Map<String, String> versions = new HashMap<>();
      for (Document document : Index.parseFrom(in).getDocumentsList()) {
        for (Occurrence occurrence : document.getOccurrencesList()) {
          String symbol = occurrence.getSymbol();
          if (!symbol.endsWith(SHARED_REFERENCE)) continue;
          // `scip-java maven maven/com.example/lib <version> com/example/Shared#run().`
          versions.put(document.getRelativePath(), symbol.split(" ")[3]);
        }
      }
      return versions;
    }

    private MavenPackage jar(String name, String version) throws IOException {
      Path jar = tmp.resolve(name);
      try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
        out.putNextEntry(new JarEntry("com/example/Shared.class"));
        out.closeEntry();
      }
      return new MavenPackage(jar, "com.example", "lib", version);
    }

    private static Occurrence occurrence(String symbol, int roles, int line) {
      return Occurrence.newBuilder()
          .setSymbol(symbol)
          .setSymbolRoles(roles)
          .setSingleLineRange(
              SingleLineRange.newBuilder().setLine(line).setStartCharacter(0).setEndCharacter(1))
          .build();
    }
  }
}
