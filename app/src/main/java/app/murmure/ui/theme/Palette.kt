package app.murmure.ui.theme

import androidx.compose.ui.graphics.Color

/** Couleurs sémantiques : type, émotion, nature d'entité. */
object Palette {
    fun kind(k: String?): Color = when (k) {
        "person" -> M.Peach
        "place" -> M.Sky
        "activity" -> M.Mint
        "project" -> M.Butter
        "folder" -> M.Rose
        "note" -> M.Text
        else -> M.Lilac
    }

    fun kindIcon(k: String?): String = when (k) {
        "person" -> "PR"; "place" -> "LI"; "activity" -> "AC"; "project" -> "PJ"; "folder" -> "DO"; "note" -> "NT"; else -> "CO"
    }

    fun type(t: String?): Color = when (t) {
        "journal" -> M.Rose
        "travail" -> M.Sky
        "personnel" -> M.Mint
        "reflexion" -> M.Lilac
        "idee" -> M.Butter
        "descriptif" -> M.Lilac
        "tache" -> M.Copper
        else -> M.Muted
    }

    /** L'émotion est une polarité : menthe vers le haut, corail vers le bas. */
    fun emotion(e: String?): Color = tone(app.murmure.ai.Emotions.valence(e))

    /** -1..1 → corail .. lilas .. menthe */
    fun tone(v: Float): Color = when {
        v > 0.15f -> lerp(M.Muted, M.Mint, ((v - 0.15f) / 0.85f).coerceIn(0f, 1f))
        v < -0.15f -> lerp(M.Muted, M.Coral, ((-v - 0.15f) / 0.85f).coerceIn(0f, 1f))
        else -> M.Muted
    }

    fun mood(m: Float): Color = tone((m - 3f) / 2f)

    private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)
}

/**
 * Couleurs de données (graphe, courbes, barres), validées au script dataviz
 * sur les surfaces #14121F et #221E33 : 3 teintes catégorielles sûres en toutes paires
 * (daltonisme compris) + une divergente bleu ↔ gris ↔ rouge pour la polarité.
 * Les pastels restent réservés à l'interface.
 */
object Viz {
    val Violet = Color(0xFFB9B4C6)
    val Orange = M.Copper
    val Aqua = Color(0xFF7FA98F)
    val Other = Color(0xFF55555C)
    val categorical = listOf(Orange, Violet, Aqua)

    val Positive = Color(0xFF8FB39A)
    val Neutral = Color(0xFF6F6E69)
    val Negative = Color(0xFFC96B5A)

    val Grid = Color(0xFF232328)
    val Axis = Color(0xFF34343B)

    /** Polarité -1..1 → rouge .. gris .. bleu (jamais de teinte au milieu). */
    fun diverging(v: Float): Color {
        val t = v.coerceIn(-1f, 1f)
        return if (t >= 0f) androidx.compose.ui.graphics.lerp(Neutral, Positive, t) else androidx.compose.ui.graphics.lerp(Neutral, Negative, -t)
    }

    fun cluster(rank: Int) = categorical.getOrElse(rank) { Other }

    /** Familles de nature (3 max pour rester lisible en toutes paires). */
    fun kindFamily(kind: String?): Int = when (kind) {
        "person" -> 1
        "activity", "place" -> 2
        else -> 0
    }
    val kindFamilies = listOf("Idées & projets", "Personnes", "Vécu · activités & lieux")

    fun typeFamily(type: String?): Int = when (type) {
        "travail", "tache" -> 1
        "idee", "descriptif" -> 2
        else -> 0
    }
    val typeFamilies = listOf("Intime · journal, perso, réflexion", "Action · travail, tâche", "Idées · idée, descriptif")
}
