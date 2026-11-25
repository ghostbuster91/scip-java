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

  // A backticked identifier naming a reserved word, referenced using the same backticks:
  // the occurrence has to be anchored past the opening quote, at "type" itself.
  def `type`: Int = 1
  def useReservedWord(): Int = `type`

  // Import selectors, exercised as local imports so they live inside this class's own
  // tree: a top-level import sits in the enclosing `PackageDef`, which tasty-query exposes
  // only through `private[tastyquery] topLevelTasty`, so it cannot be walked from here.
  // `Try` names both a class and its companion value, and `default` is an aliased import
  // of another top-level object's member -- forward-referenced, since every top-level
  // definition in a file is visible regardless of textual order.
  //
  // The alias itself (`greetingDefault`) is deliberately left unused here: using it would
  // require matching the *alias* text at its use site against the real symbol's bare name
  // ("default"), a separate, broader gap this does not attempt to close.
  def useImports(g: Greeting): String = {
    import scala.util.{Try => Attempt}
    import Greeting.{default => greetingDefault}
    import g.greet
    Greeting.default.name + greet
  }

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

// `new X(...)` constructor calls: `<init>` is never written in source, so the reference
// has to be anchored to the class name instead -- both the no-arg and the parameterized
// shape, since only one of them has args to also walk into.
def freshOverloads(): Overloads = new Overloads()
def freshGreeting(n: String): Greeting = new Greeting(n, 1)
