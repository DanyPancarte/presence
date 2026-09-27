package app.murmure.data

import app.murmure.ai.AnalysisContext
import app.murmure.ai.EntityGuess
import app.murmure.ai.GeminiClient
import app.murmure.ai.AiRouter
import app.murmure.ai.ClaudeClient
import app.murmure.ai.LocalBrain
import app.murmure.ai.NoteAnalyzer
import app.murmure.ai.NoteProposal
import app.murmure.ai.TaskGuess
import app.murmure.ai.Moment
import app.murmure.ai.MomentKind
import app.murmure.ai.SessionProposal
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

data class SessionResult(val proposal: SessionProposal, val warning: String? = null)

/** Validation d'une capture complète : n notes + tâches + agenda + mood. */
data class SessionValidation(
    val notes: List<Validation>,
    val tasks: List<TaskGuess>,
    val events: List<Moment>,
    val mood: Int?,
    val moodEmotion: String?,
)

class Repository(
    private val db: MurmureDb,
    private val settings: SettingsStore,
    private val client: GeminiClient,
    private val ai: AiRouter = AiRouter(client, ClaudeClient(client.http)),
) {
    val dao = db.dao()
    private val analyzer = NoteAnalyzer(ai)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val notes = dao.notesFlow()
    val folders = dao.foldersFlow()
    val entities = dao.entitiesFlow()
    val mentions = dao.mentionsFlow()
    val links = dao.linksFlow()
    val tasks = dao.tasksFlow()
    val events = dao.eventsFlow()
    val captures = dao.capturesFlow()
    val pendingCaptures = dao.pendingCapturesFlow()

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

    // ---------------- Captures (sessions de dictée) ----------------

    /** Sauvegarde immédiate de la dictée : elle existe avant toute analyse. */
    suspend fun saveCapture(transcript: String, durationSec: Int, isDaily: Boolean, mood: Int?, moments: List<Moment>): String {
        val now = System.currentTimeMillis()
        val id = Text.uuid()
        dao.upsertCapture(
            CaptureEntity(
                id = id, transcript = transcript, createdAt = now, dayKey = Dates.dayKey(now), durationSec = durationSec,
                isDaily = isDaily, mood = mood, timeOfDay = Dates.timeOfDay(now),
            )
        )
        pendingMoments[id] = moments
        return id
    }

    /** Moments repérés en direct, gardés en mémoire le temps de l'analyse. */
    private val pendingMoments = HashMap<String, List<Moment>>()

    fun sessionProposalOf(c: CaptureEntity): SessionProposal? =
        c.proposalJson?.let { runCatching { json.decodeFromString(SessionProposal.serializer(), it) }.getOrNull() }

    suspend fun analyzeCapture(captureId: String): SessionResult = withContext(Dispatchers.IO) {
        val c = dao.capture(captureId) ?: error("Capture introuvable")
        val ctx = context(c.isDaily, c.mood)
        val moments = pendingMoments[captureId] ?: LocalBrain.detectMoments(c.transcript)
        val s = settings.current
        var warning: String? = null
        val proposal = if (s.hasAiKey && c.transcript.isNotBlank()) {
            runCatching { analyzer.analyzeCapture(s, c.transcript, ctx, moments) }.getOrElse { e ->
                warning = "Analyse IA indisponible (${e.message?.take(90)}). Proposition locale affichée."
                LocalBrain.offlineSession(c.transcript, ctx, knownTerms(), moments)
            }
        } else {
            if (!s.hasAiKey) warning = "Sans clé API : découpage et classement proposés localement."
            LocalBrain.offlineSession(c.transcript, ctx, knownTerms(), moments)
        }
        dao.upsertCapture(c.copy(proposalJson = json.encodeToString(SessionProposal.serializer(), proposal)))
        SessionResult(proposal, warning)
    }

    /** Applique la validation d'une capture : crée les notes, tâches, rendez-vous. */
    suspend fun fileCapture(captureId: String, v: SessionValidation) = withContext(Dispatchers.IO) {
        val c = dao.capture(captureId) ?: return@withContext
        val now = System.currentTimeMillis()
        // Notes déjà créées pour cette capture (re-validation) : on les remplace.
        dao.notesOfCapture(captureId).forEach { deleteNote(it.id) }
        dao.clearSuggestedTasksOfCapture(captureId)
        dao.clearSuggestedEvents(captureId)
        var firstId: String? = null
        v.notes.forEachIndexed { i, nv ->
            val id = Text.uuid()
            if (firstId == null) firstId = id
            dao.upsertNote(
                NoteEntity(
                    id = id, captureId = captureId, title = nv.title, body = nv.body, rawTranscript = c.transcript,
                    createdAt = c.createdAt + i, updatedAt = now, dayKey = c.dayKey, durationSec = if (i == 0) c.durationSec else 0,
                    isDaily = c.isDaily && i == 0, mood = if (i == 0) (c.mood ?: v.mood) else null, timeOfDay = c.timeOfDay,
                )
            )
            file(id, nv.copy(acceptedTasks = emptyList()))
        }
        v.tasks.forEach { t ->
            dao.upsertTask(TaskEntity(id = Text.uuid(), noteId = firstId, captureId = captureId, text = t.text, dueDate = t.due, status = TaskStatus.OPEN, reason = t.reason, createdAt = now))
        }
        v.events.forEach { e ->
            dao.upsertEvent(
                EventEntity(
                    Text.uuid(), firstId, captureId, e.title, e.due ?: Dates.today().toString(), e.allDay, e.where,
                    e.who.joinToString("|"), EventStatus.CONFIRMED, e.text.take(120), now,
                )
            )
        }
        dao.upsertCapture(c.copy(status = CaptureStatus.DONE, mood = c.mood ?: v.mood, proposalJson = null))
        pendingMoments.remove(captureId)
    }

    /** « Plus tard » : la capture reste à valider ; tâches et rendez-vous en suggestion. */
    suspend fun deferCapture(captureId: String, p: SessionProposal?) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.clearSuggestedTasksOfCapture(captureId)
        dao.clearSuggestedEvents(captureId)
        p?.tasks?.forEach { dao.upsertTask(TaskEntity(id = Text.uuid(), noteId = null, captureId = captureId, text = it.text, dueDate = it.due, status = TaskStatus.SUGGESTED, reason = it.reason, createdAt = now)) }
        p?.events?.forEach { e ->
            dao.upsertEvent(EventEntity(Text.uuid(), null, captureId, e.title, e.due ?: Dates.today().toString(), e.allDay, e.where, e.who.joinToString("|"), EventStatus.SUGGESTED, e.text.take(120), now))
        }
    }

    suspend fun deleteCapture(id: String) = withContext(Dispatchers.IO) {
        dao.notesOfCapture(id).forEach { deleteNote(it.id) }
        dao.clearSuggestedTasksOfCapture(id); dao.clearSuggestedEvents(id); dao.deleteCapture(id)
    }

    suspend fun setEvent(id: String, status: String) = dao.setEventStatus(id, status)

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
            dao.upsertTask(TaskEntity(id = Text.uuid(), noteId = noteId, text = t.text, dueDate = t.due, status = TaskStatus.OPEN, reason = t.reason, createdAt = now))
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
            dao.upsertTask(TaskEntity(id = Text.uuid(), noteId = noteId, text = it.text, dueDate = it.due, status = TaskStatus.SUGGESTED, reason = it.reason, createdAt = System.currentTimeMillis()))
        }
        if (note.status != NoteStatus.FILED) dao.upsertNote(note.copy(title = proposal?.title?.ifBlank { null } ?: note.title))
    }

    suspend fun deleteNote(id: String) = withContext(Dispatchers.IO) {
        dao.deleteNote(id); dao.clearMentions(id); dao.clearLinks(id); dao.pruneEntities()
    }

    val allTasks = dao.allTasksFlow()

    suspend fun setTask(id: String, status: String) {
        val t = dao.task(id) ?: return
        dao.upsertTask(t.copy(status = status, completedAt = if (status == TaskStatus.DONE) System.currentTimeMillis() else null))
    }

    /** Repousser = un signal d'adhérence, pas juste une date. */
    suspend fun postponeTask(id: String, days: Long) {
        val t = dao.task(id) ?: return
        val base = Dates.parseDay(t.dueDate) ?: Dates.today()
        val next = maxOf(base, Dates.today()).plusDays(days)
        dao.upsertTask(t.copy(dueDate = next.toString(), postponed = t.postponed + 1, originalDue = t.originalDue ?: t.dueDate))
    }

    suspend fun abandonTask(id: String) = setTask(id, TaskStatus.ABANDONED)
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
        val out = ai.generate(s, prompt, temperature = 0.4)
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
        val out = ai.generate(s, prompt, temperature = 0.5)
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
            put("events", buildJsonArray {
                dao.allEvents().forEach { e ->
                    add(buildJsonObject { put("title", e.title); put("startAt", e.startAt); put("allDay", e.allDay); put("where", e.where); put("status", e.status) })
                }
            })
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

    /** Petite phrase d'accueil calculée localement : la dernière chose utile à savoir. */
    suspend fun homeInsight(): String? = withContext(Dispatchers.IO) {
        val notes = dao.notes()
        val events = dao.allEvents().filter { it.status == EventStatus.CONFIRMED }
        val today = Dates.today()
        events.firstOrNull { Dates.parseDay(it.startAt) == today }?.let { e ->
            val t = if (!e.allDay && e.startAt.length >= 16) " à ${e.startAt.substring(11).replace(':', 'h')}" else ""
            return@withContext "📅 Aujourd'hui$t : ${e.title}"
        }
        val portrait = app.murmure.insights.Insights.build(notes, dao.entities(), dao.mentions(), today)
        portrait.correlations.firstOrNull { it.delta > 0.4f }?.let { c ->
            return@withContext "✨ Ton mood est plus haut les jours où « ${c.entity.name} » revient (+${Text.d1(c.delta)})."
        }
        val week = notes.filter { it.createdAt > System.currentTimeMillis() - 7L * 86_400_000 }
        val topic = week.flatMap { it.keywords.split("|") }.filter { it.isNotBlank() }.groupingBy { it.lowercase() }.eachCount().maxByOrNull { it.value }
        if (topic != null && topic.value >= 2) return@withContext "🧠 Cette semaine, tu reviens souvent sur « ${topic.key} » (${topic.value}×)."
        if (portrait.streak >= 2) return@withContext "🔥 ${portrait.streak} jours de rituel d'affilée. Continue ce soir ?"
        null
    }

    suspend fun wipeEverything() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        settings.wipe()
    }
}
