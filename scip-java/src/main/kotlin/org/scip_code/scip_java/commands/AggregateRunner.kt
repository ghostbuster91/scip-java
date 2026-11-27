package org.scip_code.scip_java.commands

import java.nio.file.Path
import org.scip_code.scip.ToolInfo
import org.scip_code.scip_java.ScipJava
import org.scip_code.scip_java.ScipJavaApp
import org.scip_code.scip_java.aggregator.ScipAggregator
import org.scip_code.scip_java.aggregator.ScipAggregatorOptions
import org.scip_code.scip_java.buildtools.BuildProjects
import org.scip_code.scip_java.buildtools.ClasspathEntry

/**
 * Logic backing the `aggregate` command, factored out so build tools can aggregate their targetroot
 * shards into a single SCIP index in-process without going through clikt.
 */
object AggregateRunner {
    fun run(
        output: Path,
        targetroots: List<Path>,
        app: ScipJavaApp,
        parallel: Boolean,
        emitInverseRelationships: Boolean,
        allowEmptyIndex: Boolean,
        allowExportingGlobalSymbolsFromDirectoryEntries: Boolean,
    ): Int {
        val sourceroot = app.env.workingDirectory.toAbsolutePath()
        val absoluteTargetroots =
            if (targetroots.isEmpty()) listOf(sourceroot)
            else targetroots.map { sourceroot.resolve(it) }
        val absoluteOutput = sourceroot.resolve(output)

        // Not deduplicated: a package listed by several projects is repeated once per project,
        // which `PackageTable` counts to attribute a classfile more than one package provides.
        val packages =
            absoluteTargetroots.distinct().flatMap { ClasspathEntry.fromTargetroot(it, sourceroot) }
        // Most preferred first: the earliest project that could own a document does.
        val projects = absoluteTargetroots.distinct().flatMap { BuildProjects.fromTargetroot(it) }

        val options =
            ScipAggregatorOptions(
                absoluteTargetroots,
                absoluteOutput,
                sourceroot,
                app.reporter,
                ToolInfo.newBuilder().setName("scip-java").setVersion(ScipJava.version).build(),
                parallel,
                packages.map { it.toPackageInformation() },
                emitInverseRelationships,
                allowEmptyIndex,
                allowExportingGlobalSymbolsFromDirectoryEntries,
                projects,
            )
        ScipAggregator.run(options)
        // The one seam where accumulated reporter errors become an exit code:
        // the scip-aggregator library reports errors instead of throwing.
        if (!app.reporter.hasErrors()) {
            app.info(options.output.toString())
        }
        return app.reporter.exitCode()
    }
}
