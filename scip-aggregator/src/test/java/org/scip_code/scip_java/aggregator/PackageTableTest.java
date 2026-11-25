package org.scip_code.scip_java.aggregator;

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

  private PackageTable tableFor(Path tmp, String... classfiles) throws IOException {
    Path jar = tmp.resolve("lib.jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      for (String classfile : classfiles) {
        out.putNextEntry(new JarEntry(classfile));
        out.closeEntry();
      }
    }
    MavenPackage mavenPackage = new MavenPackage(jar, "com.example", "lib", "1.0");
    ScipAggregatorOptions options =
        new ScipAggregatorOptions(
            List.of(),
            tmp.resolve("index.scip"),
            tmp,
            new ScipAggregatorReporter() {},
            ToolInfo.newBuilder().setName("test").setVersion("0").build(),
            /* parallel= */ false,
            List.of(mavenPackage),
            /* emitInverseRelationships= */ false,
            /* allowEmptyIndex= */ true,
            /* allowExportingGlobalSymbolsFromDirectoryEntries= */ false);
    return new PackageTable(options);
  }
}
