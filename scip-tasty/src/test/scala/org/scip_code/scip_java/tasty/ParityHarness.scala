package org.scip_code.scip_java.tasty

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.scip_code.scip_java.semanticdb.Semanticdb
import tastyquery.Classpaths.Classpath
import tastyquery.Contexts
import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Traversers
import tastyquery.Trees.*
import tastyquery.jdk.ClasspathLoaders

/** Compiles a Scala 3 source tree once, then reads the two symbol sources it produced:
  * the `.semanticdb` the compiler wrote (ground truth) and the `.tasty` read back through
  * tasty-query (what the new front-end emits).
  */
object ParityHarness {

  final case class Compiled(classes: Path, semanticdb: Path, classpath: List[Path])

  /** Runs `dotc -Xsemanticdb` over `sources`, into a fresh output directory. */
  def compile(sources: List[Path], sourceroot: Path, into: Path): Compiled = {
    val classes: Path = Files.createDirectories(into.resolve("classes"))
    val semanticdbTarget: Path = Files.createDirectories(into.resolve("semanticdb"))
    val classpath: List[Path] = scalaLibraryClasspath
    val args: List[String] = List(
      "-d",
      classes.toString,
      "-classpath",
      classpath.mkString(java.io.File.pathSeparator),
      "-Xsemanticdb",
      "-semanticdb-target",
      semanticdbTarget.toString,
      "-sourceroot",
      sourceroot.toString,
      "-usejavacp",
    ) ++ sources.map(_.toString)

    val reporter = dotty.tools.dotc.Main.process(args.toArray)
    if (reporter.hasErrors) {
      throw new AssertionError(
        s"the fixture failed to compile: ${reporter.allErrors.mkString("\n")}"
      )
    }
    Compiled(classes, semanticdbTarget, classpath)
  }

  /** Every global symbol the compiler recorded, as bare descriptor strings.
    *
    * Locals (`local3`) are dropped: dotty numbers them by `(definition offset, name)` in
    * traversal order, a document-scoped scheme the TASTy side neither can nor needs to
    * reproduce.
    */
  def semanticdbSymbols(root: Path): Set[String] = {
    val builder = Set.newBuilder[String]
    eachSemanticdbDocument(root) { doc =>
      doc.getSymbolsList.asScala.foreach { info =>
        if (isGlobalSymbol(info.getSymbol)) builder += info.getSymbol
      }
    }
    builder.result()
  }

  /** Symbols the compiler recorded as *references*, keeping only those matching `prefix`.
    *
    * References are the only place Java-owned symbols appear in a Scala document, so this
    * is how the Java side of the disambiguator gets measured.
    */
  def semanticdbReferences(root: Path, prefix: String): Set[String] = {
    val builder = Set.newBuilder[String]
    eachSemanticdbDocument(root) { doc =>
      doc.getOccurrencesList.asScala.foreach { occ =>
        val sym: String = occ.getSymbol
        val isReference: Boolean =
          occ.getRole == Semanticdb.SymbolOccurrence.Role.REFERENCE
        if (isReference && isGlobalSymbol(sym) && sym.startsWith(prefix)) builder += sym
      }
    }
    builder.result()
  }

  /** Every global symbol reachable from the compiled classes, minted from TASTy.
    *
    * Walks the pickled *trees* rather than `declarations`, matching how the real indexer
    * works and avoiding the hash-backed ordering of `declarations`. Note this does not by
    * itself reproduce the compiler's symbol set: the generated members it omits (`equals`,
    * `hashCode`, `product*`, `writeReplace`, `fromProduct`, `MirroredMonoType`) are tree
    * nodes in the pickled `Template` body too. See [[TastyExclusions]] for what does and
    * does not separate them.
    */
  def tastySymbols(compiled: Compiled): Set[String] = {
    val entries: Classpath = readClasspath(compiled)
    given Context = Contexts.Context.initialize(entries)
    given JavaOrderLookup = JavaOrderLookup(Some(new JavaMethodOrder(entries)))
    val builder = Set.newBuilder[String]

    def add(sym: Symbol): Unit = TastySymbols.globalSymbol(sym).foreach(builder += _)

    def addParams(sym: TermSymbol): Unit =
      sym.paramSymss.foreach {
        case Left(termParams) => termParams.foreach(add)
        case Right(typeParams) =>
          if (!TastyExclusions.skipTypeParams(sym)) typeParams.foreach(add)
      }

    val collector = new Traversers.TreeTraverser {
      override def traverse(tree: Tree): Unit = {
        tree match {
          case cd: ClassDef =>
            add(cd.symbol)
            cd.symbol.typeParams.foreach(add)
          case dd: DefDef =>
            add(dd.symbol)
            addParams(dd.symbol)
          case vd: ValDef     => add(vd.symbol)
          case tm: TypeMember => add(tm.symbol)
          case _              => ()
        }
        super.traverse(tree)
      }
    }

    eachTopLevelClass(entries) { cls => cls.tree.foreach(collector.traverse) }
    builder.result()
  }

  /** Symbols the TASTy side resolves references to, keeping only those matching `prefix`. */
  def tastyReferences(compiled: Compiled, prefix: String): Set[String] = {
    val entries: Classpath = readClasspath(compiled)
    given Context = Contexts.Context.initialize(entries)
    given JavaOrderLookup = JavaOrderLookup(Some(new JavaMethodOrder(entries)))
    val builder = Set.newBuilder[String]

    def keep(sym: Symbol): Unit =
      TastySymbols.globalSymbol(sym).filter(_.startsWith(prefix)).foreach(builder += _)

    val collector = new Traversers.TreeTraverser {
      override def traverse(tree: Tree): Unit = {
        tree match {
          case ref: TermReferenceTree =>
            scala.util.Try(ref.symbol).toOption.foreach(keep)
          case tt: TypeTree =>
            scala.util.Try(tt.toType).toOption.foreach {
              case tr: tastyquery.Types.TypeRef => tr.optSymbol.foreach(keep)
              case _                            => ()
            }
          case _ => ()
        }
        super.traverse(tree)
      }
    }

    eachTopLevelClass(entries) { cls => cls.tree.foreach(collector.traverse) }
    builder.result()
  }

  /** Of `wanted`, those whose defining tree would claim a definition occurrence.
    *
    * A symbol the compiler's SemanticDB omits is harmless as long as it is not
    * source-positioned, because then [[TastyExclusions.excludeDefinition]] gives it no
    * occurrence and it contributes metadata only.
    */
  def sourcePositioned(compiled: Compiled, wanted: Set[String]): List[String] = {
    val entries: Classpath = readClasspath(compiled)
    given Context = Contexts.Context.initialize(entries)
    given JavaOrderLookup = JavaOrderLookup(Some(new JavaMethodOrder(entries)))
    val out = List.newBuilder[String]

    def check(sym: Symbol): Unit =
      TastySymbols.globalSymbol(sym).filter(wanted).foreach { name =>
        sym match {
          case t: TermOrTypeSymbol =>
            t.tree.foreach { tree =>
              if (!TastyExclusions.excludeDefinition(tree)) out += name
            }
          case _ => ()
        }
      }

    def visit(sym: Symbol): Unit = {
      check(sym)
      sym match {
        case cls: ClassSymbol =>
          cls.typeParams.foreach(visit)
          cls.declarations.foreach(visit)
        case term: TermSymbol if term.isMethod =>
          term.paramSymss.foreach {
            case Left(ps) => ps.foreach(visit)
            case Right(ps) =>
              if (!TastyExclusions.skipTypeParams(term)) ps.foreach(visit)
          }
        case _ => ()
      }
    }

    eachTopLevelClass(entries)(visit)
    out.result().sorted.distinct
  }

  /** tasty-query requires the JRE to be put on the classpath explicitly, one entry per
    * module -- matching every module `SbtBuildTool.jdkClasspath` puts on the real classpath,
    * not just `java.base`.
    */
  def jdkPaths: List[Path] = {
    val modulesRoot =
      java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/")).getPath("modules")
    Using.resource(Files.list(modulesRoot)) { stream =>
      stream.iterator().asScala.filter(Files.isDirectory(_)).toList
    }
  }

  private def readClasspath(compiled: Compiled): Classpath =
    ClasspathLoaders.read((compiled.classes :: compiled.classpath) ++ jdkPaths)

  /** The project's own entry is the first one, matching the order `read` was given. */
  private def eachTopLevelClass(entries: Classpath)(f: Symbol => Unit)(using Context): Unit =
    summon[Context].findSymbolsByClasspathEntry(entries.head).foreach {
      case cls: ClassSymbol => f(cls)
      case _                => ()
    }

  private def eachSemanticdbDocument(root: Path)(f: Semanticdb.TextDocument => Unit): Unit =
    Using.resource(Files.walk(root)) { stream =>
      stream
        .iterator()
        .asScala
        .filter(p => p.getFileName.toString.endsWith(".semanticdb"))
        .foreach { file =>
          Semanticdb.TextDocuments
            .parseFrom(Files.readAllBytes(file))
            .getDocumentsList
            .asScala
            .foreach(f)
        }
    }

  private def isGlobalSymbol(sym: String): Boolean =
    sym.nonEmpty && !sym.startsWith("local") && !sym.contains("<?>")

  private def scalaLibraryClasspath: List[Path] =
    System
      .getProperty("java.class.path")
      .split(java.io.File.pathSeparator)
      .filter(e => e.contains("scala3-library") || e.contains("scala-library"))
      .map(Paths.get(_))
      .toList
}

/** Occurrence-level comparison: symbol *and* range, which is what navigation actually uses.
  *
  * Symbol parity alone does not prove the index is usable -- a correct symbol at the wrong
  * range is still a broken "go to definition". This is also where the position handling gets
  * exercised: name ranges derived from `pointOffset`, and the per-file filter that keeps
  * occurrences from `Inlined` bodies out of the wrong document.
  */
object OccurrenceParity {

  /** `(symbol, startLine, startChar, endLine, endChar)`, global symbols only. */
  type Key = (String, Int, Int, Int, Int)

  def fromSemanticdb(root: Path): Set[Key] = {
    val builder = Set.newBuilder[Key]
    Using.resource(Files.walk(root)) { stream =>
      stream
        .iterator()
        .asScala
        .filter(_.getFileName.toString.endsWith(".semanticdb"))
        .foreach { file =>
          val docs = Semanticdb.TextDocuments.parseFrom(Files.readAllBytes(file))
          docs.getDocumentsList.asScala.foreach { doc =>
            doc.getOccurrencesList.asScala.foreach { occ =>
              if (isGlobal(occ.getSymbol)) {
                val r = occ.getRange
                builder +=
                  ((occ.getSymbol, r.getStartLine, r.getStartCharacter, r.getEndLine, r.getEndCharacter))
              }
            }
          }
        }
    }
    builder.result()
  }

  /** Reads back the `.scip` shards `TastyToScip` wrote under `targetRoot`. */
  def fromShards(targetRoot: Path): Set[Key] = {
    val builder = Set.newBuilder[Key]
    Using.resource(Files.walk(targetRoot)) { stream =>
      stream
        .iterator()
        .asScala
        .filter(_.getFileName.toString.endsWith(".scip"))
        .foreach { file =>
          val index = org.scip_code.scip.Index.parseFrom(Files.readAllBytes(file))
          index.getDocumentsList.asScala.foreach { doc =>
            doc.getOccurrencesList.asScala.foreach { occ =>
              if (isGlobal(occ.getSymbol)) {
                val r = org.scip_code.scip_java.shared.ScipRange.from(occ)
                builder += ((occ.getSymbol, r.startLine, r.startCharacter, r.endLine, r.endCharacter))
              }
            }
          }
        }
    }
    builder.result()
  }

  private def isGlobal(sym: String): Boolean =
    sym.nonEmpty && !sym.startsWith("local") && !sym.contains("<?>")
}
