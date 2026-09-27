package com.dany.presence.mind

import android.content.Context
import android.util.Log
import com.dany.presence.core.Bus
import com.dany.presence.core.Module
import com.dany.presence.core.Prosody
import com.dany.presence.core.Signal
import com.dany.presence.ritual.Ritual as Alarms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The ritual, mind side. The exact alarms and the full-screen intent stay in ritual/Ritual.kt (re-armed
 * from [start]); the integrator emits [Signal.Ritual] when the activity is opened by the alarm intent,
 * and this turns it into the greeting: a Proactive line the body renders, HELLO prosody. The Agent
 * hears that Proactive and opens the hot window, so the next utterance is answered.
 */
class Ritual(private val context: Context, private val bus: Bus) {
    fun start(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { Alarms.scheduleAll(context) }.onFailure { Log.w("Présence/Ritual", "alarms", it) }
        bus.of<Signal.Ritual>().collect { r ->
            val module = if (r.mode == Alarms.MODE_MED) Module.MEDS else null
            bus.emit(Signal.Proactive(Alarms.greeting(r.mode), Prosody.HELLO, module))
        }
    }
}
