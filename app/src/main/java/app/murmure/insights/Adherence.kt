package app.murmure.insights

import app.murmure.core.Dates
import app.murmure.data.MentionEntity
import app.murmure.data.NoteEntity
import app.murmure.data.NoteMention
import app.murmure.data.TaskEntity
import app.murmure.data.TaskStatus
import java.time.LocalDate
import kotlin.math.exp

/** Adhérence aux tâches : ce que je dis vouloir faire vs ce que je fais. */
data class Adherence(
    val total: Int,
    val done: Int,
    val abandoned: Int,
    val open: Int,
    val late: Int,
    val postponedTasks: Int,
    val avgSlipDays: Float,
    /** 0..1 : fait / (fait + abandonné + en retard) */
    val rate: Float,
    val onTimeRate: Float,
    /** Par jour de semaine de création : taux de complétion. */
    val byWeekday: List<Pair<String, Float>>,
    /** Tâches nées d'un mood bas vs haut. */
    val doneWhenLowMood: Float?,
    val doneWhenHighMood: Float?,
    val recentAbandoned: List<TaskEntity>,
)

/** Une personne / activité / lieu : poids et sens dans ma vie. */
data class Influence(
    val entity: MentionEntity,
    val mentions: Int,
    /** Poids récent : mentions pondérées par décroissance temporelle (30 j). */
    val weight: Float,
    val tone: Float,
    val moodDelta: Float?,
    val days: Int,
    val lastSeenDaysAgo: Int,
    val trend: Float,
) {
    /** Lecture : soutien / neutre / tension — descriptif, jamais un verdict. */
    val reading: String get() = when {
        (moodDelta ?: 0f) >= 0.4f || tone >= 0.35f -> "soutien"
        (moodDelta ?: 0f) <= -0.4f || tone <= -0.35f -> "tension"
        else -> "neutre"
    }
}

object Adherences {
    fun compute(tasks: List<TaskEntity>, notes: List<NoteEntity>, today: LocalDate = Dates.today()): Adherence {
        val real = tasks.filter { it.status != TaskStatus.SUGGESTED && it.status != TaskStatus.DISMISSED }
        val done = real.filter { it.status == TaskStatus.DONE }
        val abandoned = real.filter { it.status == TaskStatus.ABANDONED }
        val open = real.filter { it.status == TaskStatus.OPEN }
        val late = open.filter { Dates.parseDay(it.dueDate)?.isBefore(today) == true }
        val slips = real.mapNotNull { t ->
            val o = Dates.parseDay(t.originalDue) ?: return@mapNotNull null
            val d = Dates.parseDay(t.dueDate) ?: return@mapNotNull null
            java.time.temporal.ChronoUnit.DAYS.between(o, d).toFloat()
        }
        val onTime = done.count { t ->
            val due = Dates.parseDay(t.originalDue ?: t.dueDate) ?: return@count true
            val c = t.completedAt?.let { Dates.parseDay(Dates.dayKey(it)) } ?: return@count true
            !c.isAfter(due)
        }
        val settled = done.size + abandoned.size + late.size
        val moodByNote = notes.associate { it.id to (Insights.moodOf(it)) }
        fun rateFor(pred: (Float) -> Boolean): Float? {
            val group = real.filter { t -> t.noteId?.let { moodByNote[it] }?.let(pred) == true }
            val s = group.count { it.status != TaskStatus.OPEN }
            return if (s < 2) null else group.count { it.status == TaskStatus.DONE } / s.toFloat()
        }
        val fr = java.util.Locale.CANADA_FRENCH
        val byWd = real.filter { it.status != TaskStatus.OPEN }
            .groupBy { java.time.Instant.ofEpochMilli(it.createdAt).atZone(Dates.zone).dayOfWeek }
            .map { (d, ts) -> d.getDisplayName(java.time.format.TextStyle.SHORT, fr) to ts.count { it.status == TaskStatus.DONE } / ts.size.toFloat() }
        return Adherence(
            total = real.size, done = done.size, abandoned = abandoned.size, open = open.size, late = late.size,
            postponedTasks = real.count { it.postponed > 0 },
            avgSlipDays = slips.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f,
            rate = if (settled == 0) 0f else done.size / settled.toFloat(),
            onTimeRate = if (done.isEmpty()) 0f else onTime / done.size.toFloat(),
            byWeekday = byWd,
            doneWhenLowMood = rateFor { it <= 2.5f }, doneWhenHighMood = rateFor { it >= 3.5f },
            recentAbandoned = abandoned.sortedByDescending { it.createdAt }.take(5),
        )
    }

    /** Entourage et influences : qui pèse, dans quel sens, et si ça monte ou descend. */
    fun influences(
        notes: List<NoteEntity>, entities: List<MentionEntity>, mentions: List<NoteMention>,
        kinds: Set<String>, today: LocalDate = Dates.today(),
    ): List<Influence> {
        val byId = notes.associateBy { it.id }
        val entById = entities.associateBy { it.id }
        val dayMood = notes.groupBy { it.dayKey }.mapNotNull { (d, ns) ->
            val m = ns.mapNotNull { it.mood?.toFloat() }.lastOrNull() ?: ns.mapNotNull { Insights.moodOf(it) }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            m?.let { Dates.parseDay(d)!! to it }
        }.toMap()
        return mentions.groupBy { it.entityId }.mapNotNull { (eid, ms) ->
            val e = entById[eid]?.takeIf { it.kind in kinds } ?: return@mapNotNull null
            val rows = ms.mapNotNull { m -> byId[m.noteId]?.let { n -> Triple(Dates.parseDay(n.dayKey)!!, if (m.sentiment != 0f) m.sentiment else (n.valence ?: 0f), n) } }
            if (rows.isEmpty()) return@mapNotNull null
            val days = rows.map { it.first }.toSet()
            val ages = rows.map { java.time.temporal.ChronoUnit.DAYS.between(it.first, today).toInt().coerceAtLeast(0) }
            val weight = ages.sumOf { exp(-it / 30.0) }.toFloat()
            val recent = rows.count { java.time.temporal.ChronoUnit.DAYS.between(it.first, today) <= 14 }
            val older = rows.count { java.time.temporal.ChronoUnit.DAYS.between(it.first, today) in 15..28 }
            val with = days.mapNotNull { dayMood[it] }
            val without = dayMood.filterKeys { it !in days }.values
            Influence(
                entity = e, mentions = rows.size, weight = weight, tone = rows.map { it.second }.average().toFloat(),
                moodDelta = if (with.size >= 2 && without.size >= 2) (with.average() - without.average()).toFloat() else null,
                days = days.size, lastSeenDaysAgo = ages.minOrNull() ?: 0,
                trend = (recent - older).toFloat(),
            )
        }.sortedByDescending { it.weight }
    }

    /** « Pourquoi » d'un jour : entités et mots-clés des notes de ce jour. */
    fun whyOf(day: LocalDate, notes: List<NoteEntity>, entities: List<MentionEntity>, mentions: List<NoteMention>): List<String> {
        val dayNotes = notes.filter { it.dayKey == day.toString() }
        val ids = dayNotes.map { it.id }.toSet()
        val entById = entities.associateBy { it.id }
        val ents = mentions.filter { it.noteId in ids }.mapNotNull { entById[it.entityId]?.name }
        val kws = dayNotes.flatMap { it.keywords.split("|") }.filter { it.isNotBlank() }
        return (ents + kws).distinct().take(6)
    }
}
