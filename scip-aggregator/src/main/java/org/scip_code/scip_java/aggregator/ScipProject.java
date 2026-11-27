package org.scip_code.scip_java.aggregator;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One project of the build, as far as attributing its documents is concerned.
 *
 * <p>A build's dependency files list every project's classpath, and several projects can put
 * different versions of the same library on theirs -- a packaging project bundling an older
 * release, say. A document is only attributed correctly against the classpath of the project that
 * compiled it, so the aggregator needs to know which project that was. This record is how it finds
 * out:
 *
 * @param key identifies the project; for the build tools that write per-project files into the
 *     targetroot, the prefix those files share
 * @param classpath every entry this project's dependency file lists, its own output included
 * @param classDirectories the directories this project compiles into, main and test, used to tell
 *     which project a document belongs to when no front-end recorded it (see {@link #shards})
 * @param shards the shards a front-end recorded this project as having written, as absolute paths
 */
public record ScipProject(
    String key, Set<Path> classpath, List<Path> classDirectories, Set<Path> shards) {}
