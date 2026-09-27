package app.murmure.voice

import android.util.Base64
import app.murmure.ai.AiException
import app.murmure.ai.GeminiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Fonction de transcription d'un segment WAV. */
fun interface SegmentTranscriber {
    suspend fun transcribe(wav: ByteArray): String
}

/**
 * Découpe le flux micro en phrases (détection d'activité vocale par énergie)
 * et transcrit chaque phrase via une API REST, dans l'ordre.
 */
class SegmentEngine(
    override val label: String,
    private val scope: CoroutineScope,
    private val sink: TranscriptSink,
    private val transcriber: SegmentTranscriber,
) : StreamingEngine {

    private val buffer = ByteArrayOutputStream()
    private var speechMs = 0
    private var silenceMs = 0
    private var noise = 0.01f
    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    private var worker: Job? = null
    private var failures = 0

    override fun start() {
        sink.onReady(label)
        worker = scope.launch(Dispatchers.IO) {
            for (seg in queue) {
                val text = runCatching { transcriber.transcribe(AudioCapture.wav(seg)) }
                    .onFailure { e ->
                        failures++
                        if (e is AiException && (e.code == 400 || e.code == 401 || e.code == 403 || e.code == 404)) {
                            sink.onFailure(e.message ?: "Clé refusée", recoverable = true)
                        } else if (failures >= 3) {
                            sink.onFailure(e.message ?: "Transcription impossible", recoverable = true)
                        }
                    }
                    .getOrNull()
                if (text != null) failures = 0
                val clean = text?.trim().orEmpty()
                if (clean.isNotEmpty() && !clean.equals("(silence)", true)) sink.onFinal(" $clean")
                sink.onPending(pending.decrementAndGet())
            }
        }
    }

    @Synchronized
    override fun feed(pcm: ByteArray, level: Float) {
        // Plancher de bruit adaptatif
        noise = if (level < noise) noise * 0.9f + level * 0.1f else noise * 0.995f + level * 0.005f
        val speaking = level > maxOf(noise * 2.6f, 0.006f)
        buffer.write(pcm)
        val frameMs = pcm.size * 1000 / (AudioCapture.RATE * 2)
        if (speaking) { speechMs += frameMs; silenceMs = 0 } else silenceMs += frameMs
        val totalMs = buffer.size() * 1000 / (AudioCapture.RATE * 2)

        if (speechMs == 0 && totalMs > 600) {
            // Que du silence : on ne garde qu'un court pré-roll.
            val bytes = buffer.toByteArray()
            buffer.reset()
            buffer.write(bytes, bytes.size - AudioCapture.FRAME_BYTES * 3, AudioCapture.FRAME_BYTES * 3)
            return
        }
        val endOfPhrase = speechMs >= 400 && silenceMs >= 750 && totalMs >= 1_200
        if (endOfPhrase || totalMs >= 14_000) cut()
    }

    @Synchronized
    private fun cut() {
        if (speechMs >= 250) {
            sink.onPending(pending.incrementAndGet())
            queue.trySend(buffer.toByteArray())
        }
        buffer.reset(); speechMs = 0; silenceMs = 0
    }

    override suspend fun finish() {
        cut()
        queue.close()
        withTimeoutOrNull(25_000) { worker?.join() }
    }

    override fun cancel() {
        queue.close()
        worker?.cancel()
    }

    companion object {
        fun gemini(client: GeminiClient, key: String, model: String, language: String) = SegmentTranscriber { wav ->
            client.generate(
                apiKey = key, model = model, audioWav = wav, temperature = 0.0,
                prompt = "Transcris fidèlement et mot à mot cet extrait audio (langue : $language). " +
                    "Ajoute une ponctuation naturelle. Réponds UNIQUEMENT avec la transcription, sans guillemets ni commentaire. " +
                    "S'il n'y a aucune parole, réponds exactement : (silence)",
            )
        }

        fun cloudStt(client: GeminiClient, key: String, language: String) = SegmentTranscriber { wav ->
            val pcm = wav.copyOfRange(44, wav.size)
            val body = buildJsonObject {
                putJsonObject("config") {
                    put("encoding", "LINEAR16")
                    put("sampleRateHertz", AudioCapture.RATE)
                    put("languageCode", language)
                    put("enableAutomaticPunctuation", true)
                    put("model", "latest_long")
                }
                putJsonObject("audio") { put("content", Base64.encodeToString(pcm, Base64.NO_WRAP)) }
            }
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("https://speech.googleapis.com/v1/speech:recognize")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody(GeminiClient.JSON))
                    .build()
                client.http.newCall(req).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw AiException("Speech-to-Text : erreur ${r.code}", r.code)
                    val root = client.json.parseToJsonElement(txt).jsonObject
                    root["results"]?.jsonArray?.joinToString(" ") { res ->
                        res.jsonObject["alternatives"]?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("transcript")?.jsonPrimitive?.contentOrNull.orEmpty()
                    }.orEmpty()
                }
            }
        }
    }
}
