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
        "person" -> "👤"; "place" -> "📍"; "activity" -> "🏃"; "project" -> "🚀"; "folder" -> "📁"; "note" -> "📝"; else -> "✦"
    }

    fun type(t: String?): Color = when (t) {
        "journal" -> M.Rose
        "travail" -> M.Sky
        "personnel" -> M.Mint
        "reflexion" -> M.Lilac
        "idee" -> M.Butter
        "descriptif" -> Color(0xFFB8C4FF)
        "tache" -> M.Peach
        else -> M.Muted
    }

    fun emotion(e: String?): Color = when (e) {
        "joie" -> M.Butter
        "gratitude" -> M.Mint
        "fierté" -> M.Peach
        "excitation" -> Color(0xFFFFC56E)
        "calme" -> M.Sky
        "neutre" -> Color(0xFFB9B3D1)
        "fatigue" -> Color(0xFFA99FC9)
        "stress" -> Color(0xFFFF9F7A)
        "anxiété" -> Color(0xFFE7A1D8)
        "tristesse" -> Color(0xFF8FB3FF)
        "frustration" -> M.Coral
        "colère" -> Color(0xFFFF6B6B)
        else -> M.Muted
    }

    /** -1..1 → corail .. lilas .. menthe */
    fun tone(v: Float): Color = when {
        v > 0.15f -> lerp(M.Lilac, M.Mint, ((v - 0.15f) / 0.85f).coerceIn(0f, 1f))
        v < -0.15f -> lerp(M.Lilac, M.Coral, ((-v - 0.15f) / 0.85f).coerceIn(0f, 1f))
        else -> M.Lilac
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
    val Violet = Color(0xFF9085E9)
    val Orange = Color(0xFFD95926)
    val Aqua = Color(0xFF199E70)
    val Other = Color(0xFF6F6F78)
    val categorical = listOf(Violet, Orange, Aqua)

    val Positive = Color(0xFF3987E5)
    val Neutral = Color(0xFF8A8A8A)
    val Negative = Color(0xFFE66767)

    val Grid = Color(0xFF2E2A42)
    val Axis = Color(0xFF45405C)

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
