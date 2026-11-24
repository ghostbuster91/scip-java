package org.scip_code.scip_java.tasty

import scala.util.control.NonFatal

import org.junit.jupiter.api.Assertions.{assertFalse, assertTrue}
import org.junit.jupiter.api.Test

/** The conversion must survive an `AssertionError`, not only an `Exception`.
  *
  * tasty-query reports some malformed input with `assert`, which throws `AssertionError`
  * -- an `Error`, so a `catch case e: Exception` misses it entirely. Indexing tapir hit
  * exactly that while reading an annotation in a test class, and the throwable escaped
  * every handler and ended the run, losing an index that was otherwise complete.
  *
  * This pins the property the handlers depend on, so that narrowing them back to
  * `Exception` fails here rather than on someone's repository.
  */
class NonFatalHandlingTest {

  @Test
  def nonFatalCatchesAssertionError(): Unit =
    assertTrue(
      NonFatal(new AssertionError("assertion failed: at address Addr(1671)")),
      "NonFatal must treat AssertionError as recoverable, or TASTy indexing cannot survive it",
    )

  /** An `Exception` handler is what the bug was: it does not match.
    *
    * Held through a `Throwable` reference so the check is a real runtime test rather than
    * one the compiler folds away.
    */
  @Test
  def assertionErrorIsNotAnException(): Unit = {
    val thrown: Throwable = new AssertionError("assertion failed")
    assertFalse(
      thrown.isInstanceOf[Exception],
      "if this were true the `Exception` handlers would have been sufficient",
    )
  }

  /** Running out of memory or stack is not something to swallow and continue from, so those
    * stay outside `NonFatal` and are handled explicitly where it matters.
    */
  @Test
  def nonFatalLeavesVirtualMachineErrorsAlone(): Unit = {
    assertFalse(NonFatal(new OutOfMemoryError()), "OOM must stay fatal")
    assertFalse(NonFatal(new StackOverflowError()), "stack overflow is handled separately")
  }
}
