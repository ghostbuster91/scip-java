package org.scip_code.scip_java.aggregator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.ToolInfo;

/**
 * Colocated unit test for {@link PackageTable}.
 *
 * <p>Covers two resolution edge cases that are easy to regress silently, since neither shows up
 * unless a fixture actually exercises the default package or a Scala 3 file of bare top-level
 * defs/vals:
 *
 * <ul>
 *   <li>a Scala 3 top-level wrapper ({@code Foo$package.class}) must still resolve, even though its
 *       name contains {@code $} like any nested class;
 *   <li>a class with no package at all must resolve, even though {@link SymbolDescriptor#toplevel}
 *       reports its owner as the {@code _root_/} sentinel rather than real path text.
 * </ul>
 *
 * <p>It also pins down which package wins when several provide the same classfile, which on a real
 * multi-project build decides whether a reference joins the definition it was compiled against.
 */
class PackageTableTest {

  @Test
  void resolvesAScala3TopLevelWrapper(@TempDir Path tmp) throws IOException {
    PackageTable table = tableFor(tmp, "com/example/Fixture$package.class");

    Optional<Package> pkg = table.packageForSymbol("com/example/Fixture$package#topLevel().");

    assertTrue(pkg.isPresent(), "a Scala 3 top-level wrapper classfile must resolve");
  }

  @Test
  void resolvesADefaultPackageClass(@TempDir Path tmp) throws IOException {
    PackageTable table = tableFor(tmp, "Bar.class");

    Optional<Package> pkg = table.packageForSymbol("Bar#");

    assertTrue(pkg.isPresent(), "a default-package classfile must resolve");
  }

  @Test
  void resolvesAnOrdinaryTopLevelClassThroughANestedSymbol(@TempDir Path tmp) throws IOException {
    PackageTable table = tableFor(tmp, "com/example/Outer.class");

    // `Outer$Inner.class` is deliberately not in the jar: a nested class is always looked up
    // through the enclosing top-level classfile, never its own.
    Optional<Package> pkg = table.packageForSymbol("com/example/Outer#Inner#");

    assertTrue(
        pkg.isPresent(), "a nested symbol must resolve through its enclosing top-level class");
  }

  private static final String CONTESTED = "com/example/Shared.class";
  private static final String CONTESTED_SYMBOL = "com/example/Shared#run().";

  @Test
  void aDirectoryEntryBeatsAJarProvidingTheSameClassfile(@TempDir Path tmp) throws IOException {
    // A packaging project bundling an older release of the build's own module, listed by more
    // projects than the module's output directory, still must not take its definitions over.
    MavenPackage bundled = jarPackage(tmp, "bundled-1.0.jar", "1.0", CONTESTED);
    MavenPackage own = directoryPackage(tmp, "classes", "2.0", CONTESTED);

    PackageTable table = tableOf(tmp, List.of(bundled, bundled, own));

    assertEquals("2.0", versionOf(table, CONTESTED_SYMBOL));
  }

  @Test
  void thePackageMoreProjectsListBeatsTheRest(@TempDir Path tmp) throws IOException {
    MavenPackage minority = jarPackage(tmp, "lib-1.0.jar", "1.0", CONTESTED);
    MavenPackage majority = jarPackage(tmp, "lib-2.0.jar", "2.0", CONTESTED);

    // Listed last as well as first, so neither "first wins" nor "last wins" passes this.
    assertEquals(
        "2.0", versionOf(tableOf(tmp, List.of(majority, minority, majority)), CONTESTED_SYMBOL));
    assertEquals(
        "2.0", versionOf(tableOf(tmp, List.of(minority, majority, majority)), CONTESTED_SYMBOL));
  }

  @Test
  void aTieGoesToThePackageListedFirst(@TempDir Path tmp) throws IOException {
    MavenPackage first = jarPackage(tmp, "lib-1.0.jar", "1.0", CONTESTED);
    MavenPackage second = jarPackage(tmp, "lib-2.0.jar", "2.0", CONTESTED);

    assertEquals("1.0", versionOf(tableOf(tmp, List.of(first, second)), CONTESTED_SYMBOL));
    assertEquals("2.0", versionOf(tableOf(tmp, List.of(second, first)), CONTESTED_SYMBOL));
  }

  private static String versionOf(PackageTable table, String symbol) {
    return table.packageForSymbol(symbol).orElseThrow().version();
  }

  private PackageTable tableFor(Path tmp, String... classfiles) throws IOException {
    return tableOf(tmp, List.of(jarPackage(tmp, "lib.jar", "1.0", classfiles)));
  }

  private static MavenPackage jarPackage(
      Path tmp, String name, String version, String... classfiles) throws IOException {
    Path jar = tmp.resolve(name);
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      for (String classfile : classfiles) {
        out.putNextEntry(new JarEntry(classfile));
        out.closeEntry();
      }
    }
    return new MavenPackage(jar, "com.example", "lib", version);
  }

  private static MavenPackage directoryPackage(
      Path tmp, String name, String version, String... classfiles) throws IOException {
    Path dir = tmp.resolve(name);
    for (String classfile : classfiles) {
      Path file = dir.resolve(classfile);
      Files.createDirectories(file.getParent());
      Files.write(file, new byte[0]);
    }
    return new MavenPackage(dir, "com.example", "lib", version);
  }

  private static PackageTable tableOf(Path tmp, List<MavenPackage> packages) throws IOException {
    ScipAggregatorOptions options =
        new ScipAggregatorOptions(
            List.of(),
            tmp.resolve("index.scip"),
            tmp,
            new ScipAggregatorReporter() {},
            ToolInfo.newBuilder().setName("test").setVersion("0").build(),
            /* parallel= */ false,
            packages,
            /* emitInverseRelationships= */ false,
            /* allowEmptyIndex= */ true,
            /* allowExportingGlobalSymbolsFromDirectoryEntries= */ true);
    return new PackageTable(options);
  }
}
