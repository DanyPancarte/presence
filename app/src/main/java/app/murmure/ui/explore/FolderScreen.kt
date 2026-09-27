package app.murmure.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.murmure.MurmureApp
import app.murmure.ai.Emotions
import app.murmure.ai.NoteTypes
import app.murmure.core.Dates
import app.murmure.data.NoteStatus
import app.murmure.ui.Routes
import app.murmure.ui.components.Banner
import app.murmure.ui.components.Card
import app.murmure.ui.components.Eyebrow
import app.murmure.ui.components.MarkdownText
import app.murmure.ui.components.PrimaryButton
import app.murmure.ui.components.Tag
import app.murmure.ui.components.TopBar
import app.murmure.ui.theme.M
import app.murmure.ui.theme.Palette
import app.murmure.ui.theme.Viz
import kotlinx.coroutines.launch

@Composable
fun FolderScreen(nav: NavHostController, id: String) {
    val repo = MurmureApp.instance.repo
    val folders by repo.folders.collectAsState(initial = emptyList())
    val notes by repo.notes.collectAsState(initial = emptyList())
    val report by repo.report("folder:$id").collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val folder = folders.firstOrNull { it.id == id }
    val mine = notes.filter { it.folderId == id && it.status == NoteStatus.FILED }
    val color = folder?.let { M.pastel(it.colorIndex) } ?: M.Lilac

    Column(Modifier.fillMaxSize()) {
        TopBar("", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Text(folder?.emoji ?: "DO", fontSize = 40.sp)
            Text(folder?.name ?: "Dossier", style = MaterialTheme.typography.displayMedium, color = M.Text)
            Text("${mine.size} notes", style = MaterialTheme.typography.labelMedium, color = color)
            Spacer(Modifier.height(10.dp))
            val topEmotions = mine.mapNotNull { it.emotion }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                topEmotions.forEach { Tag("${it.key} ${it.value}", Palette.emotion(it.key), leading = Emotions.emoji(it.key)) }
            }
            Spacer(Modifier.height(18.dp))

            Card(tint = color) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Compte rendu IA", Modifier.weight(1f), color = color)
                    report?.let { Text(Dates.pretty(it.createdAt), style = MaterialTheme.typography.labelSmall, color = M.Faint) }
                }
                Spacer(Modifier.height(10.dp))
                error?.let { Banner(it, M.Coral); Spacer(Modifier.height(10.dp)) }
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Je relis tes notes…", color = M.Muted)
                    }
                    report != null -> MarkdownText(report!!.content)
                    else -> Text("Synthèse, points saillants et fils qui reviennent dans ce dossier.", style = MaterialTheme.typography.bodyMedium, color = M.Muted)
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (report == null) "Générer le compte rendu" else "Actualiser", color = color, leading = Icons.Rounded.AutoAwesome, enabled = !busy) {
                    busy = true; error = null
                    scope.launch {
                        runCatching { repo.folderDigest(id, folder?.name ?: "") }.onFailure { error = it.message }
                        busy = false
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Eyebrow("Notes")
            Spacer(Modifier.height(8.dp))
            mine.forEach { n ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(M.Surface)
                        .border(1.dp, M.Line, RoundedCornerShape(18.dp)).clickable { nav.navigate(Routes.note(n.id)) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 4.dp, height = 36.dp).clip(RoundedCornerShape(2.dp)).background(Viz.diverging(n.valence ?: Emotions.valence(n.emotion))))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.title, style = MaterialTheme.typography.titleSmall, color = M.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.summary.ifBlank { n.body }.take(90), style = MaterialTheme.typography.bodySmall, color = M.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(NoteTypes.emoji(n.type))
                        Text(Dates.pretty(n.createdAt).substringBefore(" ·"), style = MaterialTheme.typography.labelSmall, color = M.Faint)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}
