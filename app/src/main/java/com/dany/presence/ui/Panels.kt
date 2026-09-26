package com.dany.presence.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

// ---- primitives -----------------------------------------------------------------------------

@Composable
fun Label(text: String, color: Color = Cp.yellow, size: Int = 11) = Text(
    text.uppercase(), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
    style = TextStyle(fontFamily = Cp.display, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = (size * 0.2f).sp),
)

@Composable
fun Mono(text: String, color: Color = Cp.mute, size: Int = 10) = Text(
    text, color = color, style = TextStyle(fontFamily = Cp.mono, fontSize = size.sp, letterSpacing = 0.8.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
)

@Composable
fun Body(text: String, color: Color = Cp.ink, size: Int = 14, weight: FontWeight = FontWeight.SemiBold, maxLines: Int = 2) = Text(
    text, color = color, style = TextStyle(fontFamily = Cp.display, fontWeight = weight, fontSize = size.sp), maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

/**
 * A small street-sign tooltip: cut corners, coloured rail, tilted in 3D like a panel bolted on a
 * wall. Never wider than 240 dp; the hologram stays visible around it.
 */
@Composable
fun Tooltip(
    title: String, meta: String, accent: Color, modifier: Modifier = Modifier,
    tiltY: Float = -16f, tiltX: Float = 5f, width: Int = 236, content: @Composable () -> Unit,
) {
    val shape = Cp.cut(26f)
    Box(
        modifier
            .width(width.dp)
            .graphicsLayer {
                rotationY = tiltY; rotationX = tiltX
                cameraDistance = 9f * density
                transformOrigin = TransformOrigin(if (tiltY < 0) 1f else 0f, 0.5f)
            }
            .clip(shape)
            .background(Cp.panel)
            .border(1.dp, accent.copy(alpha = 0.55f), shape),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Label(title, accent)
                Spacer(Modifier.width(8.dp))
                Mono(meta)
            }
            Spacer(Modifier.height(5.dp))
            content()
        }
    }
}

@Composable
fun Reveal(visible: Boolean, fromRight: Boolean = true, modifier: Modifier = Modifier, content: @Composable () -> Unit) = AnimatedVisibility(
    visible, modifier = modifier,
    enter = fadeIn(tween(240)) + slideInHorizontally(tween(280)) { if (fromRight) it / 6 else -it / 6 },
    exit = fadeOut(tween(220)),
) { content() }

// ---- the overlay layer -----------------------------------------------------------------------

@Composable
fun Overlays(scene: Scene, state: HoloState, dao: PresenceDao, modifier: Modifier = Modifier) {
    val moodColor = when (scene.mood) {
        Mood.VEILLE -> Cp.mute; Mood.ECOUTE -> Cp.cyan; Mood.REFLEXION -> Cp.yellow; Mood.REPONSE -> Cp.ink; Mood.ALERTE -> Cp.red
    }
    Box(modifier) {
        // Top-left: identity + status strip (what it is doing, right now)
        Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(26.dp).background(Cp.yellow))
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(moodColor))
                        Spacer(Modifier.width(8.dp))
                        Label("Présence", size = 12)
                    }
                    Mono(scene.mood.name.lowercase() + " · " + SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH).format(Date()), size = 9)
                }
            }
            Spacer(Modifier.height(10.dp))
            scene.steps.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(if (s.done) "✓" else "▸", if (s.done) Cp.mute else moodColor, 10)
                    Spacer(Modifier.width(6.dp))
                    Label(s.label, if (s.done) Cp.mute else moodColor, 10)
                    if (s.detail.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Mono(s.detail, size = 9) }
                }
            }
        }

        // Right column: live captures (stacked, tilted) then the module tooltip
        Column(Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 12.dp), horizontalAlignment = Alignment.End) {
            scene.detections.take(3).forEachIndexed { i, d ->
                Reveal(true) {
                    Tooltip(d.module.name, d.op, d.module.color(), Modifier.padding(bottom = 6.dp, end = (i * 6).dp), width = 180) {
                        Mono("« ${d.hint} »", d.module.color(), 10)
                    }
                }
            }
            Reveal(scene.module != Module.AUCUN) {
                Box(Modifier.padding(top = 6.dp)) {
                    when (scene.module) {
                        Module.TACHES -> TasksTip(scene, dao)
                        Module.NOTES -> NotesTip(scene, dao)
                        Module.MEDS -> MedsTip(scene, dao)
                        Module.BUDGET -> BudgetTip(scene, dao)
                        Module.MOOD -> MoodTip(scene, dao)
                        Module.AGENDA -> AgendaTip(scene, dao)
                        Module.AUCUN -> Unit
                    }
                }
            }
        }

        // Bottom-left: radio while listening, analysis while thinking, alert / error
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 96.dp)) {
            Reveal(scene.mood == Mood.ECOUTE, fromRight = false) { RadioTip(scene, state) }
            Reveal(scene.mood == Mood.REFLEXION, fromRight = false) {
                Tooltip("Analyse", "…", Cp.yellow, tiltY = 16f, tiltX = -4f) { Body(scene.heard, color = Cp.mute, size = 13, weight = FontWeight.Medium) }
            }
            Reveal(scene.error.isNotEmpty(), fromRight = false) { Tooltip("Erreur", "cerveau", Cp.red, tiltY = 16f, tiltX = -4f) { Body(scene.error, size = 13, maxLines = 3) } }
        }

        // Bottom: the spoken line
        Reveal(scene.say.isNotEmpty(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(
                scene.say, color = if (scene.mood == Mood.ALERTE) Cp.red else Cp.ink, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Cp.display, fontWeight = FontWeight.Medium, fontSize = 18.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
            )
        }
    }
}

@Composable
fun RadioTip(scene: Scene, state: HoloState) = Tooltip("Écoute", "fr-CA · 16k", Cp.cyan, tiltY = 16f, tiltX = -4f) {
    Canvas(Modifier.fillMaxWidth().height(26.dp)) {
        val b = state.bands
        val gap = 2.dp.toPx(); val w = (size.width - gap * 23) / 24
        for (i in 0 until 24) {
            val h = (0.05f + b[i] * 0.95f) * size.height
            drawRect(Cp.cyan.copy(alpha = 0.85f), Offset(i * (w + gap), size.height - h), Size(w, h))
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Body("MIC 01", size = 18, weight = FontWeight.Bold, maxLines = 1)
        Mono("${(-60 + state.amp * 58).toInt()} dB")
    }
    Body(if (scene.heard.isBlank()) "▍" else scene.heard + " ▍", size = 13, weight = FontWeight.Medium, maxLines = 3)
}

@Composable
fun TasksTip(scene: Scene, dao: PresenceDao) {
    val tasks by dao.tasks().collectAsState(emptyList())
    val c = Module.TACHES.color()
    Tooltip("Tâches · 97 %", scene.moduleOp, c) {
        if (tasks.isEmpty()) Body("Aucun projet traqué", color = Cp.mute, size = 12)
        tasks.take(3).forEachIndexed { i, t ->
            val hot = i == 0 && scene.moduleOp.startsWith("+")
            val col = if (hot) c else if (i == 0) Cp.ink else Cp.ink.copy(alpha = 0.55f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Body(t.nom, color = col, size = 13, maxLines = 1)
                Mono("${t.avancement} %", c, 10)
            }
            Spacer(Modifier.height(2.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(c.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(t.avancement / 100f).fillMaxHeight().background(if (hot || i == 0) c else Cp.mute))
            }
            if (i < 2) Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
fun NotesTip(scene: Scene, dao: PresenceDao) {
    val notes by dao.notes().collectAsState(emptyList())
    Tooltip("Note", "#${notes.size.toString().padStart(4, '0')}", Module.NOTES.color()) {
        if (notes.isEmpty()) Body("Rien encore", color = Cp.mute, size = 12)
        notes.take(3).forEachIndexed { i, n -> Body(n.texte, color = if (i == 0) Cp.ink else Cp.ink.copy(alpha = 0.5f), size = 13, maxLines = 1) }
    }
}

@Composable
fun MedsTip(scene: Scene, dao: PresenceDao) {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.CANADA_FRENCH).format(Date())
    val med by dao.medFlow(today).collectAsState(null)
    val taken = med?.prisA != null
    val c = Module.MEDS.color()
    Tooltip("Médicament", if (taken) SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH).format(Date(med!!.prisA!!)) else "08:00", c) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(34.dp)) {
                val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Butt)
                drawArc(c.copy(alpha = 0.18f), 0f, 360f, false, style = stroke)
                drawArc(if (taken) c else Cp.red, -90f, if (taken) 360f else 300f, false, style = stroke)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Body(if (taken) "Pris" else "Pas confirmé", size = 15)
                Mono(if (taken) "anneau fermé" else "anneau ouvert", size = 9)
            }
        }
    }
}

@Composable
fun BudgetTip(scene: Scene, dao: PresenceDao) {
    val monthStart = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    val ex by dao.expenses(monthStart).collectAsState(emptyList())
    val spent = ex.sumOf { it.montant }
    val c = Module.BUDGET.color()
    Tooltip("Budget", SimpleDateFormat("MMM", Locale.CANADA_FRENCH).format(Date()), c) {
        Row(verticalAlignment = Alignment.Bottom) {
            Body("${(600.0 - spent).toInt()}", size = 24, weight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Mono("$ MARGE", c, 10)
        }
        Mono(ex.firstOrNull()?.let { "−${it.montant.toInt()} $ · ${it.quoi}" } ?: "aucune dépense", size = 9)
    }
}

@Composable
fun MoodTip(scene: Scene, dao: PresenceDao) {
    val moods by dao.moods().collectAsState(emptyList())
    val c = Module.MOOD.color()
    Tooltip("Mood", "14 j", c) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            moods.reversed().takeLast(14).forEach { m ->
                val col = when { m.valeur > 0 -> c; m.valeur < 0 -> Cp.red; else -> Cp.mute }
                Box(Modifier.size(10.dp).border(2.dp, col.copy(alpha = 0.5f + 0.25f * kotlin.math.abs(m.valeur))))
            }
        }
        moods.firstOrNull()?.let { Spacer(Modifier.height(5.dp)); Body(it.note.ifBlank { "valeur ${it.valeur}" }, size = 13, maxLines = 1) }
    }
}

@Composable
fun AgendaTip(scene: Scene, dao: PresenceDao) {
    val ev by dao.events(System.currentTimeMillis()).collectAsState(emptyList())
    val f = SimpleDateFormat("EEE d · HH:mm", Locale.CANADA_FRENCH)
    val c = Module.AGENDA.color()
    Tooltip("Agenda", "${ev.size} à venir", c) {
        if (ev.isEmpty()) Body("Rien de prévu", color = Cp.mute, size = 12)
        ev.take(3).forEach { e ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Body(e.quoi, size = 13, maxLines = 1)
                Mono(f.format(Date(e.quand)), c, 9)
            }
        }
    }
}
