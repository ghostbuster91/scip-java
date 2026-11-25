package com.example

/** References overloaded *Java* methods.
  *
  * This is the hard case for the disambiguator. For a Scala owner the index comes from the
  * class body, which TASTy pickles in source order. A Java owner has no tree at all --
  * tasty-query only calls `setTree` from the TASTy reader -- so there is no declaration
  * order to index into, and `OverloadDisambiguator` falls back to `()`.
  *
  * Every method referenced here is heavily overloaded in the JDK, so a fallback that
  * guesses will disagree with the compiler.
  */
object JavaInterop:

  // String.valueOf has ~9 overloads; these pick three different ones.
  def fromInt(i: Int): String = String.valueOf(i)
  def fromChar(c: Char): String = String.valueOf(c)
  def fromAny(a: Object): String = String.valueOf(a)

  // Math.max has 4 overloads (int, long, float, double).
  def maxInt(a: Int, b: Int): Int = Math.max(a, b)
  def maxLong(a: Long, b: Long): Long = Math.max(a, b)
  def maxDouble(a: Double, b: Double): Double = Math.max(a, b)

  // StringBuilder.append has ~13 overloads.
  def build(n: Int, s: String): String =
    val sb = new java.lang.StringBuilder()
    sb.append(n)
    sb.append(s)
    sb.append('!')
    sb.toString

  // A Java instance method with no overloads at all: the fallback `()` is correct here,
  // so this distinguishes "fallback happens to be right" from "fallback is right".
  def trimmed(s: String): String = s.trim

  def indexed(s: String, c: Int): Int = s.indexOf(c)

  // java.util.Calendar.Builder is a *nested* Java class, so its binary name is
  // java/util/Calendar$Builder -- the hard case for JavaMethodOrder, which has to join
  // packages with `.` and enclosing classes with `$` to find its classfile at all.
  // setTimeOfDay has two overloads (3 and 4 int params); these pick both.
  def calendar(): java.util.Calendar =
    new java.util.Calendar.Builder().setTimeOfDay(1, 2, 3).setTimeOfDay(1, 2, 3, 4).build()
