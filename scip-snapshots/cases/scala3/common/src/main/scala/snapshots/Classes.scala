package snapshots

case class Greeting(name: String, times: Int):
  def greet: String = name * times

object Greeting:
  val default: Greeting = Greeting("world", 1)
  def of(name: String): Greeting = Greeting(name, 1)
  def of(name: String, times: Int): Greeting = Greeting(name, times)

class Overloads:
  def run(): Int = 0
  def run(x: Int): Int = x
  var count: Int = 0
  def `type`: Int = 1

  def useAll(g: Greeting): String = {
    import g.greet
    val sb = new java.lang.StringBuilder()
    sb.append(JavaHelper.format(run(`type`)))
    sb.append(JavaHelper.format(count, ":"))
    sb.append(new JavaHelper().label(greet))
    sb.toString
  }

def makeGreeting(name: String): Greeting = new Greeting(name, 2)
