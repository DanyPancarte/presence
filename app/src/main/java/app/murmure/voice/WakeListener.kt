package app.murmure.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/** Audio capté avant le déclenchement, transmis à la session de dictée. */
object PendingAudio {
    @Volatile var frames: List<ByteArray> = emptyList()
    fun take(): List<ByteArray> = frames.also { frames = emptyList() }
}

/**
 * Écoute passive sur l'accueil : mesure le niveau, détecte une prise de parole soutenue
 * et déclenche la dictée avec les 2 dernières secondes déjà captées. Rien n'est envoyé nulle part.
 */
class WakeListener(private val scope: CoroutineScope, private val onSpeech: () -> Unit) {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private var capture: AudioCapture? = null
    private val ring = ArrayDeque<ByteArray>()
    private var noise = 0.01f
    private var speechMs = 0
    private var quietMs = 0
    @Volatile private var fired = false

    fun start(): Boolean {
        if (capture != null) return true
        fired = false; speechMs = 0; quietMs = 0
        val c = AudioCapture { pcm, level ->
            _level.value = (level * 6f).coerceIn(0f, 1f)
            synchronized(ring) { ring.addLast(pcm); while (ring.size > 20) ring.removeFirst() }
            noise = if (level < noise) noise * 0.9f + level * 0.1f else noise * 0.997f + level * 0.003f
            val speaking = level > maxOf(noise * 3.2f, 0.018f)
            if (speaking) { speechMs += AudioCapture.FRAME_MS; quietMs = 0 } else { quietMs += AudioCapture.FRAME_MS; if (quietMs > 400) speechMs = 0 }
            // ≥ 700 ms de parole nette, pas un claquement de porte
            if (speechMs >= 700 && !fired) {
                fired = true
                PendingAudio.frames = synchronized(ring) { ring.toList() }
                onSpeech()
            }
        }
        return runCatching { c.start(scope); capture = c; _armed.value = true; true }.getOrElse { false }
    }

    fun stop() {
        capture?.stop(); capture = null
        _armed.value = false; _level.value = 0f
        synchronized(ring) { ring.clear() }
    }
}
