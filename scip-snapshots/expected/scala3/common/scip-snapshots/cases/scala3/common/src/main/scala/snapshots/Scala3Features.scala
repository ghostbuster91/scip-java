  package snapshots
  
  enum Color:
//     ^^^^^ definition scip-java maven . . snapshots/Color#
//           kind Enum
//           display_name Color
//           signature_documentation
//           > extends scala.reflect.Enum
//           relationship scip-java maven . . snapshots/Color. reference
    case Red, Green
//   definition scip-java maven . . snapshots/Color#`<init>`().
//   kind Constructor
//   display_name <init>
//   signature_documentation
//   > ()scala.Unit
//   relationship scip-java maven . . snapshots/Color.`<init>`(). reference
//   definition scip-java maven . . snapshots/Color.`<init>`().
//   kind Constructor
//   display_name <init>
//   signature_documentation
//   > ()scala.Unit
//   relationship scip-java maven . . snapshots/Color#`<init>`(). reference
//       ^^^ reference scip-java maven . . snapshots/Color.Red.
//       ^^^ definition scip-java maven . . snapshots/Color.Red.
//           kind StaticMethod
//           display_name Red
//           signature_documentation
//           > snapshots.Color
//            ^^^^^ reference scip-java maven . . snapshots/Color.Green.
//            ^^^^^ definition scip-java maven . . snapshots/Color.Green.
//                  kind StaticMethod
//                  display_name Green
//                  signature_documentation
//                  > snapshots.Color
    case Custom(hex: String)
//   definition scip-java maven . . snapshots/Color.Custom.`<init>`().
//   kind Constructor
//   display_name <init>
//   signature_documentation
//   > ()scala.Unit
//   relationship scip-java maven . . snapshots/Color.Custom.apply(). reference
//       ^^^^^^ definition scip-java maven . . snapshots/Color.Custom#
//              kind Enum
//              display_name Custom
//              signature_documentation
//              > extends snapshots.Color
//              relationship scip-java maven . . snapshots/Color.Custom. reference
//              relationship scip-java maven . . snapshots/Color.Custom.apply(). reference
//       ^^^^^^ definition scip-java maven . . snapshots/Color.Custom.
//              kind Object
//              display_name Custom
//              signature_documentation
//              > Color$.this.Custom$
//              relationship scip-java maven . . snapshots/Color.Custom# reference
//              relationship scip-java maven . . snapshots/Color.Custom.apply(). reference
//              definition scip-java maven . . snapshots/Color.Custom#`<init>`().
//              kind Constructor
//              display_name <init>
//              signature_documentation
//              > (hex: scala.Predef.String)scala.Unit
//              ^^^ definition scip-java maven . . snapshots/Color.Custom#`<init>`().(hex)
//                  kind Parameter
//                  display_name hex
//                  signature_documentation
//                  > scala.Predef.String
//                  relationship scip-java maven . . snapshots/Color.Custom#copy().(hex) reference
//                  relationship scip-java maven . . snapshots/Color.Custom#hex. reference
//                  relationship scip-java maven . . snapshots/Color.Custom.apply().(hex) reference
//              ^^^ definition scip-java maven . . snapshots/Color.Custom#hex.
//                  kind Method
//                  display_name hex
//                  signature_documentation
//                  > scala.Predef.String
//                  relationship scip-java maven . . snapshots/Color.Custom#`<init>`().(hex) reference
//                  relationship scip-java maven . . snapshots/Color.Custom#copy().(hex) reference
//                  relationship scip-java maven . . snapshots/Color.Custom.apply().(hex) reference
//                   ^^^^^^ reference scip-java maven . . scala/Predef.String#
  
  trait Show[A]:
//      ^^^^ definition scip-java maven . . snapshots/Show#
//           kind Trait
//           display_name Show
//           definition scip-java maven . . snapshots/Show#`<init>`().
//           kind Constructor
//           display_name <init>
//           signature_documentation
//           > [A]()scala.Unit
//           ^ definition scip-java maven . . snapshots/Show#[A]
//             kind TypeParameter
//             display_name A
    def show(a: A): String
//      ^^^^ definition scip-java maven . . snapshots/Show#show().
//           kind AbstractMethod
//           display_name show
//           signature_documentation
//           > (a: Show.this.A)scala.Predef.String
//           ^ definition scip-java maven . . snapshots/Show#show().(a)
//             kind Parameter
//             display_name a
//             signature_documentation
//             > Show.this.A
//              ^ reference scip-java maven . . snapshots/Show#[A]
//                  ^^^^^^ reference scip-java maven . . scala/Predef.String#
    val separator: String
//      ^^^^^^^^^ definition scip-java maven . . snapshots/Show#separator.
//                kind AbstractMethod
//                display_name separator
//                signature_documentation
//                > scala.Predef.String
//                 ^^^^^^ reference scip-java maven . . scala/Predef.String#
  
  object Show:
//       ^^^^ definition scip-java maven . . snapshots/Show.
//            kind Object
//            display_name Show
    opaque type Label = String
//   definition scip-java maven . . snapshots/Show.`<init>`().
//   kind Constructor
//   display_name <init>
//   signature_documentation
//   > ()scala.Unit
//              ^^^^^ definition scip-java maven . . snapshots/Show.Label#
//                    kind Type
//                    display_name Label
//                    signature_documentation
//                    > = scala.Predef.String
//                      ^^^^^^ reference scip-java maven . . scala/Predef.String#
    object Label:
//         ^^^^^ definition scip-java maven . . snapshots/Show.Label.
//               kind Object
//               display_name Label
      def apply(s: String): Label = s
//     definition scip-java maven . . snapshots/Show.Label.`<init>`().
//     kind Constructor
//     display_name <init>
//     signature_documentation
//     > ()scala.Unit
//     relationship scip-java maven . . snapshots/Show.Label.apply(). reference
//        ^^^^^ definition scip-java maven . . snapshots/Show.Label.apply().
//              kind Method
//              display_name apply
//              signature_documentation
//              > (s: scala.Predef.String)Show$.this.Label
//              ^ definition scip-java maven . . snapshots/Show.Label.apply().(s)
//                kind Parameter
//                display_name s
//                signature_documentation
//                > scala.Predef.String
//                 ^^^^^^ reference scip-java maven . . scala/Predef.String#
//                          ^^^^^ reference scip-java maven . . snapshots/Show.Label#
//                                  ^ reference scip-java maven . . snapshots/Show.Label.apply().(s)
  
    type Box[A] = List[A]
//       ^^^ definition scip-java maven . . snapshots/Show.Box#
//           kind Type
//           display_name Box
//           signature_documentation
//           > = ([A] =>> scala.package.List[A])
//           ^ definition scip-java maven . . snapshots/Show.Box#[A]
//             kind TypeParameter
//             display_name A
//                ^^^^ reference scip-java maven . . scala/package.List#
//                     ^ reference scip-java maven . . snapshots/Show.Box#[A]
  
    given Show[Int] = new Show[Int]:
//        ^^^^ reference scip-java maven . . snapshots/Show#
//             ^^^ reference scip-java maven . . scala/Int#
//                        ^^^^ reference scip-java maven . . snapshots/Show#
//                             ^^^ reference scip-java maven . . scala/Int#
      def show(a: Int): String = a.toString
//        ^^^^ definition local 0
//             kind Method
//             display_name show
//             signature_documentation
//             > (a: scala.Int)scala.Predef.String
//             relationship scip-java maven . . snapshots/Show#show(). implementation reference
//             ^ definition local 1
//               kind Parameter
//               display_name a
//               signature_documentation
//               > scala.Int
//                ^^^ reference scip-java maven . . scala/Int#
//                      ^^^^^^ reference scip-java maven . . scala/Predef.String#
//                               ^ reference local 1
//                                 ^^^^^^^^ reference scip-java maven . . scala/Any#toString().
      val separator: String = ","
//        ^^^^^^^^^ definition local 2
//                  kind Variable
//                  display_name separator
//                  signature_documentation
//                  > scala.Predef.String
//                  relationship scip-java maven . . snapshots/Show#separator. implementation reference
//                   ^^^^^^ reference scip-java maven . . scala/Predef.String#
  
    extension (self: Int) def shown(using s: Show[Int]): String = s.show(self)
//             ^^^^ definition scip-java maven . . snapshots/Show.shown().(self)
//                  kind Parameter
//                  display_name self
//                  signature_documentation
//                  > scala.Int
//                   ^^^ reference scip-java maven . . scala/Int#
//                            ^^^^^ definition scip-java maven . . snapshots/Show.shown().
//                                  kind Method
//                                  display_name shown
//                                  signature_documentation
//                                  > (self: scala.Int)(using s: snapshots.Show[scala.Int])scala.Predef.String
//                                        ^ definition scip-java maven . . snapshots/Show.shown().(s)
//                                          kind Parameter
//                                          display_name s
//                                          signature_documentation
//                                          > snapshots.Show[scala.Int]
//                                           ^^^^ reference scip-java maven . . snapshots/Show#
//                                                ^^^ reference scip-java maven . . scala/Int#
//                                                       ^^^^^^ reference scip-java maven . . scala/Predef.String#
//                                                                ^ reference scip-java maven . . snapshots/Show.shown().(s)
//                                                                  ^^^^ reference scip-java maven . . snapshots/Show#show().
//                                                                       ^^^^ reference scip-java maven . . snapshots/Show.shown().(self)
  
    def demo(c: Color, xs: Box[Int]): String =
//      ^^^^ definition scip-java maven . . snapshots/Show.demo().
//           kind Method
//           display_name demo
//           signature_documentation
//           > (c: snapshots.Color, xs: Show$.this.Box[scala.Int])scala.Predef.String
//           ^ definition scip-java maven . . snapshots/Show.demo().(c)
//             kind Parameter
//             display_name c
//             signature_documentation
//             > snapshots.Color
//              ^^^^^ reference scip-java maven . . snapshots/Color#
//                     ^^ definition scip-java maven . . snapshots/Show.demo().(xs)
//                        kind Parameter
//                        display_name xs
//                        signature_documentation
//                        > Show$.this.Box[scala.Int]
//                         ^^^ reference scip-java maven . . snapshots/Show.Box#
//                             ^^^ reference scip-java maven . . scala/Int#
//                                    ^^^^^^ reference scip-java maven . . scala/Predef.String#
      val head = xs.headOption.getOrElse(0)
//        ^^^^ definition local 3
//             kind Variable
//             display_name head
//             signature_documentation
//             > scala.Int
//               ^^ reference scip-java maven . . snapshots/Show.demo().(xs)
//                  ^^^^^^^^^^ reference scip-java maven . . scala/collection/LinearSeqOps#headOption().
//                             ^^^^^^^^^ reference scip-java maven . . scala/Option#getOrElse().
      c match
//    ^ reference scip-java maven . . snapshots/Show.demo().(c)
        case Color.Red       => head.shown
//           ^^^^^ reference scip-java maven . . snapshots/Color.
//                 ^^^ reference scip-java maven . . snapshots/Color.Red.
//                              ^^^^ reference local 3
//                                   ^^^^^ reference scip-java maven . . snapshots/Show.shown().
        case Color.Green     => Label("green").toString
//           ^^^^^ reference scip-java maven . . snapshots/Color.
//                 ^^^^^ reference scip-java maven . . snapshots/Color.Green.
//                              ^^^^^ reference scip-java maven . . snapshots/Show.Label.
//                                             ^^^^^^^^ reference scip-java maven jdk 17 java/lang/String#toString().
        case Color.Custom(h) => h
//           ^^^^^ reference scip-java maven . . snapshots/Color.
//                 ^^^^^^ reference scip-java maven . . snapshots/Color.Custom.
//                        ^ definition local 4
//                          kind Variable
//                          display_name h
//                          signature_documentation
//                          > scala.Predef.String
//                              ^ reference local 4
  
