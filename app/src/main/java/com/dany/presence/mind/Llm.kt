package com.dany.presence.mind

import android.content.Context
import com.dany.presence.BuildConfig
import com.dany.presence.core.Module
import com.dany.presence.core.Prosody
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One structured turn from the agent: the line, how the voice says it, the module touched, the actions to apply. */
data class Reply(val dire: String, val prosodie: Prosody, val module: Module, val actions: List<JSONObject>)

class LlmException(msg: String) : Exception(msg)

enum class Provider(val label: String, val defaultModel: String) {
    GEMINI("Gemini (palier gratuit)", "gemini-3.8-flash"),
    CLAUDE("Claude (payant)", "claude-haiku-4-5"),
}

/**
 * The brain, provider-agnostic. Raw HTTPS + org.json: no SDK, nothing else to ship in the APK.
 * Keys live in SharedPreferences (hidden settings) or local.properties → BuildConfig; never committed.
 * Settings API used by the integrator's dialog: [provider], [key]/[setKey], [model]/[setModel].
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
        fun body(thinking: Boolean) = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.9)
                .put("maxOutputTokens", 600)
                .also { if (thinking) it.put("thinkingConfig", JSONObject().put("thinkingLevel", "low")) })
        val headers = mapOf("x-goog-api-key" to key(Provider.GEMINI))
        fun call(model: String, thinking: Boolean) =
            post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", headers, body(thinking))
        var model = model(Provider.GEMINI)
        val text = try {
            call(model, true)
        } catch (e: LlmException) {
            val m = e.message.orEmpty().lowercase()
            when {
                // Model gone or not allowed for this key: ask the API what this key can use, once.
                "not found" in m || "not supported" in m || "404" in m || "403" in m -> {
                    model = discoverGemini() ?: throw e
                    setModel(Provider.GEMINI, model)
                    runCatching { call(model, true) }.getOrElse { call(model, false) }
                }
                "thinking" in m -> call(model, false)
                else -> throw e
            }
        }
        return JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    /** Newest general-purpose flash model this key can call (GET /models). */
    private fun discoverGemini(): String? {
        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpURLConnection).apply {
            setRequestProperty("x-goog-api-key", key(Provider.GEMINI)); connectTimeout = 10_000; readTimeout = 15_000
        }
        if (conn.responseCode !in 200..299) return null
        val models = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("models") ?: return null
        val bad = listOf("lite", "image", "tts", "live", "transcribe", "embed", "preview", "exp", "thinking", "8b", "audio", "vision", "computer")
        var best: String? = null; var bestV = -1.0
        for (i in 0 until models.length()) {
            val o = models.getJSONObject(i)
            val name = o.optString("name").removePrefix("models/")
            val methods = o.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            if ("generateContent" !in methods || !name.startsWith("gemini-") || !name.contains("flash")) continue
            if (bad.any { name.contains(it) }) continue
            val v = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (v > bestV) { bestV = v; best = name }
        }
        return best
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
            .put("required", JSONArray(listOf("dire", "prosodie", "module", "actions")))
            .put("properties", JSONObject()
                .put("dire", JSONObject().put("type", "string"))
                .put("prosodie", JSONObject().put("type", "string").put("enum", JSONArray(Prosody.entries.map { it.name })))
                .put("module", JSONObject().put("type", "string").put("enum", JSONArray(Module.entries.map { it.name })))
                .put("actions", JSONObject().put("type", "array").put("items", JSONObject()
                    .put("type", "object").put("additionalProperties", false)
                    .put("required", JSONArray(listOf("type")))
                    .put("properties", JSONObject()
                        .put("type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("note", "tache", "tache_fini", "mood", "med", "depense", "agenda", "question_ouverte", "fermer_question"))))
                        .put("texte", JSONObject().put("type", "string"))
                        .put("nom", JSONObject().put("type", "string"))
                        .put("avancement", JSONObject().put("type", "integer"))
                        .put("valeur", JSONObject().put("type", "integer"))
                        .put("note", JSONObject().put("type", "string"))
                        .put("pris", JSONObject().put("type", "boolean"))
                        .put("heure", JSONObject().put("type", "string"))
                        .put("montant", JSONObject().put("type", "number"))
                        .put("quoi", JSONObject().put("type", "string"))
                        .put("quand", JSONObject().put("type", "string"))
                        .put("id", JSONObject().put("type", "integer"))))))
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

    // ---- Parsing: tolerant, never throws on a sloppy model ---------------------------------------
    private fun parse(raw: String): Reply {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = runCatching { JSONObject(cleaned) }.getOrElse { return Reply(cleaned.take(200), Prosody.CONFIRM, Module.AUCUN, emptyList()) }
        val actions = ArrayList<JSONObject>()
        o.optJSONArray("actions")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let(actions::add) }
        val dire = o.optString("dire").ifBlank { "..." }
        return Reply(
            dire = dire,
            prosodie = prosody(o, dire, actions),
            module = Module.entries.firstOrNull { it.name == o.optString("module").uppercase() } ?: Module.AUCUN,
            actions = actions,
        )
    }

    /** "prosodie" as asked; else the legacy "etat"; else a guess from the line itself. */
    private fun prosody(o: JSONObject, dire: String, actions: List<JSONObject>): Prosody {
        val p = o.optString("prosodie").uppercase()
        Prosody.entries.firstOrNull { it.name == p }?.let { return it }
        return when (o.optString("etat").uppercase()) {
            "ALERTE" -> Prosody.ALERT
            "ECOUTE" -> Prosody.QUESTION
            else -> when {
                dire.trimEnd().endsWith("?") -> Prosody.QUESTION
                actions.isNotEmpty() -> Prosody.NOTED
                else -> Prosody.CONFIRM
            }
        }
    }
}
