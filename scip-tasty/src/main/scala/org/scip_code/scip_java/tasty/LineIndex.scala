package org.scip_code.scip_java.tasty

/** Maps character offsets in a source file to zero-based line and column.
  *
  * Built from the file on disk rather than from `SourcePosition.startLine`/`startColumn`,
  * for two reasons. TASTy attaches line sizes only to the first `SOURCE` directive it sees
  * per file and `SourceFile.setLineSizes` is write-once, so
  * `SourcePosition.hasLineColumnInformation` can be false and the accessors then throw.
  * And SCIP columns are character offsets, which is what both scip-javac
  * (`LineMap.getColumnNumber(pos) - 1`) and dotty's own ranges use, so counting characters
  * here keeps the two front-ends consistent.
  */
final class LineIndex private (private val lineStarts: Array[Int], val length: Int):

  /** Zero-based line containing `offset`. */
  def lineOf(offset: Int): Int =
    val i = java.util.Arrays.binarySearch(lineStarts, offset)
    if i >= 0 then i else -i - 2

  /** Zero-based character column of `offset` within its line. */
  def columnOf(offset: Int): Int = offset - lineStarts(lineOf(offset))

  /** True when `offset` is a valid position in this file. */
  def contains(offset: Int): Boolean = offset >= 0 && offset <= length
end LineIndex

object LineIndex:
  def of(text: String): LineIndex =
    val starts = Array.newBuilder[Int]
    starts += 0
    var i = 0
    while i < text.length do
      // `\r\n` is handled by treating `\n` as the terminator; a lone `\r` is not treated as
      // a line break, matching how the compiler counts lines.
      if text.charAt(i) == '\n' then starts += i + 1
      i += 1
    LineIndex(starts.result(), text.length)
end LineIndex
