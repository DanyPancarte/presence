package com.dany.presence.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.dany.presence.brain.Scene
import com.dany.presence.data.Module
import com.dany.presence.data.PresenceDao
import com.dany.presence.render.HoloState
import com.dany.presence.render.Mood
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---- primitives -----------------------------------------------------------------------------

@Composable
fun Label(text: String, color: Color = Cp.yellow, size: Int = 12) = Text(
    text.uppercase(), color = color,
    style = TextStyle(fontFamily = Cp.display, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = (size * 0.22f).sp),
)

@Composable
fun Mono(text: String, color: Color = Cp.mute, size: Int = 11) = Text(
    text, color = color, style = TextStyle(fontFamily = Cp.mono, fontSize = size.sp, letterSpacing = 1.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
)

@Composable
fun Body(text: String, color: Color = Cp.ink, size: Int = 16, weight: FontWeight = FontWeight.SemiBold, maxLines: Int = 2) = Text(
    text, color = color, style = TextStyle(fontFamily = Cp.display, fontWeight = weight, fontSize = size.sp), maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

/** Cut-corner panel with the cyan rail. */
@Composable
fun Panel(title: String, meta: String, red: Boolean = false, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = Cp.cut(36f)
    val accent = if (red) Cp.red else Cp.cyan
    Box(
        modifier
            .widthIn(max = 420.dp)
            .clip(shape)
            .background(Cp.panel)
            .border(1.dp, if (red) Cp.red.copy(alpha = 0.6f) else Cp.cyanDim, shape),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Label(title, if (red) Cp.red else Cp.yellow)
                Mono(meta)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun Reveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) = AnimatedVisibility(
    visible, modifier = modifier,
    enter = fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 8 },
    exit = fadeOut(tween(260)),
) { content() }

// ---- the overlay layer -----------------------------------------------------------------------

@Composable
fun Overlays(scene: Scene, state: HoloState, dao: PresenceDao, modifier: Modifier = Modifier) {
    Box(modifier) {
        // Corner tag
        Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 14.dp).border(0.dp, Color.Transparent)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(30.dp).background(Cp.yellow))
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(if (scene.mood == Mood.ALERTE) Cp.red else Cp.cyan))
                        Spacer(Modifier.width(8.dp))
                        Label("Présence", size = 13)
                    }
                    Mono(scene.mood.name.lowercase() + " · " + SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH).format(Date()), size = 10)
                }
            }
        }

        // Bottom: radio / thinking / alert / error
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
            Reveal(scene.mood == Mood.ECOUTE) { RadioPanel(scene, state) }
            Reveal(scene.mood == Mood.REFLEXION) { ThinkPanel(scene) }
            Reveal(scene.mood == Mood.ALERTE && scene.error.isEmpty()) {
                Panel("Alerte", "signal", red = true) { Body(scene.say.ifBlank { "…" }, maxLines = 3) }
            }
            Reveal(scene.error.isNotEmpty()) { Panel("Erreur", "cerveau", red = true) { Body(scene.error, maxLines = 3) } }
            Reveal(scene.say.isNotEmpty() && scene.mood != Mood.ALERTE) {
                Text(
                    scene.say, color = Cp.ink, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Cp.display, fontWeight = FontWeight.Medium, fontSize = 19.sp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        // Middle: the module overlay
        Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp).padding(top = 60.dp)) {
            Reveal(scene.module != Module.AUCUN) {
                when (scene.module) {
                    Module.TACHES -> TasksPanel(scene, dao)
                    Module.NOTES -> NotesPanel(scene, dao)
                    Module.MEDS -> MedsPanel(scene, dao)
                    Module.BUDGET -> BudgetPanel(scene, dao)
                    Module.MOOD -> MoodPanel(scene, dao)
                    Module.AGENDA -> AgendaPanel(scene, dao)
                    Module.AUCUN -> Unit
                }
            }
        }
    }
}

@Composable
fun RadioPanel(scene: Scene, state: HoloState) = Panel("Écoute", "fr-CA · 16 kHz · offline") {
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val b = state.bands
        val n = 24
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = (0.04f + b[i] * 0.96f) * size.height
            drawRect(Cp.cyan.copy(alpha = 0.85f), Offset(i * (w + gap), size.height - h), Size(w, h))
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Body("MIC 01", size = 26, weight = FontWeight.Bold, maxLines = 1)
        Mono("${(-60 + state.amp * 58).toInt()} dB")
    }
    Body(if (scene.heard.isBlank()) "▍" else scene.heard + " ▍", weight = FontWeight.Medium, maxLines = 2)
}

@Composable
fun ThinkPanel(scene: Scene) = Panel("Réflexion", "gemini") {
    Body(scene.heard, color = Cp.mute, size = 14, weight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    scene.thinking.takeLast(3).forEach { Mono(it, color = Cp.cyan) }
}

@Composable
fun TasksPanel(scene: Scene, dao: PresenceDao) {
    val tasks by dao.tasks().collectAsState(emptyList())
    Panel("Tâches · 97 %", scene.moduleOp) {
        if (tasks.isEmpty()) Body("Aucun projet traqué", color = Cp.mute, size = 14)
        tasks.take(3).forEachIndexed { i, t ->
            val hot = i == 0 && scene.moduleOp.startsWith("+")
            val col = if (hot) Cp.yellow else if (i == 0) Cp.ink else Cp.ink.copy(alpha = 0.55f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Body(t.nom, color = col, size = 15, maxLines = 1)
                Mono("${t.avancement} %", Cp.yellow, 12)
            }
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Cp.cyan.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(t.avancement / 100f).fillMaxHeight().background(if (hot) Cp.yellow else if (i == 0) Cp.cyan else Cp.mute))
            }
            if (i < 2) Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun NotesPanel(scene: Scene, dao: PresenceDao) {
    val notes by dao.notes().collectAsState(emptyList())
    Panel("Note vocale", "#${notes.size.toString().padStart(4, '0')}") {
        Mono(if (scene.moduleOp.startsWith("+")) "enregistrée" else "dernières", size = 10)
        Spacer(Modifier.height(4.dp))
        if (notes.isEmpty()) Body("Rien encore", color = Cp.mute, size = 14)
        notes.take(3).forEachIndexed { i, n -> Body(n.texte, color = if (i == 0) Cp.ink else Cp.ink.copy(alpha = 0.55f), size = 15, maxLines = 1) }
    }
}

@Composable
fun MedsPanel(scene: Scene, dao: PresenceDao) {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.CANADA_FRENCH).format(Date())
    val med by dao.medFlow(today).collectAsState(null)
    val taken = med?.prisA != null
    Panel("Médicament", if (taken) SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH).format(Date(med!!.prisA!!)) else "08:00") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(44.dp)) {
                val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Butt)
                drawArc(Cp.cyan.copy(alpha = 0.18f), 0f, 360f, false, style = stroke)
                drawArc(if (taken) Cp.cyan else Cp.red, -90f, if (taken) 360f else 300f, false, style = stroke)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Body(if (taken) "Pris" else "Pas confirmé", size = 18)
                Mono(if (taken) "anneau fermé" else "anneau ouvert · pulse", size = 10)
            }
        }
    }
}

@Composable
fun BudgetPanel(scene: Scene, dao: PresenceDao) {
    val monthStart = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    val ex by dao.expenses(monthStart).collectAsState(emptyList())
    val spent = ex.sumOf { it.montant }
    val budget = 600.0
    Panel("Budget", SimpleDateFormat("MMM", Locale.CANADA_FRENCH).format(Date())) {
        Row(verticalAlignment = Alignment.Bottom) {
            Body("${(budget - spent).toInt()}", size = 28, weight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Mono("$ MARGE", size = 12)
        }
        Mono(ex.firstOrNull()?.let { "dernière · −${it.montant.toInt()} $ · ${it.quoi}" } ?: "aucune dépense ce mois", size = 10)
    }
}

@Composable
fun MoodPanel(scene: Scene, dao: PresenceDao) {
    val moods by dao.moods().collectAsState(emptyList())
    Panel("Mood", "14 j") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            moods.reversed().takeLast(14).forEach { m ->
                val c = when { m.valeur > 0 -> Cp.cyan; m.valeur < 0 -> Cp.red; else -> Cp.mute }
                Box(Modifier.size(12.dp).border(2.dp, c.copy(alpha = 0.5f + 0.25f * kotlin.math.abs(m.valeur))))
            }
        }
        moods.firstOrNull()?.let { Spacer(Modifier.height(6.dp)); Body(it.note.ifBlank { "valeur ${it.valeur}" }, size = 15, maxLines = 1) }
    }
}

@Composable
fun AgendaPanel(scene: Scene, dao: PresenceDao) {
    val ev by dao.events(System.currentTimeMillis()).collectAsState(emptyList())
    val f = SimpleDateFormat("EEE d · HH:mm", Locale.CANADA_FRENCH)
    Panel("Agenda", "${ev.size} à venir") {
        if (ev.isEmpty()) Body("Rien de prévu", color = Cp.mute, size = 14)
        ev.take(3).forEach { e ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Body(e.quoi, size = 15, maxLines = 1)
                Mono(f.format(Date(e.quand)), Cp.yellow, 11)
            }
        }
    }
}
