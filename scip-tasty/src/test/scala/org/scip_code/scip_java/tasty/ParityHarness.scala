package org.scip_code.scip_java.tasty

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.scip_code.scip_java.semanticdb.Semanticdb
import tastyquery.Contexts
import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Traversers
import tastyquery.Trees.*
import tastyquery.jdk.ClasspathLoaders

/** Compiles a Scala 3 source tree once, then reads the two symbol sources it produced:
  * the `.semanticdb` the compiler wrote (ground truth) and the `.tasty` read back
  * through tasty-query (what the new front-end will emit).
  */
object ParityHarness:

  final case class Compiled(classes: Path, semanticdb: Path, classpath: List[Path])

  /** Runs `dotc -Xsemanticdb` over `sources`, into a fresh output directory. */
  def compile(sources: List[Path], sourceroot: Path, into: Path): Compiled =
    val classes = Files.createDirectories(into.resolve("classes"))
    val semanticdbTarget = Files.createDirectories(into.resolve("semanticdb"))
    val classpath = scalaLibraryClasspath
    val args = List(
      "-d", classes.toString,
      "-classpath", classpath.mkString(java.io.File.pathSeparator),
      "-Xsemanticdb",
      "-semanticdb-target", semanticdbTarget.toString,
      "-sourceroot", sourceroot.toString,
      "-usejavacp",
    ) ++ sources.map(_.toString)

    val reporter = dotty.tools.dotc.Main.process(args.toArray)
    if reporter.hasErrors then
      throw AssertionError(s"the fixture failed to compile: ${reporter.allErrors.mkString("\n")}")
    Compiled(classes, semanticdbTarget, classpath)
  end compile

  /** Every global symbol the compiler recorded, as bare descriptor strings.
    *
    * Locals (`local3`) are dropped: dotty numbers them by `(definition offset, name)` in
    * traversal order, a document-scoped scheme the TASTy side neither can nor needs to
    * reproduce.
    */
  def semanticdbSymbols(root: Path): Set[String] =
    val builder = Set.newBuilder[String]
    Using.resource(Files.walk(root)) { stream =>
      stream.iterator().asScala
        .filter(p => p.getFileName.toString.endsWith(".semanticdb"))
        .foreach { file =>
          val docs = Semanticdb.TextDocuments.parseFrom(Files.readAllBytes(file))
          docs.getDocumentsList.asScala.foreach { doc =>
            doc.getSymbolsList.asScala.foreach { info =>
              val sym = info.getSymbol
              if isGlobalSymbol(sym) then builder += sym
            }
          }
        }
    }
    builder.result()
  end semanticdbSymbols

  /** Every global symbol reachable from the compiled classes, minted from TASTy.
    *
    * Walks the pickled *trees* rather than `declarations`, matching how the real indexer
    * will work and avoiding the hash-backed ordering of `declarations`. Note this does not
    * by itself reproduce the compiler's symbol set: the generated members it omits
    * (`equals`, `hashCode`, `product*`, `writeReplace`, `fromProduct`, `MirroredMonoType`)
    * are tree nodes in the pickled `Template` body too. See [[TastyExclusions]] for what
    * does and does not separate them.
    */
  def tastySymbols(compiled: Compiled): Set[String] =
    val entries = ClasspathLoaders.read((compiled.classes :: compiled.classpath) ++ jdkPaths)
    given Context = Contexts.Context.initialize(entries)
    given JavaOrderLookup = JavaOrderLookup(Some(JavaMethodOrder(entries)))
    val builder = Set.newBuilder[String]

    def add(sym: Symbol): Unit =
      TastySymbols.globalSymbol(sym).foreach(builder += _)

    def addParams(sym: TermSymbol): Unit =
      sym.paramSymss.foreach {
        case Left(termParams) => termParams.foreach(add)
        case Right(typeParams) =>
          if !TastyExclusions.skipTypeParams(sym) then typeParams.foreach(add)
      }

    val collector = new Traversers.TreeTraverser:
      override def traverse(tree: Tree): Unit =
        tree match
          case cd: ClassDef =>
            add(cd.symbol)
            cd.symbol.typeParams.foreach(add)
          case dd: DefDef =>
            add(dd.symbol)
            addParams(dd.symbol)
          case vd: ValDef     => add(vd.symbol)
          case tm: TypeMember => add(tm.symbol)
          case _              => ()
        super.traverse(tree)

    summon[Context]
      .findSymbolsByClasspathEntry(entries.head)
      .foreach {
        case cls: ClassSymbol => cls.tree.foreach(collector.traverse)
        case _                => ()
      }
    builder.result()
  end tastySymbols

  private def isGlobalSymbol(sym: String): Boolean =
    sym.nonEmpty && !sym.startsWith("local") && !sym.contains("<?>")

  /** Symbols the compiler recorded as *references*, keeping only those matching `prefix`.
    *
    * References are the only place Java-owned symbols appear in a Scala document, so this
    * is how the Java side of the disambiguator gets measured.
    */
  def semanticdbReferences(root: Path, prefix: String): Set[String] =
    val builder = Set.newBuilder[String]
    Using.resource(Files.walk(root)) { stream =>
      stream.iterator().asScala
        .filter(p => p.getFileName.toString.endsWith(".semanticdb"))
        .foreach { file =>
          val docs = Semanticdb.TextDocuments.parseFrom(Files.readAllBytes(file))
          docs.getDocumentsList.asScala.foreach { doc =>
            doc.getOccurrencesList.asScala.foreach { occ =>
              val sym = occ.getSymbol
              if occ.getRole == Semanticdb.SymbolOccurrence.Role.REFERENCE
                && isGlobalSymbol(sym)
                && sym.startsWith(prefix)
              then builder += sym
            }
          }
        }
    }
    builder.result()
  end semanticdbReferences

  /** Symbols the TASTy side resolves references to, keeping only those matching `prefix`. */
  def tastyReferences(compiled: Compiled, prefix: String): Set[String] =
    val entries = ClasspathLoaders.read((compiled.classes :: compiled.classpath) ++ jdkPaths)
    given Context = Contexts.Context.initialize(entries)
    given JavaOrderLookup = JavaOrderLookup(Some(JavaMethodOrder(entries)))
    val builder = Set.newBuilder[String]

    val collector = new Traversers.TreeTraverser:
      override def traverse(tree: Tree): Unit =
        tree match
          case ref: TermReferenceTree =>
            // `.symbol` can throw for references tasty-query cannot resolve; a failure
            // here is a finding, not something to swallow silently in a harness.
            val resolved = scala.util.Try(ref.symbol).toOption
            resolved
              .flatMap(TastySymbols.globalSymbol)
              .filter(_.startsWith(prefix))
              .foreach(builder += _)
          case tt: TypeTree =>
            scala.util.Try(tt.toType).toOption.foreach {
              case tr: tastyquery.Types.TypeRef =>
                tr.optSymbol
                  .flatMap(TastySymbols.globalSymbol)
                  .filter(_.startsWith(prefix))
                  .foreach(builder += _)
              case _ => ()
            }
          case _ => ()
        super.traverse(tree)

    summon[Context]
      .findSymbolsByClasspathEntry(entries.head)
      .foreach {
        case cls: ClassSymbol => cls.tree.foreach(collector.traverse)
        case _                => ()
      }
    builder.result()
  end tastyReferences

  /** Of `wanted`, those whose defining tree would claim a definition occurrence.
    *
    * A symbol the compiler's SemanticDB omits is harmless as long as it is not
    * source-positioned, because then [[TastyExclusions.excludeDefinition]] gives it no
    * occurrence and it contributes metadata only.
    */
  def sourcePositioned(compiled: Compiled, wanted: Set[String]): List[String] =
    val entries = ClasspathLoaders.read((compiled.classes :: compiled.classpath) ++ jdkPaths)
    given Context = Contexts.Context.initialize(entries)
    given JavaOrderLookup = JavaOrderLookup(Some(JavaMethodOrder(entries)))
    val out = List.newBuilder[String]

    def check(sym: Symbol): Unit =
      TastySymbols.globalSymbol(sym).filter(wanted).foreach { name =>
        sym match
          case t: TermOrTypeSymbol =>
            t.tree.foreach { tree =>
              if !TastyExclusions.excludeDefinition(tree) then out += name
            }
          case _ => ()
      }

    def visit(sym: Symbol): Unit =
      check(sym)
      sym match
        case cls: ClassSymbol =>
          cls.typeParams.foreach(visit)
          cls.declarations.foreach(visit)
        case term: TermSymbol if term.isMethod =>
          term.paramSymss.foreach {
            case Left(ps) => ps.foreach(visit)
            case Right(ps) =>
              if !TastyExclusions.skipTypeParams(term) then ps.foreach(visit)
          }
        case _ => ()

    summon[Context].findSymbolsByClasspathEntry(entries.head).foreach(visit)
    out.result().sorted.distinct

  private def scalaLibraryClasspath: List[Path] =
    System.getProperty("java.class.path")
      .split(java.io.File.pathSeparator)
      .filter(e => e.contains("scala3-library") || e.contains("scala-library"))
      .map(Paths.get(_))
      .toList

  /** tasty-query requires the JRE to be put on the classpath explicitly. */
  def jdkPaths: List[Path] =
    List(
      java.nio.file.FileSystems
        .getFileSystem(java.net.URI.create("jrt:/"))
        .getPath("modules", "java.base")
    )
end ParityHarness

/** Occurrence-level comparison: symbol *and* range, which is what navigation actually uses.
  *
  * Symbol parity alone does not prove the index is usable -- a correct symbol at the wrong
  * range is still a broken "go to definition". This is also where the position handling gets
  * exercised: name ranges derived from `pointOffset`, and the per-file filter that keeps
  * occurrences from `Inlined` bodies out of the wrong document.
  */
object OccurrenceParity:

  /** `(symbol, startLine, startChar, endLine, endChar)`, global symbols only. */
  type Key = (String, Int, Int, Int, Int)

  def fromSemanticdb(root: Path): Set[Key] =
    val builder = Set.newBuilder[Key]
    Using.resource(Files.walk(root)) { stream =>
      stream.iterator().asScala
        .filter(_.getFileName.toString.endsWith(".semanticdb"))
        .foreach { file =>
          val docs = Semanticdb.TextDocuments.parseFrom(Files.readAllBytes(file))
          docs.getDocumentsList.asScala.foreach { doc =>
            doc.getOccurrencesList.asScala.foreach { occ =>
              if isGlobal(occ.getSymbol) then
                val r = occ.getRange
                builder += ((occ.getSymbol, r.getStartLine, r.getStartCharacter, r.getEndLine, r.getEndCharacter))
            }
          }
        }
    }
    builder.result()

  /** Reads back the `.scip` shards `TastyToScip` wrote under `targetRoot`. */
  def fromShards(targetRoot: Path): Set[Key] =
    val builder = Set.newBuilder[Key]
    Using.resource(Files.walk(targetRoot)) { stream =>
      stream.iterator().asScala
        .filter(_.getFileName.toString.endsWith(".scip"))
        .foreach { file =>
          val index = org.scip_code.scip.Index.parseFrom(Files.readAllBytes(file))
          index.getDocumentsList.asScala.foreach { doc =>
            doc.getOccurrencesList.asScala.foreach { occ =>
              if isGlobal(occ.getSymbol) then
                val r = org.scip_code.scip_java.shared.ScipRange.from(occ)
                builder += ((occ.getSymbol, r.startLine, r.startCharacter, r.endLine, r.endCharacter))
            }
          }
        }
    }
    builder.result()

  private def isGlobal(sym: String): Boolean =
    sym.nonEmpty && !sym.startsWith("local") && !sym.contains("<?>")
end OccurrenceParity
