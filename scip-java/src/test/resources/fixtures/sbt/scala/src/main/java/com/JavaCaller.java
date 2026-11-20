package com;

/** Calls into Scala, so the index has to resolve a reference across the two languages. */
public class JavaCaller {
  public static String call() {
    return new Greeting("java").greet();
  }
}
