package app.murmure.ai

import kotlinx.serialization.Serializable

/** Types de note (couche catégorisation). */
object NoteTypes {
    val all = listOf("journal", "travail", "personnel", "reflexion", "idee", "descriptif", "tache")
    fun label(t: String?) = when (t) {
        "journal" -> "Journal intime"; "travail" -> "Travail"; "personnel" -> "Personnel"
        "reflexion" -> "Réflexion"; "idee" -> "Idée"; "descriptif" -> "Descriptif"; "tache" -> "Tâche"
        else -> "—"
    }
    fun emoji(t: String?) = when (t) {
        "journal" -> "📓"; "travail" -> "💼"; "personnel" -> "🌿"; "reflexion" -> "🌀"
        "idee" -> "💡"; "descriptif" -> "🔎"; "tache" -> "✅"; else -> "•"
    }
}

object Emotions {
    val all = listOf(
        "joie", "gratitude", "fierté", "excitation", "calme", "neutre",
        "fatigue", "stress", "anxiété", "tristesse", "frustration", "colère",
    )
    fun emoji(e: String?) = when (e) {
        "joie" -> "😊"; "gratitude" -> "🙏"; "fierté" -> "🦁"; "excitation" -> "⚡"; "calme" -> "🌊"
        "neutre" -> "😐"; "fatigue" -> "🥱"; "stress" -> "😣"; "anxiété" -> "🌪️"; "tristesse" -> "🌧️"
        "frustration" -> "😤"; "colère" -> "🔥"; else -> "•"
    }
    fun valence(e: String?) = when (e) {
        "joie", "gratitude", "fierté", "excitation" -> 0.8f
        "calme" -> 0.5f
        "neutre" -> 0f
        "fatigue" -> -0.3f
        "stress", "anxiété", "tristesse", "frustration" -> -0.6f
        "colère" -> -0.8f
        else -> 0f
    }
}

object Energy {
    val all = listOf("basse", "moyenne", "haute")
}

@Serializable
data class FolderSuggestion(
    val name: String,
    val existing: Boolean = false,
    val reason: String = "",
    val confidence: Double = 0.5,
)

@Serializable
data class EmotionGuess(
    val label: String = "neutre",
    val intensity: Double = 0.5,
    val valence: Double = 0.0,
)

@Serializable
data class EntityGuess(
    val name: String,
    val kind: String,
    val sentiment: Double = 0.0,
)

@Serializable
data class TaskGuess(
    val text: String,
    val due: String? = null,
    val reason: String = "",
)

/** Proposition complète de l'IA pour une note — toujours soumise à validation. */
@Serializable
data class NoteProposal(
    val title: String = "",
    val summary: String = "",
    val body: String = "",
    val declaredFolder: String? = null,
    val folderSuggestions: List<FolderSuggestion> = emptyList(),
    val type: String = "idee",
    val emotion: EmotionGuess = EmotionGuess(),
    val energy: String = "moyenne",
    val entities: List<EntityGuess> = emptyList(),
    val keywords: List<String> = emptyList(),
    val links: List<String> = emptyList(),
    val tasks: List<TaskGuess> = emptyList(),
    val byAi: Boolean = true,
)

@Serializable
data class KeywordHit(val text: String, val kind: String = "concept")

@Serializable
data class LiveKeywords(val keywords: List<KeywordHit> = emptyList(), val folder: String? = null)
