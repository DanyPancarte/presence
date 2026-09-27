package app.murmure.ai

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout

/**
 * Gemini Nano sur l'appareil (ML Kit GenAI Prompt API) : gratuit, hors ligne, instantané.
 * Disponible sur Pixel 8 Pro / 9 et suite, et d'autres appareils avec AICore.
 */
class NanoClient(private val context: Context) {
    enum class State { UNKNOWN, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, READY }

    private val _state = MutableStateFlow(State.UNKNOWN)
    val state: StateFlow<State> = _state
    private var model: GenerativeModel? = null
    @Volatile var lastError: String? = null

    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    /** Vérifie la disponibilité (et lance le téléchargement si demandé). */
    suspend fun refresh(download: Boolean = false): State {
        return runCatching {
            val st = client().checkStatus()
            val mapped = when (st) {
                FeatureStatus.AVAILABLE -> State.READY
                FeatureStatus.DOWNLOADABLE -> State.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> State.DOWNLOADING
                else -> State.UNAVAILABLE
            }
            _state.value = mapped
            if (download && mapped == State.DOWNLOADABLE) {
                _state.value = State.DOWNLOADING
                runCatching { client().download().collect { } }
                    .onFailure { lastError = it.message }
                val after = client().checkStatus()
                _state.value = if (after == FeatureStatus.AVAILABLE) State.READY else State.DOWNLOADABLE
            }
            _state.value
        }.getOrElse { e ->
            lastError = e.message
            _state.value = State.UNAVAILABLE
            State.UNAVAILABLE
        }
    }

    val ready get() = _state.value == State.READY

    suspend fun generate(prompt: String, system: String? = null, jsonMode: Boolean = false, temperature: Double = 0.3): String {
        if (!ready) throw AiException("Gemini Nano n'est pas prêt sur cet appareil.")
        val sys = listOfNotNull(system, if (jsonMode) "Réponds uniquement avec un objet JSON valide, sans commentaire." else null).joinToString("\n\n")
        val builder = if (sys.isNotBlank()) GenerateContentRequest.Builder(SystemInstruction(sys), TextPart(prompt))
        else GenerateContentRequest.Builder(TextPart(prompt))
        builder.temperature = temperature.toFloat()
        builder.maxOutputTokens = 1024
        val resp = withTimeout(25_000) { client().generateContent(builder.build()) }
        return resp.candidates.firstOrNull()?.text.orEmpty()
    }

    suspend fun warmup() = runCatching { if (ready) client().warmup() }
}
