package app.murmure

import app.murmure.ai.AnalysisContext
import app.murmure.ai.GeminiClient
import app.murmure.ai.LocalBrain
import app.murmure.ai.ModelInfo
import app.murmure.ai.NoteAnalyzer
import app.murmure.ai.NoteProposal
import app.murmure.data.MentionEntity
import app.murmure.data.NoteEntity
import app.murmure.data.NoteMention
import app.murmure.data.NoteStatus
import app.murmure.insights.Insights
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LocalBrainTest {
    private val folders = listOf("Travail", "Projet Atlas", "Journal")

    @Test fun `dossier annoncé en début de dictée`() {
        assertEquals("Projet Atlas", LocalBrain.declaredFolder("Bonjour, j'aimerais prendre une note en lien avec le dossier projet Atlas. Alors voilà", folders))
        assertEquals("Travail", LocalBrain.declaredFolder("Une note pour le dossier travail, je dois appeler Marc", folders))
        assertEquals("Recettes", LocalBrain.declaredFolder("Ok, dans le dossier recettes. Ce soir j'ai fait un pad thaï", folders))
        assertNull(LocalBrain.declaredFolder("Aujourd'hui j'ai couru au parc avec Julie", folders))
    }

    @Test fun `surlignage insensible aux accents et à la casse`() {
        val text = "Souper avec julie au cafe Olimpico après la gym."
        val hits = LocalBrain.highlight(text, mapOf("Julie" to "person", "Café Olimpico" to "place", "Gym" to "activity"))
        assertEquals(listOf("Julie", "Café Olimpico", "Gym"), hits.map { it.label })
        assertEquals("julie", text.substring(hits[0].start, hits[0].end))
        assertEquals("cafe Olimpico", text.substring(hits[1].start, hits[1].end))
    }

    @Test fun `pas de surlignage au milieu d'un mot`() {
        assertTrue(LocalBrain.highlight("Il gymnase", mapOf("Gym" to "activity")).isEmpty())
    }

    @Test fun `détection de tâches et échéances`() {
        val today = LocalDate.of(2026, 9, 23) // mercredi
        val tasks = LocalBrain.detectTasks("Il faut que j'appelle le garage demain. Je dois envoyer le rapport vendredi.", today)
        assertEquals(2, tasks.size)
        assertEquals("2026-09-24", tasks[0].due)
        assertEquals("2026-09-25", tasks[1].due)
        assertTrue(tasks[1].text.startsWith("Envoyer le rapport"))
    }

    @Test fun `proposition hors ligne complète`() {
        val ctx = AnalysisContext(folders, listOf("Julie" to "person"), listOf("Kickoff"), isDaily = true, mood = 4)
        val p = LocalBrain.offlineProposal("Bonjour, journée avec Julie. Il faut que je dorme plus tôt ce soir.", ctx, mapOf("Julie" to "person"))
        assertEquals("Journal", p.folderSuggestions.first().name)
        assertEquals("journal", p.type)
        assertEquals("Julie", p.entities.single().name)
        assertEquals(1, p.tasks.size)
    }
}

class AnalyzerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `extraction JSON robuste aux blocs markdown`() {
        val raw = "```json\n{\"title\":\"Test\",\"type\":\"idee\"}\n```"
        val p = json.decodeFromString(NoteProposal.serializer(), NoteAnalyzer.extractJson(raw))
        assertEquals("Test", p.title)
    }

    @Test fun `choix automatique des modèles`() {
        val models = listOf(
            ModelInfo("models/gemini-2.0-flash", "", listOf("generateContent")),
            ModelInfo("models/gemini-2.5-flash", "", listOf("generateContent", "countTokens")),
            ModelInfo("models/gemini-2.5-flash-lite", "", listOf("generateContent")),
            ModelInfo("models/gemini-2.5-flash-preview-tts", "", listOf("generateContent")),
            ModelInfo("models/gemini-live-2.5-flash-preview", "", listOf("bidiGenerateContent")),
            ModelInfo("models/gemini-2.5-flash-native-audio-preview", "", listOf("bidiGenerateContent")),
            ModelInfo("models/text-embedding-004", "", listOf("embedContent")),
        )
        val (text, live) = GeminiClient.pickModels(models)
        assertEquals("models/gemini-2.5-flash", text)
        assertEquals("models/gemini-live-2.5-flash-preview", live)
    }
}

class InsightsTest {
    private fun note(day: LocalDate, mood: Int?, daily: Boolean = true, id: String = day.toString()) = NoteEntity(
        id = id, title = id, body = "", rawTranscript = "", status = NoteStatus.FILED,
        createdAt = 0, updatedAt = 0, dayKey = day.toString(), isDaily = daily, mood = mood, timeOfDay = "soir",
    )

    @Test fun `série de jours consécutifs`() {
        val t = LocalDate.of(2026, 9, 27)
        val notes = listOf(note(t, 3), note(t.minusDays(1), 3), note(t.minusDays(2), 3), note(t.minusDays(5), 3))
        assertEquals(3 to 3, Insights.streak(notes, t))
        // Pas encore fait aujourd'hui : la série d'hier compte toujours
        assertEquals(2, Insights.streak(notes.drop(1), t).first)
    }

    @Test fun `corrélation activité et mood`() {
        val t = LocalDate.of(2026, 9, 27)
        val days = (0 until 8).map { t.minusDays(it.toLong()) }
        val notes = days.mapIndexed { i, d -> note(d, if (i % 2 == 0) 5 else 2) }
        val gym = MentionEntity("g", "Gym", "activity", "gym")
        val mentions = days.filterIndexed { i, _ -> i % 2 == 0 }.map { NoteMention(it.toString(), "g", 0.5f) }
        val p = Insights.build(notes, listOf(gym), mentions, t)
        val c = p.correlations.single()
        assertEquals(5f, c.avgWith, 0.01f)
        assertEquals(2f, c.avgWithout, 0.01f)
        assertEquals(4, c.daysWith)
        assertNotNull(p.avgMood)
    }
}
