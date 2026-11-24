package org.scip_code.scip_java.tasty

import java.{util as ju}

import scala.jdk.CollectionConverters.*

/** The range of TASTy versions this build can read, and the Scala 3 versions it implies.
  *
  * tasty-query accepts an older stable minor but **rejects a newer one outright**, so a
  * repository built with a Scala 3 newer than this release supports cannot be indexed from
  * TASTy at all. That is handled by cutting an indexer branch per Scala 3 minor, which only
  * works if the mismatch is reported plainly instead of degrading into a partial index.
  *
  * The constants are duplicated from `tastyquery.reader.tasties.TastyFormat`, which is
  * `private[tasties]`. They are pinned next to the `tasty-query` entry in
  * `libs.versions.toml` so a branch bump is one edit.
  */
object TastyVersion {

  /** TASTy major version tasty-query 1.10.0 reads. */
  val SupportedMajor: Int = 28

  /** Highest TASTy minor version tasty-query 1.10.0 reads. */
  val SupportedMinor: Int = 10

  /** The highest Scala 3 minor this build can index: TASTy 28.N corresponds to Scala 3.N. */
  val SupportedScalaMinor: Int = SupportedMinor

  /**
   * The Scala 3 versions in `scalaVersions` that this build cannot read TASTy for.
   *
   * Takes the versions as reported by the build rather than reading TASTy headers, so the
   * check happens before any work and names what the user has to act on. Versions that are
   * not Scala 3, or that cannot be parsed, are ignored: they are either out of scope or will
   * surface later as a per-class warning.
   */
  def unsupported(scalaVersions: ju.Collection[String]): ju.List[String] =
    scalaVersions.asScala.toList.filter(isUnsupported).distinct.asJava

  private def isUnsupported(version: String): Boolean =
    minorOf(version).exists(_ > SupportedScalaMinor)

  /** The minor of a `3.x.y` version, or `None` if it is not a parseable Scala 3 version. */
  private[tasty] def minorOf(version: String): Option[Int] = {
    val parts = version.split("[.-]")
    if (parts.length < 2 || parts(0) != "3") None
    else parts(1).toIntOption
  }

  /** The message to fail with, naming the versions and what to do about them. */
  def mismatchMessage(unsupportedVersions: ju.List[String]): String = {
    val versions = unsupportedVersions.asScala.mkString(", ")
    s"This build of scip-java reads TASTy up to $SupportedMajor.$SupportedMinor, " +
      s"which is Scala 3.$SupportedScalaMinor. The build uses Scala $versions, whose TASTy is " +
      "forwards incompatible and cannot be read at all. Use the scip-java branch that " +
      s"supports Scala $versions, or build the repository with Scala 3.$SupportedScalaMinor " +
      "or older."
  }
}
