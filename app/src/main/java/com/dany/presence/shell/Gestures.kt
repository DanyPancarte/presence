package com.dany.presence.shell

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.dany.presence.core.Bus
import com.dany.presence.core.Signal
import kotlin.math.abs

private const val SWIPE_PX = 140f

/**
 * Hands-free shell gestures. Every pointer event becomes a [Signal.Touch] (x, y normalised to -1..1,
 * y up) so the scene can react; a vertical swipe up of more than 140 px fires [onSwipeUp] once;
 * a still finger past the long-press timeout fires [onLongPress] once. Taps do nothing.
 */
fun Modifier.presenceGestures(bus: Bus, onSwipeUp: () -> Unit, onLongPress: () -> Unit): Modifier =
    pointerInput(bus, onSwipeUp, onLongPress) {
        val slop = viewConfiguration.touchSlop
        val longPressMs = viewConfiguration.longPressTimeoutMillis
        fun emit(p: Offset, down: Boolean) {
            val x = (p.x / size.width.coerceAtLeast(1)) * 2f - 1f
            val y = 1f - (p.y / size.height.coerceAtLeast(1)) * 2f
            bus.emit(Signal.Touch(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f), down))
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            val t0 = SystemClock.uptimeMillis()
            emit(start, true)
            var moved = false
            var swiped = false
            var held = false
            while (true) {
                // While a long press is still possible, wait with a deadline instead of forever.
                val event = if (!moved && !held) {
                    val left = longPressMs - (SystemClock.uptimeMillis() - t0)
                    if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent() }
                } else awaitPointerEvent()
                if (event == null) { held = true; onLongPress(); continue }

                val ch = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                val p = ch.position
                val d = p - start
                if (!moved && (abs(d.x) > slop || abs(d.y) > slop)) moved = true
                if (!ch.pressed) { emit(p, false); break }
                emit(p, true)
                if (!swiped && !held && -d.y > SWIPE_PX && abs(d.y) > abs(d.x)) { swiped = true; onSwipeUp() }
            }
        }
    }
