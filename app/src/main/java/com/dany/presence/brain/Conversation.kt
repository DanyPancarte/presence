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

/** One line of the status strip: what the system is doing right now. */
data class Step(val label: String, val detail: String = "", val done: Boolean = false)

/** What the overlay layer shows. Observed by Compose. */
data class Scene(
    val mood: Mood = Mood.VEILLE,
    val heard: String = "",
    val say: String = "",
    val module: Module = Module.AUCUN,
    val moduleOp: String = "",
    val detections: List<Detection> = emptyList(),
    val steps: List<Step> = emptyList(),
    val error: String = "",
)

/**
 * Hands-free loop. The mic is always open (on-device recognizer, restarted after every result or
 * timeout). VEILLE while nobody talks; the first syllable flips to ÉCOUTE; a finished utterance
 * that deserves it goes RÉFLEXION → RÉPONSE (radio voice), then the mic reopens. Tap = interrupt.
 */
class Conversation(
    context: Context,
    private val state: HoloState,
    private val speech: Speech,
    private val modules: Modules,
    private val onScene: (Scene) -> Unit,
) {
    val llm = Llm(context)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history = ArrayList<Pair<String, String>>()
    var onListening: (Boolean) -> Unit = {}
    /** Continuous listening on/off (off while the activity is paused). */
    @Volatile var awake = false
        private set
    /** True from the first syllable until the reply is spoken. */
    @Volatile var busy = false
        private set
    private var scene = Scene()
    private var clearJob: Runnable? = null
    private var meter: Runnable? = null
    private var restart: Runnable? = null
    private var hotUntil = 0L
    private var speaking = false

    init {
        speech.onLevel = { db ->
            val a = ((db + 2f) / 12f).coerceIn(0f, 1f)
            if (a > state.amp) state.amp = a else state.amp += (a - state.amp) * 0.15f
            val t = System.nanoTime() / 1e9
            state.bands = FloatArray(24) { i -> (a * (0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(t * (2 + i * 0.37) + i)).toFloat()) * (1 - i / 40f)).coerceIn(0f, 1f) }
        }
        speech.onBegin = { if (!busy) { busy = true; mood(Mood.ECOUTE); step("écoute", "voix détectée") } }
        speech.onPartial = { txt ->
            if (!busy) { busy = true; mood(Mood.ECOUTE) }
            val det = Intent.detect(txt)
            det.firstOrNull()?.let { state.accentT = ModuleColor.rgb(it.module) }
            set(scene.copy(heard = txt, detections = det, module = det.firstOrNull()?.module ?: Module.AUCUN, moduleOp = det.firstOrNull()?.op ?: ""))
        }
    }

    private fun set(s: Scene) { scene = s; onScene(s) }
    private fun mood(m: Mood) { state.mood = m; set(scene.copy(mood = m)) }
    private fun step(label: String, detail: String = "", done: Boolean = false) {
        val steps = scene.steps.map { it.copy(done = true) } + Step(label, detail, done)
        set(scene.copy(steps = steps.takeLast(4)))
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /** Opens the mic for good. Called on resume (after the permission). */
    fun wake(greeting: String? = null) {
        awake = true
        if (greeting != null) { busy = true; mood(Mood.REPONSE); set(scene.copy(say = greeting)); step("rituel", "voix"); speakWithMeter(greeting) { busy = false; openMic() } }
        else openMic()
    }

    fun sleep() {
        awake = false
        restart?.let(main::removeCallbacks)
        speech.stopListening()
        speech.stopSpeaking()
        meter?.let(main::removeCallbacks); meter = null
        onListening(false)
        busy = false
        state.mood = Mood.VEILLE
        state.amp = 0f
        set(Scene())
    }

    /** Tap: interrupt whatever it is doing and reopen the mic. */
    fun toggle() {
        if (busy) {
            speech.stopSpeaking()
            meter?.let(main::removeCallbacks); meter = null
            busy = false
            mood(Mood.VEILLE)
            set(Scene(steps = listOf(Step("interrompu", "", true))))
            openMic()
        } else {
            step("micro", "ouvert")
        }
    }

    private fun openMic(delay: Long = 250) {
        restart?.let(main::removeCallbacks)
        val r = Runnable {
            if (!awake || speaking) return@Runnable
            if (!speech.available) { set(scene.copy(error = "Reconnaissance vocale indisponible")); return@Runnable }
            state.accentT = ModuleColor.rgb(Module.AUCUN)
            if (!busy) { state.mood = Mood.VEILLE; set(Scene(mood = Mood.VEILLE, steps = listOf(Step("veille", "micro ouvert")))) }
            onListening(true)
            speech.listen(
                onResult = { heard -> onListening(false); onHeard(heard) },
                onError = { msg ->
                    onListening(false)
                    busy = false
                    state.mood = Mood.VEILLE
                    if (msg.isNotEmpty()) { set(scene.copy(mood = Mood.VEILLE, error = msg)); scheduleClear(4000) }
                    else set(scene.copy(mood = Mood.VEILLE, heard = "", detections = emptyList(), module = Module.AUCUN))
                    // Nothing said (or a busy recognizer): reopen, a bit slower after real errors.
                    openMic(if (msg.isEmpty()) 300 else 1500)
                },
            )
        }
        restart = r
        main.postDelayed(r, delay)
    }

    // ---- an utterance landed ----------------------------------------------------------------

    private fun onHeard(heard: String) {
        val det = Intent.detect(heard)
        if (!worthAnswering(heard, det)) {
            // Background chatter: shown briefly, not sent anywhere.
            busy = false
            set(scene.copy(heard = heard, detections = emptyList(), module = Module.AUCUN, steps = listOf(Step("ignoré", "\"${heard.take(30)}\"", true))))
            openMic(200)
            return
        }
        busy = true
        mood(Mood.REFLEXION)
        set(scene.copy(heard = heard, detections = det, module = det.firstOrNull()?.module ?: scene.module))
        step("capté", "\"${heard.take(40)}\"", true)
        step("analyse", llm.modelName)
        history.add("user" to heard)
        while (history.size > 20) history.removeAt(0)
        scope.launch {
            val ctx = withContext(Dispatchers.IO) { timeContext() + "\n" + modules.context() }
            step("contexte", "mémoire lue", true)
            step("analyse", llm.modelName)
            val reply = try {
                withContext(Dispatchers.IO) { llm.ask(history, ctx) }
            } catch (e: Exception) {
                busy = false
                mood(Mood.ALERTE)
                set(scene.copy(error = e.message ?: "Erreur", steps = scene.steps + Step("échec", "", true)))
                main.postDelayed({ if (!busy) state.mood = Mood.VEILLE }, 2500)
                scheduleClear(7000)
                openMic(600)
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
            if (reply.actions.isNotEmpty()) step("exécute", reply.actions.joinToString(" · ") { it.optString("type") }, true)
            answer(reply, module, op)
        }
    }

    /**
     * What gets a reply. It's an always-open mic, so not every sound in the room is for it:
     * addressed by name, touching a module, a question, a real sentence, or within 25 s of
     * the last exchange — yes. A two-word fragment with no intent — no.
     */
    private fun worthAnswering(text: String, det: List<Detection>): Boolean {
        val t = text.lowercase()
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        return t.contains("présence") || t.contains("presence") || det.isNotEmpty() ||
            t.endsWith("?") || Regex("\\b(est-ce|c'est quoi|combien|pourquoi|comment|quand|qu'est)").containsMatchIn(t) ||
            words >= 5 || System.currentTimeMillis() < hotUntil
    }

    private fun answer(reply: Reply, module: Module, op: String) {
        val m = if (reply.etat == "ALERTE") Mood.ALERTE else Mood.REPONSE
        state.mood = m
        state.accentT = ModuleColor.rgb(if (m == Mood.ALERTE) null else module)
        set(scene.copy(mood = m, say = reply.dire, module = module, moduleOp = op, detections = emptyList()))
        step("voix", "radio")
        speakWithMeter(reply.dire) {
            hotUntil = System.currentTimeMillis() + (if (reply.etat == "ECOUTE") 45_000 else 25_000)
            busy = false
            state.mood = Mood.VEILLE
            set(scene.copy(mood = Mood.VEILLE))
            step("prêt", if (reply.etat == "ECOUTE") "j'attends ta réponse" else "", true)
            scheduleClear(7000)
            openMic(150)
        }
    }

    /** Speaks through the radio (mic closed meanwhile) and feeds the output level to the hologram. */
    private fun speakWithMeter(text: String, then: () -> Unit) {
        speaking = true
        restart?.let(main::removeCallbacks)
        speech.stopListening()
        onListening(false)
        meter?.let(main::removeCallbacks)
        val r = object : Runnable {
            override fun run() {
                val l = speech.radio.level
                state.amp = l
                state.bands = FloatArray(24) { i -> (l * (0.4f + 0.6f * kotlin.math.abs(kotlin.math.sin((System.nanoTime() / 1e9) * (3 + i * 0.5) + i)).toFloat())).coerceIn(0f, 1f) }
                main.postDelayed(this, 33)
            }
        }
        meter = r
        main.post(r)
        speech.speak(text) {
            main.post {
                meter?.let(main::removeCallbacks); meter = null
                state.amp = 0f
                speaking = false
                then()
            }
        }
    }

    private fun scheduleClear(ms: Long) {
        clearJob?.let(main::removeCallbacks)
        val r = Runnable { if (!busy) { set(Scene(steps = listOf(Step("veille", "micro ouvert")))); state.accentT = ModuleColor.rgb(Module.AUCUN) } }
        clearJob = r
        main.postDelayed(r, ms)
    }

    /** Vertical swipe: peek at a module without talking. */
    fun peek(module: Module) {
        if (busy) return
        state.accentT = ModuleColor.rgb(module)
        set(scene.copy(module = module, moduleOp = "aperçu", steps = listOf(Step("aperçu", module.name.lowercase(), true))))
        scheduleClear(6000)
    }

    private fun timeContext(): String {
        val f = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.CANADA_FRENCH)
        return "Date et heure : ${f.format(Date())}."
    }
}

/** One colour per module — on the tooltips and on the hologram's accent particles. */
object ModuleColor {
    fun rgb(m: Module?): FloatArray = when (m) {
        Module.TACHES -> floatArrayOf(0.99f, 0.93f, 0.04f)
        Module.NOTES -> floatArrayOf(0.0f, 0.94f, 1.0f)
        Module.MOOD -> floatArrayOf(1.0f, 0.16f, 0.63f)
        Module.MEDS -> floatArrayOf(0.22f, 1.0f, 0.36f)
        Module.BUDGET -> floatArrayOf(1.0f, 0.54f, 0.0f)
        Module.AGENDA -> floatArrayOf(0.55f, 0.42f, 1.0f)
        null -> floatArrayOf(1.0f, 0.0f, 0.24f)
        else -> floatArrayOf(0.99f, 0.93f, 0.04f)
    }
}
