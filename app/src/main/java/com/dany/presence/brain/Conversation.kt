package com.dany.presence.brain

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dany.presence.data.Module
import com.dany.presence.data.Modules
import com.dany.presence.render.HoloState
import com.dany.presence.render.Mood
import com.dany.presence.voice.Speech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the overlay layer shows. Observed by Compose. */
data class Scene(
    val mood: Mood = Mood.VEILLE,
    val heard: String = "",          // live transcript while listening / what was understood
    val say: String = "",            // caption while answering
    val module: Module = Module.AUCUN,
    val moduleOp: String = "",       // e.g. "+ création", "mise à jour"
    val error: String = "",
    val thinking: List<String> = emptyList(),
)

/**
 * The loop: ÉCOUTE (STT) → RÉFLEXION (Gemini) → RÉPONSE (TTS), with the module overlays.
 */
class Conversation(
    context: Context,
    private val state: HoloState,
    private val speech: Speech,
    private val modules: Modules,
    private val onScene: (Scene) -> Unit,
) {
    val gemini = GeminiClient(context)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history = ArrayList<Pair<String, String>>()
    var onListening: (Boolean) -> Unit = {}
    @Volatile var busy = false
        private set
    private var scene = Scene()
    private var clearJob: Runnable? = null

    init {
        speech.onLevel = { db ->
            val a = ((db + 2f) / 12f).coerceIn(0f, 1f)
            if (a > state.amp) state.amp = a else state.amp += (a - state.amp) * 0.15f
            // No FFT while the recognizer owns the mic: fake a plausible EQ from the level.
            val t = System.nanoTime() / 1e9
            val b = FloatArray(24) { i -> (a * (0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(t * (2 + i * 0.37) + i)).toFloat()) * (1 - i / 40f)).coerceIn(0f, 1f) }
            state.bands = b
        }
        speech.onPartial = { txt -> set(scene.copy(heard = txt)) }
    }

    private fun set(s: Scene) { scene = s; onScene(s) }
    private fun mood(m: Mood) { state.mood = m; set(scene.copy(mood = m)) }

    /** Tap on the hologram. */
    fun toggle() { if (busy) cancel() else listen() }

    fun listen(greeting: String? = null) {
        if (!speech.available) { set(scene.copy(error = "Reconnaissance vocale indisponible")); return }
        busy = true
        clearJob?.let(main::removeCallbacks)
        speech.stopSpeaking()
        set(Scene(mood = Mood.ECOUTE))
        state.mood = Mood.ECOUTE
        val start = {
            onListening(true)
            speech.listen(
                onResult = { heard -> onListening(false); think(heard) },
                onError = { msg ->
                    onListening(false)
                    busy = false
                    mood(Mood.VEILLE)
                    set(scene.copy(error = msg))
                    if (msg.isNotEmpty()) scheduleClear(4000)
                },
            )
        }
        if (greeting != null) speech.speak(greeting) { main.post { start() } } else start()
    }

    private fun think(heard: String) {
        mood(Mood.REFLEXION)
        set(scene.copy(heard = heard, thinking = listOf("> contexte…")))
        history.add("user" to heard)
        while (history.size > 20) history.removeAt(0)
        scope.launch {
            val ctx = withContext(Dispatchers.IO) { timeContext() + "\n" + modules.context() }
            set(scene.copy(thinking = scene.thinking + "> ${gemini.model}"))
            val reply = try {
                withContext(Dispatchers.IO) { gemini.ask(history, ctx) }
            } catch (e: Exception) {
                busy = false
                mood(Mood.ALERTE)
                set(scene.copy(error = e.message ?: "Erreur", thinking = emptyList()))
                main.postDelayed({ if (!busy) mood(Mood.VEILLE) }, 2500)
                scheduleClear(6000)
                return@launch
            }
            history.add("model" to JSONObject().put("dire", reply.dire).put("etat", reply.etat).put("module", reply.module).toString())
            val applied = withContext(Dispatchers.IO) { modules.apply(reply.actions) }
            val module = if (applied != Module.AUCUN) applied else runCatching { Module.valueOf(reply.module) }.getOrDefault(Module.AUCUN)
            val op = when {
                reply.actions.any { it.optString("type") == "tache" } -> "+ mise à jour"
                reply.actions.isNotEmpty() -> "+ enregistré"
                else -> "contexte"
            }
            answer(reply, module, op)
        }
    }

    private fun answer(reply: Reply, module: Module, op: String) {
        val m = if (reply.etat == "ALERTE") Mood.ALERTE else Mood.REPONSE
        state.mood = m
        set(scene.copy(mood = m, say = reply.dire, module = module, moduleOp = op, thinking = emptyList()))
        speech.speak(reply.dire) {
            main.post {
                if (reply.etat == "ECOUTE") listen()
                else {
                    busy = false
                    mood(Mood.VEILLE)
                    scheduleClear(7000)
                }
            }
        }
    }

    private fun scheduleClear(ms: Long) {
        clearJob?.let(main::removeCallbacks)
        val r = Runnable { if (!busy) set(Scene()) }
        clearJob = r
        main.postDelayed(r, ms)
    }

    fun cancel() {
        speech.stopListening()
        speech.stopSpeaking()
        onListening(false)
        busy = false
        state.mood = Mood.VEILLE
        set(Scene())
    }

    /** Vertical swipe: peek at a module without talking. */
    fun peek(module: Module) {
        if (busy) return
        set(Scene(mood = Mood.VEILLE, module = module, moduleOp = "aperçu"))
        scheduleClear(6000)
    }

    private fun timeContext(): String {
        val f = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.CANADA_FRENCH)
        return "Date et heure : ${f.format(Date())}."
    }
}
