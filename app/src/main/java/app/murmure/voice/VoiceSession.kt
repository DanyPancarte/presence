package app.murmure.voice

import android.content.Context
import app.murmure.ai.GeminiClient
import app.murmure.core.AppSettings
import app.murmure.core.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque

enum class Phase { IDLE, STARTING, LISTENING, FINALIZING, DONE }

data class VoiceState(
    val phase: Phase = Phase.IDLE,
    val committed: String = "",
    val partial: String = "",
    val level: Float = 0f,
    val engine: String = "",
    val pending: Int = 0,
    val notice: String? = null,
    val error: String? = null,
    val startedAt: Long = 0L,
) {
    val text: String get() = (committed + if (partial.isNotBlank()) " $partial" else "").replace(Regex("\\s+"), " ").trim()
}

/**
 * Chef d'orchestre de la dictée : choisit le moteur, gère les replis automatiques
 * (Live → segments → appareil) sans jamais perdre ce qui a été dit.
 */
class VoiceSession(
    private val context: Context,
    private val settings: AppSettings,
    private val client: GeminiClient,
    private val scope: CoroutineScope,
    private val liveUrl: String = GeminiLiveEngine.URL,
) {
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val committed = StringBuilder()
    private var capture: AudioCapture? = null
    @Volatile private var engine: StreamingEngine? = null
    private var device: DeviceEngine? = null
    private val replay = ArrayDeque<ByteArray>()
    @Volatile private var anyText = false
    @Volatile private var switching = false
    private var plan: ArrayDeque<Engine> = ArrayDeque()

    private val sink = object : TranscriptSink {
        override fun onFinal(text: String) {
            anyText = true
            synchronized(committed) { committed.append(text) }
            _state.update { it.copy(committed = synchronized(committed) { committed.toString() }, partial = if (device != null) "" else it.partial) }
        }
        override fun onPartial(text: String) = _state.update { it.copy(partial = text) }
        override fun onPending(count: Int) = _state.update { it.copy(pending = count.coerceAtLeast(0)) }
        override fun onLevel(level: Float) = _state.update { it.copy(level = level) }
        override fun onReady(label: String) = _state.update { it.copy(phase = Phase.LISTENING, engine = label) }
        override fun onFailure(message: String, recoverable: Boolean) {
            scope.launch(Dispatchers.Main) { fallback(message) }
        }
    }

    fun start(preroll: List<ByteArray> = emptyList()) {
        plan = ArrayDeque(buildPlan())
        synchronized(replay) { replay.clear(); preroll.forEach { replay.addLast(it) } }
        _state.value = VoiceState(phase = Phase.STARTING, startedAt = System.currentTimeMillis())
        next(null)
    }

    private fun buildPlan(): List<Engine> {
        val s = settings
        val hasGemini = s.apiKey.isNotBlank()
        val sttKey = s.cloudSttKey.ifBlank { s.apiKey }
        return when (s.engine) {
            Engine.DEVICE -> listOf(Engine.DEVICE)
            Engine.CLOUD_STT -> listOfNotNull(
                Engine.CLOUD_STT.takeIf { sttKey.isNotBlank() },
                Engine.GEMINI_SEGMENTS.takeIf { hasGemini }, Engine.DEVICE,
            )
            Engine.GEMINI_SEGMENTS -> listOfNotNull(Engine.GEMINI_SEGMENTS.takeIf { hasGemini }, Engine.DEVICE)
            Engine.GEMINI_LIVE, Engine.AUTO -> listOfNotNull(
                Engine.GEMINI_LIVE.takeIf { hasGemini },
                Engine.GEMINI_SEGMENTS.takeIf { hasGemini },
                Engine.CLOUD_STT.takeIf { s.cloudSttKey.isNotBlank() },
                Engine.DEVICE,
            )
        }
    }

    private fun next(reason: String?) {
        val e = plan.pollFirst()
        if (e == null) {
            stopCapture()
            _state.update { it.copy(error = reason ?: "Aucun moteur de transcription disponible.", phase = Phase.DONE) }
            return
        }
        if (reason != null) _state.update { it.copy(notice = "Bascule vers ${e.label} — $reason".take(160)) }
        else if (settings.apiKey.isBlank() && e == Engine.DEVICE)
            _state.update {
                it.copy(
                    notice = if (settings.claudeKey.isNotBlank()) "Transcription par l'appareil · analyse par Claude."
                    else "Aucune clé API : reconnaissance de l'appareil. Ajoute ta clé dans Réglages pour l'IA.",
                )
            }

        when (e) {
            Engine.DEVICE -> {
                stopCapture()
                if (!anyText) synchronized(replay) { replay.clear() } // l'appareil ne relit pas le PCM
                device = DeviceEngine(context, settings.language, sink).also { it.start() }
            }
            else -> {
                val eng: StreamingEngine = when (e) {
                    Engine.GEMINI_LIVE, Engine.AUTO -> GeminiLiveEngine(client.http, settings.apiKey, settings.liveModel, sink, liveUrl)
                    Engine.GEMINI_SEGMENTS -> SegmentEngine("Gemini · segments", scope, sink,
                        SegmentEngine.gemini(client, settings.apiKey, settings.textModel, settings.language))
                    else -> SegmentEngine("Speech-to-Text", scope, sink,
                        SegmentEngine.cloudStt(client, settings.cloudSttKey.ifBlank { settings.apiKey }, settings.language))
                }
                engine = eng
                eng.start()
                // Rejoue l'audio déjà capté (pré-roll de l'accueil, ou moteur précédent muet).
                if (!anyText) synchronized(replay) { replay.forEach { eng.feed(it, 0.05f) } }
                if (capture == null) startCapture()
            }
        }
    }

    private fun startCapture() {
        capture = AudioCapture { pcm, level ->
            _state.update { it.copy(level = (level * 7f).coerceIn(0f, 1f)) }
            if (!anyText) synchronized(replay) {
                replay.addLast(pcm)
                while (replay.size > 200) replay.removeFirst()
            }
            engine?.feed(pcm, level)
        }.also {
            runCatching { it.start(scope) }.onFailure { e ->
                capture = null
                _state.update { s -> s.copy(error = e.message) }
            }
        }
    }

    private fun stopCapture() {
        capture?.stop(); capture = null
    }

    private fun fallback(reason: String) {
        if (switching || _state.value.phase == Phase.FINALIZING || _state.value.phase == Phase.DONE) return
        switching = true
        engine?.cancel(); engine = null
        device?.cancel(); device = null
        next(reason)
        switching = false
    }

    /** Arrête la dictée et renvoie la transcription finale. */
    suspend fun stop(): String {
        _state.update { it.copy(phase = Phase.FINALIZING) }
        stopCapture()
        engine?.finish()
        device?.let { it.stop(); delay(1_200) }
        val s = _state.value
        // Un provisoire non confirmé vaut mieux que rien.
        if (s.partial.isNotBlank()) synchronized(committed) { committed.append(" ").append(s.partial) }
        val finalText = synchronized(committed) { committed.toString() }.replace(Regex("\\s+"), " ").trim()
        _state.update { it.copy(phase = Phase.DONE, committed = finalText, partial = "", level = 0f) }
        return finalText
    }

    fun cancel() {
        stopCapture()
        engine?.cancel(); engine = null
        device?.cancel(); device = null
        _state.update { it.copy(phase = Phase.DONE) }
    }
}
