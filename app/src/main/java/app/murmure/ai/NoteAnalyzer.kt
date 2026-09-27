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

    /**
     * Passe « live » pendant la dictée : sujet, charge émotionnelle, dossier annoncé,
     * mots-clés et MOMENTS (notes, tâches, agenda, moods, idées) avec leurs plages de texte.
     */
    suspend fun liveAnalysis(settings: AppSettings, text: String, folders: List<String>, known: List<String>): LiveAnalysis {
        val window = text.takeLast(2200)
        val offset = text.length - window.length
        val prompt = """
Date/heure : ${Dates.nowIso()} (${Dates.weekday()}). Aujourd'hui = ${Dates.today()}.
Dossiers existants : ${folders.joinToString(", ").ifBlank { "(aucun)" }}
Entités connues : ${known.take(60).joinToString(", ").ifBlank { "(aucune)" }}

Dictée en cours (fenêtre récente) :
${"\"\"\""}$window${"\"\"\""}

Analyse ce flux de parole et renvoie UNIQUEMENT ce JSON :
{
 "topic": "de quoi la personne parle en ce moment, 2-5 mots, ou null",
 "valence": -1.0..1.0,
 "energy": "basse|moyenne|haute",
 "emotion": "${Emotions.all.joinToString("|")}",
 "folder": "dossier annoncé explicitement (« note pour le dossier X ») ou null",
 "keywords": [{"text":"mot ou groupe EXACTEMENT tel qu'écrit dans la fenêtre","kind":"person|place|activity|project|concept"}],
 "moments": [{
   "id": "m1", "kind": "note|task|event|mood|idea",
   "title": "titre court (≤ 8 mots)",
   "text": "citation EXACTE du passage dans la fenêtre (début de la phrase, ≤ 120 caractères)",
   "due": "tâche : YYYY-MM-DD ou null · event : YYYY-MM-DDTHH:MM ou YYYY-MM-DD · sinon null",
   "allDay": true|false, "where": "lieu ou null", "who": ["personnes"],
   "emotion": "pour mood, sinon null", "mood": 1-5 pour mood sinon null,
   "folder": "pour note : dossier annoncé ou suggéré, sinon null",
   "confidence": 0.0-1.0
 }],
 "insight": "UNE observation utile et courte (≤ 90 caractères) ou null — ex. lien avec une entité connue, répétition, contradiction, encouragement. Pas de conseil clinique."
}
Règles :
- "note" = la personne ouvre explicitement un nouveau sujet à conserver (« nouvelle note », « une note pour… », changement net de sujet).
- "task" = action à faire formulée par la personne. "event" = rendez-vous / rencontre avec un moment précis.
- "mood" = état émotionnel déclaré (« je me sens… »). "idea" = idée, envie, projet flou.
- Maximum 6 moments, 8 mots-clés. N'invente rien.
""".trimIndent()
        val raw = client.generate(settings.apiKey, settings.textModel, prompt, jsonMode = true, temperature = 0.15)
        val la = json.decodeFromString(LiveAnalysis.serializer(), extractJson(raw))
        // Ancre chaque moment à sa position réelle dans le texte complet.
        val anchored = la.moments.mapIndexed { i, m ->
            val idx = if (m.text.isNotBlank()) window.indexOf(m.text.take(40)) else -1
            val start = if (idx >= 0) offset + idx else -1
            m.copy(
                id = "a:${m.kind}:${if (start >= 0) start else "n$i"}",
                start = start, end = if (start >= 0) start + m.text.length else -1,
                kind = m.kind.takeIf { it in listOf("note", "task", "event", "mood", "idea") } ?: "note",
                emotion = m.emotion?.takeIf { it in Emotions.all }, byAi = true,
            )
        }
        return la.copy(
            moments = anchored,
            emotion = la.emotion.takeIf { it in Emotions.all } ?: "neutre",
            energy = la.energy.takeIf { it in Energy.all } ?: "moyenne",
            folder = la.folder?.takeIf { it.isNotBlank() && it != "null" },
            topic = la.topic?.takeIf { it.isNotBlank() && it != "null" },
            insight = la.insight?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /**
     * Analyse de fin de capture : découpe en N notes structurées + tâches + agenda + mood.
     * Les [moments] repérés en direct servent d'indices.
     */
    suspend fun analyzeCapture(settings: AppSettings, transcript: String, ctx: AnalysisContext, moments: List<Moment>): SessionProposal {
        val today = Dates.today()
        val hints = moments.joinToString("\n") { "- [${it.kind}] ${it.title}${it.due?.let { d -> " ($d)" } ?: ""}" }.ifBlank { "(aucun)" }
        val prompt = buildString {
            appendLine("Date et heure : ${Dates.nowIso()} (${Dates.weekday()}). Aujourd'hui = $today. Moment : ${Dates.timeOfDay()}.")
            if (ctx.isDaily) appendLine("Contexte : NOTE QUOTIDIENNE (rituel « raconte ta journée »). Humeur déclarée : ${ctx.mood ?: "?"}/5. La première note est le journal du jour (type journal, dossier Journal).")
            appendLine("Dossiers existants : ${ctx.folders.joinToString(", ").ifBlank { "(aucun)" }}")
            appendLine("Entités connues : ${ctx.entities.take(80).joinToString(", ") { "${it.first} (${it.second})" }.ifBlank { "(aucune)" }}")
            appendLine("Titres de notes existantes : ${ctx.recentTitles.take(60).joinToString(" | ").ifBlank { "(aucune)" }}")
            appendLine("Moments repérés pendant la dictée (indices, à confirmer) :\n$hints")
            appendLine()
            appendLine("DICTÉE BRUTE :")
            appendLine("\"\"\"$transcript\"\"\"")
            appendLine()
            appendLine(
                """
Découpe cette dictée en ce qu'elle contient réellement et renvoie UNIQUEMENT ce JSON :
{
 "notes": [ { ...NOTE... } ],
 "tasks": [{"text": "action à l'infinitif", "due": "YYYY-MM-DD ou null", "reason": "indice dans la dictée"}],
 "events": [{"id":"e1","kind":"event","title":"…","text":"passage cité","due":"YYYY-MM-DDTHH:MM ou YYYY-MM-DD","allDay":true|false,"where":"lieu ou null","who":["personnes"],"confidence":0.0-1.0}],
 "mood": 1-5 ou null (état général exprimé, seulement si la personne en parle),
 "moodEmotion": "${Emotions.all.joinToString("|")} ou null",
 "insight": "UNE observation courte et utile sur l'ensemble (≤ 120 caractères), descriptive, jamais un verdict"
}
Chaque NOTE :
{
 "title": "titre court et évocateur (max 7 mots)",
 "summary": "1-2 phrases",
 "body": "Markdown : paragraphes courts, puces, ## si utile ; sans tics de langage ni annonce de contexte ; [[ ]] autour des personnes, projets, concepts et titres de notes existantes",
 "declaredFolder": "dossier annoncé pour cette note ou null",
 "folderSuggestions": [{"name":"…","existing":true|false,"reason":"…","confidence":0.0-1.0}],
 "type": "journal|travail|personnel|reflexion|idee|descriptif|tache",
 "emotion": {"label":"…","intensity":0.0-1.0,"valence":-1.0..1.0},
 "energy": "basse|moyenne|haute",
 "entities": [{"name":"…","kind":"person|place|activity|project","sentiment":-1.0..1.0}],
 "keywords": ["5-8 concepts"],
 "links": ["titres EXACTS de notes existantes liées"],
 "tasks": []
}
Règles :
- UNE note par sujet distinct (une dictée de 5 minutes peut en contenir 1 à 4). Ne crée pas de note pour une simple tâche ou un simple rendez-vous.
- Tout le contenu doit se retrouver dans une note ; rien n'est perdu.
- folderSuggestions : privilégie les dossiers existants (orthographe exacte) ; 1 à 3 options triées.
- tasks au niveau racine (pas dans les notes). Échéances réalistes d'après les indices ; sinon null.
- Réutilise l'orthographe des entités connues.
""".trimIndent()
            )
        }
        val raw = client.generate(settings.apiKey, settings.textModel, prompt, system, jsonMode = true, temperature = 0.3)
        val sp = json.decodeFromString(SessionProposal.serializer(), extractJson(raw))
        val notes = sp.notes.ifEmpty { listOf(NoteProposal(body = transcript)) }.map { sanitize(it, transcript, ctx) }
        return sp.copy(
            notes = notes,
            events = sp.events.filter { it.title.isNotBlank() && !it.due.isNullOrBlank() }
                .mapIndexed { i, e -> e.copy(id = "a:event:$i", kind = MomentKind.EVENT, byAi = true) },
            moodEmotion = sp.moodEmotion?.takeIf { it in Emotions.all },
            mood = sp.mood?.coerceIn(1, 5),
            insight = sp.insight?.takeIf { it.isNotBlank() && it != "null" },
            byAi = true,
        )
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
