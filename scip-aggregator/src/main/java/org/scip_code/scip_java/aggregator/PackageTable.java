package org.scip_code.scip_java.aggregator;

import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.scip_code.scip_java.shared.ScipSymbols;
import org.scip_code.scip_java.shared.SymbolDescriptor;

public class PackageTable {

  private final Map<String, Package> byClassfile = new HashMap<>();
  // `isJrtClassfile` is called from `ScipAggregator`'s parallel shard stream, so both the
  // cache and the negative-result cache (there's no `ClassLoader` call to skip otherwise,
  // and most lookups miss) have to be thread-safe.
  private final Set<String> cachedJdkSymbols = ConcurrentHashMap.newKeySet();
  private final Set<String> cachedNonJdkSymbols = ConcurrentHashMap.newKeySet();
  private final JdkPackage jdkPackage;
  private final boolean indexDirectoryEntries;

  private static final PathMatcher CLASS_PATTERN =
      FileSystems.getDefault().getPathMatcher("glob:**.class");
  private static final PathMatcher JAR_PATTERN =
      FileSystems.getDefault().getPathMatcher("glob:**.jar");

  public PackageTable(ScipAggregatorOptions options) throws IOException {
    this.jdkPackage = JdkPackage.forRuntime();
    this.indexDirectoryEntries = options.allowExportingGlobalSymbolsFromDirectoryEntries();
    // NOTE: it's important that we index the JDK before maven packages. Some maven packages
    // redefine classes from the JDK and we want those maven packages to take precedence over
    // the JDK. The motivation to prioritize maven packages over the JDK is that we only want
    // to exports monikers against the JDK when indexing the JDK repo.
    for (MavenPackage pkg : options.packages()) {
      indexPackage(pkg);
    }
  }

  public Optional<Package> packageForSymbol(String symbol) {
    return SymbolDescriptor.toplevel(symbol)
        .flatMap(
            toplevel -> {
              // `_root_/` is a sentinel `SymbolDescriptor` synthesizes for the default
              // package, not a real path component; a classfile in the default package has
              // no directory prefix at all, so keeping the sentinel here would look up
              // "_root_/Foo.class" against an index keyed by "Foo.class" and always miss.
              String owner = toplevel.owner();
              String ownerPath = ScipSymbols.ROOT_PACKAGE.equals(owner) ? "" : owner;
              String classfile = ownerPath + toplevel.descriptor().name() + ".class";
              return packageForClassfile(classfile);
            });
  }

  private Optional<Package> packageForClassfile(String classfile) {

    Package result = byClassfile.get(classfile);
    if (result != null) return Optional.of(result);
    if (isJrtClassfile(classfile)) return Optional.of(jdkPackage);
    return Optional.empty();
  }

  private void indexPackage(MavenPackage pkg) throws IOException {
    if (JAR_PATTERN.matches(pkg.jar) && Files.isRegularFile(pkg.jar)) {
      indexJarFile(pkg.jar, pkg);
    } else if (this.indexDirectoryEntries && Files.isDirectory(pkg.jar)) {
      indexDirectoryPackage(pkg);
    }
  }

  private void indexDirectoryPackage(MavenPackage pkg) throws IOException {
    Files.walkFileTree(
        pkg.jar,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (CLASS_PATTERN.matches(file)) {
              String classfile = pkg.jar.relativize(file).toString();
              if (isIndexableClassfile(classfile)) {
                byClassfile.put(classfile, pkg);
              }
            }
            return super.visitFile(file, attrs);
          }
        });
  }

  private void indexJarFile(Path file, Package pkg) throws IOException {
    try (JarFile jar = new JarFile(file.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.getName().endsWith(".class") && isIndexableClassfile(entry.getName())) {
          byClassfile.put(entry.getName(), pkg);
        }
      }
    }
  }

  /**
   * Whether a classfile is worth indexing by path.
   *
   * <p>A class nested inside another (`Outer$Inner.class`) is never looked up directly -- {@link
   * #packageForSymbol} always resolves through {@link SymbolDescriptor#toplevel}, which climbs to
   * the enclosing top-level class first -- so skipping it saves memory. A Scala 3 top-level wrapper
   * (`Foo$package.class`, generated for a file of bare top-level defs/vals) is itself a top-level
   * class despite the `$` in its name, and must not be skipped the same way or every top-level
   * Scala 3 symbol fails to resolve across repositories.
   */
  private boolean isIndexableClassfile(String classfile) {
    return !classfile.contains("$") || classfile.endsWith("$package.class");
  }

  /**
   * The JRT classpath contains classfiles for the JDK for Java versions 9+.
   *
   * <p>The JRT classpath isn't a jar file on disk, it needs to be read from an internal file system
   * under the URL "jrt:/".
   */
  private boolean isJrtClassfile(String classfile) {
    if (cachedJdkSymbols.contains(classfile)) return true;
    if (cachedNonJdkSymbols.contains(classfile)) return false;
    URL resource = getClass().getResource("/" + classfile);
    boolean isJrt = resource != null && "jrt".equals(resource.getProtocol());
    (isJrt ? cachedJdkSymbols : cachedNonJdkSymbols).add(classfile);
    return isJrt;
  }
}
