package org.scip_code.scip_java.aggregator;

import java.nio.file.Path;
import java.util.List;
import org.scip_code.scip.ToolInfo;

/**
 * Configuration options to tweak the scip-aggregator command.
 *
 * <p>{@code projects} lists the build's projects, most preferred first: when more than one could
 * own a document, the earliest listed does. Empty when the build tool reported none, in which case
 * every document is attributed against the whole build's {@code packages}.
 */
public record ScipAggregatorOptions(
    List<Path> targetroots,
    Path output,
    Path sourceroot,
    ScipAggregatorReporter reporter,
    ToolInfo toolInfo,
    boolean parallel,
    List<MavenPackage> packages,
    boolean emitInverseRelationships,
    boolean allowEmptyIndex,
    boolean allowExportingGlobalSymbolsFromDirectoryEntries,
    List<ScipProject> projects) {

  /** Options for a build that reports no projects. */
  public ScipAggregatorOptions(
      List<Path> targetroots,
      Path output,
      Path sourceroot,
      ScipAggregatorReporter reporter,
      ToolInfo toolInfo,
      boolean parallel,
      List<MavenPackage> packages,
      boolean emitInverseRelationships,
      boolean allowEmptyIndex,
      boolean allowExportingGlobalSymbolsFromDirectoryEntries) {
    this(
        targetroots,
        output,
        sourceroot,
        reporter,
        toolInfo,
        parallel,
        packages,
        emitInverseRelationships,
        allowEmptyIndex,
        allowExportingGlobalSymbolsFromDirectoryEntries,
        List.of());
  }
}
