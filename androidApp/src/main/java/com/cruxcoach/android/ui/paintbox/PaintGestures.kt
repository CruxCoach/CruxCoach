package com.cruxcoach.android.ui.paintbox

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.ceil

/** Sample the whole segment, not just Android's coalesced motion endpoints. */
internal fun sampledSegment(from: Offset, to: Offset): List<Offset> {
    val steps = ceil((to - from).getDistance() / 2f).toInt().coerceAtLeast(1)
    return (0..steps).map { from + (to - from) * (it.toFloat() / steps) }
}

internal suspend fun PointerInputScope.detectPaintGestures(
    hit: (Offset) -> Int?,
    begin: () -> Unit,
    paint: (Set<Int>) -> Unit,
    end: () -> Unit,
    transform: (Float, Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var previous = down.position
        var painting = false
        var multiTouch = false
        val seen = mutableSetOf<Int>()
        try {
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    multiTouch = true
                    transform(event.calculateZoom(), event.calculatePan())
                    event.changes.forEach { it.consume() }
                } else if (!multiTouch) {
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // Defer the initial dot until movement/up, so a pinch does not paint a dot.
                    if (!painting) { begin(); painting = true }
                    val hits = sampledSegment(previous, change.position).mapNotNull(hit).filter { seen.add(it) }.toSet()
                    if (hits.isNotEmpty()) paint(hits)
                    previous = change.position
                    change.consume()
                }
            } while (event.changes.any { it.pressed })
        } finally { if (painting) end() }
    }
}
