package app.murmure.ai

import kotlinx.serialization.Serializable

/** Nature d'un moment repéré dans le flux de parole. */
object MomentKind {
    const val NOTE = "note"
    const val TASK = "task"
    const val EVENT = "event"
    const val MOOD = "mood"
    const val IDEA = "idea"
    const val PERSON = "person"

    fun label(k: String) = when (k) {
        NOTE -> "Note"; TASK -> "Tâche"; EVENT -> "Agenda"; MOOD -> "Mood"; IDEA -> "Idée"; PERSON -> "Personne"; else -> "Moment"
    }
    fun emoji(k: String) = when (k) {
        NOTE -> "📝"; TASK -> "✅"; EVENT -> "📅"; MOOD -> "💛"; IDEA -> "💡"; PERSON -> "👤"; else -> "✦"
    }
    /** Ce que Murmure « dit » quand il met le moment de côté. */
    fun verb(k: String) = when (k) {
        NOTE -> "Note mise de côté"; TASK -> "Tâche repérée"; EVENT -> "Ajouté à l'agenda"
        MOOD -> "Mood noté"; IDEA -> "Idée attrapée"; PERSON -> "Personne reconnue"; else -> "Moment gardé"
    }
}

/**
 * Un moment = un fragment signifiant de la dictée, typé.
 * [start]/[end] = plage de caractères dans la transcription (pour le surlignage), -1 si inconnu.
 */
@Serializable
data class Moment(
    val id: String,
    val kind: String,
    val title: String,
    val text: String = "",
    val start: Int = -1,
    val end: Int = -1,
    /** Tâche : échéance yyyy-MM-dd. Agenda : début ISO local. */
    val due: String? = null,
    val allDay: Boolean = true,
    val where: String? = null,
    val who: List<String> = emptyList(),
    val emotion: String? = null,
    val mood: Int? = null,
    val folder: String? = null,
    val confidence: Double = 0.6,
    val byAi: Boolean = false,
)

/** Résultat d'une passe d'analyse pendant la dictée. */
@Serializable
data class LiveAnalysis(
    val topic: String? = null,
    val valence: Double = 0.0,
    val energy: String = "moyenne",
    val emotion: String = "neutre",
    val folder: String? = null,
    val keywords: List<KeywordHit> = emptyList(),
    val moments: List<Moment> = emptyList(),
    val insight: String? = null,
)

/** Proposition complète pour une capture : n notes + tâches + agenda + mood, à valider. */
@Serializable
data class SessionProposal(
    val notes: List<NoteProposal> = emptyList(),
    val tasks: List<TaskGuess> = emptyList(),
    val events: List<Moment> = emptyList(),
    val mood: Int? = null,
    val moodEmotion: String? = null,
    val insight: String? = null,
    val byAi: Boolean = true,
)
