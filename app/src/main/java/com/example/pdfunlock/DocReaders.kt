package com.example.pdfunlock

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal enum class Kind { Pdf, Text, Word, Sheet, Slides, Unsupported }

internal data class DocRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
)

internal sealed interface DocBlock
internal data class DocPara(val runs: List<DocRun>, val heading: Int, val bullet: Boolean) : DocBlock
internal data class DocTable(val rows: List<List<String>>) : DocBlock
/** A picture inside the document (raw png/jpg/gif/bmp/webp bytes). */
internal class DocImage(val bytes: ByteArray) : DocBlock

private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/** Decides how to open a file from its extension first, then from its MIME type. */
internal fun detectKind(cr: ContentResolver, uri: Uri, name: String?): Kind {
    val ext = name?.substringAfterLast('.', "")?.lowercase() ?: ""
    when (ext) {
        "pdf" -> return Kind.Pdf
        "txt", "log", "md", "csv", "json", "xml", "ini", "conf", "text" -> return Kind.Text
        "docx", "doc" -> return Kind.Word
        "xls", "xlsx", "xlsm" -> return Kind.Sheet
        "ppt", "pptx" -> return Kind.Slides
        "odt", "ods", "odp", "rtf" -> return Kind.Unsupported
    }
    val mime = try { cr.getType(uri) } catch (_: Exception) { null }
    return when {
        mime == null -> Kind.Pdf
        mime == "application/pdf" -> Kind.Pdf
        mime.startsWith("text/") -> Kind.Text
        mime == DOCX_MIME || mime == "application/msword" -> Kind.Word
        mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
            mime == "application/vnd.ms-excel" -> Kind.Sheet
        mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
            mime == "application/vnd.ms-powerpoint" -> Kind.Slides
        mime.startsWith("application/vnd.") -> Kind.Unsupported
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

/** Turns the slides of a PowerPoint file into blocks the Word viewer can show. */
internal fun slidesToBlocks(slides: List<Office.Slide>): List<DocBlock> {
    val out = ArrayList<DocBlock>()
    slides.forEachIndexed { i, s ->
        if (i > 0) out.add(DocPara(emptyList(), 0, false))
        out.add(DocPara(listOf(DocRun("Slide ${i + 1}", bold = true)), 3, false))
        val title = s.title
        if (!title.isNullOrBlank()) out.add(DocPara(listOf(DocRun(title, bold = true)), 2, false))
        for (line in s.body) out.add(DocPara(listOf(DocRun(line)), 0, true))
        if (title.isNullOrBlank() && s.body.isEmpty()) {
            out.add(DocPara(listOf(DocRun("(no text on this slide)", italic = true)), 0, false))
        }
    }
    return out
}

/** Reads the text, headings, bold/italic/underline, bullets and tables of a .docx file. */
internal fun parseDocx(input: InputStream): List<DocBlock> {
    val zip = ZipInputStream(input)
    val names = ArrayList<String>()
    var xml: ByteArray? = null
    var relsXml: ByteArray? = null
    val media = HashMap<String, ByteArray>()
    var mediaBytes = 0
    var entry = zip.nextEntry
    while (entry != null) {
        names.add(entry.name)
        val n = entry.name.lowercase().trimStart('/')
        if (n == "word/_rels/document.xml.rels") relsXml = zip.readBytes()
        else if (n.startsWith("word/media/") && Regex(".*\\.(png|jpe?g|gif|bmp|webp)$").matches(n) && mediaBytes < 30_000_000) {
            val b = zip.readBytes()
            mediaBytes += b.size
            media[n] = b
        }
        val isMain = n == "word/document.xml" ||
            (xml == null && n.startsWith("word/document") && n.endsWith(".xml"))
        if (isMain) xml = zip.readBytes()
        entry = zip.nextEntry
    }
    val data = xml ?: throw IllegalArgumentException("This Word file is protected or is not a valid .docx")

    fun hasText(list: List<DocBlock>) = list.any {
        it is DocTable || it is DocImage || (it is DocPara && it.runs.any { r -> r.text.isNotBlank() })
    }

    val images = HashMap<String, ByteArray>()
    if (relsXml != null) {
        try {
            for ((id, target) in readRels(java.io.ByteArrayInputStream(relsXml))) {
                val path = if (target.startsWith("/")) target.trimStart('/') else "word/" + target
                val b = media[path.lowercase()]
                if (b != null) images[id] = b
            }
        } catch (_: Exception) {
        }
    }

    var blocks = try { parseDocumentXml(java.io.ByteArrayInputStream(data), images) } catch (_: Exception) { emptyList() }
    if (!hasText(blocks)) {
        // backup: plain paragraphs from any prefix, ignoring tables and styling
        val flat = try { flatParagraphs(java.io.ByteArrayInputStream(data)) } catch (_: Exception) { emptyList() }
        if (hasText(flat)) blocks = flat
    }
    if (!hasText(blocks)) {
        throw IllegalArgumentException(
            "No text found in word/document.xml (${data.size} bytes). Parts: ${names.take(12).joinToString()}"
        )
    }
    return blocks
}

/** relationship id -> target path, from word/_rels/document.xml.rels */
private fun readRels(stream: InputStream): List<Pair<String, String>> {
    val parser = Xml.newPullParser()
    parser.setInput(stream, "UTF-8")
    val out = ArrayList<Pair<String, String>>()
    var ev = parser.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG && parser.name.substringAfterLast(':') == "Relationship") {
            val id = attr(parser, "Id")
            val target = attr(parser, "Target")
            if (id != null && target != null) out.add(id to target)
        }
        ev = parser.next()
    }
    return out
}

/** Fallback reader: every paragraph (<p>) with its text (<t>), whatever the XML prefix is. */
private fun flatParagraphs(stream: InputStream): List<DocBlock> {
    val skip = setOf("a", "wp", "wps", "wp14", "pic", "c", "dgm", "v", "o")
    val parser = Xml.newPullParser()
    parser.setInput(stream, "UTF-8")
    val out = ArrayList<DocBlock>()
    var sb = StringBuilder()
    var ev = parser.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        val full = parser.name ?: ""
        val pre = full.substringBefore(':', "")
        val nm = if (pre in skip) "" else full.substringAfterLast(':')
        if (ev == XmlPullParser.START_TAG) {
            when (nm) {
                "p" -> sb = StringBuilder()
                "t" -> sb.append(parser.nextText())
                "tab" -> sb.append('\t')
                "br", "cr" -> sb.append('\n')
            }
        } else if (ev == XmlPullParser.END_TAG && nm == "p") {
            val t = sb.toString()
            out.add(DocPara(if (t.isEmpty()) emptyList() else listOf(DocRun(t)), 0, false))
            sb = StringBuilder()
        }
        ev = parser.next()
    }
    return out
}

/** Reads an attribute by its local name, so it works with any XML prefix (w:val, val, ns0:val). */
private fun attr(p: XmlPullParser, local: String): String? {
    for (i in 0 until p.attributeCount) {
        if (p.getAttributeName(i).substringAfterLast(':') == local) return p.getAttributeValue(i)
    }
    return null
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

/** Local tag name for Word's own tags (prefix w, none, or an unknown one); "" for drawing/other namespaces. */
private fun localName(p: XmlPullParser): String {
    val full = p.name ?: return ""
    val pre = full.substringBefore(':', "")
    if (pre in setOf("a", "wp", "wps", "wp14", "pic", "c", "dgm", "v", "o", "m", "mc", "w14", "w15")) return ""
    return full.substringAfterLast(':')
}

private fun flagOn(parser: XmlPullParser): Boolean {
    val v = attr(parser, "val")
    return v == null || (v != "0" && v != "false")
}

/** One open <w:tbl>; tables can be nested (many Word forms put everything inside one big outer cell). */
private class TblCtx {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    var cell = StringBuilder()
    val cellBlocks = ArrayList<DocBlock>()
    val imgs = ArrayList<DocImage>()
}

private fun parseDocumentXml(stream: InputStream, images: Map<String, ByteArray>): List<DocBlock> {
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
    var inTabs = false
    var fallbackDepth = 0           // inside <mc:Fallback>: duplicate of the real drawing, skip
    var drawOpen = false
    var blipFound = false
    val paraImgs = ArrayList<DocImage>()

    var ev = parser.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG) {
            val raw = parser.name ?: ""
            val rawLocal = raw.substringAfterLast(':')
            if (rawLocal == "Fallback") fallbackDepth++
            if (fallbackDepth == 0 && (rawLocal == "blip" || rawLocal == "imagedata")) {
                val rid = attr(parser, "embed") ?: attr(parser, "id")
                val bytes = if (rid != null) images[rid] else null
                if (bytes != null) {
                    paraImgs.add(DocImage(bytes))
                    blipFound = true
                }
            }
            if (fallbackDepth > 0) { ev = parser.next(); continue }
            when (localName(parser)) {
                "tbl" -> stack.add(TblCtx())
                "tr" -> stack.lastOrNull()?.row = ArrayList()
                "tc" -> stack.lastOrNull()?.let { it.cell = StringBuilder(); it.cellBlocks.clear() }
                "p" -> {
                    runs = ArrayList()
                    heading = 0
                    bullet = false
                    paraImgs.clear()
                }
                "pStyle" -> {
                    heading = headingLevel(attr(parser, "val") ?: "")
                }
                "numPr" -> {
                    bullet = true
                }
                "r" -> {
                    bold = false
                    italic = false
                    underline = false
                }
                "b" -> {
                    bold = flagOn(parser)
                }
                "i" -> {
                    italic = flagOn(parser)
                }
                "u" -> {
                    underline = (attr(parser, "val") ?: "single") != "none"
                }
                "t" -> {
                    val t = parser.nextText()
                    if (t.isNotEmpty()) runs.add(DocRun(t, bold, italic, underline))
                }
                "tabs" -> inTabs = true
                "tab" -> {
                    // inside <w:tabs> this is only a tab-stop definition, not a tab character
                    if (!inTabs) runs.add(DocRun("\t"))
                }
                "br", "cr" -> {
                    runs.add(DocRun("\n"))
                }
                "drawing", "pict" -> {
                    drawOpen = true
                    blipFound = false
                }
            }
        } else if (ev == XmlPullParser.END_TAG) {
            if ((parser.name ?: "").substringAfterLast(':') == "Fallback") {
                if (fallbackDepth > 0) fallbackDepth--
                ev = parser.next(); continue
            }
            if (fallbackDepth > 0) { ev = parser.next(); continue }
            when (localName(parser)) {
                "tabs" -> inTabs = false
                "drawing", "pict" -> {
                    if (drawOpen && !blipFound) runs.add(DocRun("[image]", italic = true))
                    drawOpen = false
                }
                "p" -> {
                    val cur = stack.lastOrNull()
                    val imgs = ArrayList(paraImgs)
                    paraImgs.clear()
                    val showPara = runs.isNotEmpty() || imgs.isEmpty()
                    if (cur != null) {
                        cur.cell.append(runs.joinToString("") { it.text }).append('\n')
                        if (showPara) cur.cellBlocks.add(DocPara(runs, heading, bullet))
                        cur.cellBlocks.addAll(imgs)
                        cur.imgs.addAll(imgs)
                    } else {
                        if (showPara) blocks.add(DocPara(runs, heading, bullet))
                        blocks.addAll(imgs)
                    }
                }
                "tc" -> stack.lastOrNull()?.let { it.row.add(it.cell.toString().trim()) }
                "tr" -> stack.lastOrNull()?.let { it.rows.add(it.row) }
                "tbl" -> if (stack.isNotEmpty()) {
                    val t = stack.removeAt(stack.size - 1)
                    val parent = stack.lastOrNull()
                    // a 1x1 table is just a layout wrapper: show its content directly
                    val single = t.rows.size == 1 && t.rows[0].size == 1
                    val produced: List<DocBlock> = if (single) t.cellBlocks.toList() else listOf<DocBlock>(DocTable(t.rows)) + t.imgs
                    if (parent == null) {
                        blocks.addAll(produced)
                    } else {
                        parent.cellBlocks.addAll(produced)
                        parent.imgs.addAll(produced.filterIsInstance<DocImage>())
                        parent.cell.append(t.rows.joinToString("\n") { it.joinToString("  |  ") }).append('\n')
                    }
                }
            }
        }
        ev = parser.next()
    }
    return blocks
}
