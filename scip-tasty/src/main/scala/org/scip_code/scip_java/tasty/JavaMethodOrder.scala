package org.scip_code.scip_java.tasty

import scala.collection.mutable

import tastyquery.Classpaths.Classpath
import tastyquery.Contexts.Context
import tastyquery.Symbols.*

/** Declaration order of the methods in a Java classfile.
  *
  * Needed for the overload disambiguator. For a Scala owner the order comes from the class
  * body, which TASTy pickles in source order. A Java owner has no tree at all, and
  * `ClassSymbol.declarations` is backed by a `HashMap[Name, HashSet[Symbol]]`, so the only
  * surviving record of declaration order is the classfile's own `methods[]` table.
  *
  * This is the order dotty indexes on: it enters a Java class's members in `methods[]`
  * order, `Scope.lookupAll` returns them most-recent-first, and `addOverloadIdx` reverses
  * that back. Verified against `java.lang.Math.max`, which the compiler numbers `max()`,
  * `max(+1)`, `max(+3)` for `int`/`long`/`double` -- indices 0, 1 and 3 of the JDK's
  * declared order `(int, long, float, double)`.
  *
  * tasty-query exposes `ClassData.readClassFileBytes()` but offers no `ClassSymbol ->
  * ClassData` mapping, so the index is built here from the same `Classpath` instances that
  * were handed to `Context.initialize`.
  */
final class JavaMethodOrder(classpath: Classpath) {

  private val byBinaryName =
    mutable.HashMap.empty[String, Option[Vector[JavaMethodOrderMethod]]]

  /** Same-named methods of `cls`, in the order dotty numbers them.
    *
    * `None` when the class is not on the classpath or its classfile cannot be read, which
    * callers must treat as "order unknown" rather than "no overloads".
    */
  def sameNamed(cls: ClassSymbol, name: String, staticsFirst: Boolean)(using
    Context
  ): Option[Vector[JavaMethodOrderMethod]] =
    methods(cls).map { all =>
      val matching: Vector[JavaMethodOrderMethod] = all.filter(_.name == name)
      // dotty's `JavaModule` case concatenates the statics holder's declarations with the
      // companion class's, so a static is numbered ahead of an instance method of the same
      // name. Within each group the classfile order is preserved.
      if (staticsFirst) matching.filter(_.isStatic) ++ matching.filterNot(_.isStatic)
      else matching
    }

  private def methods(cls: ClassSymbol)(using Context): Option[Vector[JavaMethodOrderMethod]] =
    binaryName(cls).flatMap(n => byBinaryName.getOrElseUpdate(n, read(n)))

  /** `java.lang.Math` for the symbol of `java.lang.Math`, dropping any module-class `$`. */
  private def binaryName(cls: ClassSymbol)(using Context): Option[String] = {
    def ownerChain(sym: Symbol, acc: List[String]): Option[List[String]] =
      sym match {
        case null                                    => Some(acc)
        case pkg: PackageSymbol if pkg.owner == null => Some(acc)
        case pkg: PackageSymbol => ownerChain(pkg.owner, pkg.name.toString :: acc)
        case c: ClassSymbol     => ownerChain(c.owner, simpleName(c) :: acc)
        case _                  => None
      }
    ownerChain(cls.owner, Nil).map(prefix => (prefix :+ simpleName(cls)).mkString("."))
  }

  private def simpleName(cls: ClassSymbol): String = {
    val n: String = cls.name.toString
    if (n.endsWith("$")) n.dropRight(1) else n
  }

  private def read(binaryName: String): Option[Vector[JavaMethodOrderMethod]] = {
    val lastDot: Int = binaryName.lastIndexOf('.')
    val (pkg, simple) =
      if (lastDot < 0) ("", binaryName)
      else (binaryName.substring(0, lastDot), binaryName.substring(lastDot + 1))
    val found = classpath.iterator
      .flatMap(_.listAllPackages())
      .filter(_.dotSeparatedName == pkg)
      .flatMap(_.getClassDataByBinaryName(simple))
      .find(_.hasClassFile)
    found.flatMap(data => ClassfileMethods.parse(data.readClassFileBytes()))
  }
}

/** A deliberately minimal classfile reader: just enough to recover `methods[]` order.
  *
  * Only the constant pool (to resolve method names and descriptors) and the field/method
  * tables are walked; attributes are skipped by length. Any malformed input yields `None`,
  * so a jar this cannot parse costs disambiguator accuracy for that class rather than the
  * whole run.
  */
private object ClassfileMethods {

  def parse(bytes: IArray[Byte]): Option[Vector[JavaMethodOrderMethod]] =
    try Some(unsafeParse(bytes))
    catch {
      case _: RuntimeException | _: IndexOutOfBoundsException => None
    }

  private def unsafeParse(bytes: IArray[Byte]): Vector[JavaMethodOrderMethod] = {
    val in = new Reader(bytes)
    require(in.u4() == 0xcafebabeL, "not a classfile")
    in.skip(4) // minor, major
    val utf8: Map[Int, String] = readConstantPool(in)
    in.skip(2) // access_flags
    in.skip(2) // this_class
    in.skip(2) // super_class
    val interfaces: Int = in.u2()
    in.skip(2 * interfaces)
    skipMembers(in) // fields
    val methodCount: Int = in.u2()
    val out = Vector.newBuilder[JavaMethodOrderMethod]
    var i: Int = 0
    while (i < methodCount) {
      val access: Int = in.u2()
      val nameIndex: Int = in.u2()
      val descIndex: Int = in.u2()
      skipAttributes(in)
      val name: String =
        utf8.getOrElse(nameIndex, throw new RuntimeException(s"bad name index $nameIndex"))
      val desc: String =
        utf8.getOrElse(descIndex, throw new RuntimeException(s"bad descriptor index $descIndex"))
      out += JavaMethodOrderMethod(name, (access & 0x0008) != 0, JvmDescriptor.paramTypes(desc))
      i += 1
    }
    out.result()
  }

  private def readConstantPool(in: Reader): Map[Int, String] = {
    val count: Int = in.u2()
    val utf8 = Map.newBuilder[Int, String]
    var i: Int = 1
    while (i < count) {
      val tag: Int = in.u1()
      tag match {
        case 1 => // CONSTANT_Utf8
          val len: Int = in.u2()
          utf8 += (i -> in.utf8(len))
        case 7 | 8 | 16 | 19 | 20               => in.skip(2)
        case 15                                 => in.skip(3)
        case 3 | 4 | 9 | 10 | 11 | 12 | 17 | 18 => in.skip(4)
        case 5 | 6 => // Long and Double occupy two constant-pool slots
          in.skip(8)
          i += 1
        case other => throw new RuntimeException(s"unknown constant pool tag $other")
      }
      i += 1
    }
    utf8.result()
  }

  private def skipMembers(in: Reader): Unit = {
    val count: Int = in.u2()
    var i: Int = 0
    while (i < count) {
      in.skip(6) // access_flags, name_index, descriptor_index
      skipAttributes(in)
      i += 1
    }
  }

  private def skipAttributes(in: Reader): Unit = {
    val count: Int = in.u2()
    var i: Int = 0
    while (i < count) {
      in.skip(2) // attribute_name_index
      val len: Long = in.u4()
      in.skip(len.toInt)
      i += 1
    }
  }

  private final class Reader(bytes: IArray[Byte]) {
    private var pos: Int = 0
    def u1(): Int = { val v = bytes(pos) & 0xff; pos += 1; v }
    def u2(): Int = (u1() << 8) | u1()
    def u4(): Long = (u2().toLong << 16) | u2().toLong
    def skip(n: Int): Unit = pos += n
    def utf8(len: Int): String = {
      val arr = new Array[Byte](len)
      var i: Int = 0
      while (i < len) { arr(i) = bytes(pos + i); i += 1 }
      pos += len
      new String(arr, java.nio.charset.StandardCharsets.UTF_8)
    }
  }
}

/** One row of a classfile's `methods[]` table. Top-level so both [[JavaMethodOrder]] and
  * [[ClassfileMethods]] can name it.
  */
final case class JavaMethodOrderMethod(
  name: String,
  isStatic: Boolean,
  paramTypes: Vector[String],
)

/** Turns a JVM method descriptor into erased parameter type names.
  *
  * Spelled the way `tastyquery.Signatures.Signature` spells them -- `scala.Int`,
  * `java.lang.String`, `scala.Char[]` -- so a symbol's signature can be matched against a
  * classfile row without erasing types ourselves (`Erasure.eraseForSigName` is
  * `private[tastyquery]`).
  */
object JvmDescriptor {

  /** `(IJ)D` -> `Vector("scala.Int", "scala.Long")`. */
  def paramTypes(descriptor: String): Vector[String] = {
    val close: Int = descriptor.indexOf(')')
    if (!descriptor.startsWith("(") || close < 0) Vector.empty
    else {
      val out = Vector.newBuilder[String]
      var i: Int = 1
      while (i < close) {
        val (tpe, next) = readOne(descriptor, i)
        out += tpe
        i = next
      }
      out.result()
    }
  }

  private def readOne(s: String, start: Int): (String, Int) =
    s.charAt(start) match {
      case '[' =>
        val (base, next) = readOne(s, start + 1)
        (base + "[]", next)
      case 'L' =>
        val end: Int = s.indexOf(';', start)
        (s.substring(start + 1, end).replace('/', '.'), end + 1)
      case prim => (primitive(prim), start + 1)
    }

  /** Primitives carry their *Scala* names.
    *
    * `tastyquery.Signatures.Signature` erases `int` to `scala.Int`, not `int` -- verified
    * against `java.lang.Math.max`, whose four signatures read `(scala.Int,scala.Int)`,
    * `(scala.Long,scala.Long)` and so on. Spelling these the JVM way silently fails every
    * match and collapses the overloads to `()`.
    */
  private def primitive(c: Char): String = c match {
    case 'B'   => "scala.Byte"
    case 'C'   => "scala.Char"
    case 'D'   => "scala.Double"
    case 'F'   => "scala.Float"
    case 'I'   => "scala.Int"
    case 'J'   => "scala.Long"
    case 'S'   => "scala.Short"
    case 'Z'   => "scala.Boolean"
    case 'V'   => "scala.Unit"
    case other => other.toString
  }
}

/** Whether classfile declaration order is available to the disambiguator.
  *
  * Passed explicitly rather than defaulted: a silently-absent lookup degrades every Java
  * overload to `()`, so it must be a visible choice at the call site.
  */
final case class JavaOrderLookup(order: Option[JavaMethodOrder])
