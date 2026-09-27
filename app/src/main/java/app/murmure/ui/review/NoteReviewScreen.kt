package app.murmure.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.murmure.ai.Emotions
import app.murmure.ai.Energy
import app.murmure.ai.NoteTypes
import app.murmure.core.Dates
import app.murmure.data.EntityKind
import app.murmure.ui.Routes
import app.murmure.ui.components.Banner
import app.murmure.ui.components.Card
import app.murmure.ui.components.Eyebrow
import app.murmure.ui.components.GhostButton
import app.murmure.ui.components.MarkdownText
import app.murmure.ui.components.PrimaryButton
import app.murmure.ui.components.PulsingDot
import app.murmure.ui.components.Tag
import app.murmure.ui.components.TopBar
import app.murmure.ui.theme.M
import app.murmure.ui.theme.Palette
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteReviewScreen(nav: NavHostController, id: String) {
    val vm: NoteReviewViewModel = viewModel()
    LaunchedEffect(id) { vm.load(id) }
    val ui by vm.ui.collectAsState()

    if (ui.filed) {
        Success(ui) { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } }
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Reclasser la note", onBack = { nav.popBackStack() }, backIcon = Icons.Rounded.Close) {
            if (!ui.loading) IconButton(onClick = vm::reanalyze) { Icon(Icons.Rounded.Refresh, "Réanalyser", tint = M.Muted) }
        }
        if (ui.loading) { Thinking(); return@Column }
        val p = ui.proposal
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            ui.warning?.let { Banner(it, M.Butter); Spacer(Modifier.height(12.dp)) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(NoteTypes.label(ui.type), Palette.type(ui.type), leading = NoteTypes.emoji(ui.type))
                Spacer(Modifier.width(8.dp))
                ui.note?.let { Text(Dates.pretty(it.createdAt), style = MaterialTheme.typography.labelMedium, color = M.Faint) }
            }
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = ui.title, onValueChange = vm::setTitle,
                textStyle = MaterialTheme.typography.headlineLarge.copy(color = M.Text),
                cursorBrush = SolidColor(M.Peach), modifier = Modifier.fillMaxWidth(),
            )
            if (!p?.summary.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(p!!.summary, style = MaterialTheme.typography.bodyMedium, color = M.Muted)
            }
            Spacer(Modifier.height(18.dp))

            FolderCard(ui, vm)
            Spacer(Modifier.height(14.dp))

            // ---------- Classification ----------
            Card {
                Eyebrow("Classification proposée")
                Spacer(Modifier.height(10.dp))
                Text("Type", style = MaterialTheme.typography.labelMedium, color = M.Muted)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoteTypes.all.forEach { t ->
                        Tag(NoteTypes.label(t), Palette.type(t), selected = t == ui.type, leading = NoteTypes.emoji(t)) { vm.setType(t) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Émotion dominante", style = MaterialTheme.typography.labelMedium, color = M.Muted)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Emotions.all.forEach { e ->
                        Tag(e, Palette.emotion(e), selected = e == ui.emotion, leading = Emotions.emoji(e)) { vm.setEmotion(e) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Énergie", style = MaterialTheme.typography.labelMedium, color = M.Muted, modifier = Modifier.width(70.dp))
                    Energy.all.forEach { e ->
                        Tag(e, M.Sky, selected = e == ui.energy) { vm.setEnergy(e) }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                ui.note?.let { n ->
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Contexte", style = MaterialTheme.typography.labelMedium, color = M.Muted, modifier = Modifier.width(70.dp))
                        Tag(n.timeOfDay.ifBlank { "—" }, M.Butter, leading = "HR")
                        n.mood?.let { Spacer(Modifier.width(6.dp)); Tag("mood $it/5", Palette.mood(it.toFloat()), leading = app.murmure.ui.components.moodFaces[it - 1]) }
                    }
                }
            }

            if (ui.entities.isNotEmpty() || ui.keywords.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Card {
                    Eyebrow("Entités et liens détectés")
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ui.entities.forEach { e ->
                            Tag(e.name, Palette.kind(e.kind), leading = Palette.kindIcon(e.kind), trailing = {
                                Icon(Icons.Rounded.Close, "Retirer", tint = M.Muted, modifier = Modifier.size(14.dp).clickable { vm.removeEntity(e) })
                            })
                        }
                        ui.keywords.forEach { k ->
                            Tag("#$k", M.Lilac, trailing = {
                                Icon(Icons.Rounded.Close, "Retirer", tint = M.Muted, modifier = Modifier.size(14.dp).clickable { vm.removeKeyword(k) })
                            })
                        }
                    }
                    if (!p?.links.isNullOrEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Liée à : " + p!!.links.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = M.Muted)
                    }
                    Text(
                        "Légende : " + EntityKind.all.joinToString("  ") { "${Palette.kindIcon(it)} ${EntityKind.label(it)}" },
                        style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            if (ui.tasks.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Card(tint = M.Mint) {
                    Eyebrow("Tâches repérées · à toi de voir", color = M.Mint)
                    Spacer(Modifier.height(8.dp))
                    ui.tasks.forEachIndexed { i, t -> TaskRow(t, onToggle = { vm.toggleTask(i) }, onDue = { vm.setDue(i, it) }) }
                }
            }

            Spacer(Modifier.height(14.dp))
            Card {
                Eyebrow("Aperçu de la note")
                Spacer(Modifier.height(8.dp))
                MarkdownText(p?.body.orEmpty())
            }
            Spacer(Modifier.height(20.dp))
        }

        // ---------- Actions ----------
        Row(
            Modifier.fillMaxWidth().background(M.Ink).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhostButton("Plus tard", Modifier.weight(0.8f)) { vm.later { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } } }
            PrimaryButton("Classer · ${ui.folder.let { if (it.length > 16) it.take(15) + "…" else it }}", Modifier.weight(1.6f), leading = Icons.Rounded.Check) { vm.file() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderCard(ui: NoteReviewUi, vm: NoteReviewViewModel) {
    var changing by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val sugg = ui.proposal?.folderSuggestions.orEmpty()
    val top = sugg.firstOrNull { it.name == ui.folder }
    Card(tint = M.Rose) {
        Eyebrow(if (ui.proposal?.declaredFolder != null) "Dossier annoncé" else "Classement recommandé", color = M.Rose)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DO", fontSize = 26.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.folder, style = MaterialTheme.typography.headlineSmall, color = M.Text)
                val why = top?.reason ?: if (ui.folderConfirmed) "Ton choix" else ""
                if (why.isNotBlank()) Text(
                    why + (top?.let { if (!it.existing) " · nouveau dossier" else "" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = M.Muted,
                )
            }
            if (top != null) Text("${(top.confidence * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, color = M.Rose)
        }
        Spacer(Modifier.height(12.dp))
        Tag(if (changing) "Garder ce dossier" else "Changer de dossier", M.Lilac, selected = changing, leading = if (changing) "OK" else "↺") { changing = !changing }
        AnimatedVisibility(changing, enter = expandVertically() + fadeIn(), exit = shrinkVertically()) {
            Column(Modifier.padding(top = 12.dp)) {
                if (sugg.size > 1) {
                    Text("Autres recommandations", style = MaterialTheme.typography.labelMedium, color = M.Muted)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sugg.forEach { s -> Tag(s.name, M.Rose, selected = s.name == ui.folder, leading = if (s.existing) "DO" else "SG") { vm.setFolder(s.name); changing = false } }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Text("Tous les dossiers", style = MaterialTheme.typography.labelMedium, color = M.Muted)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.allFolders.forEach { f -> Tag(f, M.Sky, selected = f == ui.folder) { vm.setFolder(f); changing = false } }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it }, singleLine = true,
                        placeholder = { Text("Nouveau dossier…", color = M.Faint) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = M.Lilac, unfocusedBorderColor = M.Line, focusedTextColor = M.Text, unfocusedTextColor = M.Text),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { if (newName.isNotBlank()) { vm.setFolder(newName); newName = ""; changing = false } }) {
                        Icon(Icons.Rounded.Add, "Créer", tint = M.Lilac)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: NoteTaskChoice, onToggle: () -> Unit, onDue: (String?) -> Unit) {
    val today = Dates.today()
    val options = listOf(
        t.task.due, today.toString(), today.plusDays(1).toString(),
        today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).toString(), null,
    ).distinct()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                .background(if (t.accepted) M.Mint else M.Surface2)
                .border(1.dp, if (t.accepted) M.Mint else M.Line, RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) { if (t.accepted) Icon(Icons.Rounded.Check, null, tint = M.Ink, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.task.text, style = MaterialTheme.typography.bodyMedium, color = if (t.accepted) M.Text else M.Faint)
            if (t.task.reason.isNotBlank()) Text(t.task.reason, style = MaterialTheme.typography.labelSmall, color = M.Faint)
        }
        Spacer(Modifier.width(8.dp))
        Tag(Dates.prettyDue(t.task.due), M.Butter, leading = "EV") {
            val i = options.indexOf(t.task.due)
            onDue(options[(i + 1) % options.size])
        }
    }
}

@Composable
private fun Thinking() {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = M.Lilac, strokeWidth = 3.dp)
        Spacer(Modifier.height(22.dp))
        Text("Je structure ta note…", style = MaterialTheme.typography.headlineMedium, color = M.Text)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(M.Peach); Spacer(Modifier.width(8.dp))
            Text("titre · dossier · émotions · liens · tâches", style = MaterialTheme.typography.bodySmall, color = M.Muted)
        }
    }
}

@Composable
private fun Success(ui: NoteReviewUi, onDone: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (shown) 1f else 0.3f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "s")
    val daily = ui.note?.isDaily == true
    LaunchedEffect(Unit) { shown = true; delay(if (daily) 2600 else 1400); onDone() }
    Column(
        Modifier.fillMaxSize().clickable(onClick = onDone).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(120.dp).scale(s).clip(CircleShape)
                .background(Brush.linearGradient(listOf(M.Mint, M.Sky))),
            contentAlignment = Alignment.Center,
        ) {
            if (daily) Text("ST", fontSize = 54.sp) else Icon(Icons.Rounded.Check, null, tint = M.Ink, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (daily) "${ui.streakAfter} jour${if (ui.streakAfter > 1) "s" else ""} d'affilée" else "Classée.",
            style = MaterialTheme.typography.displayMedium, color = M.Text, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (daily) "Boucle fermée. Merci d'avoir pris ce moment pour toi." else "Dans « ${ui.folder} ». Rien d'autre à faire.",
            style = MaterialTheme.typography.bodyLarge, color = M.Muted, textAlign = TextAlign.Center,
        )
    }
}
