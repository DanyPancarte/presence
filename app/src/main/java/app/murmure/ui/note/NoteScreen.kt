package app.murmure.ui.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.murmure.MurmureApp
import app.murmure.ai.Emotions
import app.murmure.ai.NoteTypes
import app.murmure.core.Dates
import app.murmure.core.Text as T
import app.murmure.data.NoteStatus
import app.murmure.data.TaskStatus
import app.murmure.ui.Routes
import app.murmure.ui.components.Card
import app.murmure.ui.components.Eyebrow
import app.murmure.ui.components.MarkdownText
import app.murmure.ui.components.Tag
import app.murmure.ui.components.TopBar
import app.murmure.ui.components.moodFaces
import app.murmure.ui.theme.M
import app.murmure.ui.theme.Palette
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteScreen(nav: NavHostController, id: String) {
    val repo = MurmureApp.instance.repo
    val note by repo.dao.noteFlow(id).collectAsState(initial = null)
    val notes by repo.notes.collectAsState(initial = emptyList())
    val folders by repo.folders.collectAsState(initial = emptyList())
    val links by repo.links.collectAsState(initial = emptyList())
    val mentions by repo.mentions.collectAsState(initial = emptyList())
    val entities by repo.entities.collectAsState(initial = emptyList())
    val tasks by repo.tasks.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    val n = note ?: run {
        Column(Modifier.fillMaxSize()) { TopBar("", onBack = { nav.popBackStack() }) }
        return
    }
    val byId = notes.associateBy { it.id }
    val outgoing = links.filter { it.fromId == id }.mapNotNull { byId[it.toId] }
    val backlinks = links.filter { it.toId == id }.mapNotNull { byId[it.fromId] }
    val myEntities = mentions.filter { it.noteId == id }.mapNotNull { m -> entities.firstOrNull { it.id == m.entityId } }
    val folder = folders.firstOrNull { it.id == n.folderId }
    val myTasks = tasks.filter { it.noteId == id }

    fun openLink(target: String) {
        val k = T.key(target)
        notes.firstOrNull { T.key(it.title) == k }?.let { nav.navigate(Routes.note(it.id)); return }
        entities.firstOrNull { T.key(it.name) == k }?.let { e ->
            // Première autre note qui mentionne l'entité
            mentions.firstOrNull { it.entityId == e.id && it.noteId != id }?.let { nav.navigate(Routes.note(it.noteId)) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(folder?.let { "${it.emoji ?: "DO"} ${it.name}" } ?: "Note", onBack = { nav.popBackStack() }) {
            IconButton(onClick = { nav.navigate(Routes.reclass(id)) }) { Icon(Icons.Rounded.AutoFixHigh, "Reclasser", tint = M.Muted) }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Supprimer", tint = M.Muted) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Text(Dates.pretty(n.createdAt) + " · ${n.durationSec / 60}:${"%02d".format(n.durationSec % 60)}", style = MaterialTheme.typography.labelMedium, color = M.Faint)
            Spacer(Modifier.height(6.dp))
            Text(n.title, style = MaterialTheme.typography.headlineLarge, color = M.Text)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (n.status == NoteStatus.PENDING) Tag("À classer", M.Butter, leading = "IN") { nav.navigate(Routes.reclass(id)) }
                n.type?.let { Tag(NoteTypes.label(it), Palette.type(it), leading = NoteTypes.emoji(it)) }
                n.emotion?.let { Tag(it, Palette.emotion(it), leading = Emotions.emoji(it)) }
                n.energy?.let { Tag("énergie $it", M.Sky) }
                n.mood?.let { Tag("mood $it/5", Palette.mood(it.toFloat()), leading = moodFaces[it - 1]) }
                if (n.timeOfDay.isNotBlank()) Tag(n.timeOfDay, M.Butter, leading = "HR")
            }
            val bodyStart = T.key(n.body.replace(Regex("[\\[\\]#*]"), "").take(60))
            if (n.summary.isNotBlank() && !bodyStart.startsWith(T.key(n.summary.take(40)))) {
                Spacer(Modifier.height(14.dp))
                Text(n.summary, style = MaterialTheme.typography.bodyMedium, color = M.Muted)
            }
            Spacer(Modifier.height(18.dp))
            MarkdownText(n.body, onLink = ::openLink)

            if (myEntities.isNotEmpty() || n.keywords.isNotBlank()) {
                Spacer(Modifier.height(22.dp))
                Eyebrow("Entités & mots-clés")
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    myEntities.forEach { e -> Tag(e.name, Palette.kind(e.kind), leading = Palette.kindIcon(e.kind)) { openLink(e.name) } }
                    n.keywords.split("|").filter { it.isNotBlank() }.forEach { Tag("#$it", M.Lilac) }
                }
            }
            if (myTasks.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Card(tint = M.Mint) {
                    Eyebrow("Tâches", color = M.Mint)
                    myTasks.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (t.status == TaskStatus.DONE) "OK" else "⬜", modifier = Modifier.clickable {
                                scope.launch { repo.setTask(t.id, if (t.status == TaskStatus.DONE) TaskStatus.OPEN else TaskStatus.DONE) }
                            })
                            Spacer(Modifier.width(10.dp))
                            Text(t.text, style = MaterialTheme.typography.bodyMedium, color = M.Text, modifier = Modifier.weight(1f))
                            Text(Dates.prettyDue(t.dueDate), style = MaterialTheme.typography.labelSmall, color = M.Butter)
                        }
                    }
                }
            }
            if (outgoing.isNotEmpty() || backlinks.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Card {
                    if (outgoing.isNotEmpty()) {
                        Eyebrow("Liens sortants")
                        outgoing.forEach { o -> LinkRow(o.title, "→") { nav.navigate(Routes.note(o.id)) } }
                    }
                    if (backlinks.isNotEmpty()) {
                        if (outgoing.isNotEmpty()) Spacer(Modifier.height(10.dp))
                        Eyebrow("Rétroliens · mentionnée dans")
                        backlinks.forEach { b -> LinkRow(b.title, "←") { nav.navigate(Routes.note(b.id)) } }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Eyebrow("Transcription brute")
            Spacer(Modifier.height(6.dp))
            Text(n.rawTranscript, style = MaterialTheme.typography.bodySmall, color = M.Faint)
            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = M.Surface,
        title = { Text("Supprimer cette note ?") },
        text = { Text("Elle disparaîtra définitivement de ton appareil.", color = M.Muted) },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; scope.launch { repo.deleteNote(id); nav.popBackStack() } }) { Text("Supprimer", color = M.Coral) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler", color = M.Text) } },
    )
}

@Composable
private fun LinkRow(title: String, arrow: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(arrow, color = M.Lilac)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = M.Text)
    }
}
