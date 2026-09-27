package app.murmure

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import app.murmure.ai.GeminiClient
import app.murmure.ai.NoteAnalyzer
import app.murmure.core.SettingsStore
import app.murmure.core.Vault
import app.murmure.data.MurmureDb
import app.murmure.data.Repository
import app.murmure.reminder.DailyReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class MurmureApp : Application(), Configuration.Provider {
    lateinit var vault: Vault; private set
    lateinit var settings: SettingsStore; private set
    lateinit var client: GeminiClient; private set
    lateinit var analyzer: NoteAnalyzer; private set
    lateinit var claude: app.murmure.ai.ClaudeClient; private set
    lateinit var nano: app.murmure.ai.NanoClient; private set
    lateinit var router: app.murmure.ai.AiRouter; private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repo: Repository by lazy { Repository(openDatabase(), settings, client, router) }

    protected open fun createVault(): Vault = Vault(this)
    protected open fun openDatabase(): MurmureDb = MurmureDb.open(this, vault.databasePassphrase())
    protected open val scheduleReminders = true

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        vault = createVault()
        settings = SettingsStore(this, vault)
        client = GeminiClient(GeminiClient.defaultHttp())
        claude = app.murmure.ai.ClaudeClient(client.http)
        nano = app.murmure.ai.NanoClient(this)
        router = app.murmure.ai.AiRouter(client, claude, nano)
        analyzer = NoteAnalyzer(router)
        appScope.launch {
            val st = nano.refresh(download = true)
            settings.update { it.copy(nanoReady = st == app.murmure.ai.NanoClient.State.READY) }
            if (st == app.murmure.ai.NanoClient.State.READY) nano.warmup()
        }
        app.murmure.ui.components.Feedback.init(this)
        appScope.launch {
            settings.state.collect { s ->
                app.murmure.ui.components.Feedback.soundsEnabled = s.sounds
                app.murmure.ui.components.Feedback.hapticsEnabled = s.haptics
            }
        }
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(DailyReminder.CHANNEL, "Rituel quotidien", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Rappel pour raconter ta journée"
                },
                NotificationChannel(app.murmure.voice.RecordingService.CHANNEL, "Dictée en cours", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Indique que le micro est actif"
                },
            )
        )
        appScope.launch {
            repo.ensureDefaults()
            if (scheduleReminders) runCatching { DailyReminder.schedule(this@MurmureApp, settings.current) }
        }
    }

    companion object {
        lateinit var instance: MurmureApp; private set
    }
}
