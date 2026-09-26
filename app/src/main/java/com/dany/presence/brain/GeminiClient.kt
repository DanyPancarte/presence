package com.dany.presence.brain

import android.content.Context
import com.dany.presence.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One structured turn from the agent. */
data class Reply(val dire: String, val etat: String, val module: String, val actions: List<JSONObject>)

class GeminiException(msg: String) : Exception(msg)

/**
 * Minimal REST client for Gemini (generateContent), JSON mode. No SDK: one HTTPS call, org.json.
 * Key: hidden settings (long press) overrides local.properties → BuildConfig.
 */
class GeminiClient(context: Context) {
    private val prefs = context.getSharedPreferences("presence", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("gemini_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_API_KEY
        set(v) { prefs.edit().putString("gemini_key", v.trim()).apply() }

    var model: String
        get() = prefs.getString("gemini_model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(v) { prefs.edit().putString("gemini_model", v.trim()).apply() }

    val configured get() = apiKey.isNotBlank()

    /** Blocking; call off the main thread. [history] alternates user/model turns, oldest first. */
    fun ask(history: List<Pair<String, String>>, context: String): Reply {
        if (!configured) throw GeminiException("Pas de clé API. Appui long 3 s pour la saisir.")
        val contents = JSONArray()
        history.forEach { (role, text) ->
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Persona.SYSTEM + "\n\nContexte du moment :\n" + context))))
            .put("contents", contents)
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.9)
                .put("maxOutputTokens", 400)
                .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text)
            throw GeminiException("Gemini $code : ${msg.take(160)}")
        }
        val raw = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return parse(raw)
    }

    private fun parse(raw: String): Reply {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = runCatching { JSONObject(cleaned) }.getOrElse {
            // Model broke the contract: still speak something.
            return Reply(cleaned.take(200), "REPONSE", "AUCUN", emptyList())
        }
        val actions = ArrayList<JSONObject>()
        o.optJSONArray("actions")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let(actions::add) }
        return Reply(
            dire = o.optString("dire").ifBlank { "..." },
            etat = o.optString("etat", "REPONSE").uppercase(),
            module = o.optString("module", "AUCUN").uppercase(),
            actions = actions,
        )
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}
