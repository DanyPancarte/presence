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
        // « Nouvelle note : … » / « une note pour le dossier X. … » en tête
        noteCue.find(t)?.takeIf { it.range.first <= 3 }?.let { m ->
            val after = t.substring(m.range.last + 1)
            val cut = Regex("^\\s*(?:pour|dans)\\s+(?:le\\s+)?dossier\\s+[^.,:!?\\n]{1,40}").find(after)?.range?.last?.plus(1) ?: 0
            t = after.substring(cut).trimStart(' ', ',', ':', '.', '-', '!')
        }
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

    // ------------------------------------------------------------------
    //  Moments : détection instantanée pendant la dictée (sans réseau)
    // ------------------------------------------------------------------

    private val noteCue = Regex(
        "(?:(?:nouvelle|autre|deuxième|troisième|prochaine)\\s+note|prends?(?:\\s+ça)?\\s+en\\s+note|note\\s+(?:ça|cela)|(?:ça|ceci)\\s+c['’]est\\s+une\\s+note|je\\s+(?:veux|voudrais|aimerais)\\s+(?:prendre\\s+)?(?:une\\s+)?note|note\\s+(?:pour|dans)\\s+(?:le\\s+)?dossier)",
        RegexOption.IGNORE_CASE,
    )
    private val eventCue = Regex(
        "(?:rendez-vous|rdv|réunion|rencontre|meeting|appel|call|dîner|souper|lunch|brunch|café|entrevue|entraînement|cours|séance|présentation|démo|livraison|vol|train|fête|party|anniversaire|dentiste|médecin|docteur)" +
            "[^.!?\\n]{0,80}?" +
            "(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|demain|après-demain|ce soir|ce midi|cet après-midi|\\d{1,2}\\s*h(?:\\s*\\d{2})?|\\d{1,2}\\s+(?:janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|novembre|décembre))",
        RegexOption.IGNORE_CASE,
    )
    private val timeCue = Regex("(\\d{1,2})\\s*h\\s*(\\d{2})?", RegexOption.IGNORE_CASE)
    private val moodCue = Regex(
        "(?:je\\s+me\\s+sens|je\\s+suis|j['’]étais|je\\s+me\\s+sentais|ça\\s+va|je\\s+file|j['’]ai\\s+l['’]air)\\s+(?:vraiment\\s+|très\\s+|un\\s+peu\\s+|pas\\s+mal\\s+|super\\s+|tellement\\s+|assez\\s+|full\\s+|ben\\s+)?" +
            "(bien|mal|content|contente|heureux|heureuse|fier|fière|calme|zen|serein|sereine|motivé|motivée|excité|excitée|reconnaissant|reconnaissante|" +
            "fatigué|fatiguée|épuisé|épuisée|vidé|vidée|brûlé|brûlée|stressé|stressée|anxieux|anxieuse|inquiet|inquiète|nerveux|nerveuse|triste|down|déprimé|déprimée|frustré|frustrée|fâché|fâchée|en\\s+colère|tanné|tannée|découragé|découragée|" +
            "correct|moyen|bof|ordinaire|neutre)",
        RegexOption.IGNORE_CASE,
    )
    private val ideaCue = Regex("(?:j['’]ai\\s+(?:eu\\s+)?une\\s+idée|idée\\s*:|et\\s+si\\s+on|ça\\s+serait\\s+(?:cool|bien|le\\s+fun)\\s+(?:de|d['’]|si))\\s*([^.!?\\n]{4,140})?", RegexOption.IGNORE_CASE)

    fun moodFromWord(w: String): Pair<String, Int> {
        val k = Text.key(w)
        return when {
            k.startsWith("heureu") || k == "content" || k == "contente" -> "joie" to 5
            k.startsWith("fier") || k.startsWith("motiv") || k.startsWith("excit") -> "fierté" to 4
            k.startsWith("reconnaiss") -> "gratitude" to 4
            k == "bien" || k == "calme" || k == "zen" || k.startsWith("serein") -> "calme" to 4
            k.startsWith("fatigu") || k.startsWith("epuis") || k.startsWith("vid") || k.startsWith("brul") -> "fatigue" to 2
            k.startsWith("stress") || k.startsWith("nerv") -> "stress" to 2
            k.startsWith("anxi") || k.startsWith("inqui") -> "anxiété" to 2
            k == "triste" || k == "down" || k.startsWith("deprim") || k.startsWith("decourag") -> "tristesse" to 1
            k.startsWith("frustr") || k.startsWith("tann") -> "frustration" to 2
            k.startsWith("fach") || k.contains("colere") -> "colère" to 1
            k == "mal" -> "tristesse" to 2
            else -> "neutre" to 3
        }
    }

    /** Heure « 14h30 » → LocalTime, sinon null. */
    fun timeFrom(s: String): java.time.LocalTime? = timeCue.find(s)?.let { m ->
        val h = m.groupValues[1].toIntOrNull() ?: return null
        if (h !in 0..23) return null
        java.time.LocalTime.of(h, m.groupValues[2].toIntOrNull() ?: 0)
    }

    /**
     * Passe locale : repère tâches, rendez-vous, moods, idées et coupures de note.
     * Rapide, déterministe, tourne à chaque mise à jour du texte.
     */
    fun detectMoments(text: String, today: LocalDate = LocalDate.now()): List<Moment> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<Moment>()
        fun id(kind: String, start: Int) = "l:$kind:$start"

        noteCue.findAll(text).forEach { m ->
            // La note commence après l'annonce ; le titre vient de la suite de la phrase.
            val rest = text.substring(m.range.last + 1).trimStart(' ', ',', ':', '.', '-')
            val folder = declaredFolder(text.substring(m.range.first).take(160), emptyList())
            out += Moment(
                id = id(MomentKind.NOTE, m.range.first), kind = MomentKind.NOTE,
                title = folder?.let { "Note · $it" } ?: NoteAnalyzer.fallbackTitle(rest).takeIf { rest.length > 8 } ?: "Nouvelle note",
                text = rest.take(160), start = m.range.first, end = m.range.last + 1, folder = folder, confidence = 0.8,
            )
        }
        taskCue.findAll(text).forEach { m ->
            val body = m.groupValues[1].trim().trimEnd(',', ';')
            out += Moment(
                id = id(MomentKind.TASK, m.range.first), kind = MomentKind.TASK, title = Text.capitalize(body),
                text = m.value.trim(), start = m.range.first, end = m.range.last + 1,
                due = dueFrom(m.value, today)?.toString(), confidence = 0.75,
            )
        }
        eventCue.findAll(text).forEach { m ->
            if (out.any { it.kind == MomentKind.TASK && it.start <= m.range.first && it.end >= m.range.last }) return@forEach
            // On lit toute la phrase : l'heure et les personnes viennent souvent après le jour.
            val endIdx = text.indexOfAny(charArrayOf('.', '!', '?', '\n'), m.range.first).let { if (it < 0) text.length else it }
            val sentence = text.substring(m.range.first, endIdx).trim()
            val day = dueFrom(sentence, today) ?: today
            val time = timeFrom(sentence)
            val start = if (time != null) day.atTime(time).toString().take(16) else day.toString()
            val who = Regex("avec\\s+([A-ZÉÈÀ][\\p{L}'’-]+)").findAll(sentence).map { it.groupValues[1] }.toList()
            val where = Regex("\\b(?:au|à la|à l['’]|chez)\\s+([\\p{L}'’ -]{3,30}?)(?=\\s+(?:avec|à|le|pour)\\b|$)", RegexOption.IGNORE_CASE).find(sentence)?.groupValues?.get(1)?.trim()
            out += Moment(
                id = id(MomentKind.EVENT, m.range.first), kind = MomentKind.EVENT,
                title = Text.capitalize(sentence.take(70)), text = sentence, start = m.range.first, end = endIdx,
                due = start, allDay = time == null, who = who, where = where?.let { Text.capitalize(it) }, confidence = 0.7,
            )
        }
        moodCue.findAll(text).forEach { m ->
            val (emotion, mood) = moodFromWord(m.groupValues[1])
            out += Moment(
                id = id(MomentKind.MOOD, m.range.first), kind = MomentKind.MOOD,
                title = "${Emotions.emoji(emotion)} $emotion", text = m.value.trim(), start = m.range.first, end = m.range.last + 1,
                emotion = emotion, mood = mood, confidence = 0.7,
            )
        }
        ideaCue.findAll(text).forEach { m ->
            val body = m.groupValues.getOrNull(1)?.trim()?.trimStart(':', '-', ' ')?.trim().orEmpty()
            out += Moment(
                id = id(MomentKind.IDEA, m.range.first), kind = MomentKind.IDEA,
                title = if (body.length > 6) Text.capitalize(body.take(70)) else "Une idée", text = m.value.trim(),
                start = m.range.first, end = m.range.last + 1, confidence = 0.6,
            )
        }
        return out.sortedBy { it.start }
    }

    /** Fusion : l'IA affine ou remplace un moment local qui recouvre la même plage. */
    fun mergeMoments(local: List<Moment>, ai: List<Moment>): List<Moment> {
        val kept = local.filter { l ->
            ai.none { a -> a.kind == l.kind && a.start >= 0 && (a.start < l.end && a.end > l.start) }
        }
        return (kept + ai).sortedBy { if (it.start < 0) Int.MAX_VALUE else it.start }
    }

    /** Sujet courant : le concept le plus fréquent dans les 400 derniers caractères. */
    fun localTopic(text: String, terms: Map<String, String>): String? {
        val tail = text.takeLast(400)
        val hits = highlight(tail, terms).filter { it.kind != "note" }
        return hits.groupingBy { it.label }.eachCount().maxByOrNull { it.value }?.key
    }

    /**
     * Proposition de session hors ligne : découpe aux annonces de note, tâches, agenda, mood.
     * Tout reste modifiable ; l'IA peut repasser plus tard.
     */
    fun offlineSession(transcript: String, ctx: AnalysisContext, knownTerms: Map<String, String>, moments: List<Moment>): SessionProposal {
        val all = moments.ifEmpty { detectMoments(transcript) }
        val cuts = all.filter { it.kind == MomentKind.NOTE && it.start > 40 }.map { it.start }.distinct().sorted()
        val bounds = (listOf(0) + cuts + listOf(transcript.length)).zipWithNext()
        val chunks = bounds.map { (a, b) -> transcript.substring(a, b).trim() }.filter { it.length > 12 }.ifEmpty { listOf(transcript) }
        val notes = chunks.mapIndexed { i, chunk ->
            val p = offlineProposal(chunk, ctx.copy(isDaily = ctx.isDaily && i == 0), knownTerms)
            p.copy(tasks = emptyList())
        }
        val moodMoment = all.filter { it.kind == MomentKind.MOOD }.lastOrNull()
        return SessionProposal(
            notes = notes,
            tasks = all.filter { it.kind == MomentKind.TASK }.map { TaskGuess(it.title, it.due, "« ${it.text.take(60)} »") },
            events = all.filter { it.kind == MomentKind.EVENT },
            mood = ctx.mood ?: moodMoment?.mood,
            moodEmotion = moodMoment?.emotion ?: ctx.mood?.let { moodEmotion(it) },
            insight = localInsight(all, ctx),
            byAi = false,
        )
    }

    fun localInsight(moments: List<Moment>, ctx: AnalysisContext): String? {
        val tasks = moments.count { it.kind == MomentKind.TASK }
        val events = moments.count { it.kind == MomentKind.EVENT }
        val notes = moments.count { it.kind == MomentKind.NOTE }
        return when {
            tasks >= 3 -> "$tasks actions dans une seule dictée : ta tête déborde, elles sont ici maintenant."
            events >= 1 && tasks >= 1 -> "Un rendez-vous et une tâche dans le même souffle — je les ai séparés."
            notes >= 2 -> "${notes + 1} sujets distincts : je les ai découpés en notes séparées."
            ctx.isDaily && ctx.mood != null && ctx.mood <= 2 -> "Journée lourde. Merci de l'avoir dite quand même."
            else -> null
        }
    }
}
