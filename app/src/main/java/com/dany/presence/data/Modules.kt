package com.dany.presence.data

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Which overlay the hologram shows on top of itself. */
enum class Module { AUCUN, TACHES, MOOD, NOTES, MEDS, BUDGET, AGENDA }

/** Applies the agent's structured actions to the database and builds the context it reads back. */
class Modules(context: Context) {
    val dao = PresenceDb.get(context).dao()
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.CANADA_FRENCH)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.CANADA_FRENCH)
    private val hm = SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH)

    fun today(): String = day.format(Date())

    /** Returns the module most relevant to what was just applied (drives the overlay). */
    suspend fun apply(actions: List<JSONObject>): Module {
        var m = Module.AUCUN
        for (a in actions) when (a.optString("type")) {
            "tache" -> {
                val nom = a.optString("nom").ifBlank { continue }
                val pct = a.optInt("avancement", 97).coerceIn(0, 100)
                val existing = dao.findTask(nom.take(12))
                if (existing != null) dao.update(existing.copy(avancement = pct, touche = System.currentTimeMillis(), fini = pct >= 100))
                else dao.insert(Task(nom = nom, avancement = pct, fini = pct >= 100))
                m = Module.TACHES
            }
            "note" -> { a.optString("texte").ifBlank { null }?.let { dao.insert(Note(texte = it)); m = Module.NOTES } }
            "mood" -> { dao.insert(MoodEntry(valeur = a.optInt("valeur", 0).coerceIn(-2, 2), note = a.optString("note"))); m = Module.MOOD }
            "med" -> {
                val at = a.optString("heure").takeIf { it.matches(Regex("\\d{1,2}:\\d{2}")) }?.let { h ->
                    Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h.substringBefore(':').toInt()); set(Calendar.MINUTE, h.substringAfter(':').toInt()); set(Calendar.SECOND, 0) }.timeInMillis
                } ?: System.currentTimeMillis()
                dao.upsert(MedLog(today(), if (a.optBoolean("pris", true)) at else null))
                m = Module.MEDS
            }
            "depense" -> { dao.insert(Expense(montant = a.optDouble("montant", 0.0), quoi = a.optString("quoi"))); m = Module.BUDGET }
            "agenda" -> {
                val quand = runCatching { iso.parse(a.optString("quand"))?.time }.getOrNull() ?: continue
                dao.insert(Event(quoi = a.optString("quoi"), quand = quand)); m = Module.AGENDA
            }
            "question_ouverte" -> a.optString("texte").ifBlank { null }?.let { dao.insert(OpenQuestion(texte = it)) }
        }
        return m
    }

    /** What the agent knows before answering. Short on purpose. */
    suspend fun context(): String {
        val sb = StringBuilder()
        val tasks = dao.tasksNow()
        if (tasks.isNotEmpty()) {
            sb.append("Projets ouverts : ")
            sb.append(tasks.take(5).joinToString("; ") { "${it.nom} (${it.avancement} %, touché il y a ${daysAgo(it.touche)} j)" })
            sb.append(".\n")
        }
        val med = dao.med(today())
        sb.append("Médicament aujourd'hui : ").append(if (med?.prisA != null) "pris à ${hm.format(Date(med.prisA))}" else "pas confirmé").append(".\n")
        val monthStart = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        val budget = dao.setting("budget_mois")?.toDoubleOrNull() ?: 600.0
        val spent = dao.spent(monthStart)
        sb.append("Budget perso du mois : $budget $, dépensé ${"%.0f".format(spent)} $, marge ${"%.0f".format(budget - spent)} $.\n")
        dao.moodsNow().firstOrNull()?.let { sb.append("Dernière humeur : ${it.valeur} (${it.note}) il y a ${daysAgo(it.quand)} j.\n") }
        val ev = dao.eventsNow(System.currentTimeMillis())
        if (ev.isNotEmpty()) sb.append("Agenda : ").append(ev.joinToString("; ") { "${it.quoi} le ${iso.format(Date(it.quand))}" }).append(".\n")
        val q = dao.openQuestions()
        if (q.isNotEmpty()) sb.append("Questions que tu lui as posées et pas refermées : ").append(q.joinToString(" | ") { it.texte }).append(".\n")
        dao.notesNow().takeIf { it.isNotEmpty() }?.let { sb.append("Dernières notes : ").append(it.joinToString(" | ") { n -> n.texte }).append(".\n") }
        return sb.toString()
    }

    private fun daysAgo(t: Long) = ((System.currentTimeMillis() - t) / 86_400_000L)
}
