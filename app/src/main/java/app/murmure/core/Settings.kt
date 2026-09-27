package app.murmure.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Engine(val label: String, val detail: String) {
    AUTO("Automatique", "Gemini Live en direct, bascule seule si besoin"),
    GEMINI_LIVE("Gemini Live", "Streaming temps réel par WebSocket (clé Gemini)"),
    GEMINI_SEGMENTS("Gemini par segments", "Transcrit par phrases, très précis (clé Gemini)"),
    CLOUD_STT("Google Cloud Speech-to-Text", "API Speech v1 par segments (clé Google Cloud)"),
    DEVICE("Reconnaissance de l'appareil", "Hors clé, moteur vocal Android"),
}

data class AppSettings(
    val apiKey: String = "",
    val cloudSttKey: String = "",
    val engine: Engine = Engine.AUTO,
    val language: String = "fr-CA",
    val textModel: String = DEFAULT_TEXT_MODEL,
    val liveModel: String = DEFAULT_LIVE_MODEL,
    val reminderEnabled: Boolean = true,
    val reminderMinutes: Int = 20 * 60 + 30,
    val onboarded: Boolean = false,
    val liveHighlights: Boolean = true,
) {
    val hasAiKey get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_TEXT_MODEL = "models/gemini-2.5-flash"
        const val DEFAULT_LIVE_MODEL = "models/gemini-live-2.5-flash-preview"
    }
}

class SettingsStore(context: Context, private val vault: Vault) {
    private val prefs = context.getSharedPreferences("murmure_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current get() = _state.value

    private fun load() = AppSettings(
        apiKey = vault.getString("api_key").orEmpty(),
        cloudSttKey = vault.getString("stt_key").orEmpty(),
        engine = runCatching { Engine.valueOf(prefs.getString("engine", "AUTO")!!) }.getOrDefault(Engine.AUTO),
        language = prefs.getString("language", "fr-CA")!!,
        textModel = prefs.getString("text_model", AppSettings.DEFAULT_TEXT_MODEL)!!,
        liveModel = prefs.getString("live_model", AppSettings.DEFAULT_LIVE_MODEL)!!,
        reminderEnabled = prefs.getBoolean("reminder", true),
        reminderMinutes = prefs.getInt("reminder_min", 20 * 60 + 30),
        onboarded = prefs.getBoolean("onboarded", false),
        liveHighlights = prefs.getBoolean("live_hl", true),
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val old = _state.value
        val new = block(old)
        if (new.apiKey != old.apiKey) vault.putString("api_key", new.apiKey.trim())
        if (new.cloudSttKey != old.cloudSttKey) vault.putString("stt_key", new.cloudSttKey.trim())
        prefs.edit()
            .putString("engine", new.engine.name)
            .putString("language", new.language)
            .putString("text_model", new.textModel)
            .putString("live_model", new.liveModel)
            .putBoolean("reminder", new.reminderEnabled)
            .putInt("reminder_min", new.reminderMinutes)
            .putBoolean("onboarded", new.onboarded)
            .putBoolean("live_hl", new.liveHighlights)
            .apply()
        _state.value = new.copy(apiKey = new.apiKey.trim(), cloudSttKey = new.cloudSttKey.trim())
    }

    fun wipe() {
        prefs.edit().clear().commit()
        _state.value = AppSettings(onboarded = true)
    }
}
