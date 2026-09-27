package app.murmure.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.murmure.ai.Emotions
import app.murmure.ai.Energy
import app.murmure.ai.NoteTypes
import app.murmure.core.Dates
import app.murmure.ui.Routes
import app.murmure.ui.capture.prettyWhen
import app.murmure.ui.components.Banner
import app.murmure.ui.components.Card
import app.murmure.ui.components.Eyebrow
import app.murmure.ui.components.Feedback
import app.murmure.ui.components.GhostButton
import app.murmure.ui.components.IconAction
import app.murmure.ui.components.MarkdownText
import app.murmure.ui.components.PrimaryButton
import app.murmure.ui.components.PulsingDot
import app.murmure.ui.components.Tag
import app.murmure.ui.components.TopBar
import app.murmure.ui.components.moodFaces
import app.murmure.ui.components.moodLabels
import app.murmure.ui.components.pressScale
import app.murmure.ui.theme.M
import app.murmure.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun ReviewScreen(nav: NavHostController, id: String) {
    val vm: ReviewViewModel = viewModel()
    LaunchedEffect(id) { vm.load(id) }
    val ui by vm.ui.collectAsState()
    var confirmDiscard by remember { mutableStateOf(false) }
    fun home() = nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }

    if (ui.filed) { Success(ui, ::home); return }

    Column(Modifier.fillMaxSize()) {
        TopBar("Ce que j'ai retenu", onBack = { nav.popBackStack() }, backIcon = Icons.Rounded.Close) {
            if (!ui.loading) {
                IconAction(Icons.Rounded.Refresh, "Réanalyser avec l'IA", onClick = vm::reanalyze)
                IconAction(Icons.Rounded.DeleteOutline, "Jeter cette dictée") { confirmDiscard = true }
            }
        }
        if (ui.loading) { Thinking(); return@Column }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            ui.warning?.let { Banner(it, M.Butter); Spacer(Modifier.height(12.dp)) }
            Summary(ui)
            ui.insight?.let {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(M.Lilac.copy(alpha = 0.12f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text("✨", fontSize = 16.sp); Spacer(Modifier.width(8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = M.Text) }
            }

            if (ui.notes.isNotEmpty()) {
                SectionTitle("Notes", ui.keptNotes.size, M.Lilac)
                ui.notes.forEachIndexed { i, d -> NoteCard(i, d, ui, vm); Spacer(Modifier.height(10.dp)) }
            }
            if (ui.tasks.isNotEmpty()) {
                SectionTitle("Tâches", ui.tasks.count { it.accepted }, M.Mint)
                Card(tint = M.Mint) { ui.tasks.forEachIndexed { i, t -> TaskRow(t, { vm.toggleTask(i) }, { vm.setDue(i, it) }) } }
                Spacer(Modifier.height(10.dp))
            }
            if (ui.events.isNotEmpty()) {
                SectionTitle("Agenda", ui.events.count { it.accepted }, M.Sky)
                Card(tint = M.Sky) { ui.events.forEachIndexed { i, e -> EventRow(e) { vm.toggleEvent(i) } } }
                Spacer(Modifier.height(10.dp))
            }
            if (ui.capture?.isDaily == true || ui.mood != null || ui.proposal?.moodEmotion != null) {
                SectionTitle("Mood", if (ui.mood != null) 1 else 0, M.Rose)
                MoodCard(ui, vm)
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(16.dp))
            Eyebrow("Transcription brute")
            Spacer(Modifier.height(6.dp))
            Text(ui.capture?.transcript.orEmpty(), style = MaterialTheme.typography.bodySmall, color = M.Faint)
            Spacer(Modifier.height(24.dp))
        }

        Row(
            Modifier.fillMaxWidth().background(M.Ink).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhostButton("Plus tard", Modifier.weight(0.8f)) { vm.later(::home) }
            PrimaryButton("Tout valider · ${ui.total}", Modifier.weight(1.6f), leading = Icons.Rounded.Check, enabled = ui.total > 0) { vm.file() }
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, containerColor = M.Surface,
        title = { Text("Jeter cette dictée ?") },
        text = { Text("La transcription et tout ce qui en a été tiré disparaissent.", color = M.Muted) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discard(::home) }) { Text("Jeter", color = M.Coral) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Garder", color = M.Text) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(ui: ReviewUi) {
    val c = ui.capture
    Text(
        if (c != null) Dates.pretty(c.createdAt) + " · ${c.durationSec / 60}:${"%02d".format(c.durationSec % 60)}" else "",
        style = MaterialTheme.typography.labelMedium, color = M.Faint,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        when {
            ui.total == 0 -> "Rien à retenir ?"
            ui.notes.size >= 2 -> "${ui.notes.size} sujets, bien séparés."
            ui.tasks.isNotEmpty() && ui.events.isNotEmpty() -> "Une note, des actions, un rendez-vous."
            ui.tasks.isNotEmpty() -> "Une note et ${ui.tasks.size} action${if (ui.tasks.size > 1) "s" else ""}."
            else -> "Une note, prête à classer."
        },
        style = MaterialTheme.typography.headlineMedium, color = M.Text,
    )
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ui.keptNotes.isNotEmpty()) Tag("${ui.keptNotes.size} note${if (ui.keptNotes.size > 1) "s" else ""}", M.Lilac, leading = "📝")
        ui.tasks.count { it.accepted }.takeIf { it > 0 }?.let { Tag("$it tâche${if (it > 1) "s" else ""}", M.Mint, leading = "✅") }
        ui.events.count { it.accepted }.takeIf { it > 0 }?.let { Tag("$it agenda", M.Sky, leading = "📅") }
        ui.mood?.let { Tag("mood ${moodLabels[it - 1].lowercase()}", Palette.mood(it.toFloat()), leading = moodFaces[it - 1]) }
        ui.keptNotes.flatMap { it.entities }.distinctBy { it.name }.take(4).forEach { Tag(it.name, Palette.kind(it.kind), leading = Palette.kindIcon(it.kind)) }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = M.Text, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteCard(i: Int, d: NoteDraft, ui: ReviewUi, vm: ReviewViewModel) {
    val p = d.proposal
    var changing by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val view = LocalView.current
    Card(tint = if (d.kept) Palette.type(d.type) else null, padding = 16.dp) {
        Column(Modifier.animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(NoteTypes.emoji(d.type), fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = d.title, onValueChange = { vm.setTitle(i, it) },
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = if (d.kept) M.Text else M.Faint),
                    cursorBrush = SolidColor(M.Peach), modifier = Modifier.weight(1f), maxLines = 2,
                )
                IconAction(if (d.expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (d.expanded) "Réduire" else "Détails") { vm.toggleExpanded(i) }
            }
            if (p.summary.isNotBlank()) Text(p.summary, style = MaterialTheme.typography.bodySmall, color = M.Muted, maxLines = if (d.expanded) 10 else 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            // ---- Dossier : la décision principale ----
            val sugg = p.folderSuggestions
            val top = sugg.firstOrNull { it.name == d.folder }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(d.folder, M.Rose, leading = "📁", trailing = { Text(top?.let { " ${(it.confidence * 100).toInt()} %" } ?: "", style = MaterialTheme.typography.labelSmall, color = M.Rose) }) { changing = !changing }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (changing) "Choisis ci-dessous" else (top?.reason ?: "Ton choix") + (top?.let { if (!it.existing) " · nouveau" else "" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = M.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Tag(if (d.kept) "Garder" else "Ignorée", if (d.kept) M.Mint else M.Faint, selected = d.kept) { vm.toggleKept(i) }
            }
            AnimatedVisibility(changing, enter = expandVertically() + fadeIn(), exit = shrinkVertically()) {
                Column(Modifier.padding(top = 10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sugg.forEach { s -> Tag(s.name, M.Rose, selected = s.name == d.folder, leading = if (s.existing) "📁" else "✨") { vm.setFolder(i, s.name); changing = false } }
                        ui.allFolders.filter { f -> sugg.none { it.name == f } }.forEach { f -> Tag(f, M.Sky, selected = f == d.folder) { vm.setFolder(i, f); changing = false } }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it }, singleLine = true,
                            placeholder = { Text("Nouveau dossier…", color = M.Faint) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = M.Lilac, unfocusedBorderColor = M.Line, focusedTextColor = M.Text, unfocusedTextColor = M.Text),
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { if (newName.isNotBlank()) { Feedback.tap(view); vm.setFolder(i, newName); newName = ""; changing = false } }) { Icon(Icons.Rounded.Add, "Créer", tint = M.Lilac) }
                    }
                }
            }
            AnimatedVisibility(d.expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically()) {
                Column(Modifier.padding(top = 12.dp)) {
                    Text("Type", style = MaterialTheme.typography.labelMedium, color = M.Muted)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NoteTypes.all.forEach { t -> Tag(NoteTypes.label(t), Palette.type(t), selected = t == d.type, leading = NoteTypes.emoji(t)) { vm.setType(i, t) } }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Émotion", style = MaterialTheme.typography.labelMedium, color = M.Muted)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Emotions.all.forEach { e -> Tag(e, Palette.emotion(e), selected = e == d.emotion, leading = Emotions.emoji(e)) { vm.setEmotion(i, e) } }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Énergie", style = MaterialTheme.typography.labelMedium, color = M.Muted, modifier = Modifier.width(70.dp))
                        Energy.all.forEach { e -> Tag(e, M.Sky, selected = e == d.energy) { vm.setEnergy(i, e) }; Spacer(Modifier.width(6.dp)) }
                    }
                    if (d.entities.isNotEmpty() || d.keywords.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Entités et mots-clés · touche pour retirer", style = MaterialTheme.typography.labelMedium, color = M.Muted)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.entities.forEach { e -> Tag(e.name, Palette.kind(e.kind), leading = Palette.kindIcon(e.kind), trailing = { Icon(Icons.Rounded.Close, null, tint = M.Muted, modifier = Modifier.size(13.dp)) }) { vm.removeEntity(i, e) } }
                            d.keywords.forEach { k -> Tag("#$k", M.Lilac, trailing = { Icon(Icons.Rounded.Close, null, tint = M.Muted, modifier = Modifier.size(13.dp)) }) { vm.removeKeyword(i, k) } }
                        }
                    }
                    if (p.links.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text("Liée à : " + p.links.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = M.Muted) }
                    Spacer(Modifier.height(12.dp))
                    Eyebrow("Aperçu")
                    Spacer(Modifier.height(6.dp))
                    MarkdownText(p.body)
                }
            }
        }
    }
}

@Composable
private fun Check(on: Boolean, color: Color, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    Box(
        Modifier.size(26.dp).pressScale(interaction, 0.8f).clip(RoundedCornerShape(8.dp))
            .background(if (on) color else M.Surface2).border(1.dp, if (on) color else M.Line, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null) { Feedback.tap(view); onToggle() },
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(Icons.Rounded.Check, null, tint = M.Ink, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun TaskRow(t: TaskChoice, onToggle: () -> Unit, onDue: (String?) -> Unit) {
    val today = Dates.today()
    val options = listOf(t.task.due, today.toString(), today.plusDays(1).toString(), today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).toString(), null).distinct()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Check(t.accepted, M.Mint, onToggle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.task.text, style = MaterialTheme.typography.bodyMedium, color = if (t.accepted) M.Text else M.Faint)
            if (t.task.reason.isNotBlank()) Text(t.task.reason, style = MaterialTheme.typography.labelSmall, color = M.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Tag(Dates.prettyDue(t.task.due), M.Butter, leading = "📅") { onDue(options[(options.indexOf(t.task.due) + 1) % options.size]) }
    }
}

@Composable
private fun EventRow(e: EventChoice, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Check(e.accepted, M.Sky, onToggle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.event.title, style = MaterialTheme.typography.bodyMedium, color = if (e.accepted) M.Text else M.Faint)
            val meta = listOfNotNull(e.event.where, e.event.who.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "avec $it" }).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = M.Faint)
        }
        Spacer(Modifier.width(8.dp))
        Tag(e.event.due?.let { prettyWhen(it, e.event.allDay) } ?: "?", M.Sky, leading = "🕐")
    }
}

@Composable
private fun MoodCard(ui: ReviewUi, vm: ReviewViewModel) {
    val view = LocalView.current
    Card(tint = M.Rose) {
        Text(
            when {
                ui.capture?.mood != null -> "Ton mood déclaré au début du rituel."
                ui.mood != null -> "D'après ce que tu as dit. Corrige si ça sonne faux."
                else -> "Et là, maintenant ?"
            },
            style = MaterialTheme.typography.bodySmall, color = M.Muted,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            moodFaces.forEachIndexed { i, f ->
                val on = ui.mood == i + 1
                val c = Palette.mood(i + 1f)
                val interaction = remember { MutableInteractionSource() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(46.dp).pressScale(interaction, 0.85f).clip(CircleShape)
                            .background(if (on) c.copy(alpha = 0.35f) else M.Surface2).border(1.dp, if (on) c else M.Line, CircleShape)
                            .clickable(interactionSource = interaction, indication = null) { Feedback.tap(view); vm.setMood(if (on) null else i + 1) },
                        contentAlignment = Alignment.Center,
                    ) { Text(f, fontSize = 22.sp) }
                    Text(moodLabels[i], style = MaterialTheme.typography.labelSmall, color = if (on) c else M.Faint)
                }
            }
        }
        ui.moodEmotion?.let { Spacer(Modifier.height(8.dp)); Tag(it, Palette.emotion(it), leading = Emotions.emoji(it)) }
    }
}

@Composable
private fun Thinking() {
    val steps = listOf("je relis", "je sépare les sujets", "je repère les actions", "je cherche les liens", "je classe")
    var i by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1100); i = (i + 1) % steps.size } }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = M.Lilac, strokeWidth = 3.dp)
        Spacer(Modifier.height(22.dp))
        Text("Je range tout ça…", style = MaterialTheme.typography.headlineMedium, color = M.Text)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { PulsingDot(M.Peach); Spacer(Modifier.width(8.dp)); Text(steps[i], style = MaterialTheme.typography.bodyMedium, color = M.Muted) }
    }
}

@Composable
private fun Success(ui: ReviewUi, onDone: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (shown) 1f else 0.3f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "s")
    val view = LocalView.current
    val daily = ui.capture?.isDaily == true
    LaunchedEffect(Unit) { shown = true; Feedback.confirm(view); delay(if (daily) 2800 else 1600); onDone() }
    Column(Modifier.fillMaxSize().clickable(onClick = onDone).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(120.dp).scale(s).clip(CircleShape).background(Brush.linearGradient(listOf(M.Mint, M.Sky))), contentAlignment = Alignment.Center) {
            if (daily) Text("🔥", fontSize = 54.sp) else Icon(Icons.Rounded.Check, null, tint = M.Ink, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (daily) "${ui.streakAfter} jour${if (ui.streakAfter > 1) "s" else ""} d'affilée" else "Rangé.",
            style = MaterialTheme.typography.displayMedium, color = M.Text, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (daily) "Boucle fermée. Merci d'avoir pris ce moment pour toi."
            else listOfNotNull(
                ui.keptNotes.size.takeIf { it > 0 }?.let { "$it note${if (it > 1) "s" else ""}" },
                ui.tasks.count { it.accepted }.takeIf { it > 0 }?.let { "$it tâche${if (it > 1) "s" else ""}" },
                ui.events.count { it.accepted }.takeIf { it > 0 }?.let { "$it rendez-vous" },
            ).joinToString(" · ").ifBlank { "Rien d'autre à faire." },
            style = MaterialTheme.typography.bodyLarge, color = M.Muted, textAlign = TextAlign.Center,
        )
    }
}
