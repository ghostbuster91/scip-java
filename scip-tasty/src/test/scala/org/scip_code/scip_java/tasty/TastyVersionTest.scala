package org.scip_code.scip_java.tasty

import scala.jdk.CollectionConverters.*

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test

/** TASTy accepts an older stable minor but rejects a newer one outright, so the check has to
  * be one-sided: only versions *above* the supported minor are a problem.
  */
class TastyVersionTest {

  @Test
  def acceptsTheSupportedMinorAndOlder(): Unit = {
    assertEquals(
      List.empty[String],
      TastyVersion.unsupported(List("3.3.9", "3.7.4", s"3.${TastyVersion.SupportedScalaMinor}.0").asJava).asScala.toList,
    )
  }

  @Test
  def rejectsANewerMinor(): Unit = {
    val newer: String = s"3.${TastyVersion.SupportedScalaMinor + 1}.0"
    assertEquals(List(newer), TastyVersion.unsupported(List(newer).asJava).asScala.toList)
  }

  /** Scala 2 is out of scope and has no TASTy, so it is not a TASTy version mismatch. */
  @Test
  def ignoresScala2(): Unit = {
    assertEquals(
      List.empty[String],
      TastyVersion.unsupported(List("2.13.16").asJava).asScala.toList,
    )
  }

  @Test
  def ignoresUnparseableVersions(): Unit = {
    assertEquals(
      List.empty[String],
      TastyVersion.unsupported(List("", "not-a-version", "3").asJava).asScala.toList,
    )
  }

  /** A release candidate of a newer minor is still unreadable. */
  @Test
  def rejectsAPrereleaseOfANewerMinor(): Unit = {
    val newer: String = s"3.${TastyVersion.SupportedScalaMinor + 1}.0-RC1"
    assertEquals(List(newer), TastyVersion.unsupported(List(newer).asJava).asScala.toList)
  }

  /** The message has to name the versions and the way out, since it is the only thing the
    * user sees before the run stops.
    */
  @Test
  def messageNamesTheVersionsAndTheRemedy(): Unit = {
    val message: String = TastyVersion.mismatchMessage(List("3.99.0").asJava)
    assertTrue(message.contains("3.99.0"), message)
    assertTrue(message.contains(s"3.${TastyVersion.SupportedScalaMinor}"), message)
    assertTrue(message.contains("branch"), message)
  }
}
