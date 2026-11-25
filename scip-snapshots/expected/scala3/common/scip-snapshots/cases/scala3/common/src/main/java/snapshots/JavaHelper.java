  package snapshots;
  
  /** Java helper referenced from Scala 3 to verify cross-language symbol parity in snapshots. */
//⌄ enclosing_range_start scip-java maven . . snapshots/JavaHelper#
  public class JavaHelper {
//             ^^^^^^^^^^ definition scip-java maven . . snapshots/JavaHelper#
//                        kind Class
//                        display_name JavaHelper
//                        signature_documentation
//                        > public class JavaHelper
//                        documentation
//                        > Java helper referenced from Scala 3 to verify cross-language symbol parity in snapshots.
//             ^^^^^^^^^^ definition scip-java maven . . snapshots/JavaHelper#`<init>`().
//                        kind Constructor
//                        display_name <init>
//                        signature_documentation
//                        > public JavaHelper()
  
//  ⌄ enclosing_range_start scip-java maven . . snapshots/JavaHelper#format().
//                              ⌄ enclosing_range_start local 0
    public static String format(int value) {
//                ^^^^^^ reference scip-java maven jdk 17 java/lang/String#
//                       ^^^^^^ definition scip-java maven . . snapshots/JavaHelper#format().
//                              kind StaticMethod
//                              display_name format
//                              signature_documentation
//                              > public static String format(int value)
//                                  ^^^^^ definition local 0
//                                        display_name value
//                                        signature_documentation
//                                        > int value
//                                      ⌃ enclosing_range_end local 0
      return Integer.toString(value);
//           ^^^^^^^ reference scip-java maven jdk 17 java/lang/Integer#
//                   ^^^^^^^^ reference scip-java maven jdk 17 java/lang/Integer#toString(+2).
//                            ^^^^^ reference local 0
    }
//  ⌃ enclosing_range_end scip-java maven . . snapshots/JavaHelper#format().
  
//  ⌄ enclosing_range_start scip-java maven . . snapshots/JavaHelper#format(+1).
//                              ⌄ enclosing_range_start local 1
//                                         ⌄ enclosing_range_start local 2
    public static String format(int value, String prefix) {
//                ^^^^^^ reference scip-java maven jdk 17 java/lang/String#
//                       ^^^^^^ definition scip-java maven . . snapshots/JavaHelper#format(+1).
//                              kind StaticMethod
//                              display_name format
//                              signature_documentation
//                              > public static String format(int value, String prefix)
//                                  ^^^^^ definition local 1
//                                        display_name value
//                                        signature_documentation
//                                        > int value
//                                         ^^^^^^ reference scip-java maven jdk 17 java/lang/String#
//                                                ^^^^^^ definition local 2
//                                                       display_name prefix
//                                                       signature_documentation
//                                                       > String prefix
//                                      ⌃ enclosing_range_end local 1
//                                                     ⌃ enclosing_range_end local 2
      return prefix + value;
//           ^^^^^^ reference local 2
//                    ^^^^^ reference local 1
    }
//  ⌃ enclosing_range_end scip-java maven . . snapshots/JavaHelper#format(+1).
  
//  ⌄ enclosing_range_start scip-java maven . . snapshots/JavaHelper#label().
//                      ⌄ enclosing_range_start local 3
    public String label(String name) {
//         ^^^^^^ reference scip-java maven jdk 17 java/lang/String#
//                ^^^^^ definition scip-java maven . . snapshots/JavaHelper#label().
//                      kind Method
//                      display_name label
//                      signature_documentation
//                      > public String label(String name)
//                      ^^^^^^ reference scip-java maven jdk 17 java/lang/String#
//                             ^^^^ definition local 3
//                                  display_name name
//                                  signature_documentation
//                                  > String name
//                                ⌃ enclosing_range_end local 3
      return "[" + name + "]";
//                 ^^^^ reference local 3
    }
//  ⌃ enclosing_range_end scip-java maven . . snapshots/JavaHelper#label().
  }
//⌃ enclosing_range_end scip-java maven . . snapshots/JavaHelper#
  
