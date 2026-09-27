package com.dany.presence.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dany.presence.brain.ModuleColor
import com.dany.presence.brain.Scene
import com.dany.presence.data.Module
import com.dany.presence.data.PresenceDao
import com.dany.presence.render.HoloState
import com.dany.presence.render.Mood
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun Module.color(): Color = ModuleColor.rgb(this).let { Color(it[0], it[1], it[2]) }

// ---- type ---------------------------------------------------------------------------------------

@Composable
fun Label(text: String, color: Color = Cp.yellow, size: Int = 10) = Text(
    text.uppercase(), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
    style = TextStyle(fontFamily = Cp.display, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = (size * 0.24f).sp),
)

@Composable
fun Mono(text: String, color: Color = Cp.mute, size: Int = 9) = Text(
    text, color = color, style = TextStyle(fontFamily = Cp.mono, fontSize = size.sp, letterSpacing = 0.6.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
)

@Composable
fun Body(text: String, color: Color = Cp.ink, size: Int = 13, weight: FontWeight = FontWeight.SemiBold, maxLines: Int = 1) = Text(
    text, color = color, style = TextStyle(fontFamily = Cp.display, fontWeight = weight, fontSize = size.sp), maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

// ---- HUD primitives -----------------------------------------------------------------------------

/**
 * A HUD chip: one accent bar, a hairline frame with a cut corner, nearly no fill. One or two lines.
 * Slightly tilted in 3D so it reads as a sign in space, not a dialog.
 */
@Composable
fun Chip(title: String, accent: Color, meta: String = "", modifier: Modifier = Modifier, tilt: Float = -12f, width: Int = 196, content: @Composable () -> Unit) {
    Box(
        modifier
            .width(width.dp)
            .graphicsLayer {
                rotationY = tilt; rotationX = 3f
                cameraDistance = 10f * density
                transformOrigin = TransformOrigin(if (tilt < 0) 1f else 0f, 0.5f)
            }
            .drawBehind {
                val cut = 8.dp.toPx()
                val p = Path().apply {
                    moveTo(0f, 0f); lineTo(size.width - cut, 0f); lineTo(size.width, cut)
                    lineTo(size.width, size.height); lineTo(0f, size.height); close()
                }
                drawPath(p, Cp.bg.copy(alpha = 0.55f))
                drawPath(p, accent.copy(alpha = 0.45f), style = Stroke(1.dp.toPx()))
                drawRect(accent, Offset.Zero, Size(2.dp.toPx(), size.height))
                // corner tick, bottom-right
                drawLine(accent, Offset(size.width - 10.dp.toPx(), size.height), Offset(size.width, size.height), 2.dp.toPx())
            }
            .padding(start = 9.dp, end = 8.dp, top = 5.dp, bottom = 6.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Label(title, accent)
                if (meta.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Mono(meta) }
            }
            content()
        }
    }
}

@Composable
fun Reveal(visible: Boolean, fromRight: Boolean = true, modifier: Modifier = Modifier, content: @Composable () -> Unit) = AnimatedVisibility(
    visible, modifier = modifier,
    enter = fadeIn(tween(200)) + slideInHorizontally(tween(240)) { if (fromRight) it / 5 else -it / 5 },
    exit = fadeOut(tween(180)),
) { content() }

/** Hairline progress with a tick at the end. */
@Composable
fun Line(fraction: Float, accent: Color, dim: Boolean = false) = Canvas(Modifier.fillMaxWidth().height(3.dp)) {
    drawLine(accent.copy(alpha = 0.15f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
    val x = size.width * fraction.coerceIn(0f, 1f)
    drawLine(if (dim) Cp.mute else accent, Offset(0f, size.height / 2), Offset(x, size.height / 2), 1.5.dp.toPx())
    drawLine(if (dim) Cp.mute else accent, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
}

/** The frame: four corner brackets, top readout, bottom level line. Pure HUD, no fill. */
@Composable
fun HudFrame(scene: Scene, state: HoloState, moodColor: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val m = 14.dp.toPx(); val l = 18.dp.toPx(); val w = 1.dp.toPx()
        val c = Cp.cyan.copy(alpha = 0.5f)
        fun bracket(x: Float, y: Float, sx: Float, sy: Float) {
            drawLine(c, Offset(x, y), Offset(x + l * sx, y), w)
            drawLine(c, Offset(x, y), Offset(x, y + l * sy), w)
        }
        bracket(m, m, 1f, 1f); bracket(size.width - m, m, -1f, 1f)
        bracket(m, size.height - m, 1f, -1f); bracket(size.width - m, size.height - m, -1f, -1f)
        // bottom level line: ticks light up with the voice
        val y = size.height - m - 6.dp.toPx()
        val n = 40; val gap = 3.dp.toPx(); val tw = (size.width - 2 * m - gap * (n - 1)) / n
        for (i in 0 until n) {
            val lit = i < (state.amp * n).toInt()
            drawRect(if (lit) moodColor else Cp.mute.copy(alpha = 0.25f), Offset(m + i * (tw + gap), y), Size(tw, if (lit) 4.dp.toPx() else 2.dp.toPx()))
        }
    }
}

// ---- the overlay layer --------------------------------------------------------------------------

@Composable
fun Overlays(scene: Scene, state: HoloState, dao: PresenceDao, modifier: Modifier = Modifier) {
    val moodColor = when (scene.mood) {
        Mood.VEILLE -> Cp.mute; Mood.ECOUTE -> Cp.cyan; Mood.REFLEXION -> Cp.yellow; Mood.REPONSE -> Cp.ink; Mood.ALERTE -> Cp.red
    }
    Box(modifier) {
        HudFrame(scene, state, moodColor)

        // Top-left: identity + the current step (one line, previous one faint)
        Column(Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(moodColor))
                Spacer(Modifier.width(7.dp))
                Label("Présence", Cp.ink, 11)
                Spacer(Modifier.width(8.dp))
                Label(scene.mood.name, moodColor, 9)
            }
            Spacer(Modifier.height(4.dp))
            scene.steps.takeLast(2).forEachIndexed { i, s ->
                val last = i == scene.steps.takeLast(2).lastIndex
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(if (s.done) "✓" else "▸", if (last && !s.done) moodColor else Cp.mute, 9)
                    Spacer(Modifier.width(5.dp))
                    Label(s.label, if (last && !s.done) moodColor else Cp.mute, 9)
                    if (s.detail.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Mono(s.detail, Cp.mute.copy(alpha = if (last) 1f else 0.6f), 8) }
                }
            }
        }

        // Top-right: clock
        Box(Modifier.align(Alignment.TopEnd).padding(end = 22.dp, top = 20.dp)) { Mono(SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH).format(Date()), Cp.mute, 10) }

        // Right column: live captures (chips), then the module chip
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp), horizontalAlignment = Alignment.End) {
            scene.detections.take(3).forEachIndexed { i, d ->
                Reveal(true) {
                    Chip(d.module.name, d.module.color(), d.op, Modifier.padding(bottom = 5.dp, end = (i * 5).dp), width = 150) {
                        Mono("« ${d.hint} »", d.module.color(), 9)
                    }
                }
            }
            Reveal(scene.module != Module.AUCUN) {
                Box(Modifier.padding(top = 4.dp)) {
                    when (scene.module) {
                        Module.TACHES -> TasksChip(scene, dao)
                        Module.NOTES -> NotesChip(scene, dao)
                        Module.MEDS -> MedsChip(scene, dao)
                        Module.BUDGET -> BudgetChip(scene, dao)
                        Module.MOOD -> MoodChip(scene, dao)
                        Module.AGENDA -> AgendaChip(scene, dao)
                        Module.AUCUN -> Unit
                    }
                }
            }
        }

        // Bottom-left: radio while listening / analysis / error
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 92.dp)) {
            Reveal(scene.mood == Mood.ECOUTE, fromRight = false) { RadioChip(scene, state) }
            Reveal(scene.mood == Mood.REFLEXION, fromRight = false) {
                Chip("Analyse", Cp.yellow, "…", tilt = 12f, width = 210) { Body(scene.heard, color = Cp.mute, size = 12, weight = FontWeight.Medium, maxLines = 2) }
            }
            Reveal(scene.error.isNotEmpty(), fromRight = false) { Chip("Erreur", Cp.red, tilt = 12f, width = 220) { Body(scene.error, size = 12, maxLines = 3) } }
        }

        // Bottom: the spoken line
        Reveal(scene.say.isNotEmpty(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(
                scene.say, color = if (scene.mood == Mood.ALERTE) Cp.red else Cp.ink, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Cp.display, fontWeight = FontWeight.Medium, fontSize = 17.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 36.dp),
            )
        }
    }
}

@Composable
fun RadioChip(scene: Scene, state: HoloState) = Chip("Écoute", Cp.cyan, "fr-CA", tilt = 12f, width = 210) {
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val b = state.bands
        val gap = 2.dp.toPx(); val w = (size.width - gap * 23) / 24
        for (i in 0 until 24) {
            val h = (0.08f + b[i] * 0.92f) * size.height
            drawRect(Cp.cyan.copy(alpha = 0.85f), Offset(i * (w + gap), size.height - h), Size(w, h))
        }
    }
    Spacer(Modifier.height(3.dp))
    Body(if (scene.heard.isBlank()) "▍" else scene.heard + " ▍", size = 12, weight = FontWeight.Medium, maxLines = 2)
}

@Composable
fun TasksChip(scene: Scene, dao: PresenceDao) {
    val tasks by dao.tasks().collectAsState(emptyList())
    val c = Module.TACHES.color()
    Chip("Tâches", c, scene.moduleOp) {
        if (tasks.isEmpty()) Body("Aucun projet", color = Cp.mute, size = 12)
        tasks.take(2).forEachIndexed { i, t ->
            val hot = i == 0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Body(t.nom, color = if (hot) Cp.ink else Cp.ink.copy(alpha = 0.55f), size = 12)
                Mono("${t.avancement}", if (hot) c else Cp.mute, 10)
            }
            Line(t.avancement / 100f, c, dim = !hot)
        }
    }
}

@Composable
fun NotesChip(scene: Scene, dao: PresenceDao) {
    val notes by dao.notes().collectAsState(emptyList())
    Chip("Note", Module.NOTES.color(), "#${notes.size.toString().padStart(3, '0')}") {
        Body(notes.firstOrNull()?.texte ?: "—", size = 12, maxLines = 2)
    }
}

@Composable
fun MedsChip(scene: Scene, dao: PresenceDao) {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.CANADA_FRENCH).format(Date())
    val med by dao.medFlow(today).collectAsState(null)
    val taken = med?.prisA != null
    val c = Module.MEDS.color()
    Chip("Méd", c, if (taken) SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH).format(Date(med!!.prisA!!)) else "08:00") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(16.dp)) {
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Butt)
                drawArc(c.copy(alpha = 0.2f), 0f, 360f, false, style = stroke)
                drawArc(if (taken) c else Cp.red, -90f, if (taken) 360f else 300f, false, style = stroke)
            }
            Spacer(Modifier.width(8.dp))
            Body(if (taken) "Pris" else "Pas confirmé", size = 12)
        }
    }
}

@Composable
fun BudgetChip(scene: Scene, dao: PresenceDao) {
    val monthStart = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    val ex by dao.expenses(monthStart).collectAsState(emptyList())
    val spent = ex.sumOf { it.montant }
    val c = Module.BUDGET.color()
    Chip("Budget", c, ex.firstOrNull()?.let { "−${it.montant.toInt()} $" } ?: "") {
        Row(verticalAlignment = Alignment.Bottom) {
            Body("${(600.0 - spent).toInt()} $", size = 16, weight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Mono("marge", c, 9)
        }
        Line(((600.0 - spent) / 600.0).toFloat(), c)
    }
}

@Composable
fun MoodChip(scene: Scene, dao: PresenceDao) {
    val moods by dao.moods().collectAsState(emptyList())
    val c = Module.MOOD.color()
    Chip("Mood", c, moods.firstOrNull()?.let { "${if (it.valeur > 0) "+" else ""}${it.valeur}" } ?: "") {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            moods.reversed().takeLast(14).forEach { m ->
                val col = when { m.valeur > 0 -> c; m.valeur < 0 -> Cp.red; else -> Cp.mute }
                Box(Modifier.size(width = 7.dp, height = (6 + 3 * kotlin.math.abs(m.valeur)).dp).background(col))
            }
        }
    }
}

@Composable
fun AgendaChip(scene: Scene, dao: PresenceDao) {
    val ev by dao.events(System.currentTimeMillis()).collectAsState(emptyList())
    val f = SimpleDateFormat("EEE HH:mm", Locale.CANADA_FRENCH)
    val c = Module.AGENDA.color()
    Chip("Agenda", c, "${ev.size}") {
        if (ev.isEmpty()) Body("Rien", color = Cp.mute, size = 12)
        ev.take(2).forEach { e ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Body(e.quoi, size = 12)
                Mono(f.format(Date(e.quand)), c, 9)
            }
        }
    }
}
