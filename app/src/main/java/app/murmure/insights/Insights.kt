package app.murmure.insights

import app.murmure.core.Dates
import app.murmure.core.Text
import app.murmure.data.EntityKind
import app.murmure.data.MentionEntity
import app.murmure.data.NoteEntity
import app.murmure.data.NoteMention
import app.murmure.data.NoteStatus
import java.time.LocalDate
import kotlin.math.abs

data class Correlation(
    val entity: MentionEntity,
    val avgWith: Float,
    val avgWithout: Float,
    val daysWith: Int,
    val daysWithout: Int,
) {
    val delta get() = avgWith - avgWithout
}

data class PersonPresence(
    val entity: MentionEntity,
    val mentions: Int,
    /** -1..1 : tonalité moyenne associée */
    val tone: Float,
)

data class Portrait(
    val streak: Int,
    val bestStreak: Int,
    val dailyDone: Boolean,
    val totalNotes: Int,
    val moodByDay: List<Pair<LocalDate, Float?>>,
    val avgMood: Float?,
    val correlations: List<Correlation>,
    val people: List<PersonPresence>,
    val activities: List<PersonPresence>,
    val places: List<PersonPresence>,
    val emotions: List<Pair<String, Int>>,
    val types: List<Pair<String, Int>>,
    val timeOfDay: List<Pair<String, Float>>,
    val energyByTime: List<Pair<String, Float>>,
    val themes: List<Pair<String, Int>>,
)

object Insights {

    /** Humeur 1..5 d'une note : déclarée, sinon estimée depuis la valence. */
    fun moodOf(n: NoteEntity): Float? = n.mood?.toFloat() ?: n.valence?.let { 3f + 2f * it }

    fun streak(notes: List<NoteEntity>, today: LocalDate = Dates.today()): Pair<Int, Int> {
        val days = notes.filter { it.isDaily }.mapNotNull { Dates.parseDay(it.dayKey) }.toSortedSet()
        var cur = 0
        var d = if (today in days) today else today.minusDays(1)
        while (d in days) { cur++; d = d.minusDays(1) }
        var best = 0; var run = 0; var prev: LocalDate? = null
        days.forEach { x ->
            run = if (prev != null && prev!!.plusDays(1) == x) run + 1 else 1
            best = maxOf(best, run); prev = x
        }
        return cur to maxOf(best, cur)
    }

    fun build(
        notes: List<NoteEntity>,
        entities: List<MentionEntity>,
        mentions: List<NoteMention>,
        today: LocalDate = Dates.today(),
    ): Portrait {
        val filed = notes.filter { it.status == NoteStatus.FILED || it.isDaily }
        val byId = filed.associateBy { it.id }
        val entById = entities.associateBy { it.id }

        // Humeur par jour : le rituel prime, sinon moyenne estimée.
        val dayMood = filed.groupBy { it.dayKey }.mapNotNull { (day, ns) ->
            val d = Dates.parseDay(day) ?: return@mapNotNull null
            val declared = ns.mapNotNull { it.mood }.lastOrNull()?.toFloat()
            val est = ns.mapNotNull { moodOf(it) }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            (declared ?: est)?.let { d to it }
        }.toMap()

        val series = (29 downTo 0).map { today.minusDays(it.toLong()) }.map { it to dayMood[it] }

        // Jours où chaque entité est mentionnée
        val entityDays = HashMap<String, MutableSet<LocalDate>>()
        val entityTone = HashMap<String, MutableList<Float>>()
        mentions.forEach { m ->
            val n = byId[m.noteId] ?: return@forEach
            val d = Dates.parseDay(n.dayKey) ?: return@forEach
            entityDays.getOrPut(m.entityId) { mutableSetOf() } += d
            val tone = if (m.sentiment != 0f) m.sentiment else (n.valence ?: 0f)
            entityTone.getOrPut(m.entityId) { mutableListOf() } += tone
        }

        val moodDays = dayMood.keys
        val correlations = entityDays.mapNotNull { (eid, days) ->
            val e = entById[eid] ?: return@mapNotNull null
            val with = days.filter { it in moodDays }
            val without = moodDays.filter { it !in days }
            if (with.size < 2 || without.size < 2) return@mapNotNull null
            Correlation(
                e, with.map { dayMood[it]!! }.average().toFloat(), without.map { dayMood[it]!! }.average().toFloat(),
                with.size, without.size,
            )
        }.filter { abs(it.delta) >= 0.25f }.sortedByDescending { abs(it.delta) * minOf(it.daysWith, 6) }.take(8)

        fun presence(kind: String) = entityTone.mapNotNull { (eid, tones) ->
            val e = entById[eid]?.takeIf { it.kind == kind } ?: return@mapNotNull null
            PersonPresence(e, tones.size, tones.average().toFloat())
        }.sortedByDescending { it.mentions }.take(12)

        val emotions = filed.mapNotNull { it.emotion }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
        val types = filed.mapNotNull { it.type }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
        val order = listOf("matin", "après-midi", "soir", "nuit")
        val tod = filed.filter { it.timeOfDay.isNotBlank() }.groupBy { it.timeOfDay }
            .mapNotNull { (k, ns) -> ns.mapNotNull { moodOf(it) }.takeIf { it.isNotEmpty() }?.let { k to it.average().toFloat() } }
            .sortedBy { order.indexOf(it.first) }
        val energyScore = mapOf("basse" to 1f, "moyenne" to 2f, "haute" to 3f)
        val energy = filed.filter { it.energy != null && it.timeOfDay.isNotBlank() }.groupBy { it.timeOfDay }
            .map { (k, ns) -> k to ns.mapNotNull { energyScore[it.energy] }.average().toFloat() }
            .sortedBy { order.indexOf(it.first) }
        val themes = filed.flatMap { it.keywords.split("|") }.filter { it.isNotBlank() }
            .groupingBy { it.lowercase() }.eachCount().toList().sortedByDescending { it.second }.take(20)

        val (streak, best) = streak(notes, today)
        return Portrait(
            streak = streak, bestStreak = best,
            dailyDone = notes.any { it.isDaily && it.dayKey == today.toString() },
            totalNotes = notes.size,
            moodByDay = series,
            avgMood = dayMood.values.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            correlations = correlations,
            people = presence(EntityKind.PERSON),
            activities = presence(EntityKind.ACTIVITY),
            places = presence(EntityKind.PLACE),
            emotions = emotions, types = types, timeOfDay = tod, energyByTime = energy, themes = themes,
        )
    }

    /** Résumé agrégé (aucun texte brut) envoyé à l'IA pour la lecture du portrait. */
    fun statsForAi(p: Portrait): String = buildString {
        appendLine("Notes : ${p.totalNotes}. Série actuelle de notes quotidiennes : ${p.streak} jours.")
        p.avgMood?.let { appendLine("Humeur moyenne (1-5) : ${Text.d1(it)}") }
        appendLine("Humeur des 30 derniers jours : " + p.moodByDay.filter { it.second != null }
            .joinToString(", ") { "${it.first.dayOfWeek.name.take(3)} ${"%.1f".format(it.second)}" })
        if (p.correlations.isNotEmpty()) appendLine("Corrélations (humeur moyenne avec / sans) : " + p.correlations.joinToString("; ") {
            "${it.entity.name} [${it.entity.kind}] ${"%.1f".format(it.avgWith)} vs ${"%.1f".format(it.avgWithout)} (${it.daysWith} j)"
        })
        if (p.people.isNotEmpty()) appendLine("Personnes (mentions, tonalité -1..1) : " + p.people.joinToString("; ") { "${it.entity.name} ${it.mentions}× ${"%.2f".format(it.tone)}" })
        if (p.activities.isNotEmpty()) appendLine("Activités : " + p.activities.joinToString("; ") { "${it.entity.name} ${it.mentions}× ${"%.2f".format(it.tone)}" })
        if (p.places.isNotEmpty()) appendLine("Lieux : " + p.places.joinToString("; ") { "${it.entity.name} ${it.mentions}× ${"%.2f".format(it.tone)}" })
        if (p.timeOfDay.isNotEmpty()) appendLine("Humeur selon le moment : " + p.timeOfDay.joinToString("; ") { "${it.first} ${"%.1f".format(it.second)}" })
        if (p.energyByTime.isNotEmpty()) appendLine("Énergie (1-3) selon le moment : " + p.energyByTime.joinToString("; ") { "${it.first} ${"%.1f".format(it.second)}" })
        if (p.emotions.isNotEmpty()) appendLine("Émotions : " + p.emotions.joinToString("; ") { "${it.first} ${it.second}" })
        if (p.themes.isNotEmpty()) appendLine("Thèmes récurrents : " + p.themes.take(12).joinToString(", ") { "${it.first} (${it.second})" })
    }
}
