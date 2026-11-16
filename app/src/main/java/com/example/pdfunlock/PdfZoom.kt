package com.example.pdfunlock

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

private const val MAX_ZOOM = 4f

/**
 * A vertically scrolling list that can be zoomed:
 * pinch with two fingers, drag sideways when zoomed in, double-tap to zoom in / back out.
 * The list is laid out 1/zoom as tall as the screen and then scaled up, so normal scrolling keeps working.
 */
@Composable
internal fun ZoomableColumn(
    key: Any?,
    contentPadding: PaddingValues,
    spacing: Dp,
    content: LazyListScope.() -> Unit
) {
    val listState = rememberLazyListState()
    var zoom by remember(key) { mutableFloatStateOf(1f) }
    var tx by remember(key) { mutableFloatStateOf(0f) }

    /** Zoom to [newZoomRaw] keeping the point (cx, cy) fixed on screen, then move by (panX, panY). */
    fun applyTransform(newZoomRaw: Float, cx: Float, cy: Float, panX: Float, panY: Float, width: Float) {
        val old = zoom
        val nz = newZoomRaw.coerceIn(1f, MAX_ZOOM)
        // vertical: scroll so the content under the finger stays under it (list is laid out in 1/zoom units)
        listState.dispatchRawDelta(cy / old - cy / nz - panY / nz)
        // horizontal: scale around the finger, then pan, then keep inside the page
        val lx = (cx - tx) / old
        val newTx = cx - lx * nz + panX
        zoom = nz
        tx = if (nz <= 1f) 0f else newTx.coerceIn(width * (1f - nz), 0f)
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(key) {
                detectTapGestures(onDoubleTap = { pos ->
                    val target = if (zoom > 1.05f) 1f else 2.5f
                    applyTransform(target, pos.x, pos.y, 0f, 0f, size.width.toFloat())
                })
            }
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val zc = event.calculateZoom()
                            val pan = event.calculatePan()
                            val c = event.calculateCentroid(useCurrent = true)
                            applyTransform(zoom * zc, c.x, c.y, pan.x, pan.y, size.width.toFloat())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pressed == 1 && zoom > 1f) {
                            // one finger: sideways drag moves the zoomed page; vertical scroll is left to the list
                            val pan = event.calculatePan()
                            if (pan.x != 0f) {
                                tx = (tx + pan.x).coerceIn(size.width * (1f - zoom), 0f)
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .layout { measurable, constraints ->
                    val z = zoom
                    val h = (constraints.maxHeight / z).toInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, h))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = tx
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
    }
}
