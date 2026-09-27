package app.murmure.ui.tasks

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.murmure.MurmureApp
import app.murmure.core.Dates
import app.murmure.data.TaskEntity
import app.murmure.data.EventStatus
import app.murmure.ui.capture.prettyWhen
import app.murmure.ui.components.IconAction
import app.murmure.data.TaskStatus
import app.murmure.ui.Routes
import app.murmure.ui.components.EmptyState
import app.murmure.ui.components.Eyebrow
import app.murmure.ui.components.Tag
import app.murmure.ui.theme.M
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(nav: NavHostController) {
    val repo = MurmureApp.instance.repo
    val tasks by repo.tasks.collectAsState(initial = emptyList())
    val events by repo.events.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val today = Dates.today()
    val suggestedEvents = events.filter { it.status == EventStatus.SUGGESTED }
    val upcoming = events.filter { it.status == EventStatus.CONFIRMED && Dates.parseDay(it.startAt)?.isBefore(today) == false }.take(12)

    val suggested = tasks.filter { it.status == TaskStatus.SUGGESTED }
    val open = tasks.filter { it.status == TaskStatus.OPEN }
    val done = tasks.filter { it.status == TaskStatus.DONE }.take(15)
    val late = open.filter { Dates.parseDay(it.dueDate)?.isBefore(today) == true }
    val now = open.filter { Dates.parseDay(it.dueDate) == today }
    val week = open.filter { d -> Dates.parseDay(d.dueDate)?.let { it.isAfter(today) && it.isBefore(today.plusDays(7)) } == true }
    val later = open.filter { d -> Dates.parseDay(d.dueDate)?.let { !it.isBefore(today.plusDays(7)) } == true }
    val undated = open.filter { it.dueDate == null }

    fun set(t: TaskEntity, s: String) = scope.launch { repo.setTask(t.id, s) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
        item {
            Text("Agenda", style = MaterialTheme.typography.displayMedium, color = M.Text)
            Text(
                when {
                    open.isEmpty() -> "Rien qui presse."
                    late.isNotEmpty() -> "${open.size} en cours · ${now.size} aujourd'hui · ${late.size} en retard"
                    else -> "${open.size} en cours · ${now.size} aujourd'hui"
                },
                style = MaterialTheme.typography.bodyMedium, color = M.Muted,
            )
            Spacer(Modifier.padding(8.dp))
        }
        if (tasks.isEmpty() && events.isEmpty()) item {
            EmptyState("🫶", "Rien à faire, rien de prévu", "Quand tu dis « faut que je… » ou « rendez-vous jeudi 14h », je te le propose ici. Tu restes maître du oui ou du non.")
        }
        if (suggestedEvents.isNotEmpty()) {
            item { Header("Rendez-vous à confirmer", M.Sky) }
            items(suggestedEvents, key = { "e-" + it.id }) { e ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                        .background(M.Sky.copy(alpha = 0.10f)).border(1.dp, M.Sky.copy(alpha = 0.3f), RoundedCornerShape(18.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyMedium, color = M.Text)
                        Text(prettyWhen(e.startAt, e.allDay), style = MaterialTheme.typography.labelSmall, color = M.Sky)
                    }
                    IconAction(Icons.Rounded.Close, "Ignorer") { scope.launch { repo.setEvent(e.id, EventStatus.DISMISSED) } }
                    IconAction(Icons.Rounded.Check, "Confirmer", tint = M.Mint) { scope.launch { repo.setEvent(e.id, EventStatus.CONFIRMED) } }
                }
            }
        }
        if (upcoming.isNotEmpty()) {
            item { Header("À venir", M.Sky) }
            items(upcoming, key = { "u-" + it.id }) { e ->
                val d = Dates.parseDay(e.startAt)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(M.Surface)
                        .clickable { e.noteId?.let { nav.navigate(Routes.note(it)) } }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(d?.dayOfMonth?.toString() ?: "?", style = MaterialTheme.typography.headlineSmall, color = if (d == today) M.Peach else M.Text)
                        Text(d?.format(java.time.format.DateTimeFormatter.ofPattern("EEE", Dates.fr)).orEmpty(), style = MaterialTheme.typography.labelSmall, color = M.Muted)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(width = 3.dp, height = 34.dp).clip(RoundedCornerShape(2.dp)).background(M.Sky))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyMedium, color = M.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        val meta = listOfNotNull(if (!e.allDay && e.startAt.length >= 16) e.startAt.substring(11).replace(':', 'h') else "journée", e.where).joinToString(" · ")
                        Text(meta, style = MaterialTheme.typography.labelSmall, color = M.Muted)
                    }
                    IconAction(Icons.Rounded.Close, "Retirer") { scope.launch { repo.setEvent(e.id, EventStatus.DISMISSED) } }
                }
            }
        }
        if (suggested.isNotEmpty()) {
            item { Header("À confirmer", M.Butter) }
            items(suggested, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                        .background(M.Butter.copy(alpha = 0.10f)).border(1.dp, M.Butter.copy(alpha = 0.3f), RoundedCornerShape(18.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.text, style = MaterialTheme.typography.bodyMedium, color = M.Text)
                        Text("Échéance suggérée : ${Dates.prettyDue(t.dueDate)}", style = MaterialTheme.typography.labelSmall, color = M.Butter)
                    }
                    IconButton(onClick = { set(t, TaskStatus.DISMISSED) }) { Icon(Icons.Rounded.Close, "Ignorer", tint = M.Muted) }
                    IconButton(onClick = { set(t, TaskStatus.OPEN) }) { Icon(Icons.Rounded.Check, "Accepter", tint = M.Mint) }
                }
            }
        }
        section("En retard", M.Coral, late, ::set, nav)
        section("Aujourd'hui", M.Peach, now, ::set, nav)
        section("Cette semaine", M.Sky, week, ::set, nav)
        section("Plus tard", M.Lilac, later, ::set, nav)
        section("Sans échéance", M.Muted, undated, ::set, nav)
        section("Fait ✓", M.Mint, done, ::set, nav)
        item { Spacer(Modifier.padding(20.dp)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String, color: Color, list: List<TaskEntity>, set: (TaskEntity, String) -> Any, nav: NavHostController,
) {
    if (list.isEmpty()) return
    item(key = "h-$title") { Header(title, color) }
    items(list, key = { it.id }) { t -> TaskItem(t, color, { set(t, if (t.status == TaskStatus.DONE) TaskStatus.OPEN else TaskStatus.DONE) }) { t.noteId?.let { nav.navigate(Routes.note(it)) } } }
}

@Composable
private fun Header(title: String, color: Color) {
    Eyebrow(title, Modifier.padding(top = 16.dp, bottom = 6.dp), color = color)
}

@Composable
private fun TaskItem(t: TaskEntity, color: Color, onToggle: () -> Unit, onOpen: () -> Unit) {
    val done = t.status == TaskStatus.DONE
    val bg by animateColorAsState(if (done) M.Mint else Color.Transparent, label = "c")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(M.Surface).clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(bg)
                .border(1.5.dp, if (done) M.Mint else color.copy(alpha = 0.7f), RoundedCornerShape(9.dp)).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) { if (done) Icon(Icons.Rounded.Check, null, tint = M.Ink, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(
            t.text, style = MaterialTheme.typography.bodyMedium, color = if (done) M.Faint else M.Text,
            textDecoration = if (done) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f),
        )
        if (!done && t.dueDate != null) Tag(Dates.prettyDue(t.dueDate).substringAfter("En retard · "), color)
    }
}
