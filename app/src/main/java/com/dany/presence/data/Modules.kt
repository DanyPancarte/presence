package com.dany.presence.data

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Which overlay the hologram shows on top of itself. */
typealias Module = com.dany.presence.core.Module

/** Applies the agent's structured actions to the database and builds the context it reads back. */
class Modules(context: Context) {
    val dao = PresenceDb.get(context).dao()
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.CANADA_FRENCH)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.CANADA_FRENCH)
    private val hm = SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH)

    fun today(): String = day.format(Date())

    /** One action applied, as the Executed signal reports it. */
    data class Applied(val module: Module, val type: String, val summary: String)

    /** Returns the module most relevant to what was just applied (drives the overlay). */
    suspend fun apply(actions: List<JSONObject>): Module {
        var m = Module.AUCUN
        for (a in actions) applyOne(a)?.let { if (it.module != Module.AUCUN) m = it.module }
        return m
    }

    /** Applies one of the agent's actions. Null when it is unknown, empty or malformed (nothing written). */
    suspend fun applyOne(a: JSONObject): Applied? {
        val type = a.optString("type")
        return when (type) {
            "tache" -> {
                val nom = a.optString("nom").ifBlank { return null }
                val pct = a.optInt("avancement", 97).coerceIn(0, 100)
                val existing = dao.findTask(nom.take(12))
                if (existing != null) dao.update(existing.copy(avancement = pct, touche = System.currentTimeMillis(), fini = pct >= 100))
                else dao.insert(Task(nom = nom, avancement = pct, fini = pct >= 100))
                Applied(Module.TACHES, type, "${existing?.nom ?: nom} → $pct %")
            }
            "tache_fini" -> {
                val nom = a.optString("nom").ifBlank { return null }
                val existing = dao.findTask(nom.take(12))
                if (existing != null) dao.update(existing.copy(avancement = 100, touche = System.currentTimeMillis(), fini = true))
                else dao.insert(Task(nom = nom, avancement = 100, fini = true))
                Applied(Module.TACHES, type, "${existing?.nom ?: nom} — fini")
            }
            "note" -> {
                val texte = a.optString("texte").ifBlank { return null }
                dao.insert(Note(texte = texte))
                Applied(Module.NOTES, type, texte.take(40))
            }
            "mood" -> {
                val v = a.optInt("valeur", 0).coerceIn(-2, 2)
                val note = a.optString("note")
                dao.insert(MoodEntry(valeur = v, note = note))
                Applied(Module.MOOD, type, "humeur ${if (v > 0) "+$v" else "$v"}" + (if (note.isNotBlank()) " · ${note.take(30)}" else ""))
            }
            "med" -> {
                val at = a.optString("heure").takeIf { it.matches(Regex("\\d{1,2}:\\d{2}")) }?.let { h ->
                    Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h.substringBefore(':').toInt()); set(Calendar.MINUTE, h.substringAfter(':').toInt()); set(Calendar.SECOND, 0) }.timeInMillis
                } ?: System.currentTimeMillis()
                val pris = a.optBoolean("pris", true)
                dao.upsert(MedLog(today(), if (pris) at else null))
                Applied(Module.MEDS, type, if (pris) "pris à ${hm.format(Date(at))}" else "pas pris")
            }
            "depense" -> {
                val montant = a.optDouble("montant", 0.0)
                val quoi = a.optString("quoi")
                dao.insert(Expense(montant = montant, quoi = quoi))
                Applied(Module.BUDGET, type, "${"%.2f".format(montant)} $" + (if (quoi.isNotBlank()) " — ${quoi.take(30)}" else ""))
            }
            "agenda" -> {
                val quand = runCatching { iso.parse(a.optString("quand"))?.time }.getOrNull() ?: return null
                val quoi = a.optString("quoi")
                dao.insert(Event(quoi = quoi, quand = quand))
                Applied(Module.AGENDA, type, "${quoi.take(30)} le ${iso.format(Date(quand))}")
            }
            "question_ouverte" -> {
                val texte = a.optString("texte").ifBlank { return null }
                dao.insert(OpenQuestion(texte = texte))
                Applied(Module.AUCUN, type, texte.take(40))
            }
            "fermer_question" -> {
                // By id (given in the context), else by matching text among the open ones.
                val id = a.optLong("id", -1L).takeIf { it > 0 }
                    ?: a.optString("texte").ifBlank { null }?.let { t -> dao.openQuestions().firstOrNull { it.texte.contains(t, ignoreCase = true) || t.contains(it.texte, ignoreCase = true) }?.id }
                    ?: return null
                dao.closeQuestion(id)
                Applied(Module.AUCUN, type, "question #$id refermée")
            }
            else -> null
        }
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
        if (q.isNotEmpty()) sb.append("Questions que tu lui as posées et pas refermées (id → fermer_question) : ")
            .append(q.joinToString(" | ") { "#${it.id} ${it.texte} (il y a ${hoursAgo(it.quand)} h)" }).append(".\n")
        dao.notesNow().takeIf { it.isNotEmpty() }?.let { sb.append("Dernières notes : ").append(it.joinToString(" | ") { n -> n.texte }).append(".\n") }
        return sb.toString()
    }

    private fun daysAgo(t: Long) = ((System.currentTimeMillis() - t) / 86_400_000L)
    private fun hoursAgo(t: Long) = ((System.currentTimeMillis() - t) / 3_600_000L)
}
