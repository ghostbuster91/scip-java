package com.sourcegraph.scip_java

import scala.jdk.CollectionConverters._

import com.sourcegraph.scip_java.commands.CommentSyntax
import com.sourcegraph.scip_java.commands.SnapshotLsifCommand
import com.sourcegraph.scip_semanticdb.ScipSemanticdb
import com.sourcegraph.scip_semanticdb.ScipTextDocument
import com.sourcegraph.scip_semanticdb.SignatureFormatter
import com.sourcegraph.scip_semanticdb.Symtab
import com.sourcegraph.semanticdb_javac.Semanticdb.SymbolOccurrence
import com.sourcegraph.semanticdb_javac.Semanticdb.TextDocument

object SemanticdbPrinters {
  def printTextDocument(
      doc: TextDocument,
      comments: CommentSyntax = CommentSyntax.default
  ): String = {
    val occurrencesByLine = ScipTextDocument
      .sortedSymbolOccurrences(doc)
      .asScala
      .groupBy(_.getRange.getStartLine)
    val out = new StringBuilder()
    val symtab = new Symtab(doc)
    val extension = doc.getUri.split("\\.").lastOption.getOrElse("")
    val commentSyntax = comments.extensionSyntax(extension)
    doc
      .getText
      .linesWithSeparators
      .zipWithIndex
      .foreach { case (line, i) =>
        out.append(line.replace("\t", "→"))
        val occurrences = occurrencesByLine
          .getOrElse(i, Nil)
          .sortBy(o =>
            (
              o.getRange.getStartCharacter,
              o.getRange.getEndLine,
              o.getRange.getEndCharacter
            )
          )
        occurrences.foreach { occ =>
          formatOccurrence(out, occ, line, symtab, commentSyntax)
        }
      }
    out.toString()
  }

  private def formatOccurrence(
      out: StringBuilder,
      occ: SymbolOccurrence,
      line: String,
      symtab: Symtab,
      commentSyntax: String
  ): Unit = {
    val r = occ.getRange
    val isMultiline = r.getStartLine != r.getEndLine
    val width =
      if (isMultiline) {
        line.length - r.getStartCharacter - 1
      } else {
        r.getEndCharacter - r.getStartCharacter
      }
    out
      .append(
        if (r.getStartCharacter > commentSyntax.length)
          s"$commentSyntax " +
            " " *
            (r.getStartCharacter - commentSyntax.length - 1)
        else
          commentSyntax
      )
      .append(
        if (r.getStartCharacter == 1)
          "^" * (width - 1)
        else
          "^" * width
      )
      .append(" ")
      .append(occ.getRole.toString.toLowerCase)
      .append(" ")
      .append(occ.getSymbol)
      .append(
        if (isMultiline)
          s" ${r.getEndLine - r.getStartLine}:${r.getEndCharacter}"
        else
          ""
      )
      .append(
        symtab.symbols.asScala.get(occ.getSymbol) match {
          case Some(info) if ScipSemanticdb.isDefinitionRole(occ.getRole) =>
            val signature: String =
              if (info.hasSignature) {
                new SignatureFormatter(info, symtab)
                  .formatSymbol()
                  .trim
                  .replace('\n', ' ')
              } else if (info.hasDocumentation) {
                SnapshotLsifCommand
                  .signatureLines(info.getDocumentation.getMessage)
                  .mkString(" ")
              } else {
                ""
              }
            if (signature.isEmpty)
              " " + info.getDisplayName
            else
              " " + signature
          case _ =>
            ""
        }
      )
    while (out.last == ' ') { // Trim trailing whitespace
      out.setLength(out.length() - 1)
    }
    out.append("\n")
  }

}
