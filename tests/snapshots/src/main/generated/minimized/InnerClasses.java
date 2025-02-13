package minimized;

public class InnerClasses {
//           ^^^^^^^^^^^^ definition minimized/InnerClasses# public class InnerClasses

  private final int exampleField;
//                  ^^^^^^^^^^^^ definition minimized/InnerClasses#exampleField. private final int exampleField

  private static final String STRING = "asdf";
//                     ^^^^^^ reference java/lang/String#
//                            ^^^^^^ definition minimized/InnerClasses#STRING. private static final String STRING

  private static final int top = 5;
//                         ^^^ definition minimized/InnerClasses#top. private static final int top
  private static final int bottom = 10;
//                         ^^^^^^ definition minimized/InnerClasses#bottom. private static final int bottom

  public InnerClasses(int exampleField) {
//       ^^^^^^^^^^^^ definition minimized/InnerClasses#`<init>`(). public InnerClasses(int exampleField)
//                        ^^^^^^^^^^^^ definition local0 int exampleField
    this.exampleField = exampleField;
//       ^^^^^^^^^^^^ reference minimized/InnerClasses#exampleField.
//                      ^^^^^^^^^^^^ reference local0
  }

  public enum InnerEnum {
//            ^^^^^^^^^ definition minimized/InnerClasses#InnerEnum# public enum InnerEnum
//            ^^^^^^^^^ definition minimized/InnerClasses#InnerEnum#`<init>`(). private InnerEnum()
    A,
//  ^ definition minimized/InnerClasses#InnerEnum#A. InnerEnum.A /* ordinal 0 */
    B,
//  ^ definition minimized/InnerClasses#InnerEnum#B. InnerEnum.B /* ordinal 1 */
    C
//  ^ definition minimized/InnerClasses#InnerEnum#C. InnerEnum.C /* ordinal 2 */
  }

  public interface InnerInterface<A, B> {
//                 ^^^^^^^^^^^^^^ definition minimized/InnerClasses#InnerInterface# public interface InnerInterface<A, B>
//                                ^ definition minimized/InnerClasses#InnerInterface#[A] A
//                                   ^ definition minimized/InnerClasses#InnerInterface#[B] B
    B apply(A a);
//  ^ reference minimized/InnerClasses#InnerInterface#[B]
//    ^^^^^ definition minimized/InnerClasses#InnerInterface#apply(). public abstract B apply(A a)
//          ^ reference minimized/InnerClasses#InnerInterface#[A]
//            ^ definition local1 A a
  }

  public @interface InnerAnnotation {
//                  ^^^^^^^^^^^^^^^ definition minimized/InnerClasses#InnerAnnotation# public @interface InnerAnnotation
    int value();
//      ^^^^^ definition minimized/InnerClasses#InnerAnnotation#value(). public abstract int value()
  }

  @SuppressWarnings(STRING + " ")
// ^^^^^^^^^^^^^^^^ reference java/lang/SuppressWarnings#
//                  ^^^^^^ reference minimized/InnerClasses#STRING.
  @InnerAnnotation(top / bottom)
// ^^^^^^^^^^^^^^^ reference minimized/InnerClasses#InnerAnnotation#
//                 ^^^ reference minimized/InnerClasses#top.
//                       ^^^^^^ reference minimized/InnerClasses#bottom.
  public static class InnerStaticClass {
//                    ^^^^^^^^^^^^^^^^ definition minimized/InnerClasses#InnerStaticClass# @SuppressWarnings(STRING + " ") @InnerAnnotation(top / bottom) public static class InnerStaticClass
//                    ^^^^^^^^^^^^^^^^ definition minimized/InnerClasses#InnerStaticClass#`<init>`(). public InnerStaticClass()

    public static void innerStaticMethod() {}
//                     ^^^^^^^^^^^^^^^^^ definition minimized/InnerClasses#InnerStaticClass#innerStaticMethod(). public static void innerStaticMethod()
  }

  public class InnerClass implements InnerInterface<Integer, Integer> {
//             ^^^^^^^^^^ definition minimized/InnerClasses#InnerClass# public class InnerClass
//                                   ^^^^^^^^^^^^^^ reference minimized/InnerClasses#InnerInterface#
//                                                  ^^^^^^^ reference java/lang/Integer#
//                                                           ^^^^^^^ reference java/lang/Integer#
    private final int field;
//                    ^^^^^ definition minimized/InnerClasses#InnerClass#field. private final int field

    public InnerClass(int field) {
//         ^^^^^^^^^^ definition minimized/InnerClasses#InnerClass#`<init>`(). public InnerClass(int field)
//                        ^^^^^ definition local2 int field
      this.field = field;
//         ^^^^^ reference minimized/InnerClasses#InnerClass#field.
//                 ^^^^^ reference local2
    }

    public void innerMethod() {
//              ^^^^^^^^^^^ definition minimized/InnerClasses#InnerClass#innerMethod(). public void innerMethod()
      System.out.println(field + exampleField);
//    ^^^^^^ reference java/lang/System#
//           ^^^ reference java/lang/System#out.
//               ^^^^^^^ reference java/io/PrintStream#println(+3).
//                       ^^^^^ reference minimized/InnerClasses#InnerClass#field.
//                               ^^^^^^^^^^^^ reference minimized/InnerClasses#exampleField.
    }

    @Override
//   ^^^^^^^^ reference java/lang/Override#
    public Integer apply(Integer integer) {
//         ^^^^^^^ reference java/lang/Integer#
//                 ^^^^^ definition minimized/InnerClasses#InnerClass#apply(). @Override public Integer apply(Integer integer)
//                       ^^^^^^^ reference java/lang/Integer#
//                               ^^^^^^^ definition local3 Integer integer
      return field * integer;
//           ^^^^^ reference minimized/InnerClasses#InnerClass#field.
//                   ^^^^^^^ reference local3
    }
  }

  private static <A, B> B runInnerInterface(InnerInterface<A, B> fn, A a) {
//                ^ definition minimized/InnerClasses#runInnerInterface().[A] A
//                   ^ definition minimized/InnerClasses#runInnerInterface().[B] B
//                      ^ reference minimized/InnerClasses#runInnerInterface().[B]
//                        ^^^^^^^^^^^^^^^^^ definition minimized/InnerClasses#runInnerInterface(). private static <A, B> B runInnerInterface(InnerInterface<A, B> fn, A a)
//                                          ^^^^^^^^^^^^^^ reference minimized/InnerClasses#InnerInterface#
//                                                         ^ reference minimized/InnerClasses#runInnerInterface().[A]
//                                                            ^ reference minimized/InnerClasses#runInnerInterface().[B]
//                                                               ^^ definition local4 InnerInterface<A, B> fn
//                                                                   ^ reference minimized/InnerClasses#runInnerInterface().[A]
//                                                                     ^ definition local5 A a
    return fn.apply(a);
//         ^^ reference local4
//            ^^^^^ reference minimized/InnerClasses#InnerInterface#apply().
//                  ^ reference local5
  }

  public static void testEnum(InnerEnum magicEnum) {
//                   ^^^^^^^^ definition minimized/InnerClasses#testEnum(). public static void testEnum(InnerEnum magicEnum)
//                            ^^^^^^^^^ reference minimized/InnerClasses#InnerEnum#`<init>`().
//                            ^^^^^^^^^ reference minimized/InnerClasses#InnerEnum#`<init>`().
//                            ^^^^^^^^^ reference minimized/InnerClasses#InnerEnum#`<init>`().
//                            ^^^^^^^^^ reference minimized/InnerClasses#InnerEnum#
//                                      ^^^^^^^^^ definition local6 InnerEnum magicEnum
    if (System.nanoTime() > System.currentTimeMillis()) {
//      ^^^^^^ reference java/lang/System#
//             ^^^^^^^^ reference java/lang/System#nanoTime().
//                          ^^^^^^ reference java/lang/System#
//                                 ^^^^^^^^^^^^^^^^^ reference java/lang/System#currentTimeMillis().
      magicEnum = InnerEnum.B;
//    ^^^^^^^^^ reference local6
//                ^^^^^^^^^ reference minimized/InnerClasses#InnerEnum#
//                          ^ reference minimized/InnerClasses#InnerEnum#B.
    }
    switch (magicEnum) {
//          ^^^^^^^^^ reference local6
      case B:
//         ^ reference minimized/InnerClasses#InnerEnum#B.
        System.out.println("b");
//      ^^^^^^ reference java/lang/System#
//             ^^^ reference java/lang/System#out.
//                 ^^^^^^^ reference java/io/PrintStream#println(+8).
        break;
      case A:
//         ^ reference minimized/InnerClasses#InnerEnum#A.
        System.out.println("a");
//      ^^^^^^ reference java/lang/System#
//             ^^^ reference java/lang/System#out.
//                 ^^^^^^^ reference java/io/PrintStream#println(+8).
        break;
      default:
        break;
    }
    if (magicEnum == InnerEnum.A) System.out.println("a");
//      ^^^^^^^^^ reference local6
//                   ^^^^^^^^^ reference minimized/InnerClasses#InnerEnum#
//                             ^ reference minimized/InnerClasses#InnerEnum#A.
//                                ^^^^^^ reference java/lang/System#
//                                       ^^^ reference java/lang/System#out.
//                                           ^^^^^^^ reference java/io/PrintStream#println(+8).
    else if (magicEnum == InnerEnum.C) System.out.println("b");
//           ^^^^^^^^^ reference local6
//                        ^^^^^^^^^ reference minimized/InnerClasses#InnerEnum#
//                                  ^ reference minimized/InnerClasses#InnerEnum#C.
//                                     ^^^^^^ reference java/lang/System#
//                                            ^^^ reference java/lang/System#out.
//                                                ^^^^^^^ reference java/io/PrintStream#println(+8).
    else System.out.println("c");
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+8).
  }

  public static void testAnon() {
//                   ^^^^^^^^ definition minimized/InnerClasses#testAnon(). public static void testAnon()
    InnerInterface<String, String> fn =
//  ^^^^^^^^^^^^^^ reference minimized/InnerClasses#InnerInterface#
//                 ^^^^^^ reference java/lang/String#
//                         ^^^^^^ reference java/lang/String#
//                                 ^^ definition local7 InnerInterface<String, String> fn
        new InnerInterface<String, String>() {
//          ^^^^^^^^^^^^^^ reference minimized/InnerClasses#InnerInterface#
//                         ^^^^^^ reference java/lang/String#
//                                 ^^^^^^ reference java/lang/String#
          @Override
//         ^^^^^^^^ reference java/lang/Override#
          public String apply(String s) {
//               ^^^^^^ reference java/lang/String#
//                      ^^^^^ definition local10 @Override public String apply(String s)
//                            ^^^^^^ reference java/lang/String#
//                                   ^ definition local11 String s
            return s + "b";
//                 ^ reference local11
          }
        };
    System.out.println(fn.apply("a"));
//  ^^^^^^ reference java/lang/System#
//         ^^^ reference java/lang/System#out.
//             ^^^^^^^ reference java/io/PrintStream#println(+8).
//                     ^^ reference local7
//                        ^^^^^ reference minimized/InnerClasses#InnerInterface#apply().
  }

  public static String app() {
//              ^^^^^^ reference java/lang/String#
//                     ^^^ definition minimized/InnerClasses#app(). public static String app()
    int a = 42;
//      ^ definition local12 int a
    InnerStaticClass.innerStaticMethod();
//  ^^^^^^^^^^^^^^^^ reference minimized/InnerClasses#InnerStaticClass#
//                   ^^^^^^^^^^^^^^^^^ reference minimized/InnerClasses#InnerStaticClass#innerStaticMethod().
    InnerClasses innerClasses = new InnerClasses(a);
//  ^^^^^^^^^^^^ reference minimized/InnerClasses#
//               ^^^^^^^^^^^^ definition local13 InnerClasses innerClasses
//                                  ^^^^^^^^^^^^ reference minimized/InnerClasses#`<init>`().
//                                               ^ reference local12
    InnerClass innerClass = innerClasses.new InnerClass(a);
//  ^^^^^^^^^^ reference minimized/InnerClasses#InnerClass#
//             ^^^^^^^^^^ definition local14 InnerClass innerClass
//                                           ^^^^^^^^^^ reference minimized/InnerClasses#InnerClass#`<init>`().
//                                                      ^ reference local12
    innerClass.innerMethod();
//  ^^^^^^^^^^ reference local14
//             ^^^^^^^^^^^ reference minimized/InnerClasses#InnerClass#innerMethod().
    System.out.println(runInnerInterface(innerClass, a));
//  ^^^^^^ reference java/lang/System#
//         ^^^ reference java/lang/System#out.
//             ^^^^^^^ reference java/io/PrintStream#println(+9).
//                     ^^^^^^^^^^^^^^^^^ reference minimized/InnerClasses#runInnerInterface().
//                                       ^^^^^^^^^^ reference local14
//                                                   ^ reference local12
    testEnum(InnerEnum.A);
//  ^^^^^^^^ reference minimized/InnerClasses#testEnum().
//           ^^^^^^^^^ reference minimized/InnerClasses#InnerEnum#
//                     ^ reference minimized/InnerClasses#InnerEnum#A.
    testAnon();
//  ^^^^^^^^ reference minimized/InnerClasses#testAnon().
    return "";
  }
}
