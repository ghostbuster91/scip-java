package snapshots

enum Color:
  case Red, Green
  case Custom(hex: String)

trait Show[A]:
  def show(a: A): String
  val separator: String

object Show:
  opaque type Label = String
  object Label:
    def apply(s: String): Label = s

  type Box[A] = List[A]

  given Show[Int] = new Show[Int]:
    def show(a: Int): String = a.toString
    val separator: String = ","

  extension (self: Int) def shown(using s: Show[Int]): String = s.show(self)

  def demo(c: Color, xs: Box[Int]): String =
    val head = xs.headOption.getOrElse(0)
    c match
      case Color.Red       => head.shown
      case Color.Green     => Label("green").toString
      case Color.Custom(h) => h
