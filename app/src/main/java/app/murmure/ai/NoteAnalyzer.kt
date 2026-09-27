package app.murmure.ai

import app.murmure.core.AppSettings
import app.murmure.core.Dates
import app.murmure.core.Text
import app.murmure.data.EntityKind
import kotlinx.serialization.json.Json

data class AnalysisContext(
    val folders: List<String>,
    val entities: List<Pair<String, String>>, // name, kind
    val recentTitles: List<String>,
    val isDaily: Boolean,
    val mood: Int?,
)

/** Transforme une dictée brute en note structurée + classification proposée. */
class NoteAnalyzer(private val client: GeminiClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val system = """
Tu es Murmure, l'assistant d'un carnet de notes vocal conçu pour les cerveaux TDAH.
Tu transformes une dictée brute (avec hésitations) en note claire, fidèle à la voix de la personne, en français québécois naturel.
Tu ne poses JAMAIS de diagnostic, tu restes descriptif et bienveillant. Tu n'inventes aucun fait.
Tu réponds UNIQUEMENT en JSON valide.
""".trimIndent()

    suspend fun analyze(settings: AppSettings, transcript: String, ctx: AnalysisContext): NoteProposal {
        val today = Dates.today()
        val prompt = buildString {
            appendLine("Date et heure actuelles : ${Dates.nowIso()} (${Dates.weekday()}). Moment : ${Dates.timeOfDay()}.")
            if (ctx.isDaily) appendLine("Contexte : c'est la NOTE QUOTIDIENNE (rituel « raconte ta journée »). Humeur déclarée : ${ctx.mood ?: "?"}/5. Type attendu : journal.")
            appendLine("Dossiers existants : ${ctx.folders.joinToString(", ").ifBlank { "(aucun)" }}")
            appendLine("Entités connues : ${ctx.entities.take(80).joinToString(", ") { "${it.first} (${it.second})" }.ifBlank { "(aucune)" }}")
            appendLine("Titres de notes existantes : ${ctx.recentTitles.take(60).joinToString(" | ").ifBlank { "(aucune)" }}")
            appendLine()
            appendLine("DICTÉE BRUTE :")
            appendLine("\"\"\"$transcript\"\"\"")
            appendLine()
            appendLine(
                """
Produis ce JSON :
{
 "title": "titre court et évocateur (max 7 mots)",
 "summary": "résumé en 1 à 2 phrases",
 "body": "note rédigée en Markdown : paragraphes courts, listes à puces, sous-titres ## si utile. Retire les tics de langage et l'annonce de contexte du début. Entoure de [[ ]] les concepts, personnes, projets et titres de notes existantes pertinents (ex. [[Julie]], [[Projet Atlas]]).",
 "declaredFolder": "nom du dossier si la personne l'a annoncé explicitement (ex. « en lien avec le dossier X »), sinon null",
 "folderSuggestions": [{"name": "…", "existing": true|false, "reason": "pourquoi, en quelques mots", "confidence": 0.0-1.0}],
 "type": "journal|travail|personnel|reflexion|idee|descriptif|tache",
 "emotion": {"label": "${Emotions.all.joinToString("|")}", "intensity": 0.0-1.0, "valence": -1.0..1.0},
 "energy": "basse|moyenne|haute",
 "entities": [{"name": "Nom propre ou activité", "kind": "person|place|activity|project", "sentiment": -1.0..1.0}],
 "keywords": ["5 à 8 concepts clés, mots présents ou très proches du texte"],
 "links": ["titres EXACTS de notes existantes liées"],
 "tasks": [{"text": "action à faire, à l'infinitif", "due": "YYYY-MM-DD ou null", "reason": "indice dans la dictée"}]
}
Règles :
- folderSuggestions : 1 à 3 options, triées par pertinence ; privilégie les dossiers existants (même orthographe), propose un nouveau nom court seulement si rien ne colle. Si declaredFolder existe, il est la première option.
- entities : réutilise l'orthographe des entités connues quand c'est la même chose. Activités = gym, course, travail, sortie, lecture, etc.
- tasks : seulement de vraies actions à faire formulées par la personne. Échéance suggérée réaliste à partir d'indices (« demain », « lundi », « avant vendredi ») ; aujourd'hui = $today. Sinon null.
- Sentiment d'une entité = la couleur émotionnelle associée à cette entité dans la dictée.
""".trimIndent()
            )
        }
        val raw = client.generate(settings.apiKey, settings.textModel, prompt, system, jsonMode = true, temperature = 0.3)
        val p = json.decodeFromString(NoteProposal.serializer(), extractJson(raw))
        return sanitize(p, transcript, ctx)
    }

    private fun sanitize(p: NoteProposal, transcript: String, ctx: AnalysisContext): NoteProposal {
        val existing = ctx.folders.associateBy { Text.key(it) }
        val folders = buildList {
            p.declaredFolder?.takeIf { it.isNotBlank() }?.let {
                add(FolderSuggestion(existing[Text.key(it)] ?: Text.capitalize(it), Text.key(it) in existing, "Annoncé dans ta dictée", 0.95))
            }
            p.folderSuggestions.forEach { s ->
                val match = existing[Text.key(s.name)]
                add(s.copy(name = match ?: Text.capitalize(s.name), existing = match != null))
            }
            if (ctx.isDaily) add(0, FolderSuggestion("Journal", "journal" in existing, "Rituel quotidien", 0.99))
        }.distinctBy { Text.key(it.name) }.take(3)
        return p.copy(
            title = p.title.ifBlank { fallbackTitle(transcript) }.trim().trim('"'),
            body = p.body.ifBlank { transcript },
            type = if (ctx.isDaily) "journal" else p.type.takeIf { it in NoteTypes.all } ?: "idee",
            emotion = p.emotion.copy(label = p.emotion.label.lowercase().takeIf { it in Emotions.all } ?: "neutre"),
            energy = p.energy.takeIf { it in Energy.all } ?: "moyenne",
            entities = p.entities.filter { it.name.isNotBlank() }
                .map { it.copy(kind = it.kind.takeIf { k -> k in EntityKind.all } ?: EntityKind.ACTIVITY, name = it.name.trim()) }
                .distinctBy { it.kind + Text.key(it.name) },
            folderSuggestions = folders.ifEmpty { listOf(FolderSuggestion("Boîte de réception", "boite de reception" in existing, "Par défaut", 0.3)) },
            keywords = p.keywords.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { Text.key(it) }.take(10),
            byAi = true,
        )
    }

    /** Détection rapide de mots-clés pendant la dictée. */
    suspend fun liveKeywords(settings: AppSettings, text: String, folders: List<String>): LiveKeywords {
        val prompt = """
Extrait de dictée en cours : ""${'"'}${text.takeLast(1500)}""${'"'}
Dossiers existants : ${folders.joinToString(", ")}
Renvoie en JSON {"keywords":[{"text":"mot ou groupe de mots EXACTEMENT tel qu'écrit dans l'extrait","kind":"person|place|activity|project|concept"}], "folder": "dossier annoncé explicitement par la personne ou null"}.
Maximum 8 mots-clés, les plus signifiants (noms propres, projets, thèmes). Pas de mots vides.
""".trimIndent()
        val raw = client.generate(settings.apiKey, settings.textModel, prompt, jsonMode = true, temperature = 0.1)
        return json.decodeFromString(LiveKeywords.serializer(), extractJson(raw))
    }

    companion object {
        fun extractJson(raw: String): String {
            val s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val start = s.indexOfFirst { it == '{' }
            val end = s.lastIndexOf('}')
            return if (start >= 0 && end > start) s.substring(start, end + 1) else s
        }

        fun fallbackTitle(t: String): String {
            val firstSentence = t.split(Regex("[.!?\\n]")).firstOrNull { it.isNotBlank() } ?: t
            val words = firstSentence.replace(Regex("[^\\p{L}\\p{N}'’ -]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
            val title = words.take(7).joinToString(" ") + if (words.size > 7) "…" else ""
            return Text.capitalize(title).ifBlank { "Note vocale" }
        }
    }
}
