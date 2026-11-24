package com.example

/** Exercises the constructs the symbol minter has to get right. The existing
  * `fixtures/sbt/scala` fixture has none of them, so parity there would pass vacuously.
  */
class Overloads:
  // Three same-named alternatives: the disambiguator must number these `()`, `(+1)`,
  // `(+2)` in dotty's order, which is not plain source order once synthetics are mixed in.
  def run(): Int = 0
  def run(x: Int): Int = x
  def run(x: Int, y: String): Int = x + y.length

  // A `var` is `Mutable`, so it occupies an index alongside same-named methods, and the
  // generated getter/setter share its name.
  var count: Int = 0

  def typed[A](value: A): A = value

  type Alias = Int

case class Greeting(name: String, times: Int):
  // Synthetic members (`copy`, `copy$default$1`, `productElement`, ...) are pickled into
  // TASTy and are partitioned *after* non-synthetic ones when numbering overloads.
  def greet: String = name * times

object Greeting:
  val default: Greeting = Greeting("world", 1)
  def of(name: String): Greeting = Greeting(name, 1)
  def of(name: String, times: Int): Greeting = Greeting(name, times)

enum Color:
  case Red, Green
  case Custom(hex: String)

trait Show[A]:
  def show(a: A): String

object Show:
  given Show[Int] = (a: Int) => a.toString

  extension (self: Int) def shown(using s: Show[Int]): String = s.show(self)

/** A top-level definition: these live in a synthetic `Fixture$package` module class. */
def topLevel(n: Int): Int = n + 1

val topLevelValue: Int = 1
