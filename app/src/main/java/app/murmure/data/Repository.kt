package app.murmure.data

import app.murmure.ai.AnalysisContext
import app.murmure.ai.EntityGuess
import app.murmure.ai.GeminiClient
import app.murmure.ai.LocalBrain
import app.murmure.ai.NoteAnalyzer
import app.murmure.ai.NoteProposal
import app.murmure.ai.TaskGuess
import app.murmure.core.Dates
import app.murmure.core.SettingsStore
import app.murmure.core.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Ce que l'utilisateur a validé à l'écran de revue. */
data class Validation(
    val title: String,
    val body: String,
    val summary: String,
    val folderName: String,
    val type: String,
    val emotion: String,
    val intensity: Float,
    val valence: Float,
    val energy: String,
    val entities: List<EntityGuess>,
    val keywords: List<String>,
    val links: List<String>,
    val acceptedTasks: List<TaskGuess>,
)

data class AnalysisResult(val proposal: NoteProposal, val warning: String? = null)

class Repository(
    private val db: MurmureDb,
    private val settings: SettingsStore,
    private val client: GeminiClient,
) {
    val dao = db.dao()
    private val analyzer = NoteAnalyzer(client)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val notes = dao.notesFlow()
    val folders = dao.foldersFlow()
    val entities = dao.entitiesFlow()
    val mentions = dao.mentionsFlow()
    val links = dao.linksFlow()
    val tasks = dao.tasksFlow()

    suspend fun ensureDefaults() {
        if (dao.folderCount() > 0) return
        listOf("Boîte de réception" to "📥", "Journal" to "📓", "Travail" to "💼", "Personnel" to "🌿", "Idées" to "💡")
            .forEachIndexed { i, (n, e) -> dao.insertFolder(FolderEntity(Text.uuid(), n, i, System.currentTimeMillis(), e)) }
    }

    suspend fun folderId(name: String): String {
        dao.folderByName(name.trim())?.let { return it.id }
        val f = FolderEntity(Text.uuid(), Text.capitalize(name), dao.folderCount(), System.currentTimeMillis())
        dao.insertFolder(f)
        return dao.folderByName(name.trim())?.id ?: f.id
    }

    /** Sauvegarde immédiate de la dictée brute : on ne perd jamais une note. */
    suspend fun saveDraft(transcript: String, durationSec: Int, isDaily: Boolean, mood: Int?): String {
        val now = System.currentTimeMillis()
        val id = Text.uuid()
        dao.upsertNote(
            NoteEntity(
                id = id, title = NoteAnalyzer.fallbackTitle(transcript), body = transcript, rawTranscript = transcript,
                createdAt = now, updatedAt = now, dayKey = Dates.dayKey(now), durationSec = durationSec,
                isDaily = isDaily, mood = mood, timeOfDay = Dates.timeOfDay(now),
                valence = mood?.let { (it - 3) / 2f },
            )
        )
        return id
    }

    /** Termes connus pour le surlignage live et hors ligne. */
    suspend fun knownTerms(): Map<String, String> = withContext(Dispatchers.IO) {
        val m = LinkedHashMap<String, String>()
        dao.recentTitles(200).forEach { if (it.title.length >= 4) m[it.title] = "note" }
        dao.folders().forEach { m[it.name] = "folder" }
        dao.entities().forEach { m[it.name] = it.kind }
        m
    }

    suspend fun context(isDaily: Boolean, mood: Int?) = AnalysisContext(
        folders = dao.folders().map { it.name },
        entities = dao.entities().map { it.name to it.kind },
        recentTitles = dao.recentTitles(60).map { it.title },
        isDaily = isDaily, mood = mood,
    )

    fun proposalOf(n: NoteEntity): NoteProposal? =
        n.proposalJson?.let { runCatching { json.decodeFromString(NoteProposal.serializer(), it) }.getOrNull() }

    /** Analyse IA (ou locale en repli) ; la proposition est stockée à part, rien n'est appliqué. */
    suspend fun analyze(noteId: String): AnalysisResult = withContext(Dispatchers.IO) {
        val note = dao.note(noteId) ?: error("Note introuvable")
        val ctx = context(note.isDaily, note.mood)
        val s = settings.current
        var warning: String? = null
        val proposal = if (s.hasAiKey && note.rawTranscript.isNotBlank()) {
            runCatching { analyzer.analyze(s, note.rawTranscript, ctx) }
                .getOrElse { e ->
                    warning = "Analyse IA indisponible (${e.message?.take(90)}). Proposition locale affichée."
                    LocalBrain.offlineProposal(note.rawTranscript, ctx, knownTerms())
                }
        } else {
            if (!s.hasAiKey) warning = "Sans clé API : classement proposé localement."
            LocalBrain.offlineProposal(note.rawTranscript, ctx, knownTerms())
        }
        dao.upsertNote(note.copy(proposalJson = json.encodeToString(NoteProposal.serializer(), proposal), updatedAt = System.currentTimeMillis()))
        AnalysisResult(proposal, warning)
    }

    /** Applique le classement — uniquement après accord explicite. */
    suspend fun file(noteId: String, v: Validation) = withContext(Dispatchers.IO) {
        val note = dao.note(noteId) ?: return@withContext
        val folderId = folderId(v.folderName.ifBlank { "Boîte de réception" })
        dao.clearMentions(noteId)
        dao.clearOutgoingLinks(noteId)
        dao.clearSuggestedTasks(noteId)

        v.entities.forEach { g ->
            val key = Text.key(g.name)
            val existing = dao.entity(g.kind, key)
            val ent = existing ?: MentionEntity(Text.uuid(), Text.capitalize(g.name), g.kind, key).also { dao.insertEntity(it) }
            val id = dao.entity(g.kind, key)?.id ?: ent.id
            dao.upsertMention(NoteMention(noteId, id, g.sentiment.toFloat()))
        }
        val titles = dao.recentTitles(500)
        v.links.forEach { t ->
            titles.firstOrNull { Text.key(it.title) == Text.key(t) && it.id != noteId }?.let { dao.upsertLink(NoteLink(noteId, it.id)) }
        }
        // Liens [[...]] du corps vers des notes existantes
        Regex("\\[\\[([^\\]]+)]]").findAll(v.body).forEach { m ->
            titles.firstOrNull { Text.key(it.title) == Text.key(m.groupValues[1]) && it.id != noteId }
                ?.let { dao.upsertLink(NoteLink(noteId, it.id)) }
        }
        val now = System.currentTimeMillis()
        v.acceptedTasks.forEach { t ->
            dao.upsertTask(TaskEntity(Text.uuid(), noteId, t.text, t.due, TaskStatus.OPEN, t.reason, now))
        }
        dao.upsertNote(
            note.copy(
                title = v.title.ifBlank { note.title }, body = v.body, summary = v.summary,
                folderId = folderId, status = NoteStatus.FILED, type = v.type, emotion = v.emotion,
                emotionIntensity = v.intensity, valence = note.mood?.let { (it - 3) / 2f } ?: v.valence,
                energy = v.energy, keywords = v.keywords.joinToString("|"), proposalJson = null,
                analyzed = true, updatedAt = now,
            )
        )
        dao.pruneEntities()
    }

    /** « Plus tard » : la note reste dans la boîte, tâches en suggestion. */
    suspend fun defer(noteId: String, proposal: NoteProposal?) = withContext(Dispatchers.IO) {
        val note = dao.note(noteId) ?: return@withContext
        dao.clearSuggestedTasks(noteId)
        proposal?.tasks?.forEach {
            dao.upsertTask(TaskEntity(Text.uuid(), noteId, it.text, it.due, TaskStatus.SUGGESTED, it.reason, System.currentTimeMillis()))
        }
        if (note.status != NoteStatus.FILED) dao.upsertNote(note.copy(title = proposal?.title?.ifBlank { null } ?: note.title))
    }

    suspend fun deleteNote(id: String) = withContext(Dispatchers.IO) {
        dao.deleteNote(id); dao.clearMentions(id); dao.clearLinks(id); dao.pruneEntities()
    }

    suspend fun setTask(id: String, status: String) = dao.setTaskStatus(id, status)
    suspend fun updateTask(t: TaskEntity) = dao.upsertTask(t)

    // ---------------- Comptes rendus IA ----------------
    fun report(key: String) = dao.reportFlow(key)

    suspend fun folderDigest(folderId: String?, folderName: String): String = withContext(Dispatchers.IO) {
        val s = settings.current
        require(s.hasAiKey) { "Ajoute ta clé API dans Réglages pour générer un compte rendu." }
        val all = dao.notes().filter { it.status == NoteStatus.FILED && (folderId == null || it.folderId == folderId) }.take(40)
        require(all.isNotEmpty()) { "Aucune note classée ici pour l'instant." }
        val corpus = all.joinToString("\n---\n") { n ->
            "[${Dates.dayKey(n.createdAt)} · ${n.type ?: "?"} · ${n.emotion ?: "?"}] ${n.title}\n${n.body.take(1200)}"
        }
        val prompt = """
Voici ${all.size} notes du dossier « $folderName ».
$corpus

Rédige un compte rendu en Markdown, en français québécois, bienveillant et concret :
## Synthèse
(3-4 phrases)
## Points saillants
(puces)
## Fils qui reviennent
(thèmes, personnes, questions récurrentes)
## Prochaines actions possibles
(puces, formulées comme suggestions)
Reste descriptif, n'invente rien.
""".trimIndent()
        val out = client.generate(s.apiKey, s.textModel, prompt, temperature = 0.4)
        dao.upsertReport(ReportEntity("folder:${folderId ?: "all"}", out, System.currentTimeMillis()))
        out
    }

    suspend fun portraitReading(stats: String): String = withContext(Dispatchers.IO) {
        val s = settings.current
        require(s.hasAiKey) { "Ajoute ta clé API dans Réglages pour obtenir une lecture IA." }
        val prompt = """
Données agrégées issues du carnet vocal d'une personne (moyennes et fréquences, pas de texte brut) :
$stats

Rédige en Markdown, au tutoiement, en français québécois :
## Ce que je remarque
3 à 5 observations formulées comme des constats (« Les jours où… ton humeur moyenne est… »), jamais comme des verdicts.
## Où tu sembles le plus à ton aise
2-3 contextes/environnements favorables observés.
## À essayer ou à répéter
2-3 suggestions douces et concrètes.
Règles : tu n'es pas un outil clinique, aucun diagnostic, aucune causalité affirmée (corrélation ≠ cause), mentionne quand l'échantillon est petit.
""".trimIndent()
        val out = client.generate(s.apiKey, s.textModel, prompt, temperature = 0.5)
        dao.upsertReport(ReportEntity("portrait", out, System.currentTimeMillis()))
        out
    }

    // ---------------- Vie privée ----------------
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val folders = dao.folders().associateBy { it.id }
        val ents = dao.entities().associateBy { it.id }
        val mentions = dao.mentions().groupBy { it.noteId }
        buildJsonObject {
            put("app", "Murmure")
            put("exportedAt", Dates.nowIso())
            put("notes", buildJsonArray {
                dao.notes().forEach { n ->
                    add(buildJsonObject {
                        put("id", n.id); put("title", n.title); put("body", n.body); put("transcript", n.rawTranscript)
                        put("summary", n.summary); put("folder", folders[n.folderId]?.name)
                        put("status", n.status); put("createdAt", n.createdAt); put("day", n.dayKey)
                        put("daily", n.isDaily); put("mood", n.mood); put("type", n.type); put("emotion", n.emotion)
                        put("energy", n.energy); put("timeOfDay", n.timeOfDay)
                        put("keywords", JsonArray(n.keywords.split("|").filter { it.isNotBlank() }.map { JsonPrimitive(it) }))
                        put("entities", JsonArray(mentions[n.id].orEmpty().mapNotNull { m ->
                            ents[m.entityId]?.let { JsonPrimitive("${it.name} (${it.kind})") }
                        }))
                    })
                }
            })
        }.toString()
    }

    suspend fun wipeEverything() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        settings.wipe()
    }
}
