package app.murmure.core

import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Text {
    private val diacritics = "\\p{InCombiningDiacriticalMarks}+".toRegex()

    /** minuscules, sans accents, espaces compactés — clé de comparaison. */
    fun key(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.CANADA_FRENCH), Normalizer.Form.NFD)
            .replace(diacritics, "")
            .replace("[’']".toRegex(), "'")
            .replace("\\s+".toRegex(), " ")
            .trim()

    fun capitalize(s: String) = s.trim().replaceFirstChar { it.titlecase(Locale.CANADA_FRENCH) }

    fun uuid() = java.util.UUID.randomUUID().toString()

    /** 3,5 — une décimale, à la française. */
    fun d1(v: Number): String = String.format(Locale.CANADA_FRENCH, "%.1f", v.toDouble())

    /** +0,8 / −1,1 */
    fun signed1(v: Number): String = (if (v.toDouble() >= 0) "+" else "−") + d1(kotlin.math.abs(v.toDouble()))
}

object Dates {
    val zone: ZoneId get() = ZoneId.systemDefault()
    val fr: Locale = Locale.CANADA_FRENCH
    private val dayFmt = DateTimeFormatter.ISO_LOCAL_DATE

    fun dayKey(epochMs: Long = System.currentTimeMillis()): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().format(dayFmt)

    fun today(): LocalDate = LocalDate.now(zone)

    fun parseDay(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    fun timeOfDay(epochMs: Long = System.currentTimeMillis()): String {
        val h = java.time.Instant.ofEpochMilli(epochMs).atZone(zone).hour
        return when (h) {
            in 5..11 -> "matin"
            in 12..16 -> "après-midi"
            in 17..21 -> "soir"
            else -> "nuit"
        }
    }

    fun nowIso(): String = LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

    fun weekday(d: LocalDate = today()): String = d.format(DateTimeFormatter.ofPattern("EEEE", fr))

    fun pretty(epochMs: Long): String {
        val dt = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
        val d = dt.toLocalDate()
        val t = dt.format(DateTimeFormatter.ofPattern("HH'h'mm"))
        return when (d) {
            today() -> "Aujourd'hui · $t"
            today().minusDays(1) -> "Hier · $t"
            else -> dt.format(DateTimeFormatter.ofPattern("d MMM · HH'h'mm", fr))
        }
    }

    fun prettyDue(s: String?): String {
        val d = parseDay(s) ?: return "Sans échéance"
        val t = today()
        return when {
            d == t -> "Aujourd'hui"
            d == t.plusDays(1) -> "Demain"
            d.isBefore(t) -> "En retard · " + d.format(DateTimeFormatter.ofPattern("d MMM", fr))
            d.isBefore(t.plusDays(7)) -> Text.capitalize(d.format(DateTimeFormatter.ofPattern("EEEE", fr)))
            else -> d.format(DateTimeFormatter.ofPattern("d MMM", fr))
        }
    }
}
