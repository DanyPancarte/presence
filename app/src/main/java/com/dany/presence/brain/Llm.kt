package com.dany.presence.brain

import android.content.Context
import com.dany.presence.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One structured turn from the agent. */
data class Reply(val dire: String, val etat: String, val module: String, val actions: List<JSONObject>)

class LlmException(msg: String) : Exception(msg)

enum class Provider(val label: String, val defaultModel: String) {
    GEMINI("Gemini (palier gratuit)", "gemini-2.5-flash"),
    CLAUDE("Claude (payant)", "claude-haiku-4-5"),
}

/**
 * The brain, provider-agnostic. Raw HTTPS + org.json: no SDK, nothing else to ship in the APK.
 * Keys live in SharedPreferences (hidden settings) or local.properties → BuildConfig; never committed.
 */
class Llm(context: Context) {
    private val prefs = context.getSharedPreferences("presence", Context.MODE_PRIVATE)

    var provider: Provider
        get() = runCatching { Provider.valueOf(prefs.getString("provider", null) ?: "") }.getOrDefault(Provider.GEMINI)
        set(v) { prefs.edit().putString("provider", v.name).apply() }

    fun key(p: Provider): String = prefs.getString("key_${p.name}", null)?.takeIf { it.isNotBlank() }
        ?: if (p == Provider.GEMINI) BuildConfig.GEMINI_API_KEY else BuildConfig.CLAUDE_API_KEY
    fun setKey(p: Provider, k: String) = prefs.edit().putString("key_${p.name}", k.trim()).apply()
    fun model(p: Provider): String = prefs.getString("model_${p.name}", null)?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun setModel(p: Provider, m: String) = prefs.edit().putString("model_${p.name}", m.trim()).apply()

    val configured get() = key(provider).isNotBlank()
    val modelName get() = model(provider)

    /** Blocking; call off the main thread. [history] alternates user/model turns, oldest first. */
    fun ask(history: List<Pair<String, String>>, context: String): Reply {
        if (!configured) throw LlmException("Pas de clé ${provider.name.lowercase()}. Appui long 3 s pour la saisir.")
        val system = Persona.SYSTEM + "\n\nContexte du moment :\n" + context
        val raw = when (provider) {
            Provider.GEMINI -> gemini(system, history)
            Provider.CLAUDE -> claude(system, history)
        }
        return parse(raw)
    }

    // ---- Gemini: generateContent, JSON mode ----------------------------------------------------
    private fun gemini(system: String, history: List<Pair<String, String>>): String {
        val contents = JSONArray()
        history.forEach { (role, text) ->
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.9)
                .put("maxOutputTokens", 400)
                .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
        val text = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${model(Provider.GEMINI)}:generateContent",
            mapOf("x-goog-api-key" to key(Provider.GEMINI)), body,
        )
        return JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    // ---- Claude: Messages API, structured output -----------------------------------------------
    private fun claude(system: String, history: List<Pair<String, String>>): String {
        val messages = JSONArray()
        history.forEach { (role, text) ->
            messages.put(JSONObject().put("role", if (role == "model") "assistant" else "user").put("content", text))
        }
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray(listOf("dire", "etat", "module", "actions")))
            .put("properties", JSONObject()
                .put("dire", JSONObject().put("type", "string"))
                .put("etat", JSONObject().put("type", "string").put("enum", JSONArray(listOf("ECOUTE", "REFLEXION", "REPONSE", "ALERTE"))))
                .put("module", JSONObject().put("type", "string").put("enum", JSONArray(listOf("TACHES", "MOOD", "NOTES", "MEDS", "BUDGET", "AGENDA", "AUCUN"))))
                .put("actions", JSONObject().put("type", "array").put("items", JSONObject()
                    .put("type", "object").put("additionalProperties", false)
                    .put("required", JSONArray(listOf("type")))
                    .put("properties", JSONObject()
                        .put("type", JSONObject().put("type", "string"))
                        .put("texte", JSONObject().put("type", "string"))
                        .put("nom", JSONObject().put("type", "string"))
                        .put("avancement", JSONObject().put("type", "integer"))
                        .put("valeur", JSONObject().put("type", "integer"))
                        .put("note", JSONObject().put("type", "string"))
                        .put("pris", JSONObject().put("type", "boolean"))
                        .put("heure", JSONObject().put("type", "string"))
                        .put("montant", JSONObject().put("type", "number"))
                        .put("quoi", JSONObject().put("type", "string"))
                        .put("quand", JSONObject().put("type", "string"))))))
        val body = JSONObject()
            .put("model", model(Provider.CLAUDE))
            .put("max_tokens", 400)
            .put("system", system)
            .put("messages", messages)
            .put("output_config", JSONObject().put("format", JSONObject().put("type", "json_schema").put("schema", schema)))
        val text = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key(Provider.CLAUDE), "anthropic-version" to "2023-06-01"), body,
        )
        val r = JSONObject(text)
        if (r.optString("stop_reason") == "refusal") throw LlmException("Claude a refusé (${r.optJSONObject("stop_details")?.optString("category")})")
        val content = r.getJSONArray("content")
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") return b.getString("text")
        }
        throw LlmException("Claude : réponse vide")
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text)
            throw LlmException("${provider.name.lowercase()} $code : ${msg.take(160)}")
        }
        return text
    }

    private fun parse(raw: String): Reply {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = runCatching { JSONObject(cleaned) }.getOrElse { return Reply(cleaned.take(200), "REPONSE", "AUCUN", emptyList()) }
        val actions = ArrayList<JSONObject>()
        o.optJSONArray("actions")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let(actions::add) }
        return Reply(
            dire = o.optString("dire").ifBlank { "..." },
            etat = o.optString("etat", "REPONSE").uppercase(),
            module = o.optString("module", "AUCUN").uppercase(),
            actions = actions,
        )
    }
}
