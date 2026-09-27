package app.murmure.ai

import app.murmure.core.Text
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Intelligence locale, instantanée et hors ligne : intentions, surlignage, tâches. */
object LocalBrain {

    private val folderIntent = Regex(
        "(?:dans|pour|en lien avec|concernant|à propos d[ue]|au sujet d[ue]|classe[rz]?(?: ça)? dans)?\\s*" +
            "(?:le |la |mon |ma |du |au )?(?:dossier|projet|catégorie|carnet)\\s+" +
            "(?:de |du |des |d'|sur |« )?([\\p{L}\\p{N}'’-]+(?:\\s+[\\p{L}\\p{N}'’-]+){0,3}?)" +
            "(?=\\s*[.,;:!?»]|\\s+(?:et|pour|parce|alors|donc|je|j'|on|qui|que|ou|mais|euh|bon)\\b|\\s*$)",
        RegexOption.IGNORE_CASE,
    )

    /** Dossier annoncé à l'oral (« une note en lien avec le dossier X »), au début de la dictée. */
    fun declaredFolder(text: String, folders: List<String>): String? {
        val head = text.take(260)
        val m = folderIntent.find(head) ?: return null
        val raw = m.groupValues[1].trim().trimEnd('.', ',')
        if (raw.length < 2) return null
        val k = Text.key(raw)
        folders.firstOrNull { Text.key(it) == k }?.let { return it }
        folders.firstOrNull { k.startsWith(Text.key(it)) || Text.key(it).startsWith(k) }?.let { return it }
        return Text.capitalize(raw)
    }

    data class Hit(val start: Int, val end: Int, val kind: String, val label: String)

    /**
     * Repère dans le texte les termes connus (entités, dossiers, titres, mots-clés IA)
     * en ignorant accents et casse. Retourne des plages non chevauchantes.
     */
    fun highlight(text: String, terms: Map<String, String>): List<Hit> {
        if (text.isBlank() || terms.isEmpty()) return emptyList()
        val folded = fold(text)
        val hits = mutableListOf<Hit>()
        terms.entries.sortedByDescending { it.key.length }.forEach { (term, kind) ->
            val k = fold(term)
            if (k.length < 3) return@forEach
            var idx = folded.indexOf(k)
            while (idx >= 0) {
                val end = idx + k.length
                val boundaryL = idx == 0 || !folded[idx - 1].isLetterOrDigit()
                val boundaryR = end >= folded.length || !folded[end].isLetterOrDigit()
                if (boundaryL && boundaryR && hits.none { idx < it.end && end > it.start }) {
                    hits += Hit(idx, end, kind, term)
                }
                idx = folded.indexOf(k, end)
            }
        }
        return hits.sortedBy { it.start }
    }

    /** Pliage caractère à caractère (même longueur que l'original). */
    fun fold(s: String): String = buildString(s.length) {
        s.forEach { c ->
            append(
                if (c.code < 128) c.lowercaseChar()
                else if (c == '’') '\''
                else java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)[0].lowercaseChar()
            )
        }
    }

    private val taskCue = Regex(
        "(?:(?:il )?faudrait que (?:je |j['’])|(?:il )?faut (?:que )?(?:je |j['’])|je dois |j['’]ai besoin de |penser à |ne pas oublier de |n['’]oublie pas de |rappelle-moi de |à faire\\s*:?\\s*|je vais devoir )([^.!?\\n]{3,120})",
        RegexOption.IGNORE_CASE,
    )

    fun detectTasks(text: String, today: LocalDate = LocalDate.now()): List<TaskGuess> =
        taskCue.findAll(text).map { m ->
            val body = m.groupValues[1].trim().trimEnd(',', ';')
            TaskGuess(Text.capitalize(body), dueFrom(m.value, today)?.toString(), "« ${m.value.trim().take(60)} »")
        }.distinctBy { Text.key(it.text) }.take(8).toList()

    fun dueFrom(s: String, today: LocalDate): LocalDate? {
        val k = Text.key(s)
        val days = mapOf(
            "lundi" to DayOfWeek.MONDAY, "mardi" to DayOfWeek.TUESDAY, "mercredi" to DayOfWeek.WEDNESDAY,
            "jeudi" to DayOfWeek.THURSDAY, "vendredi" to DayOfWeek.FRIDAY, "samedi" to DayOfWeek.SATURDAY, "dimanche" to DayOfWeek.SUNDAY,
        )
        return when {
            "apres-demain" in k || "apres demain" in k -> today.plusDays(2)
            "demain" in k -> today.plusDays(1)
            "aujourd'hui" in k || "ce soir" in k || "tantot" in k -> today
            "semaine prochaine" in k -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            "fin de semaine" in k || "ce week" in k -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            "mois prochain" in k -> today.plusMonths(1).withDayOfMonth(1)
            else -> days.entries.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(k) }
                ?.let { today.with(TemporalAdjusters.next(it.value)) }
        }
    }

    private val greeting = Regex("^(bonjour|bonsoir|salut|allo|allô|ok|okay|alors|bon)[,.!\\s]+", RegexOption.IGNORE_CASE)

    /** Retire la salutation et la phrase d'annonce (« une note pour le dossier X ») du début. */
    fun stripIntro(text: String): String {
        var t = text.trim().replace(greeting, "")
        val first = Regex("^[^.!?]*[.!?]\\s*").find(t)
        if (first != null && folderIntent.containsMatchIn(first.value) && t.length > first.value.length + 10) {
            t = t.substring(first.value.length)
        }
        return Text.capitalize(t.replace(greeting, ""))
    }

    /** Proposition sans IA (pas de clé / hors ligne) : rien n'est perdu, tout reste modifiable. */
    fun offlineProposal(transcript: String, ctx: AnalysisContext, knownTerms: Map<String, String>): NoteProposal {
        val declared = declaredFolder(transcript, ctx.folders)
        val content = stripIntro(transcript)
        val hits = highlight(transcript, knownTerms)
        val entities = hits.filter { it.kind in listOf("person", "place", "activity", "project") }
            .map { EntityGuess(it.label, it.kind) }.distinctBy { it.name }
        val folders = buildList {
            if (ctx.isDaily) add(FolderSuggestion("Journal", ctx.folders.any { Text.key(it) == "journal" }, "Rituel quotidien", 0.99))
            declared?.let { d -> add(FolderSuggestion(d, ctx.folders.any { Text.key(it) == Text.key(d) }, "Annoncé dans ta dictée", 0.9)) }
            hits.firstOrNull { it.kind == "folder" }?.let { add(FolderSuggestion(it.label, true, "Mentionné dans la note", 0.6)) }
            add(FolderSuggestion("Boîte de réception", ctx.folders.any { Text.key(it) == "boite de reception" }, "À trier plus tard", 0.3))
        }.distinctBy { Text.key(it.name) }.take(3)
        val body = content.split(Regex("(?<=[.!?])\\s+")).chunked(3).joinToString("\n\n") { it.joinToString(" ") }
        return NoteProposal(
            title = NoteAnalyzer.fallbackTitle(content),
            summary = content.take(140) + if (content.length > 140) "…" else "",
            body = body,
            declaredFolder = declared,
            folderSuggestions = folders,
            type = if (ctx.isDaily) "journal" else "idee",
            emotion = EmotionGuess(moodEmotion(ctx.mood), 0.5, ((ctx.mood ?: 3) - 3) / 2.0),
            entities = entities,
            keywords = hits.map { it.label }.distinct().take(8),
            links = hits.filter { it.kind == "note" }.map { it.label }.distinct(),
            tasks = detectTasks(transcript),
            byAi = false,
        )
    }

    fun moodEmotion(mood: Int?) = when (mood) {
        1 -> "tristesse"; 2 -> "fatigue"; 4 -> "calme"; 5 -> "joie"; else -> "neutre"
    }
}
