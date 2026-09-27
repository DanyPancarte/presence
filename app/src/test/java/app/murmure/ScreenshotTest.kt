package app.murmure

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.murmure.ai.LocalBrain
import app.murmure.core.Vault
import app.murmure.data.DemoData
import app.murmure.data.MurmureDb
import app.murmure.data.NoteStatus
import app.murmure.ui.capture.HomeScreen
import app.murmure.ui.capture.LiveUi
import app.murmure.ui.capture.RecordContent
import app.murmure.ui.explore.ExploreScreen
import app.murmure.ui.explore.FolderScreen
import app.murmure.ui.note.NoteScreen
import app.murmure.ui.onboarding.OnboardingScreen
import app.murmure.ui.insights.InsightsScreen
import app.murmure.ui.review.ReviewScreen
import app.murmure.ui.settings.SettingsScreen
import app.murmure.ui.tasks.TasksScreen
import app.murmure.ui.theme.M
import app.murmure.ui.theme.MurmureTheme
import app.murmure.voice.Phase
import app.murmure.voice.VoiceState
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executor

/** Application de test : base Room en mémoire, coffre sans Keystore. */
class TestApp : MurmureApp() {
    override fun createVault(): Vault = object : Vault(this@TestApp) {
        private val m = HashMap<String, String>()
        override fun putString(name: String, value: String?) { if (value == null) m.remove(name) else m[name] = value }
        override fun getString(name: String) = m[name]
        override fun databasePassphrase() = ByteArray(32)
        override fun wipe() = m.clear()
    }

    override fun openDatabase(): MurmureDb {
        val direct = Executor { it.run() }
        return Room.inMemoryDatabaseBuilder(this, MurmureDb::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
    }

    override val scheduleReminders = false
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi", application = TestApp::class)
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as MurmureApp
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screens").apply { mkdirs() }

    private fun seed() = runBlocking {
        app.settings.update { it.copy(onboarded = true, apiKey = "demo") }
        DemoData.seed(app.repo)
    }

    private fun shoot(name: String, bottomBar: Boolean = false, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MurmureTheme {
                Box(Modifier.fillMaxSize().background(M.Ink).padding(bottom = if (bottomBar) 76.dp else 0.dp)) { content() }
            }
        }
        repeat(40) { rule.mainClock.advanceTimeBy(100); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() { seed(); shoot("01-accueil", true) { HomeScreen(rememberNavController()) } }

    @Test fun live() {
        val text = "Bonjour, j'aimerais prendre une note en lien avec le dossier Projet Atlas. Alors ce matin avec Marc on a revu la maquette, " +
            "faut que j'envoie le brief demain. Rendez-vous jeudi 14h avec Julie au Café Olimpico. Je me sens motivé, " +
            "et j'ai une idée : un mode focus"
        val terms = mapOf("Projet Atlas" to "project", "Marc" to "person", "maquette" to "concept", "Julie" to "person", "Café Olimpico" to "place", "brief" to "concept")
        val full = "$text de vingt-cinq minutes"
        val ui = LiveUi(
            voice = VoiceState(phase = Phase.LISTENING, committed = text, partial = "de vingt-cinq minutes", engine = "Gemini Live", startedAt = 0, level = 0.5f),
            levels = List(42) { i -> (0.2f + 0.6f * kotlin.math.abs(kotlin.math.sin(i * 0.45f))) * (i / 42f) },
            hits = LocalBrain.highlight(full, terms),
            declaredFolder = "Projet Atlas", elapsedSec = 47, started = true,
            moments = LocalBrain.detectMoments(full), topic = "Maquette Atlas", valence = 0.6f, emotion = "fierté", energy = "haute",
            insight = "Marc revient pour la 3e fois cette semaine — toujours autour de la maquette.",
        )
        shoot("02-dictee-live") { RecordContent(ui, daily = false, permissionDenied = false, onClose = {}, onStop = {}) }
    }

    @Test fun ritual() {
        val text = "Grosse journée. Gym ce matin, puis bureau. Souper avec Julie, ça m'a fait du bien"
        val ui = LiveUi(
            voice = VoiceState(phase = Phase.LISTENING, committed = text, engine = "Gemini Live", level = 0.3f),
            levels = List(42) { i -> 0.1f + 0.5f * kotlin.math.abs(kotlin.math.cos(i * 0.3f)) },
            hits = LocalBrain.highlight(text, mapOf("Gym" to "activity", "Julie" to "person", "bureau" to "place")),
            elapsedSec = 112, started = true, mood = 4, valence = 0.5f, emotion = "calme",
        )
        shoot("03-rituel") { RecordContent(ui, daily = true, permissionDenied = false, onClose = {}, onStop = {}) }
    }

    @Test fun review() {
        seed()
        val id = runBlocking {
            val text = "Bonjour, une note pour le dossier Projet Atlas. Avec Marc on a revu la maquette, faut que j'envoie le brief demain. " +
                "Nouvelle note : idées pour le studio de Julie, un nom court et des ateliers le samedi. Rendez-vous jeudi 14h avec Julie au café. Je me sens motivé."
            val cid = app.repo.saveCapture(text, 74, false, null, LocalBrain.detectMoments(text))
            app.repo.analyzeCapture(cid) // clé « demo » invalide → proposition locale, sauvegardée
            cid
        }
        shoot("04-validation") { ReviewScreen(rememberNavController(), id) }
    }

    @Test fun graph() { seed(); shoot("05-graphe", true) { ExploreScreen(rememberNavController()) } }

    @Test fun portrait() { seed(); shoot("06-insights", true) { InsightsScreen(rememberNavController()) } }

    @Test fun tasks() { seed(); shoot("07-taches", true) { TasksScreen(rememberNavController()) } }

    @Test fun note() {
        seed()
        val id = runBlocking { app.repo.dao.notes().first { it.title.startsWith("Maquette v2") }.id }
        shoot("08-note") { NoteScreen(rememberNavController(), id) }
    }

    @Test fun folder() {
        seed()
        val id = runBlocking { app.repo.dao.folders().first { it.name == "Travail" }.id }
        shoot("09-dossier") { FolderScreen(rememberNavController(), id) }
    }

    @Test fun settings() { shoot("10-reglages") { SettingsScreen(rememberNavController()) } }

    @Test fun icon() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)!!
        val size = 432
        val bmp = Bitmap.createBitmap(size * 3 + 80, size + 40, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(0xFF0E0C16.toInt())
        // 1) icône adaptative carrée arrondie, 2) ronde, 3) premier plan seul
        fun clip(x: Int, round: Boolean) {
            c.save()
            val path = android.graphics.Path()
            val r = android.graphics.RectF(x.toFloat(), 20f, (x + size).toFloat(), (20 + size).toFloat())
            if (round) path.addOval(r, android.graphics.Path.Direction.CW) else path.addRoundRect(r, 96f, 96f, android.graphics.Path.Direction.CW)
            c.clipPath(path)
            d.setBounds(x, 20, x + size, 20 + size)
            d.draw(c)
            c.restore()
        }
        clip(20, false); clip(size + 40, true)
        val fg = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_foreground)!!
        fg.setBounds(size * 2 + 60 - size / 4, 20 - size / 4, size * 3 + 60 + size / 4, 20 + size + size / 4)
        fg.draw(c)
        File(outDir, "00-icone.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun onboarding() { shoot("11-accueil-premier-lancement") { OnboardingScreen {} } }
}
