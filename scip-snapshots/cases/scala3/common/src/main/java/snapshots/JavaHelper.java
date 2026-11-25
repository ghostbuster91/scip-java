package snapshots;

/** Java helper referenced from Scala 3 to verify cross-language symbol parity in snapshots. */
public class JavaHelper {

  public static String format(int value) {
    return Integer.toString(value);
  }

  public static String format(int value, String prefix) {
    return prefix + value;
  }

  public String label(String name) {
    return "[" + name + "]";
  }
}
