package org.scip_code.scip_java.aggregator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.scip_code.scip.Document;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip_java.shared.ScipSymbols;

/**
 * Decides which project of the build a shard's documents belong to, so they can be attributed
 * against that project's classpath rather than the whole build's.
 *
 * <p>Two sources of truth, in order:
 *
 * <ol>
 *   <li>the shards a front-end recorded each project as having written ({@link ScipProject#shards})
 *       -- exact, and the only way to own a shard that defines no class, such as an
 *       external-symbols shard;
 *   <li>otherwise, the project whose class directory holds the classfile of a type the document
 *       defines. A compiler writes a source's classes into the output directory of the project
 *       compiling it, so this identifies a javac-written shard without the plugin having to know
 *       which project it ran in.
 * </ol>
 *
 * <p>When several projects qualify -- cross-built projects compiling one shared source -- the one
 * listed first in {@link ScipAggregatorOptions#projects} wins. Thread-safe, because shards are
 * processed in parallel.
 */
final class ProjectOwners {
  private final List<ScipProject> projects;
  private final Map<Path, ScipProject> byShard = new HashMap<>();
  private final Map<String, Optional<ScipProject>> byClassfile = new ConcurrentHashMap<>();
  private final AtomicInteger recorded = new AtomicInteger();
  private final AtomicInteger inferred = new AtomicInteger();
  private final AtomicInteger unowned = new AtomicInteger();

  ProjectOwners(List<ScipProject> projects) {
    this.projects = projects;
    for (ScipProject project : projects) {
      for (Path shard : project.shards()) byShard.putIfAbsent(normalize(shard), project);
    }
  }

  boolean isEmpty() {
    return projects.isEmpty();
  }

  /** The project that recorded writing {@code shard}, if any did. */
  Optional<ScipProject> recordedOwner(Path shard) {
    return Optional.ofNullable(byShard.get(normalize(shard)));
  }

  /** The project owning {@code document}, read from {@code shard}; {@code null} for none. */
  ScipProject ownerOf(Path shard, Document document) {
    if (projects.isEmpty()) return null;
    Optional<ScipProject> recorded = recordedOwner(shard);
    if (recorded.isPresent()) {
      this.recorded.incrementAndGet();
      return recorded.get();
    }
    for (SymbolInformation info : document.getSymbolsList()) {
      String symbol = info.getSymbol();
      if (symbol.isEmpty() || !ScipSymbols.isGlobal(symbol)) continue;
      Optional<ScipProject> owner =
          PackageTable.classfileOf(symbol)
              .flatMap(classfile -> byClassfile.computeIfAbsent(classfile, this::compiledInto));
      if (owner.isPresent()) {
        inferred.incrementAndGet();
        return owner.get();
      }
    }
    unowned.incrementAndGet();
    return null;
  }

  /** One line for the user, so a build that attributes nothing per project is noticeable. */
  String summary() {
    return String.format(
        "attributed %d document(s) to the project that wrote them, %d by class directory, and %d"
            + " against the whole build",
        recorded.get(), inferred.get(), unowned.get());
  }

  private Optional<ScipProject> compiledInto(String classfile) {
    for (ScipProject project : projects) {
      for (Path directory : project.classDirectories()) {
        if (Files.isRegularFile(directory.resolve(classfile))) return Optional.of(project);
      }
    }
    return Optional.empty();
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }
}
