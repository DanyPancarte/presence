package com.dany.presence.mind

import com.dany.presence.core.Bus
import com.dany.presence.core.Module
import com.dany.presence.core.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Something the live transcript revealed: a module, what's being done to it, the word that gave it away. */
data class Detection(val module: Module, val op: String, val word: String)

/**
 * Instant, local intent detection on transcripts — the system reacts to the words as they land,
 * long before the model answers. Purely lexical on purpose: zero latency, zero cost. fr-CA, Dany's life.
 */
object Intent {
    /** The op strings carried by [Signal.Captured]. */
    object Op {
        const val CREER = "créer"
        const val MODIFIER = "modifier"
        const val SUPPRIMER = "supprimer"
        const val QUESTION = "question"
        const val CONFIRMATION = "confirmation"
        const val CAPTURE = "capture"
        /** An utterance heard but not sent to the model (background chatter). */
        const val IGNORE = "ignoré"
    }

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val modules: List<Pair<Module, Regex>> = listOf(
        Module.TACHES to rx("\\b(t[âa]ches?|projets?|97|pour ?cent|%|finir|fini[re]?|termin\\w*|avanc\\w*|rendu [àa]|reste juste|il reste|dernier bout|to.?do|livrable|deadline|chantier|prototype|shipper|ship|bloqu\\w*|procrastin\\w*|repo|commit|app|beat tape|mixtape|ep\\b|album)"),
        Module.MEDS to rx("\\b(m[ée]dic\\w*|m[ée]ds?\\b|pilules?|comprim[ée]s?|dose|pris[e]? (ma|mon|le|la|mes)|j'ai pris|j'ai pas pris|pas pris|oubli[ée] (ma|mon|le|la)|vyvanse|adderall|concerta|ritalin|biphentin|strattera|foquest|ordonnance|pharmacie|prescription)"),
        Module.BUDGET to rx("\\b(budget|d[ée]pens\\w*|piasses?|dollars?|\\d+ ?\\$|\\$ ?\\d+|co[ûu]t\\w*|achet\\w*|achat|prix|jordan|sneakers?|kicks|drop|marge|pay[ée]s?|paie|facture|loyer|carte de cr[ée]dit|rembours\\w*|[ée]conomi\\w*|cash|argent|abonnement|amazon|solde|promo|resell|stockx)"),
        Module.AGENDA to rx("\\b(agenda|rendez-?vous|rdv|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|demain|apr[èe]s-?demain|ce soir|ce matin|cet apr[èe]s-?midi|cette semaine|semaine prochaine|fin de semaine|week-?end|\\d{1,2} ?h(\\d{2})?\\b|\\d{1,2}:\\d{2}|heures?\\b|midi|minuit|show|spectacle|meeting|r[ée]union|calendrier|horaire|cours|examen|[ée]cole|gym|dentiste|m[ée]decin|garage|date)"),
        Module.MOOD to rx("\\b(mood|humeur|je (me )?sens|je feel|feel|fatigu\\w*|motiv\\w*|plate|down|anxi\\w*|stress\\w*|content|heureux|[ée]nerv\\w*|frustr\\w*|d[ée]courag\\w*|[ée]puis\\w*|tann[ée]|[ée]c[oœ]ur\\w*|overwhelm\\w*|d[ée]bord[ée]|en feu|pumped|hype|dopamine|[ée]nergie|burn-?out|angoiss\\w*|d[ée]prim\\w*|gros nerf|moins (un|deux)|plus (un|deux)|ça va pas|ça va bien|pas de focus|focus|dort mal|mal dormi|insomnie)"),
        Module.NOTES to rx("\\b(notes?|id[ée]es?|retiens|rappelle|rappel|sample|beat|track|flash|penser [àa]|oublie pas|faut pas que j'oublie|pense-b[êe]te|m[ée]mo|marque ça|[ée]cris ça|garde ça|en passant|concept|inspi\\w*)"),
    )
    private val ops: List<Pair<String, Regex>> = listOf(
        Op.CONFIRMATION to rx("^\\s*(oui|ouais|yes|yep|yeah|c'est fait|done|ok|okay|correct|exact|c'est bon|c'est ça|fait|pris|je l'ai pris|affirmatif|non|nope|pas encore|pas vraiment|n[ée]gatif)\\b"),
        Op.SUPPRIMER to rx("\\b(supprime|efface|enl[èe]ve|annule|oublie ça|oublie la|oublie le|kill|tue|scrap|jette|abandonne|laisse tomber|ferme)\\b"),
        Op.MODIFIER to rx("\\b(modifie|change|update|rendu [àa]|maintenant [àa]|passe [àa]|rend[us]? [àa]|d[ée]place|reporte|remets|monte [àa]|descend [àa]|bouge|corrige|renomme)\\b"),
        Op.CREER to rx("\\b(ajoute|cr[ée]e|note-?moi|nouveau|nouvelle|mets|enregistre|retiens|rappelle-moi|commence|d[ée]marre|garde|prends en note|marque)\\b"),
        Op.QUESTION to rx("(\\?|\\b(c'est quoi|combien|quand|est-ce que|est-ce qu|qu'est-ce|o[ùu] (est|sont|en)|quel|quelle|quels|quelles|pourquoi|comment|y a-tu|est-tu|c'tu|j'ai-tu|dis-moi|j'en suis o[ùu]|il reste quoi|c'est combien))"),
    )
    private val question = rx("(\\?\\s*$|\\b(est-ce|c'est quoi|c'est combien|combien|pourquoi|comment|quand|qu'est|o[ùu] (est|sont|en)|quel|quelle|y a-tu|c'tu|j'ai-tu|j'en suis o[ùu]|il reste quoi))")
    private val name = rx("\\bpr[ée]sence\\b")

    /** Every module the text touches, first match first. */
    fun detect(text: String): List<Detection> {
        if (text.isBlank()) return emptyList()
        val op = op(text)
        return modules.filter { it.second.containsMatchIn(text) }.map { (m, r) ->
            Detection(m, op, r.find(text)?.value?.trim()?.lowercase().orEmpty())
        }
    }

    /** The operation the text asks for, [Op.CAPTURE] when it just mentions things. */
    fun op(text: String): String = ops.firstOrNull { it.second.containsMatchIn(text) }?.first ?: Op.CAPTURE

    fun isQuestion(text: String): Boolean = question.containsMatchIn(text)

    /** Addressed by name. */
    fun addressed(text: String): Boolean = name.containsMatchIn(text)

    fun words(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }
}

/**
 * Watches the live transcript and emits one [Signal.Captured] per module, the first time it shows up
 * in the current utterance. Partial transcripts repeat themselves; the ember must not. Resets on
 * SpeechStart / SpeechFinal (the final text is scanned once more so nothing the partials missed is lost).
 */
class IntentWatcher(private val bus: Bus) {
    private val seen = HashSet<Module>()

    fun start(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        bus.signals.collect { s ->
            when (s) {
                is Signal.SpeechStart -> seen.clear()
                is Signal.SpeechPartial -> scan(s.text)
                is Signal.SpeechFinal -> { scan(s.text); seen.clear() }
                is Signal.SpeechIdle -> seen.clear()
                else -> {}
            }
        }
    }

    private fun scan(text: String) {
        for (d in Intent.detect(text)) {
            if (seen.add(d.module)) bus.emit(Signal.Captured(d.module, d.op, d.word))
        }
    }
}
