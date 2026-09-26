package com.dany.presence.brain

import com.dany.presence.data.Module

/** Something the live transcript revealed: a module and what's being done to it. */
data class Detection(val module: Module, val op: String, val hint: String = "")

/**
 * Instant, local intent detection on partial transcripts — the system reacts to the words as they
 * land, long before the model answers. Purely lexical on purpose: zero latency, zero cost.
 */
object Intent {
    private val modules: List<Pair<Module, Regex>> = listOf(
        Module.TACHES to Regex("\\b(t[âa]che|projet|97|pour ?cent|%|finir|fini|termin|avanc|rendu [àa]|reste juste|il reste|to.?do)", RegexOption.IGNORE_CASE),
        Module.MEDS to Regex("\\b(m[ée]dic|pilule|comprim|dose|pris[e]? (ma|mon|le|la)|j'ai pris|vyvanse|adderall|concerta|ritalin)", RegexOption.IGNORE_CASE),
        Module.BUDGET to Regex("\\b(budget|d[ée]pens|piasse|dollars?|\\d+ ?\\$|co[ûu]t|achet|acheter|prix|jordan|sneaker|drop|marge|paye|payé)", RegexOption.IGNORE_CASE),
        Module.AGENDA to Regex("\\b(agenda|rendez|rdv|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|demain|ce soir|\\d{1,2} ?h(\\d{2})?|heures?|show|meeting|calendrier)", RegexOption.IGNORE_CASE),
        Module.MOOD to Regex("\\b(mood|humeur|je (me )?sens|feel|fatigu|motiv|plate|down|anxie|stress|content|heureux|[ée]nerv|moins (un|deux)|plus (un|deux))", RegexOption.IGNORE_CASE),
        Module.NOTES to Regex("\\b(note|id[ée]e|retiens|rappelle|rappel|sample|beat|track|flash|penser [àa])", RegexOption.IGNORE_CASE),
    )
    private val ops: List<Pair<String, Regex>> = listOf(
        "+ création" to Regex("\\b(ajoute|cr[ée]e|note-?moi|nouveau|nouvelle|mets|enregistre|retiens|rappelle-moi)", RegexOption.IGNORE_CASE),
        "~ modification" to Regex("\\b(modifie|change|update|rendu [àa]|maintenant [àa]|passe [àa]|d[ée]place|reporte)", RegexOption.IGNORE_CASE),
        "× suppression" to Regex("\\b(supprime|efface|enl[èe]ve|annule|oublie|kill|tue)", RegexOption.IGNORE_CASE),
        "? question" to Regex("\\b(c'est quoi|combien|quand|est-ce que|qu'est-ce|o[ùu]|quel|quelle|\\?)", RegexOption.IGNORE_CASE),
        "✓ confirmation" to Regex("^\\s*(oui|ouais|yes|c'est fait|done|ok)\\b", RegexOption.IGNORE_CASE),
    )

    /** Every module the text touches, first match first. */
    fun detect(text: String): List<Detection> {
        if (text.isBlank()) return emptyList()
        val op = ops.firstOrNull { it.second.containsMatchIn(text) }?.first ?: "· capture"
        return modules.filter { it.second.containsMatchIn(text) }.map { (m, rx) ->
            Detection(m, op, rx.find(text)?.value?.trim()?.lowercase().orEmpty())
        }
    }
}
