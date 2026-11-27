package tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.scip_code.scip_java.buildtools.SbtBuildTool
import org.scip_code.scip_java.buildtools.SbtBuildTool.ScalaVersions

/**
 * Which project describes a source that several cross-built projects compile: the one on the
 * build's default Scala version, see [SbtBuildTool.rankProjects].
 */
class SbtProjectRankingTest {

    @Test
    fun theProjectOnTheBuildsDefaultScalaVersionComesFirst() {
        val ranking =
            SbtBuildTool.rankProjects(
                mapOf(
                    "core2_13" to ScalaVersions("2.13.16", "2.12.21"),
                    "core2_12" to ScalaVersions("2.12.21", "2.12.21"),
                    "core3" to ScalaVersions("3.9.0", "2.12.21"),
                )
            )

        assertEquals(listOf("core2_12", "core3", "core2_13"), ranking)
    }

    @Test
    fun aBuildDefaultNoProjectUsesFallsBackToTheVersionMostProjectsUse() {
        // zinc's shape: `ThisBuild / scalaVersion` is left at sbt's own default, the libraries
        // are all on one Scala 3 version, and the compiler bridges are cross-built for Scala 2.
        val ranking =
            SbtBuildTool.rankProjects(
                mapOf(
                    "zincCore" to ScalaVersions("3.9.0", "3.7.4"),
                    "zincPersist" to ScalaVersions("3.9.0", "3.7.4"),
                    "bridge2_10" to ScalaVersions("2.10.7", "3.7.4"),
                    "bridge2_13" to ScalaVersions("2.13.16", "3.7.4"),
                    "bridge2_11" to ScalaVersions("2.11.12", "3.7.4"),
                )
            )

        // With no bridge on the default, the newest Scala version describes their shared sources.
        assertEquals(
            listOf("zincCore", "zincPersist", "bridge2_13", "bridge2_11", "bridge2_10"),
            ranking,
        )
    }

    @Test
    fun versionsCompareNumericallyNotAsText() {
        assert(SbtBuildTool.compareVersions("2.13.16", "2.9.3") > 0)
        assert(SbtBuildTool.compareVersions("3.9.0", "3.10.0") < 0)
        assertEquals(0, SbtBuildTool.compareVersions("2.12.21", "2.12.21"))
    }
}
