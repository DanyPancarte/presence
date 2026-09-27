package app.murmure.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Client minimal de l'API Messages d'Anthropic (clé `sk-ant-…`), pour l'analyse texte. */
class ClaudeClient(private val http: OkHttpClient, private val base: String = BASE) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun generate(
        apiKey: String, model: String, prompt: String, system: String? = null,
        jsonMode: Boolean = false, temperature: Double = 0.4, maxTokens: Int = 4096,
    ): String = withContext(Dispatchers.IO) {
        val sys = listOfNotNull(system, if (jsonMode) "Réponds UNIQUEMENT avec un objet JSON valide, sans texte autour ni bloc de code." else null)
            .joinToString("\n\n")
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (sys.isNotBlank()) put("system", sys)
            if (!model.startsWith("claude-haiku")) {
                // Modèles 4.6+ : effort bas = rapide et économique pour de l'extraction structurée.
                put("output_config", buildJsonObject { put("effort", "low") })
            } else {
                put("temperature", temperature)
            }
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", prompt) } }
        }
        val req = Request.Builder().url("$base/v1/messages")
            .header("x-api-key", apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(GeminiClient.JSON))
            .build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(explain(r.code, txt), r.code)
            val root = json.parseToJsonElement(txt).jsonObject
            if (root["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") throw AiException("Claude a décliné cette requête.")
            root["content"]?.jsonArray.orEmpty()
                .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        }
    }

    /** Vérifie la clé avec une requête minuscule. */
    suspend fun ping(apiKey: String, model: String): String {
        val out = generate(apiKey, model, "Réponds exactement : ok", maxTokens = 16)
        return "✓ Clé Anthropic valide · $model" + if (out.isNotBlank()) "" else " (réponse vide)"
    }

    private fun explain(code: Int, body: String): String {
        val msg = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
        return when (code) {
            401 -> "Clé Anthropic invalide."
            403 -> "Accès refusé : ${msg ?: "vérifie la clé et le modèle"}"
            404 -> "Modèle introuvable : ${msg.orEmpty()}".trim()
            400 -> if (msg?.contains("credit", true) == true) "Crédit API épuisé — recharge sur console.anthropic.com." else "Requête refusée : ${msg ?: code}"
            429 -> "Limite de débit atteinte, réessaie dans un instant."
            529 -> "Claude est surchargé, réessaie."
            in 500..599 -> "Anthropic momentanément indisponible ($code)."
            else -> "Erreur $code ${msg.orEmpty()}".trim()
        }
    }

    companion object {
        const val BASE = "https://api.anthropic.com"
        val models = listOf(
            "claude-haiku-4-5" to "Haiku 4.5 · le moins cher (~0,10 $ / session)",
            "claude-sonnet-5" to "Sonnet 5 · plus fin (~0,25 $ / session)",
            "claude-opus-5" to "Opus 5 · le plus capable (~0,60 $ / session)",
        )
    }
}
