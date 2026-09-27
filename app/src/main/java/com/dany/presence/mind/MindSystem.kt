package com.dany.presence.mind

import android.content.Context
import com.dany.presence.core.Bus
import com.dany.presence.data.Modules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Entry point of the mind, wired by the integrator: `MindSystem(context, bus, scope)` then [start] on
 * resume and [stop] on pause. Listens: SpeechPartial, SpeechFinal, Notified, Ritual, Proactive, Speaking.
 * Emits: Captured, Thinking, Executed, Said, Alert, Failed, Proactive.
 *
 * Subscriptions are registered synchronously inside [start] (UNDISPATCHED), so a signal emitted right
 * after `start()` returns — e.g. `Signal.Ritual(mode)` from the alarm intent — is not lost.
 */
class MindSystem(context: Context, private val bus: Bus, private val scope: CoroutineScope) {
    private val app = context.applicationContext

    /** Provider / key / model settings for the hidden dialog: [Llm.provider], [Llm.key], [Llm.setKey], [Llm.model], [Llm.setModel]. */
    val llm = Llm(app)
    /** Room-backed memory: actions applied, context read back. */
    val modules = Modules(app)
    val agent = Agent(bus, llm, modules)
    private val intent = IntentWatcher(bus)
    private val proactive = Proactive(app, bus, modules)
    private val ritual = Ritual(app, bus)
    private var job: Job? = null

    val started: Boolean get() = job?.isActive == true

    fun start() {
        if (started) return
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            intent.start(this)
            agent.start(this)
            proactive.start(this)
            ritual.start(this)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Answer the next utterance no matter what (e.g. after the body played a greeting on its own). */
    fun hot(ms: Long = Agent.HOT_AFTER_QUESTION) = agent.openHotWindow(ms)
}
