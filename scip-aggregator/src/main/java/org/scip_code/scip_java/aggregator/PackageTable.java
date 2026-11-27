package org.scip_code.scip_java.aggregator;

import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.scip_code.scip_java.shared.ScipSymbols;
import org.scip_code.scip_java.shared.SymbolDescriptor;

public class PackageTable {

  /**
   * Which package a classfile is attributed to, and why it beat any other package providing the
   * same classfile; see {@link #isPreferredOver}.
   */
  private record Candidate(MavenPackage pkg, boolean isDirectory, int listings, int order) {

    /**
     * Whether this candidate should own a classfile that {@code other} also provides.
     *
     * <p>A classfile can be on several projects' classpaths in different versions -- a packaging
     * project bundling an older release of a library the rest of the build compiles against, say.
     * For a document whose project is known, only the candidates on that project's classpath
     * compete (see {@link #packageForSymbol(String, ScipProject)}). Otherwise -- or when none of
     * them is on it, as for a sibling project's output, which sbt and Gradle leave out of a
     * project's dependency file -- the rule picks what most of the build saw, in this order:
     *
     * <ol>
     *   <li>a directory entry beats a jar, because a directory is a project's own output in this
     *       build and its sources are the authority on what they define;
     *   <li>then the package more projects list, because that is the version most of the build
     *       compiled against;
     *   <li>then whichever was listed first, so the result does not depend on hash order.
     * </ol>
     *
     * <p>Before this rule the last package put into the map won, which made attribution depend on
     * the order the targetroot's dependency files happened to be listed in.
     */
    boolean isPreferredOver(Candidate other) {
      if (isDirectory != other.isDirectory) return isDirectory;
      if (listings != other.listings) return listings > other.listings;
      return order < other.order;
    }
  }

  private final Map<String, Candidate> byClassfile = new HashMap<>();

  /**
   * Every candidate, winner included, for the classfiles more than one package provides. Kept apart
   * from {@link #byClassfile} because almost every classfile has exactly one, and a list per entry
   * would multiply the memory a large build's table takes.
   */
  private final Map<String, List<Candidate>> contested = new HashMap<>();

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
    //
    // `options.packages()` lists each package once per project that lists it, so the number of
    // repeats is how many projects compiled against it -- the tiebreak in `Candidate`.
    Map<List<Object>, Integer> listings = new LinkedHashMap<>();
    Map<List<Object>, MavenPackage> distinct = new LinkedHashMap<>();
    for (MavenPackage pkg : options.packages()) {
      List<Object> key = List.of(pkg.jar, pkg.groupId, pkg.artifactId, pkg.version);
      listings.merge(key, 1, Integer::sum);
      distinct.putIfAbsent(key, pkg);
    }
    int order = 0;
    for (Map.Entry<List<Object>, MavenPackage> entry : distinct.entrySet()) {
      indexPackage(entry.getValue(), listings.get(entry.getKey()), order++);
    }
  }

  public Optional<Package> packageForSymbol(String symbol) {
    return packageForSymbol(symbol, null);
  }

  /**
   * The package {@code symbol} belongs to, as seen from a document of {@code project}: of the
   * packages providing its classfile, one on that project's classpath wins over any that is not.
   * {@code null} stands for a document whose project is unknown.
   */
  public Optional<Package> packageForSymbol(String symbol, ScipProject project) {
    return classfileOf(symbol).flatMap(classfile -> packageForClassfile(classfile, project));
  }

  /**
   * The classfile path a global symbol's top-level class compiles to, such as {@code
   * com/example/Outer.class} for {@code com/example/Outer#Inner#}.
   */
  static Optional<String> classfileOf(String symbol) {
    return SymbolDescriptor.toplevel(symbol)
        .map(
            toplevel -> {
              // `_root_/` is a sentinel `SymbolDescriptor` synthesizes for the default
              // package, not a real path component; a classfile in the default package has
              // no directory prefix at all, so keeping the sentinel here would look up
              // "_root_/Foo.class" against an index keyed by "Foo.class" and always miss.
              String owner = toplevel.owner();
              String ownerPath = ScipSymbols.ROOT_PACKAGE.equals(owner) ? "" : owner;
              return ownerPath + toplevel.descriptor().name() + ".class";
            });
  }

  private Optional<Package> packageForClassfile(String classfile, ScipProject project) {
    if (project != null) {
      List<Candidate> candidates = contested.get(classfile);
      if (candidates != null) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
          if (!project.classpath().contains(candidate.pkg().jar)) continue;
          if (best == null || candidate.isPreferredOver(best)) best = candidate;
        }
        if (best != null) return Optional.of(best.pkg());
      }
    }
    Candidate result = byClassfile.get(classfile);
    if (result != null) return Optional.of(result.pkg());
    if (isJrtClassfile(classfile)) return Optional.of(jdkPackage);
    return Optional.empty();
  }

  private void indexPackage(MavenPackage pkg, int listings, int order) throws IOException {
    if (JAR_PATTERN.matches(pkg.jar) && Files.isRegularFile(pkg.jar)) {
      indexJarFile(pkg.jar, new Candidate(pkg, false, listings, order));
    } else if (this.indexDirectoryEntries && Files.isDirectory(pkg.jar)) {
      indexDirectoryPackage(new Candidate(pkg, true, listings, order));
    }
  }

  private void put(String classfile, Candidate candidate) {
    Candidate existing = byClassfile.get(classfile);
    if (existing != null) {
      contested.computeIfAbsent(classfile, k -> new ArrayList<>(List.of(existing))).add(candidate);
    }
    byClassfile.merge(
        classfile,
        candidate,
        (winner, incoming) -> incoming.isPreferredOver(winner) ? incoming : winner);
  }

  private void indexDirectoryPackage(Candidate candidate) throws IOException {
    Path root = candidate.pkg().jar;
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (CLASS_PATTERN.matches(file)) {
              String classfile = root.relativize(file).toString();
              if (isIndexableClassfile(classfile)) {
                put(classfile, candidate);
              }
            }
            return super.visitFile(file, attrs);
          }
        });
  }

  private void indexJarFile(Path file, Candidate candidate) throws IOException {
    try (JarFile jar = new JarFile(file.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.getName().endsWith(".class") && isIndexableClassfile(entry.getName())) {
          put(entry.getName(), candidate);
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
