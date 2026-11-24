package org.scip_code.scip_java.tasty

import java.nio.file.Path

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `TastyToScip.relativize` has to cope with both shapes TASTy can pickle.
  *
  * dotty relativizes source paths against `-sourceroot`, default `"."`, so under sbt --
  * which compiles with the build root as its working directory -- the pickled path is
  * already build-root-relative. A build that sets `-sourceroot` elsewhere, or a source
  * outside it, yields an absolute path instead. Both have to produce the same
  * source-root-relative result, because that is what the shard path and
  * `Document.relative_path` are keyed on.
  */
class RelativizeTest {

  private val root: Path = Path.of("/work/project")

  @Test
  def keepsARelativePickledPath(): Unit =
    assertEquals(
      Some("src/main/scala/com/Foo.scala"),
      TastyToScip.relativize(root, "src/main/scala/com/Foo.scala"),
    )

  @Test
  def relativizesAnAbsolutePickledPath(): Unit =
    assertEquals(
      Some("src/main/scala/com/Foo.scala"),
      TastyToScip.relativize(root, "/work/project/src/main/scala/com/Foo.scala"),
    )

  /** `ScipShardPaths.shardPath` returns empty for a source outside the root, so there is no
    * shard path to write and the file has to be skipped rather than guessed at.
    */
  @Test
  def rejectsASourceOutsideTheRoot(): Unit = {
    assertEquals(None, TastyToScip.relativize(root, "/elsewhere/Foo.scala"))
    assertEquals(None, TastyToScip.relativize(root, "../sibling/Foo.scala"))
  }

  @Test
  def normalizesTraversal(): Unit =
    assertEquals(Some("src/Foo.scala"), TastyToScip.relativize(root, "src/main/../Foo.scala"))

  @Test
  def rejectsTheRootItself(): Unit =
    assertEquals(None, TastyToScip.relativize(root, "."))
}
