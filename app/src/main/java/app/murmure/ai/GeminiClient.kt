package app.murmure.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiException(message: String, val code: Int = 0) : IOException(message)

data class ModelInfo(val name: String, val displayName: String, val methods: List<String>)

/** Client REST minimal pour l'API Gemini (Google AI Studio), authentifié par clé API. */
class GeminiClient(val http: OkHttpClient, private val base: String = BASE) {

    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    suspend fun listModels(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ModelInfo>()
        var page: String? = null
        do {
            val url = "$base/models?pageSize=200" + (page?.let { "&pageToken=$it" } ?: "")
            val req = Request.Builder().url(url).header("x-goog-api-key", apiKey).get().build()
            val body = execute(req)
            val root = json.parseToJsonElement(body).jsonObject
            root["models"]?.jsonArray?.forEach { m ->
                val o = m.jsonObject
                out += ModelInfo(
                    name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    displayName = o["displayName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    methods = o["supportedGenerationMethods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                )
            }
            page = root["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (!page.isNullOrBlank())
        out
    }

    /**
     * Appel generateContent. [audio] optionnel (WAV) pour la transcription par segments.
     * Retourne le texte concaténé de la première candidate.
     */
    suspend fun generate(
        apiKey: String,
        model: String,
        prompt: String,
        system: String? = null,
        jsonMode: Boolean = false,
        audioWav: ByteArray? = null,
        temperature: Double = 0.4,
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        if (audioWav != null) addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "audio/wav")
                                put("data", android.util.Base64.encodeToString(audioWav, android.util.Base64.NO_WRAP))
                            }
                        }
                        addJsonObject { put("text", prompt) }
                    }
                }
            }
            if (system != null) putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                if (jsonMode) put("responseMimeType", "application/json")
            }
        }
        val req = Request.Builder()
            .url("$base/${normalize(model)}:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val body = execute(req)
        val root = json.parseToJsonElement(body).jsonObject
        val cands = root["candidates"] as? JsonArray
        if (cands.isNullOrEmpty()) {
            val reason = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
            throw AiException("Réponse vide de Gemini" + (reason?.let { " ($it)" } ?: ""))
        }
        val parts = (cands[0] as JsonObject)["content"]?.jsonObject?.get("parts")?.jsonArray ?: return@withContext ""
        parts.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    private fun execute(req: Request): String {
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(explain(r.code, body), r.code)
            return body
        }
    }

    private fun explain(code: Int, body: String): String {
        val msg = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when (code) {
            400 -> if (msg?.contains("API key", true) == true) "Clé API invalide." else "Requête refusée : ${msg ?: code}"
            401, 403 -> "Clé API refusée ou API non activée. ${msg.orEmpty()}".trim()
            404 -> "Modèle introuvable : ${msg.orEmpty()}".trim()
            429 -> "Quota atteint, réessaie dans un instant."
            in 500..599 -> "Gemini est momentanément indisponible ($code)."
            else -> "Erreur $code ${msg.orEmpty()}".trim()
        }
    }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        val JSON = "application/json; charset=utf-8".toMediaType()
        fun normalize(model: String) = if (model.startsWith("models/")) model else "models/$model"

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        /** Choisit les meilleurs modèles disponibles pour cette clé. */
        fun pickModels(models: List<ModelInfo>): Pair<String?, String?> {
            fun score(n: String): Int {
                var s = 0
                if ("flash" in n) s += 40
                if ("lite" in n) s -= 8
                if ("preview" in n || "exp" in n) s -= 5
                if ("thinking" in n || "tts" in n || "image" in n || "embedding" in n) s -= 100
                // Version « 2.5 » n'importe où dans le nom (gemini-2.5-flash, gemini-live-2.5-flash…)
                Regex("(\\d+)\\.(\\d+)").find(n)?.let { s += it.groupValues[1].toInt() * 10 + it.groupValues[2].toInt() }
                    ?: Regex("gemini-(\\d+)").find(n)?.let { s += it.groupValues[1].toInt() * 10 }
                return s
            }
            val text = models.filter { "generateContent" in it.methods && "gemini" in it.name }
                .maxByOrNull { score(it.name) }?.name
            val live = models.filter { "bidiGenerateContent" in it.methods }
                .maxByOrNull { score(it.name) + if ("native-audio" in it.name) -15 else 0 }?.name
            return text to live
        }
    }
}
