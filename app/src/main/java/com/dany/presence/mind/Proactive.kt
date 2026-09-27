package com.dany.presence.mind

import android.content.Context
import android.util.Log
import com.dany.presence.core.Bus
import com.dany.presence.core.Module
import com.dany.presence.core.Prosody
import com.dany.presence.core.Signal
import com.dany.presence.data.Modules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * The initiative loop. Every minute while started, when Dany has been silent for 10 min and nothing
 * proactive was said in the last 2 h (persisted, survives restarts), one line — at most — about:
 *  1. the medication not confirmed after 09:00,
 *  2. a 90–99 % task untouched for 3 days (the 97 % that sleeps),
 *  3. an open question older than 24 h.
 * Never on Saturday. The Agent hears the Proactive signal too: it remembers the line and opens a hot window.
 */
class Proactive(context: Context, private val bus: Bus, private val modules: Modules) {
    companion object {
        private const val TAG = "Présence/Proactive"
        const val TICK = 60_000L
        const val SILENCE = 10 * 60_000L
        const val COOLDOWN = 2 * 3_600_000L
        const val TASK_ASLEEP = 3 * 86_400_000L
        const val QUESTION_STALE = 24 * 3_600_000L
        private const val KEY_LAST = "proactive_last"
    }

    private val prefs = context.getSharedPreferences("presence", Context.MODE_PRIVATE)
    @Volatile private var lastUserAt = System.currentTimeMillis()
    private var lastProactiveAt: Long
        get() = prefs.getLong(KEY_LAST, 0L)
        set(v) { prefs.edit().putLong(KEY_LAST, v).apply() }

    fun start(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        lastUserAt = System.currentTimeMillis()
        launch(start = CoroutineStart.UNDISPATCHED) {
            bus.signals.collect { s ->
                when (s) {
                    is Signal.SpeechStart, is Signal.SpeechPartial, is Signal.SpeechFinal -> lastUserAt = System.currentTimeMillis()
                    is Signal.Proactive -> lastProactiveAt = System.currentTimeMillis()   // the ritual counts too
                    else -> {}
                }
            }
        }
        while (isActive) {
            delay(TICK)
            try { tick() } catch (e: Exception) { Log.w(TAG, "tick", e) }
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) return
        if (now - lastUserAt < SILENCE) return
        if (now - lastProactiveAt < COOLDOWN) return
        val line = candidate(now, cal) ?: return
        lastProactiveAt = now
        bus.emit(line)
    }

    /** The line it would say right now, or null. Pure read; nothing is emitted. */
    suspend fun candidate(now: Long = System.currentTimeMillis(), cal: Calendar = Calendar.getInstance()): Signal.Proactive? {
        val dao = modules.dao
        if (cal.get(Calendar.HOUR_OF_DAY) >= 9 && dao.med(modules.today())?.prisA == null)
            return Signal.Proactive("Médicament. Pris ?", Prosody.QUESTION, Module.MEDS)
        dao.tasksNow().firstOrNull { it.avancement in 90..99 && now - it.touche >= TASK_ASLEEP }?.let {
            return Signal.Proactive("${it.nom}. Encore à ${it.avancement} %. Il reste quoi, vraiment ?", Prosody.QUESTION, Module.TACHES)
        }
        dao.openQuestions().firstOrNull { now - it.quand >= QUESTION_STALE }?.let {
            return Signal.Proactive("Tu m'avais pas répondu : ${it.texte}", Prosody.QUESTION, null)
        }
        return null
    }
}
