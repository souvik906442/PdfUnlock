@file:OptIn(ExperimentalFoundationApi::class)

package com.example.pdfunlock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
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
 * Line-by-line text viewer built for copying:
 * tap a line to select it (tap again to unselect), long-press a line to select everything
 * from the last tapped line down/up to it, then press Copy.
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
    val clipboard = LocalClipboardManager.current

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
            items(lines.size) { i ->
                val isSel = i in selected
                Row(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .clip(paperShape(i, lines.size))
                        .background(Color.White)
                        .background(if (isSel) Sky.copy(alpha = 0.22f) else Color.Transparent)
                        .combinedClickable(
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
                        .padding(
                            start = 8.dp,
                            end = 14.dp,
                            top = if (i == 0) 14.dp else 0.dp,
                            bottom = if (i == lines.size - 1) 14.dp else 0.dp
                        )
                ) {
                    Text(
                        text = (i + 1).toString(),
                        color = GridLine,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 19.sp),
                        modifier = Modifier.width(34.dp)
                    )
                    Text(
                        text = if (lines[i].isEmpty()) " " else lines[i],
                        color = PaperInk,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (selected.isNotEmpty()) {
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
                Pill("All", p, Modifier.weight(1f)) {
                    selected = lines.indices.toSet()
                    anchor = -1
                }
                Pill("Clear", p, Modifier.weight(1f)) {
                    selected = emptySet()
                    anchor = -1
                }
                Pill("Copy ${selected.size}", p, Modifier.weight(1.6f), tint = Sky) {
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

@Composable
internal fun WordViewer(blocks: List<DocBlock>, pad: PaddingValues) {
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
                }
            }
        }
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
            withStyle(style) { append(r.text) }
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
                            text = row.getOrElse(c) { "" },
                            color = PaperInk,
                            style = TextStyle(fontFamily = Jakarta, fontSize = 13.sp)
                        )
                    }
                }
            }
        }
    }
}
