package com

case class Greeting(name: String) {
  def greet: String = {
    // A local val, so the document contains local symbols.
    val prefix = "hello"
    s"$prefix $name"
  }
}

object Greeting {
  val default: Greeting = Greeting("world")
}
