@file:OptIn(ExperimentalFoundationApi::class)

package com.example.pdfunlock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PaperInk = Color(0xFF172033)
private val GridLine = Color(0xFFB8C0CC)

/** A white sheet made of rows: only the first row gets round top corners and the last round bottom corners. */
private fun paperShape(i: Int, n: Int): RoundedCornerShape {
    val r = 12.dp
    return RoundedCornerShape(
        topStart = if (i == 0) r else 0.dp,
        topEnd = if (i == 0) r else 0.dp,
        bottomEnd = if (i == n - 1) r else 0.dp,
        bottomStart = if (i == n - 1) r else 0.dp
    )
}

/**
 * Text viewer built for copying, two modes:
 *  - Lines: tap a line to select it (tap again to unselect), long-press a line to select a range
 *    from the last tapped line, then press Copy.
 *  - Words: normal Android text selection — long-press a word, drag the handles to pick exactly
 *    the part you want (even a few letters inside a line, across many lines), then use the
 *    system "Copy" popup.
 * The control bar floats above the bottom bar, and the list has extra bottom padding so
 * the last lines can always be scrolled above it.
 */
@Composable
internal fun TextViewer(
    lines: List<String>,
    p: Palette,
    pad: PaddingValues,
    onCopied: (String) -> Unit
) {
    var selected by remember(lines) { mutableStateOf(emptySet<Int>()) }
    var anchor by remember(lines) { mutableStateOf(-1) }
    var wordMode by remember(lines) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // room for the floating selection bar so it never hides the last lines
    val listPad = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 84.dp)

    Box(Modifier.fillMaxSize()) {
        val list: @Composable () -> Unit = {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad) {
                items(lines.size) { i ->
                    val isSel = !wordMode && i in selected
                    Row(
                        Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth()
                            .clip(paperShape(i, lines.size))
                            .background(Color.White)
                            .background(if (isSel) Sky.copy(alpha = 0.22f) else Color.Transparent)
                            .then(
                                if (wordMode) Modifier else Modifier.combinedClickable(
                                    onClick = {
                                        selected = if (isSel) selected - i else selected + i
                                        anchor = i
                                    },
                                    onLongClick = {
                                        val a = if (anchor >= 0) anchor else i
                                        selected = selected + (minOf(a, i)..maxOf(a, i))
                                        anchor = i
                                    }
                                )
                            )
                            .padding(
                                start = 8.dp,
                                end = 14.dp,
                                top = if (i == 0) 14.dp else 0.dp,
                                bottom = if (i == lines.size - 1) 14.dp else 0.dp
                            )
                    ) {
                        DisableSelection {
                            Text(
                                text = (i + 1).toString(),
                                color = GridLine,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 19.sp),
                                modifier = Modifier.width(34.dp)
                            )
                        }
                        Text(
                            text = if (lines[i].isEmpty()) " " else lines[i],
                            color = PaperInk,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        if (wordMode) SelectionContainer { list() } else list()

        val barShape = RoundedCornerShape(26.dp)
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = pad.calculateBottomPadding(), start = 12.dp, end = 12.dp)
                .fillMaxWidth()
                .clip(barShape)
                .background(p.frost)
                .border(1.dp, p.rim, barShape)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Pill(if (wordMode) "Lines" else "Words", p, Modifier.weight(1.1f)) {
                wordMode = !wordMode
                selected = emptySet()
                anchor = -1
            }
            if (wordMode) {
                Text(
                    text = "Long-press a word, drag the handles, then tap Copy",
                    color = p.inkSoft,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 15.sp),
                    modifier = Modifier.weight(2.6f).align(Alignment.CenterVertically).padding(horizontal = 6.dp)
                )
            } else {
                Pill("All", p, Modifier.weight(0.8f)) {
                    selected = lines.indices.toSet()
                    anchor = -1
                }
                Pill("Clear", p, Modifier.weight(0.9f), enabled = selected.isNotEmpty()) {
                    selected = emptySet()
                    anchor = -1
                }
                Pill("Copy ${selected.size}", p, Modifier.weight(1.4f), tint = Sky, enabled = selected.isNotEmpty()) {
                    val text = selected.sorted().joinToString("\n") { lines[it] }
                    if (text.length > 900_000) {
                        onCopied("Too much to copy at once (limit about 1 MB)")
                    } else {
                        clipboard.setText(AnnotatedString(text))
                        onCopied(if (selected.size == 1) "Line copied" else "${selected.size} lines copied")
                        selected = emptySet()
                        anchor = -1
                    }
                }
            }
        }
    }
}

/** Spreadsheet viewer: sheet tabs at the bottom, a scrollable grid; tap a cell to copy it, long-press to copy its row. */
@Composable
internal fun SheetViewer(
    sheets: List<Office.Sheet>,
    p: Palette,
    pad: PaddingValues,
    onCopied: (String) -> Unit
) {
    if (sheets.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            Text("No sheets found in this file", color = p.inkSoft, fontSize = 14.sp)
        }
        return
    }
    var idx by remember(sheets) { mutableStateOf(0) }
    val sheet = sheets[idx.coerceIn(0, sheets.size - 1)]
    val rows = sheet.rows
    val clipboard = LocalClipboardManager.current
    val hScroll = rememberScrollState()

    val cols = remember(sheet) { rows.maxOfOrNull { it.size } ?: 0 }
    val widths = remember(sheet) {
        List(cols) { c ->
            var longest = 3
            for (r in 0 until minOf(rows.size, 300)) {
                val len = rows[r].getOrNull(c)?.length ?: 0
                if (len > longest) longest = len
            }
            (minOf(longest, 36) * 8 + 18).dp
        }
    }
    val numW = 44.dp
    val rowH = 30.dp
    val total = widths.fold(numW + 24.dp) { acc, w -> acc + w }
    val listPad = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 84.dp)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            LazyColumn(Modifier.width(total).fillMaxHeight(), contentPadding = listPad) {
                item {
                    Row(Modifier.padding(start = 12.dp).background(Color(0xFFE6EBF3))) {
                        Box(Modifier.width(numW).height(rowH))
                        for (c in 0 until cols) {
                            Box(Modifier.width(widths[c]).height(rowH).border(0.5.dp, GridLine), contentAlignment = Alignment.Center) {
                                Text(colName(c), color = PaperInk.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                items(rows.size) { r ->
                    val row = rows[r]
                    Row(Modifier.padding(start = 12.dp).background(Color.White)) {
                        Box(Modifier.width(numW).height(rowH).background(Color(0xFFF1F4F8)).border(0.5.dp, GridLine), contentAlignment = Alignment.Center) {
                            Text((r + 1).toString(), color = PaperInk.copy(alpha = 0.45f), fontSize = 10.sp)
                        }
                        for (c in 0 until cols) {
                            val text = row.getOrNull(c) ?: ""
                            Box(
                                Modifier
                                    .width(widths[c])
                                    .height(rowH)
                                    .border(0.5.dp, GridLine)
                                    .combinedClickable(
                                        onClick = {
                                            if (text.isNotEmpty()) {
                                                clipboard.setText(AnnotatedString(text))
                                                onCopied("Cell copied")
                                            }
                                        },
                                        onLongClick = {
                                            clipboard.setText(AnnotatedString(row.joinToString("\t")))
                                            onCopied("Row ${r + 1} copied")
                                        }
                                    )
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(text, color = PaperInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (sheet.truncated) {
                    item {
                        Text(
                            "Only the first ${Office.MAX_ROWS} rows / ${Office.MAX_COLS} columns are shown",
                            color = PaperInk.copy(alpha = 0.7f), fontSize = 12.sp,
                            modifier = Modifier.padding(start = 24.dp, top = 10.dp)
                        )
                    }
                }
            }
        }

        val barShape = RoundedCornerShape(26.dp)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = pad.calculateBottomPadding(), start = 12.dp, end = 12.dp)
                .fillMaxWidth()
                .clip(barShape)
                .background(p.frost)
                .border(1.dp, p.rim, barShape)
                .padding(8.dp)
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sheets.forEachIndexed { i, s ->
                    Pill(s.name.ifBlank { "Sheet ${i + 1}" }, p, tint = if (i == idx) Sky else null) { idx = i }
                }
            }
        }
    }
}

private fun colName(c: Int): String {
    var n = c + 1
    val sb = StringBuilder()
    while (n > 0) {
        val r = (n - 1) % 26
        sb.insert(0, ('A' + r))
        n = (n - 1) / 26
    }
    return sb.toString()
}

@Composable
internal fun WordViewer(blocks: List<DocBlock>, pad: PaddingValues, emptyColor: Color = Color.White) {
    if (blocks.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            Text("No readable text found in this document", color = emptyColor, fontSize = 14.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        items(blocks.size) { i ->
            Box(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .clip(paperShape(i, blocks.size))
                    .background(Color.White)
                    .padding(
                        start = 18.dp,
                        end = 18.dp,
                        top = if (i == 0) 18.dp else 3.dp,
                        bottom = if (i == blocks.size - 1) 18.dp else 3.dp
                    )
            ) {
                when (val b = blocks[i]) {
                    is DocPara -> ParaView(b)
                    is DocTable -> TableView(b)
                    is DocImage -> ImageView(b)
                }
            }
        }
    }
}

@Composable
private fun ImageView(img: DocImage) {
    val bmp by produceState<android.graphics.Bitmap?>(null, img) {
        value = withContext(Dispatchers.Default) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= 1400) sample *= 2
                val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, o2)
            } catch (_: Throwable) {
                null
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentScale = ContentScale.FillWidth
        )
    } else {
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ParaView(b: DocPara) {
    if (b.runs.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        return
    }
    val size = when (b.heading) {
        0 -> 15.sp
        1 -> 24.sp
        2 -> 20.sp
        3 -> 18.sp
        else -> 16.sp
    }
    val text = buildAnnotatedString {
        if (b.bullet) append("•  ")
        for (r in b.runs) {
            val style = SpanStyle(
                fontWeight = if (r.bold || b.heading > 0) FontWeight.Bold else null,
                fontStyle = if (r.italic) FontStyle.Italic else null,
                textDecoration = if (r.underline) TextDecoration.Underline else null
            )
            withStyle(style) { append(r.text.replace("\t", "    ")) }
        }
    }
    Text(
        text = text,
        color = PaperInk,
        style = TextStyle(fontFamily = Jakarta, fontSize = size, lineHeight = size * 1.45f)
    )
}

@Composable
private fun TableView(t: DocTable) {
    val cols = t.rows.maxOfOrNull { it.size } ?: 0
    if (cols == 0) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .border(1.dp, GridLine)
    ) {
        for (row in t.rows) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (c in 0 until cols) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, GridLine)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = row.getOrElse(c) { "" }.replace("\t", "    "),
                            color = PaperInk,
                            style = TextStyle(fontFamily = Jakarta, fontSize = 13.sp)
                        )
                    }
                }
            }
        }
    }
}
