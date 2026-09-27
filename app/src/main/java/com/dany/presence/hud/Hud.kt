package com.dany.presence.hud

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dany.presence.core.Bus
import com.dany.presence.core.World
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime
import java.time.temporal.IsoFields

/**
 * The whole on-screen text, and nothing else: state (2 lines, top-left), clock (2 lines, top-right),
 * the capture stack under the state in gold, one discreet line at the bottom.
 * Draws over the scene; passes touches through (no pointer handling here).
 */
@Composable
fun Hud(bus: Bus, world: StateFlow<World>, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val model = remember(bus) { HudModel(bus, scope) }
    val st by model.state.collectAsState()
    // The scene owns World; the HUD only keeps the subscription so future readouts stay cheap.
    @Suppress("UNUSED_VARIABLE") val w by world.collectAsState()
    val clock by rememberClock()

    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // ---- top-left: identity + mode, then the capture stack
        Column(Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 22.dp)) {
            BasicText("PRÉSENCE", style = HudTheme.label(9.sp, FontWeight.Medium, HudTheme.bone))
            AnimatedContent(
                targetState = st.mode, label = "mode",
                transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) },
            ) { m ->
                BasicText(m.label.uppercase(), Modifier.padding(top = 4.dp), style = HudTheme.label(9.sp, color = modeColor(m)))
            }
            Spacer(Modifier.height(14.dp))
            val capsAlpha by animateFloatAsState(if (st.capturesOn) 1f else 0f, tween(600), label = "caps")
            Column(Modifier.graphicsLayer { alpha = capsAlpha }) {
                st.captures.forEach { c -> key(c.id) { CaptureRow(c) } }
            }
        }

        // ---- top-right: clock
        Column(Modifier.align(Alignment.TopEnd).padding(end = 22.dp, top = 22.dp), horizontalAlignment = Alignment.End) {
            BasicText(clock.first, style = HudTheme.number(11.sp, HudTheme.bone))
            BasicText(clock.second.uppercase(), Modifier.padding(top = 4.dp), style = HudTheme.label(9.sp))
        }

        // ---- bottom: the said line, or the error in ember
        val fail = st.failedOn
        val bottomAlpha by animateFloatAsState(if (fail || st.saidOn) 1f else 0f, tween(500), label = "say")
        val style = if (fail) HudTheme.label(9.sp, color = HudTheme.ember) else HudTheme.said()
        BasicText(
            text = if (fail) st.failed.uppercase() else st.said,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
                .graphicsLayer { alpha = bottomAlpha * (if (fail) 1f else 0.8f) },
            style = style.copy(textAlign = TextAlign.Center),
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun modeColor(m: HudMode): Color = when (m) {
    HudMode.VEILLE -> HudTheme.ash
    HudMode.ECOUTE, HudMode.EXECUTE -> HudTheme.bone
    HudMode.ANALYSE -> HudTheme.gold
    HudMode.ALERTE, HudMode.ERREUR -> HudTheme.ember
}

/** One capture: a short gold rule, then « MODULE · MOT ». Fades in over 300 ms. */
@Composable
private fun CaptureRow(c: Capture) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(300)) }
    Row(Modifier.padding(top = 4.dp).graphicsLayer { alpha = a.value }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(18.dp).height(1.dp).background(HudTheme.gold.copy(alpha = 0.7f)))
        Spacer(Modifier.width(6.dp))
        BasicText(
            "${c.module.hudLabel()} · ${c.word}".uppercase(),
            style = HudTheme.label(9.sp, color = HudTheme.gold), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "HH:mm" and "mer · sem 39", refreshed on the minute. */
@Composable
private fun rememberClock(): State<Pair<String, String>> = produceState(clockNow()) {
    while (true) {
        value = clockNow()
        delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
    }
}

private val DAYS = arrayOf("dim", "lun", "mar", "mer", "jeu", "ven", "sam")

private fun clockNow(): Pair<String, String> {
    val t = LocalDateTime.now()
    val day = DAYS[t.dayOfWeek.value % 7]
    val week = t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    return String.format("%02d:%02d", t.hour, t.minute) to "$day · sem $week"
}
