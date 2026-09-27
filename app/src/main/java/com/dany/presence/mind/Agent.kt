package com.dany.presence.mind

import android.util.Log
import com.dany.presence.core.Bus
import com.dany.presence.core.Module
import com.dany.presence.core.Prosody
import com.dany.presence.core.Signal
import com.dany.presence.data.Modules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * The agent. A finished utterance that deserves it goes: gate → Thinking → context (clock + memory +
 * last notification + last 20 turns) → LLM → actions applied one by one (Executed) → Said (+ Alert).
 * Anything that breaks becomes Failed; the mic never depends on it.
 *
 * The mic is always open, so not every sound in the room is for it — see [worthAnswering].
 */
class Agent(private val bus: Bus, val llm: Llm, private val modules: Modules) {
    companion object {
        private const val TAG = "Présence/Agent"
        const val HOT_AFTER_REPLY = 25_000L
        const val HOT_AFTER_QUESTION = 45_000L
        private const val TURNS = 20
    }

    private val history = ArrayList<Pair<String, String>>()   // "user" / "model", oldest first
    private val queue = Channel<String>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val clock = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.CANADA_FRENCH)
    private val hm = SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH)

    @Volatile private var hotUntil = 0L
    /** Window to re-open once the voice has finished playing the last line (the body tells us via Speaking). */
    @Volatile private var pendingHot = 0L
    /** "dernière notification : …", read by the next LLM call only. */
    @Volatile private var lastNotification: String? = null

    /** True while the next utterance gets answered without a wake word. */
    val hot: Boolean get() = System.currentTimeMillis() < hotUntil

    /** Keeps the mic "addressed" for [ms]; never shortens a window already open. */
    fun openHotWindow(ms: Long) { hotUntil = max(hotUntil, System.currentTimeMillis() + ms) }

    fun start(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            bus.signals.collect { s ->
                when (s) {
                    is Signal.SpeechFinal -> queue.trySend(s.text)
                    is Signal.Notified -> lastNotification = "${s.app} — ${s.title} (${hm.format(Date())})"
                    // Something we said on our own initiative: the model must know it when the answer lands.
                    is Signal.Proactive -> { remember("model", "[initiative] ${s.text}"); openHotWindow(HOT_AFTER_QUESTION) }
                    is Signal.Speaking -> if (!s.on && pendingHot > 0) { openHotWindow(pendingHot); pendingHot = 0 }
                    else -> {}
                }
            }
        }
        for (text in queue) handle(text)
    }

    // ---- one utterance ------------------------------------------------------------------------

    private suspend fun handle(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val det = Intent.detect(text)
        // Background chatter: heard, sent nowhere, no trace on screen or in the sound.
        if (!worthAnswering(text, det)) return
        bus.emit(Signal.Thinking(llm.modelName))
        remember("user", text)
        try {
            val reply = withContext(Dispatchers.IO) {
                val ctx = buildString {
                    append("Date et heure : ").append(clock.format(Date())).append(".\n")
                    append(modules.context())
                    lastNotification?.let { append("Dernière notification : ").append(it).append(".\n") }
                }
                llm.ask(turns(), ctx)
            }
            remember("model", JSONObject().put("dire", reply.dire).put("prosodie", reply.prosodie.name).put("module", reply.module.name).toString())
            for (a in reply.actions) {
                val done = withContext(Dispatchers.IO) { modules.applyOne(a) } ?: continue
                bus.emit(Signal.Executed(done.module, done.type, done.summary))
            }
            bus.emit(Signal.Said(reply.dire, reply.prosodie))
            if (reply.prosodie == Prosody.ALERT) bus.emit(Signal.Alert(reply.dire))
            val window = if (reply.prosodie == Prosody.QUESTION) HOT_AFTER_QUESTION else HOT_AFTER_REPLY
            openHotWindow(window)
            pendingHot = window
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "agent", e)
            bus.emit(Signal.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Erreur : ${e.javaClass.simpleName}"))
        }
    }

    /**
     * What gets a reply: addressed by name, touching a module, a question, a real sentence (≥ 5 words),
     * or within the hot window after a reply (25 s) / a question (45 s). A two-word fragment with no intent — no.
     */
    fun worthAnswering(text: String, det: List<Detection> = Intent.detect(text)): Boolean =
        Intent.addressed(text) || det.isNotEmpty() || Intent.isQuestion(text) || Intent.words(text) >= 5 || hot

    // ---- memory of the exchange -------------------------------------------------------------

    private fun remember(role: String, text: String) = synchronized(history) {
        history.add(role to text)
        while (history.size > TURNS) history.removeAt(0)
    }

    private fun turns(): List<Pair<String, String>> = synchronized(history) { history.toList() }
}
