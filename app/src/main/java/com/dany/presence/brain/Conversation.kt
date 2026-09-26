package com.dany.presence.brain

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dany.presence.render.Mood
import com.dany.presence.render.SphereState
import com.dany.presence.voice.Speech
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The loop: ÉCOUTE (STT) → RÉFLEXION (Gemini) → RÉPONSE (TTS). Drives the organism's mood and the
 * one-line caption. Actions returned by the agent go to [onActions] (persisted by the modules).
 */
class Conversation(
    context: Context,
    private val state: SphereState,
    private val speech: Speech,
    private val onLine: (String) -> Unit,
) {
    val gemini = GeminiClient(context)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val history = ArrayList<Pair<String, String>>()
    var onActions: (List<JSONObject>) -> Unit = {}
    /** True while the recognizer owns the microphone (the ambient analyser must let go of it). */
    var onListening: (Boolean) -> Unit = {}
    var contextProvider: () -> String = { "" }
    @Volatile var busy = false
        private set

    init {
        speech.onLevel = { db ->
            // The recognizer owns the mic while listening: feed its level to the organism.
            val a = ((db + 2f) / 12f).coerceIn(0f, 1f)
            if (a > state.amp) state.amp = a else state.amp += (a - state.amp) * 0.15f
        }
    }

    /** Tap on the sphere. */
    fun toggle() {
        if (busy) { cancel(); return }
        listen()
    }

    fun listen() {
        if (!speech.available) { onLine("Reconnaissance vocale indisponible"); return }
        busy = true
        speech.stopSpeaking()
        state.mood = Mood.ECOUTE
        onLine("")
        onListening(true)
        speech.listen(
            onResult = { heard -> onListening(false); think(heard) },
            onError = { msg ->
                onListening(false)
                busy = false
                state.mood = Mood.VEILLE
                if (msg.isNotEmpty()) onLine(msg)
            },
        )
    }

    private fun think(heard: String) {
        state.mood = Mood.REFLEXION
        onLine(heard)
        history.add("user" to heard)
        while (history.size > 20) history.removeAt(0)
        io.execute {
            val reply = try {
                gemini.ask(history, timeContext() + "\n" + contextProvider())
            } catch (e: Exception) {
                main.post {
                    busy = false
                    state.mood = Mood.ALERTE
                    onLine(e.message ?: "Erreur")
                    main.postDelayed({ if (!busy) state.mood = Mood.VEILLE }, 2500)
                }
                return@execute
            }
            history.add("model" to JSONObject().put("dire", reply.dire).put("etat", reply.etat).put("module", reply.module).toString())
            main.post { answer(reply) }
        }
    }

    private fun answer(reply: Reply) {
        state.mood = if (reply.etat == "ALERTE") Mood.ALERTE else Mood.REPONSE
        onLine(reply.dire)
        if (reply.actions.isNotEmpty()) onActions(reply.actions)
        speech.speak(reply.dire) {
            main.post {
                if (reply.etat == "ECOUTE") listen()
                else {
                    busy = false
                    state.mood = Mood.VEILLE
                    main.postDelayed({ if (!busy) onLine("") }, 6000)
                }
            }
        }
    }

    fun cancel() {
        speech.stopListening()
        onListening(false)
        speech.stopSpeaking()
        busy = false
        state.mood = Mood.VEILLE
        onLine("")
    }

    private fun timeContext(): String {
        val f = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.CANADA_FRENCH)
        return "Date et heure : ${f.format(Date())}."
    }
}
