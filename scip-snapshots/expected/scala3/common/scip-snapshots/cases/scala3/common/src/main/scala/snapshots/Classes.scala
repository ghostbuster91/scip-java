  package snapshots
  
  case class Greeting(name: String, times: Int):
//           ^^^^^^^^ definition scip-java maven . . snapshots/Greeting#
//                    kind Class
//                    display_name Greeting
//                    signature_documentation
//                    > extends scala.Product with scala.Serializable
//                    relationship scip-java maven . . snapshots/Greeting.apply(). reference
//                    definition scip-java maven . . snapshots/Greeting#`<init>`().
//                    kind Constructor
//                    display_name <init>
//                    signature_documentation
//                    > (name: scala.Predef.String, times: scala.Int)scala.Unit
//                    ^^^^ definition scip-java maven . . snapshots/Greeting#`<init>`().(name)
//                         kind Parameter
//                         display_name name
//                         signature_documentation
//                         > scala.Predef.String
//                         relationship scip-java maven . . snapshots/Greeting#copy().(name) reference
//                         relationship scip-java maven . . snapshots/Greeting#name. reference
//                         relationship scip-java maven . . snapshots/Greeting.apply().(name) reference
//                    ^^^^ definition scip-java maven . . snapshots/Greeting#name.
//                         kind Method
//                         display_name name
//                         signature_documentation
//                         > scala.Predef.String
//                         relationship scip-java maven . . snapshots/Greeting#`<init>`().(name) reference
//                         relationship scip-java maven . . snapshots/Greeting#copy().(name) reference
//                         relationship scip-java maven . . snapshots/Greeting.apply().(name) reference
//                          ^^^^^^ reference scip-java maven . . scala/Predef.String#
//                                  ^^^^^ definition scip-java maven . . snapshots/Greeting#`<init>`().(times)
//                                        kind Parameter
//                                        display_name times
//                                        signature_documentation
//                                        > scala.Int
//                                        relationship scip-java maven . . snapshots/Greeting#copy().(times) reference
//                                        relationship scip-java maven . . snapshots/Greeting#times. reference
//                                        relationship scip-java maven . . snapshots/Greeting.apply().(times) reference
//                                  ^^^^^ definition scip-java maven . . snapshots/Greeting#times.
//                                        kind Method
//                                        display_name times
//                                        signature_documentation
//                                        > scala.Int
//                                        relationship scip-java maven . . snapshots/Greeting#`<init>`().(times) reference
//                                        relationship scip-java maven . . snapshots/Greeting#copy().(times) reference
//                                        relationship scip-java maven . . snapshots/Greeting.apply().(times) reference
//                                         ^^^ reference scip-java maven . . scala/Int#
    def greet: String = name * times
//      ^^^^^ definition scip-java maven . . snapshots/Greeting#greet().
//            kind Method
//            display_name greet
//            signature_documentation
//            > scala.Predef.String
//             ^^^^^^ reference scip-java maven . . scala/Predef.String#
//                      ^^^^ reference scip-java maven . . snapshots/Greeting#name.
//                           ^ reference scip-java maven . . scala/collection/StringOps#`*`().
//                             ^^^^^ reference scip-java maven . . snapshots/Greeting#times.
  
  object Greeting:
//       ^^^^^^^^ definition scip-java maven . . snapshots/Greeting.
//                kind Object
//                display_name Greeting
//                signature_documentation
//                > extends Mirror$.this.Product
    val default: Greeting = Greeting("world", 1)
//   definition scip-java maven . . snapshots/Greeting.`<init>`().
//   kind Constructor
//   display_name <init>
//   signature_documentation
//   > ()scala.Unit
//      ^^^^^^^ definition scip-java maven . . snapshots/Greeting.default.
//              kind Method
//              display_name default
//              signature_documentation
//              > snapshots.Greeting
//               ^^^^^^^^ reference scip-java maven . . snapshots/Greeting#
//                          ^^^^^^^^ reference scip-java maven . . snapshots/Greeting.
    def of(name: String): Greeting = Greeting(name, 1)
//      ^^ definition scip-java maven . . snapshots/Greeting.of().
//         kind Method
//         display_name of
//         signature_documentation
//         > (name: scala.Predef.String)snapshots.Greeting
//         ^^^^ definition scip-java maven . . snapshots/Greeting.of().(name)
//              kind Parameter
//              display_name name
//              signature_documentation
//              > scala.Predef.String
//               ^^^^^^ reference scip-java maven . . scala/Predef.String#
//                        ^^^^^^^^ reference scip-java maven . . snapshots/Greeting#
//                                   ^^^^^^^^ reference scip-java maven . . snapshots/Greeting.
//                                            ^^^^ reference scip-java maven . . snapshots/Greeting.of().(name)
    def of(name: String, times: Int): Greeting = Greeting(name, times)
//      ^^ definition scip-java maven . . snapshots/Greeting.of(+1).
//         kind Method
//         display_name of
//         signature_documentation
//         > (name: scala.Predef.String, times: scala.Int)snapshots.Greeting
//         ^^^^ definition scip-java maven . . snapshots/Greeting.of(+1).(name)
//              kind Parameter
//              display_name name
//              signature_documentation
//              > scala.Predef.String
//               ^^^^^^ reference scip-java maven . . scala/Predef.String#
//                       ^^^^^ definition scip-java maven . . snapshots/Greeting.of(+1).(times)
//                             kind Parameter
//                             display_name times
//                             signature_documentation
//                             > scala.Int
//                              ^^^ reference scip-java maven . . scala/Int#
//                                    ^^^^^^^^ reference scip-java maven . . snapshots/Greeting#
//                                               ^^^^^^^^ reference scip-java maven . . snapshots/Greeting.
//                                                        ^^^^ reference scip-java maven . . snapshots/Greeting.of(+1).(name)
//                                                              ^^^^^ reference scip-java maven . . snapshots/Greeting.of(+1).(times)
  
  class Overloads:
//      ^^^^^^^^^ definition scip-java maven . . snapshots/Overloads#
//                kind Class
//                display_name Overloads
    def run(): Int = 0
//   definition scip-java maven . . snapshots/Overloads#`<init>`().
//   kind Constructor
//   display_name <init>
//   signature_documentation
//   > ()scala.Unit
//      ^^^ definition scip-java maven . . snapshots/Overloads#run().
//          kind Method
//          display_name run
//          signature_documentation
//          > ()scala.Int
//             ^^^ reference scip-java maven . . scala/Int#
    def run(x: Int): Int = x
//      ^^^ definition scip-java maven . . snapshots/Overloads#run(+1).
//          kind Method
//          display_name run
//          signature_documentation
//          > (x: scala.Int)scala.Int
//          ^ definition scip-java maven . . snapshots/Overloads#run(+1).(x)
//            kind Parameter
//            display_name x
//            signature_documentation
//            > scala.Int
//             ^^^ reference scip-java maven . . scala/Int#
//                   ^^^ reference scip-java maven . . scala/Int#
//                         ^ reference scip-java maven . . snapshots/Overloads#run(+1).(x)
    var count: Int = 0
//      ^^^^^ definition scip-java maven . . snapshots/Overloads#count().
//            kind Method
//            display_name count
//            signature_documentation
//            > scala.Int
//             ^^^ reference scip-java maven . . scala/Int#
    def `type`: Int = 1
//       ^^^^ definition scip-java maven . . snapshots/Overloads#type().
//            kind Method
//            display_name type
//            signature_documentation
//            > scala.Int
//              ^^^ reference scip-java maven . . scala/Int#
  
    def useAll(g: Greeting): String = {
//      ^^^^^^ definition scip-java maven . . snapshots/Overloads#useAll().
//             kind Method
//             display_name useAll
//             signature_documentation
//             > (g: snapshots.Greeting)scala.Predef.String
//             ^ definition scip-java maven . . snapshots/Overloads#useAll().(g)
//               kind Parameter
//               display_name g
//               signature_documentation
//               > snapshots.Greeting
//                ^^^^^^^^ reference scip-java maven . . snapshots/Greeting#
//                           ^^^^^^ reference scip-java maven . . scala/Predef.String#
      import g.greet
//           ^ reference scip-java maven . . snapshots/Overloads#useAll().(g)
//             ^^^^^ reference scip-java maven . . snapshots/Greeting#greet().
      val sb = new java.lang.StringBuilder()
//        ^^ definition local 0
//           kind Variable
//           display_name sb
//           signature_documentation
//           > java.lang.StringBuilder
//                 ^^^^ reference scip-java maven . . java/
//                      ^^^^ reference scip-java maven . . java/lang/
//                           ^^^^^^^^^^^^^ reference scip-java maven jdk 17 java/lang/StringBuilder#`<init>`().
//                           ^^^^^^^^^^^^^ reference scip-java maven jdk 17 java/lang/StringBuilder#
      sb.append(JavaHelper.format(run(`type`)))
//    ^^ reference local 0
//       ^^^^^^ reference scip-java maven jdk 17 java/lang/StringBuilder#append(+1).
//              ^^^^^^^^^^ reference scip-java maven . . snapshots/JavaHelper#
//                         ^^^^^^ reference scip-java maven . . snapshots/JavaHelper#format().
//                                ^^^ reference scip-java maven . . snapshots/Overloads#run(+1).
//                                    ^^^^^^ reference scip-java maven . . snapshots/Overloads#type().
      sb.append(JavaHelper.format(count, ":"))
//    ^^ reference local 0
//       ^^^^^^ reference scip-java maven jdk 17 java/lang/StringBuilder#append(+1).
//              ^^^^^^^^^^ reference scip-java maven . . snapshots/JavaHelper#
//                         ^^^^^^ reference scip-java maven . . snapshots/JavaHelper#format(+1).
//                                ^^^^^ reference scip-java maven . . snapshots/Overloads#count().
      sb.append(new JavaHelper().label(greet))
//    ^^ reference local 0
//       ^^^^^^ reference scip-java maven jdk 17 java/lang/StringBuilder#append(+1).
//                  ^^^^^^^^^^ reference scip-java maven . . snapshots/JavaHelper#`<init>`().
//                  ^^^^^^^^^^ reference scip-java maven . . snapshots/JavaHelper#
//                               ^^^^^ reference scip-java maven . . snapshots/JavaHelper#label().
//                                     ^ reference scip-java maven . . snapshots/Overloads#useAll().(g)
//                                     ^^^^^ reference scip-java maven . . snapshots/Greeting#greet().
      sb.toString
//    ^^ reference local 0
//       ^^^^^^^^ reference scip-java maven jdk 17 java/lang/StringBuilder#toString().
    }
  
  def makeGreeting(name: String): Greeting = new Greeting(name, 2)
// definition scip-java maven . . snapshots/Classes$package.`<init>`().
// kind Constructor
// display_name <init>
// signature_documentation
// > ()scala.Unit
//    ^^^^^^^^^^^^ definition scip-java maven . . snapshots/Classes$package.makeGreeting().
//                 kind Method
//                 display_name makeGreeting
//                 signature_documentation
//                 > (name: scala.Predef.String)snapshots.Greeting
//                 ^^^^ definition scip-java maven . . snapshots/Classes$package.makeGreeting().(name)
//                      kind Parameter
//                      display_name name
//                      signature_documentation
//                      > scala.Predef.String
//                       ^^^^^^ reference scip-java maven . . scala/Predef.String#
//                                ^^^^^^^^ reference scip-java maven . . snapshots/Greeting#
//                                               ^^^^^^^^ reference scip-java maven . . snapshots/Greeting#`<init>`().
//                                               ^^^^^^^^ reference scip-java maven . . snapshots/Greeting#
//                                                        ^^^^ reference scip-java maven . . snapshots/Classes$package.makeGreeting().(name)
  
