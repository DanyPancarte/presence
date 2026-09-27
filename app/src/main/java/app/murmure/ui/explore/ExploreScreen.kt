package app.murmure.ui.explore

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.murmure.MurmureApp
import app.murmure.ai.NoteTypes
import app.murmure.core.Dates
import app.murmure.data.EntityKind
import app.murmure.data.FolderEntity
import app.murmure.data.NoteEntity
import app.murmure.data.NoteStatus
import app.murmure.ui.Routes
import app.murmure.ui.components.Dot
import app.murmure.ui.components.EmptyState
import app.murmure.ui.components.Tag
import app.murmure.ui.theme.M
import app.murmure.ui.theme.Viz
import app.murmure.ai.Emotions

@Composable
fun ExploreScreen(nav: NavHostController) {
    val repo = MurmureApp.instance.repo
    val notes by repo.notes.collectAsState(initial = emptyList())
    val folders by repo.folders.collectAsState(initial = emptyList())
    val entities by repo.entities.collectAsState(initial = emptyList())
    val mentions by repo.mentions.collectAsState(initial = emptyList())
    val links by repo.links.collectAsState(initial = emptyList())
    var graphTab by rememberSaveable { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Explorer", style = MaterialTheme.typography.displayMedium, color = M.Text, modifier = Modifier.weight(1f))
            Segmented(listOf("Graphe", "Dossiers"), if (graphTab) 0 else 1) { graphTab = it == 0 }
        }
        if (notes.none { it.status == NoteStatus.FILED }) {
            EmptyState("🌌", "Ton univers est vide", "Dicte et classe quelques notes : les liens, thèmes et personnes apparaîtront ici.")
            if (!graphTab) FolderTree(nav, folders, notes)
            return@Column
        }
        if (graphTab) GraphPane(nav, notes, folders, entities, mentions, links)
        else FolderTree(nav, folders, notes)
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(M.Surface).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            Text(
                o, style = MaterialTheme.typography.labelMedium,
                color = if (i == selected) M.Ink else M.Muted,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (i == selected) M.Lilac else M.Surface)
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun GraphPane(
    nav: NavHostController,
    notes: List<NoteEntity>,
    folders: List<FolderEntity>,
    entities: List<app.murmure.data.MentionEntity>,
    mentions: List<app.murmure.data.NoteMention>,
    links: List<app.murmure.data.NoteLink>,
) {
    var mode by rememberSaveable { mutableStateOf(GraphMode.THEMES) }
    var colorMode by rememberSaveable { mutableStateOf(ColorMode.CLUSTER) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val model = remember(mode, colorMode, notes, folders, entities, mentions, links) {
        selected = null
        GraphBuilder.build(mode, colorMode, notes, folders, entities, mentions, links)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GraphMode.entries.forEach { m -> Tag(m.label, M.Lilac, selected = m == mode) { mode = m } }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Couleur", style = MaterialTheme.typography.labelSmall, color = M.Faint)
            ColorMode.entries.forEach { c -> Tag(c.label, M.Peach, selected = c == colorMode) { colorMode = c } }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (model.nodes.isEmpty()) EmptyState("🫧", "Pas encore assez de matière", "Ce mode s'enrichit à mesure que tu parles de gens, d'activités et de thèmes.")
            else GraphView(model, selected, { selected = it }, Modifier.fillMaxSize())
            Legend(model, Modifier.align(Alignment.TopEnd).padding(12.dp))
            Text(
                "Taille = fréquence · pince pour zoomer",
                style = MaterialTheme.typography.labelSmall, color = M.Faint,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            )
            val sel = selected?.let { model.nodes.getOrNull(it) }
            androidx.compose.animation.AnimatedVisibility(
                visible = sel != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                sel?.let { node -> NodeCard(node, notes, onClose = { selected = null }, onNote = { nav.navigate(Routes.note(it)) }, onFolder = { nav.navigate(Routes.folder(it)) }) }
            }
        }
    }
}

@Composable
private fun Legend(model: GraphModel, modifier: Modifier) {
    if (model.legend.isEmpty()) return
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(M.Night.copy(alpha = 0.88f)).padding(10.dp)) {
        model.legend.forEach { (l, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Dot(c, 8.dp); Spacer(Modifier.width(6.dp))
                Text(l, style = MaterialTheme.typography.labelSmall, color = M.Muted)
            }
        }
    }
}

@Composable
private fun NodeCard(node: GNode, notes: List<NoteEntity>, onClose: () -> Unit, onNote: (String) -> Unit, onFolder: (String) -> Unit) {
    val related = notes.filter { it.id in node.noteIds }.take(12)
    Column(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(M.Surface)
            .border(1.dp, node.color.copy(alpha = 0.5f), RoundedCornerShape(24.dp)).padding(16.dp).animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(node.color, 12.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(node.label, style = MaterialTheme.typography.titleLarge, color = M.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${EntityKind.label(node.kind).takeIf { node.kind != "note" && node.kind != "folder" } ?: if (node.kind == "folder") "Dossier" else "Note"} · ${node.noteIds.size} note${if (node.noteIds.size > 1) "s" else ""}",
                    style = MaterialTheme.typography.labelMedium, color = M.Muted,
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Fermer", tint = M.Muted) }
        }
        if (node.kind == "folder" && node.ref != null) {
            Tag("Ouvrir le dossier et son compte rendu", M.Rose, leading = "📁", modifier = Modifier.padding(vertical = 6.dp)) { onFolder(node.ref) }
        }
        LazyColumn(Modifier.heightIn(max = 220.dp)) {
            items(related, key = { it.id }) { n ->
                Row(Modifier.fillMaxWidth().clickable { onNote(n.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(NoteTypes.emoji(n.type), fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(n.title, style = MaterialTheme.typography.bodyMedium, color = M.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Dates.pretty(n.createdAt).substringBefore(" ·"), style = MaterialTheme.typography.labelSmall, color = M.Faint)
                }
            }
        }
    }
}

/** Arborescence vivante : dossiers → notes, avec lignes de branche et teinte émotionnelle. */
@Composable
private fun FolderTree(nav: NavHostController, folders: List<FolderEntity>, notes: List<NoteEntity>) {
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    val filed = notes.filter { it.status == NoteStatus.FILED }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(folders.sortedByDescending { f -> filed.filter { it.folderId == f.id }.maxOfOrNull { it.createdAt } ?: 0 }, key = { it.id }) { f ->
            val mine = filed.filter { it.folderId == f.id }
            val color = M.pastel(f.colorIndex)
            val isOpen = f.id in open
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(M.Surface)
                    .border(1.dp, color.copy(alpha = if (isOpen) 0.5f else 0.18f), RoundedCornerShape(22.dp)).animateContentSize()
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { open = if (isOpen) open - f.id else open + f.id }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Text(f.emoji ?: "📁", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.titleMedium, color = M.Text)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${mine.size} note${if (mine.size > 1) "s" else ""}", style = MaterialTheme.typography.labelMedium, color = M.Muted)
                            Spacer(Modifier.width(8.dp))
                            // Ruban émotionnel du dossier
                            mine.take(14).forEach { n -> Box(Modifier.padding(end = 2.dp).size(width = 6.dp, height = 10.dp).clip(RoundedCornerShape(3.dp)).background(Viz.diverging(n.valence ?: Emotions.valence(n.emotion)))) }
                        }
                    }
                    IconButton(onClick = { nav.navigate(Routes.folder(f.id)) }) { Icon(Icons.Rounded.ChevronRight, "Ouvrir", tint = color) }
                    Icon(if (isOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = M.Muted)
                }
                if (isOpen) {
                    if (mine.isEmpty()) Text("Vide pour l'instant.", style = MaterialTheme.typography.bodySmall, color = M.Faint, modifier = Modifier.padding(start = 72.dp, bottom = 16.dp))
                    mine.forEachIndexed { i, n ->
                        Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.note(n.id)) }.padding(end = 16.dp).height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                            val last = i == mine.lastIndex
                            Canvas(Modifier.width(56.dp).fillMaxSize()) {
                                val x = size.width * 0.6f
                                drawLine(color.copy(alpha = 0.4f), Offset(x, 0f), Offset(x, if (last) size.height / 2 else size.height), 2f)
                                drawLine(color.copy(alpha = 0.4f), Offset(x, size.height / 2), Offset(size.width, size.height / 2), 2f)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(NoteTypes.emoji(n.type), fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(n.title, style = MaterialTheme.typography.bodyMedium, color = M.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Dates.pretty(n.createdAt).substringBefore(" ·"), style = MaterialTheme.typography.labelSmall, color = M.Faint)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
