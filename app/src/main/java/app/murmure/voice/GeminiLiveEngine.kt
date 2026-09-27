package app.murmure.voice

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.ArrayDeque

/**
 * Transcription en direct via l'API Gemini Live (WebSocket BidiGenerateContent),
 * en exploitant `inputAudioTranscription` : le texte arrive pendant qu'on parle.
 */
class GeminiLiveEngine(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val sink: TranscriptSink,
    private val url: String = URL,
) : StreamingEngine {
    override val label = "Gemini Live"

    private val json = Json { ignoreUnknownKeys = true }
    private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var finishing = false
    @Volatile private var closedByUs = false
    @Volatile private var gotText = false
    private var reconnects = 0
    private val backlog = ArrayDeque<ByteArray>()
    private var lastTextAt = 0L
    private var lastChar = ' '
    private var finished = CompletableDeferred<Unit>()

    override fun start() = connect()

    @Volatile private var gen = 0

    private fun connect() {
        ready = false
        val g = ++gen
        val req = Request.Builder().url("$url?key=$apiKey").build()
        ws = http.newWebSocket(req, Listener(g))
    }

    private fun setupMessage(): String {
        val nativeAudio = "native-audio" in model
        return buildJsonObject {
            putJsonObject("setup") {
                put("model", if (model.startsWith("models/")) model else "models/$model")
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add(if (nativeAudio) "AUDIO" else "TEXT") }
                }
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", "Tu es un simple micro de dictée. Ne réponds jamais au contenu. Réponds uniquement « . ».")
                        }
                    }
                }
                putJsonObject("inputAudioTranscription") {}
            }
        }.toString()
    }

    private inner class Listener(private val g: Int) : WebSocketListener() {
        private val current get() = g == gen

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (current) webSocket.send(setupMessage())
        }

        override fun onMessage(webSocket: WebSocket, text: String) { if (current) handle(text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (current) handle(bytes.utf8()) }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (current) onEnded(explainClose(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("Murmure", "Live failure", t)
            if (current) onEnded(t.message ?: "Réseau indisponible")
        }
    }

    private fun explainClose(code: Int, reason: String): String = when {
        reason.contains("API key", true) -> "clé API refusée par Gemini Live"
        reason.contains("not found", true) || reason.contains("not supported", true) -> "modèle Live indisponible pour cette clé"
        reason.contains("quota", true) || code == 1011 && reason.contains("exceed", true) -> "quota Gemini atteint"
        else -> "connexion Live fermée ($code) ${reason.take(120)}"
    }

    private fun handle(raw: String) {
        val msg = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        if ("setupComplete" in msg) {
            ready = true
            reconnects = 0
            sink.onReady(label)
            synchronized(backlog) { while (backlog.isNotEmpty()) sendAudio(backlog.removeFirst()) }
            return
        }
        (msg["serverContent"] as? JsonObject)?.let { sc ->
            val t = (sc["inputTranscription"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            if (!t.isNullOrEmpty()) {
                gotText = true
                lastTextAt = System.currentTimeMillis()
                // Après une ponctuation, on garantit l'espace ; sinon on respecte les fragments tels quels.
                val needsSpace = lastChar in ".!?,;:" && t.first().isLetterOrDigit()
                sink.onFinal(if (needsSpace) " $t" else t)
                lastChar = t.last()
            }
        }
        if ("goAway" in msg && !finishing) {
            // Fin de session imminente côté serveur : on renoue une nouvelle session
            // (la nouvelle génération rend l'ancienne socket muette).
            val old = ws
            reconnects = 0
            connect()
            old?.close(1000, "renew")
        }
    }

    private fun onEnded(reason: String) {
        ready = false
        if (closedByUs) return
        if (finishing) { finished.complete(Unit); return }
        if (!gotText && reconnects == 0) {
            // Échec dès l'ouverture : clé, modèle ou réseau. Le chef d'orchestre bascule.
            sink.onFailure(reason, recoverable = true)
            return
        }
        if (reconnects < 3) {
            reconnects++
            connect()
        } else sink.onFailure(reason, recoverable = true)
    }

    private fun sendAudio(pcm: ByteArray) {
        val payload = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                    put("mimeType", "audio/pcm;rate=16000")
                }
            }
        }.toString()
        ws?.send(payload)
    }

    override fun feed(pcm: ByteArray, level: Float) {
        if (ready) sendAudio(pcm)
        else synchronized(backlog) {
            backlog.addLast(pcm)
            while (backlog.size > 150) backlog.removeFirst() // 15 s max en attente
        }
    }

    override suspend fun finish() {
        finishing = true
        if (!ready) { cancel(); return }
        finished = CompletableDeferred()
        ws?.send(buildJsonObject { putJsonObject("realtimeInput") { put("audioStreamEnd", true) } }.toString())
        // Laisse arriver la fin de la transcription : on attend un silence de texte.
        withTimeoutOrNull(4_000) {
            val start = System.currentTimeMillis()
            while (true) {
                kotlinx.coroutines.delay(150)
                val quiet = System.currentTimeMillis() - maxOf(lastTextAt, start)
                if (quiet > 1_300) break
            }
        }
        closedByUs = true
        ws?.close(1000, "done")
    }

    override fun cancel() {
        closedByUs = true
        ws?.cancel()
        ws = null
    }

    companion object {
        const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
