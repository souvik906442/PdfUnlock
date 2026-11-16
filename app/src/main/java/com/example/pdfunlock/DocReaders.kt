package com.example.pdfunlock

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal enum class Kind { Pdf, Text, Word, Unsupported }

internal data class DocRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
)

internal sealed interface DocBlock
internal data class DocPara(val runs: List<DocRun>, val heading: Int, val bullet: Boolean) : DocBlock
internal data class DocTable(val rows: List<List<String>>) : DocBlock

private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/** Decides how to open a file from its extension first, then from its MIME type. */
internal fun detectKind(cr: ContentResolver, uri: Uri, name: String?): Kind {
    val ext = name?.substringAfterLast('.', "")?.lowercase() ?: ""
    when (ext) {
        "pdf" -> return Kind.Pdf
        "txt", "log", "md", "csv", "json", "xml", "ini", "conf", "text" -> return Kind.Text
        "docx" -> return Kind.Word
        "doc", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf" -> return Kind.Unsupported
    }
    val mime = try { cr.getType(uri) } catch (_: Exception) { null }
    return when {
        mime == null -> Kind.Pdf
        mime == "application/pdf" -> Kind.Pdf
        mime.startsWith("text/") -> Kind.Text
        mime == DOCX_MIME -> Kind.Word
        mime.startsWith("application/vnd.") || mime == "application/msword" -> Kind.Unsupported
        else -> Kind.Pdf
    }
}

/** Reads a text file (first 8 MB) into lines. */
internal fun readTextLines(input: InputStream): List<String> {
    val max = 8 * 1024 * 1024
    val out = ByteArrayOutputStream()
    val buf = ByteArray(65536)
    var total = 0
    while (total < max) {
        val n = input.read(buf, 0, minOf(buf.size, max - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    val truncated = total >= max && input.read() >= 0
    var text = out.toString("UTF-8")
    if (text.startsWith("\uFEFF")) text = text.substring(1)
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
    if (truncated) lines.add("… (file cut off after 8 MB)")
    return lines
}

/** Reads the text, headings, bold/italic/underline, bullets and tables of a .docx file. */
internal fun parseDocx(input: InputStream): List<DocBlock> {
    val zip = ZipInputStream(input)
    var entry = zip.nextEntry
    while (entry != null) {
        if (entry.name == "word/document.xml") return parseDocumentXml(zip)
        entry = zip.nextEntry
    }
    throw IllegalArgumentException("This Word file is protected or is not a valid .docx")
}

private fun headingLevel(style: String): Int {
    val l = style.lowercase()
    return when {
        l.startsWith("heading") -> l.removePrefix("heading").trim().toIntOrNull()?.coerceIn(1, 6) ?: 1
        l == "title" -> 1
        l == "subtitle" -> 2
        else -> 0
    }
}

private fun flagOn(parser: XmlPullParser): Boolean {
    val v = parser.getAttributeValue(null, "w:val")
    return v == null || (v != "0" && v != "false")
}

/** One open <w:tbl>; tables can be nested (many Word forms put everything inside one big outer cell). */
private class TblCtx {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    var cell = StringBuilder()
    val cellBlocks = ArrayList<DocBlock>()
}

private fun parseDocumentXml(stream: InputStream): List<DocBlock> {
    val parser = Xml.newPullParser()
    parser.setInput(stream, "UTF-8")

    val blocks = ArrayList<DocBlock>()
    val stack = ArrayList<TblCtx>()
    var runs = ArrayList<DocRun>()
    var heading = 0
    var bullet = false
    var bold = false
    var italic = false
    var underline = false

    var ev = parser.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG) {
            when (parser.name) {
                "w:tbl" -> stack.add(TblCtx())
                "w:tr" -> stack.lastOrNull()?.row = ArrayList()
                "w:tc" -> stack.lastOrNull()?.let { it.cell = StringBuilder(); it.cellBlocks.clear() }
                "w:p" -> {
                    runs = ArrayList()
                    heading = 0
                    bullet = false
                }
                "w:pStyle" -> {
                    heading = headingLevel(parser.getAttributeValue(null, "w:val") ?: "")
                }
                "w:numPr" -> {
                    bullet = true
                }
                "w:r" -> {
                    bold = false
                    italic = false
                    underline = false
                }
                "w:b" -> {
                    bold = flagOn(parser)
                }
                "w:i" -> {
                    italic = flagOn(parser)
                }
                "w:u" -> {
                    underline = (parser.getAttributeValue(null, "w:val") ?: "single") != "none"
                }
                "w:t" -> {
                    val t = parser.nextText()
                    if (t.isNotEmpty()) runs.add(DocRun(t, bold, italic, underline))
                }
                "w:tab" -> {
                    runs.add(DocRun("\t"))
                }
                "w:br", "w:cr" -> {
                    runs.add(DocRun("\n"))
                }
                "w:drawing", "w:pict" -> {
                    runs.add(DocRun("[image]", italic = true))
                }
            }
        } else if (ev == XmlPullParser.END_TAG) {
            when (parser.name) {
                "w:p" -> {
                    val cur = stack.lastOrNull()
                    if (cur != null) {
                        cur.cell.append(runs.joinToString("") { it.text }).append('\n')
                        cur.cellBlocks.add(DocPara(runs, heading, bullet))
                    } else {
                        blocks.add(DocPara(runs, heading, bullet))
                    }
                }
                "w:tc" -> stack.lastOrNull()?.let { it.row.add(it.cell.toString().trim()) }
                "w:tr" -> stack.lastOrNull()?.let { it.rows.add(it.row) }
                "w:tbl" -> if (stack.isNotEmpty()) {
                    val t = stack.removeAt(stack.size - 1)
                    val parent = stack.lastOrNull()
                    // a 1x1 table is just a layout wrapper: show its content directly
                    val single = t.rows.size == 1 && t.rows[0].size == 1
                    val produced: List<DocBlock> = if (single) t.cellBlocks.toList() else listOf(DocTable(t.rows))
                    if (parent == null) {
                        blocks.addAll(produced)
                    } else {
                        parent.cellBlocks.addAll(produced)
                        parent.cell.append(t.rows.joinToString("\n") { it.joinToString("  |  ") }).append('\n')
                    }
                }
            }
        }
        ev = parser.next()
    }
    return blocks
}
