package com

case class Greeting(name: String) {
  def greet: String = s"hello $name"
}

object Greeting {
  val default: Greeting = Greeting("world")
}
