package app.murmure.ui.explore

import androidx.compose.ui.graphics.Color
import app.murmure.data.FolderEntity
import app.murmure.data.MentionEntity
import app.murmure.data.NoteEntity
import app.murmure.data.NoteLink
import app.murmure.data.NoteMention
import app.murmure.data.NoteStatus
import app.murmure.ui.theme.M
import app.murmure.ai.Emotions
import app.murmure.ui.theme.Viz
import kotlin.math.sqrt

enum class GraphMode(val label: String) { THEMES("Thèmes"), PEOPLE("Entourage"), NOTES("Notes"), ALL("Tout") }
enum class ColorMode(val label: String) { KIND("Nature"), TYPE("Type"), EMOTION("Émotion"), CLUSTER("Clusters") }

data class GNode(
    val id: String,
    val label: String,
    val kind: String,
    val weight: Float,
    val color: Color,
    val noteIds: Set<String>,
    val ref: String? = null,
)

data class GEdge(val a: Int, val b: Int, val w: Float)

class GraphModel(val nodes: List<GNode>, val edges: List<GEdge>, val legend: List<Pair<String, Color>> = emptyList()) {
    val neighbors: List<Set<Int>> = List(nodes.size) { i ->
        edges.filter { it.a == i || it.b == i }.map { if (it.a == i) it.b else it.a }.toSet()
    }
}

object GraphBuilder {

    fun build(
        mode: GraphMode,
        colorMode: ColorMode,
        notesAll: List<NoteEntity>,
        folders: List<FolderEntity>,
        entities: List<MentionEntity>,
        mentions: List<NoteMention>,
        links: List<NoteLink>,
    ): GraphModel {
        val notes = notesAll.filter { it.status == NoteStatus.FILED }
        val noteById = notes.associateBy { it.id }
        val nodes = mutableListOf<GNode>()
        val index = HashMap<String, Int>()
        val edgeW = HashMap<Pair<Int, Int>, Float>()
        fun add(n: GNode): Int = index.getOrPut(n.id) { nodes += n; nodes.size - 1 }
        fun edge(a: Int, b: Int, w: Float = 1f) {
            if (a == b) return
            val k = if (a < b) a to b else b to a
            edgeW[k] = (edgeW[k] ?: 0f) + w
        }

        // Concepts = entités + mots-clés, avec les notes qui les portent
        val conceptNotes = LinkedHashMap<String, MutableSet<String>>()
        val conceptKind = HashMap<String, String>()
        val conceptLabel = HashMap<String, String>()
        val entById = entities.associateBy { it.id }
        mentions.forEach { m ->
            val e = entById[m.entityId] ?: return@forEach
            if (m.noteId !in noteById) return@forEach
            val key = "e:${e.id}"
            conceptNotes.getOrPut(key) { mutableSetOf() } += m.noteId
            conceptKind[key] = e.kind; conceptLabel[key] = e.name
        }
        notes.forEach { n ->
            n.keywords.split("|").filter { it.isNotBlank() }.forEach { k ->
                val low = k.lowercase().trim()
                if (conceptLabel.values.any { it.lowercase() == low }) return@forEach
                val key = "k:$low"
                conceptNotes.getOrPut(key) { mutableSetOf() } += n.id
                conceptKind[key] = "concept"; conceptLabel[key] = k.trim()
            }
        }

        fun valenceOf(n: NoteEntity) = n.valence ?: Emotions.valence(n.emotion)
        fun noteColor(n: NoteEntity) = when (colorMode) {
            ColorMode.EMOTION -> Viz.diverging(valenceOf(n))
            ColorMode.TYPE -> Viz.categorical[Viz.typeFamily(n.type)]
            ColorMode.KIND, ColorMode.CLUSTER -> Viz.Other
        }
        fun conceptColor(key: String): Color {
            val ids = conceptNotes[key].orEmpty().mapNotNull { noteById[it] }
            return when (colorMode) {
                ColorMode.EMOTION -> Viz.diverging(ids.map { valenceOf(it) }.average().toFloat())
                ColorMode.TYPE -> Viz.categorical[ids.groupingBy { Viz.typeFamily(it.type) }.eachCount().maxByOrNull { it.value }?.key ?: 0]
                else -> Viz.categorical[Viz.kindFamily(conceptKind[key])]
            }
        }

        when (mode) {
            GraphMode.THEMES, GraphMode.PEOPLE -> {
                val kinds = if (mode == GraphMode.PEOPLE) setOf("person", "activity", "place") else null
                val chosen = conceptNotes.filterKeys { kinds == null || conceptKind[it] in kinds }
                    .entries.sortedByDescending { it.value.size }.take(70)
                chosen.forEach { (k, ids) ->
                    add(GNode(k, conceptLabel[k]!!, conceptKind[k]!!, ids.size.toFloat(), conceptColor(k), ids, k.substringAfter(':')))
                }
                // Co-occurrence : deux concepts dans la même note
                for (i in chosen.indices) for (j in i + 1 until chosen.size) {
                    val shared = chosen[i].value.intersect(chosen[j].value).size
                    if (shared > 0) edge(index[chosen[i].key]!!, index[chosen[j].key]!!, shared.toFloat())
                }
            }
            GraphMode.NOTES, GraphMode.ALL -> {
                val folderById = folders.associateBy { it.id }
                notes.take(120).forEach { n ->
                    val ni = add(GNode("n:${n.id}", n.title, "note", 1f + (n.body.length / 900f).coerceAtMost(2f), noteColor(n), setOf(n.id), n.id))
                    n.folderId?.let { fid ->
                        folderById[fid]?.let { f ->
                            val cnt = notes.count { it.folderId == fid }
                            val fi = add(GNode("f:$fid", f.name, "folder", 2f + cnt, M.Text, notes.filter { it.folderId == fid }.map { it.id }.toSet(), fid))
                            edge(ni, fi, 1.5f)
                        }
                    }
                }
                links.forEach { l ->
                    val a = index["n:${l.fromId}"]; val b = index["n:${l.toId}"]
                    if (a != null && b != null) edge(a, b, 2f)
                }
                if (mode == GraphMode.ALL) {
                    conceptNotes.entries.filter { it.value.size >= 2 || conceptKind[it.key] != "concept" }
                        .sortedByDescending { it.value.size }.take(40).forEach { (k, ids) ->
                            val ci = add(GNode(k, conceptLabel[k]!!, conceptKind[k]!!, ids.size.toFloat(), conceptColor(k), ids, k.substringAfter(':')))
                            ids.forEach { nid -> index["n:$nid"]?.let { edge(it, ci, 0.8f) } }
                        }
                }
            }
        }

        val edges = edgeW.map { (k, w) -> GEdge(k.first, k.second, w) }
        val raw = GraphModel(nodes, edges)
        return when (colorMode) {
            ColorMode.CLUSTER -> {
                val clusters = clusters(raw)
                val legend = (0 until 3).mapNotNull { r ->
                    val members = nodes.indices.filter { clusters[it] == r }
                    val head = members.filter { nodes[it].kind != "note" }.maxByOrNull { nodes[it].weight }
                        ?: members.maxByOrNull { nodes[it].weight }
                    head?.let { "Autour de « ${nodes[it].label.take(22)} »" to Viz.cluster(r) }
                } + ("Autres pensées" to Viz.Other)
                GraphModel(nodes.mapIndexed { i, n -> if (n.kind == "folder") n else n.copy(color = Viz.cluster(clusters[i])) }, edges, legend)
            }
            ColorMode.KIND -> GraphModel(nodes, edges, Viz.kindFamilies.mapIndexed { i, l -> l to Viz.categorical[i] } +
                if (mode == GraphMode.NOTES || mode == GraphMode.ALL) listOf("Notes (anneaux) · dossiers (carrés)" to Viz.Other) else emptyList())
            ColorMode.TYPE -> GraphModel(nodes, edges, Viz.typeFamilies.mapIndexed { i, l -> l to Viz.categorical[i] })
            ColorMode.EMOTION -> GraphModel(nodes, edges, listOf("Charge positive" to Viz.Positive, "Neutre" to Viz.Neutral, "Charge négative" to Viz.Negative))
        }
    }

    /** Propagation d'étiquettes pondérée : détecte les « clusters de pensée ». */
    fun clusters(g: GraphModel): IntArray {
        val n = g.nodes.size
        val label = IntArray(n) { it }
        val adj = Array(n) { mutableListOf<Pair<Int, Float>>() }
        g.edges.forEach { adj[it.a] += it.b to it.w; adj[it.b] += it.a to it.w }
        val order = (0 until n).sortedByDescending { g.nodes[it].weight }
        repeat(12) {
            var changed = false
            order.forEach { i ->
                if (adj[i].isEmpty()) return@forEach
                val score = HashMap<Int, Float>()
                adj[i].forEach { (j, w) -> score[label[j]] = (score[label[j]] ?: 0f) + w * sqrt(g.nodes[j].weight) }
                val best = score.maxByOrNull { it.value }!!.key
                if (best != label[i]) { label[i] = best; changed = true }
            }
            if (!changed) return@repeat
        }
        // Renumérote par poids de cluster ; les singletons isolés ne forment pas un cluster.
        val weightOf = HashMap<Int, Float>()
        label.forEachIndexed { i, l -> weightOf[l] = (weightOf[l] ?: 0f) + g.nodes[i].weight }
        val sizes = label.toList().groupingBy { it }.eachCount()
        val ranked = weightOf.entries.filter { (sizes[it.key] ?: 0) >= 2 }.sortedByDescending { it.value }.map { it.key }
        return IntArray(n) { ranked.indexOf(label[it]).let { r -> if (r < 0) 99 else r } }
    }
}
