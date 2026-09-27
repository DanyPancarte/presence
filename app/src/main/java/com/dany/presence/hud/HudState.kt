package com.dany.presence.hud

import com.dany.presence.core.Bus
import com.dany.presence.core.Module
import com.dany.presence.core.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The word under « Présence », top-left. */
enum class HudMode(val label: String) {
    VEILLE("veille"), ECOUTE("écoute"), ANALYSE("analyse"), EXECUTE("exécute"), ALERTE("alerte"), ERREUR("erreur"),
}

/** One line of the capture stack: module · word. */
data class Capture(val id: Long, val module: Module, val word: String)

/** Everything the HUD draws. Booleans drive fades; text stays until replaced. */
data class HudState(
    val mode: HudMode = HudMode.VEILLE,
    val captures: List<Capture> = emptyList(),
    val capturesOn: Boolean = true,
    val said: String = "",
    val saidOn: Boolean = false,
    val failed: String = "",
    val failedOn: Boolean = false,
)

/** Short French label of a module for the capture stack. */
fun Module.hudLabel(): String = when (this) {
    Module.AUCUN -> "—"
    Module.TACHES -> "tâches"
    Module.MOOD -> "mood"
    Module.NOTES -> "notes"
    Module.MEDS -> "méds"
    Module.BUDGET -> "budget"
    Module.AGENDA -> "agenda"
}

/**
 * Folds bus signals into a HudState and owns every timeout back to veille.
 * Runs on the given scope (the composition's main-thread scope), so no locking is needed.
 */
class HudModel(bus: Bus, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(HudState())
    val state: StateFlow<HudState> = _state

    private var modeJob: Job? = null
    private var capsJob: Job? = null
    private var sayJob: Job? = null
    private var failJob: Job? = null
    private var utteranceOpen = false
    private var nextId = 0L

    init { scope.launch { bus.signals.collect { on(it) } } }

    private fun on(s: Signal) {
        when (s) {
            Signal.SpeechStart -> { utteranceOpen = true; capsJob?.cancel(); mode(HudMode.ECOUTE) }
            is Signal.SpeechPartial -> { utteranceOpen = true; if (_state.value.mode == HudMode.VEILLE) mode(HudMode.ECOUTE) }
            is Signal.SpeechFinal -> { utteranceOpen = false; mode(HudMode.ECOUTE, 2_500); scheduleCaptureFade() }
            Signal.SpeechIdle -> { utteranceOpen = false; if (_state.value.mode == HudMode.ECOUTE) mode(HudMode.VEILLE); scheduleCaptureFade() }
            is Signal.Captured -> capture(s)
            is Signal.Thinking -> mode(HudMode.ANALYSE, 20_000)
            is Signal.Executed -> mode(HudMode.EXECUTE, 3_000)
            is Signal.Said -> { say(s.text); if (_state.value.mode != HudMode.ALERTE && _state.value.mode != HudMode.ERREUR) mode(_state.value.mode, 1_500) }
            is Signal.Proactive -> say(s.text)
            is Signal.Alert -> mode(HudMode.ALERTE, 8_000)
            is Signal.Failed -> { mode(HudMode.ERREUR, 8_000); fail(s.message) }
            else -> Unit
        }
    }

    /** Set the mode; after [backToVeilleMs] (if given) return to veille unless something else happened. */
    private fun mode(m: HudMode, backToVeilleMs: Long? = null) {
        modeJob?.cancel()
        _state.update { it.copy(mode = m) }
        if (backToVeilleMs != null) modeJob = scope.launch {
            delay(backToVeilleMs)
            _state.update { if (it.mode == m) it.copy(mode = HudMode.VEILLE) else it }
        }
    }

    private fun capture(s: Signal.Captured) {
        capsJob?.cancel()
        val c = Capture(nextId++, s.module, s.word)
        _state.update { st ->
            val base = if (st.capturesOn) st.captures else emptyList()
            st.copy(captures = (base + c).takeLast(4), capturesOn = true)
        }
        if (!utteranceOpen) scheduleCaptureFade()
    }

    /** The stack fades 6 s after the utterance ends, then clears. */
    private fun scheduleCaptureFade() {
        capsJob?.cancel()
        if (_state.value.captures.isEmpty()) return
        capsJob = scope.launch {
            delay(6_000)
            _state.update { it.copy(capturesOn = false) }
            delay(600)
            _state.update { if (!it.capturesOn) it.copy(captures = emptyList(), capturesOn = true) else it }
        }
    }

    private fun say(text: String) {
        sayJob?.cancel()
        _state.update { it.copy(said = text, saidOn = text.isNotBlank()) }
        sayJob = scope.launch { delay(8_000); _state.update { it.copy(saidOn = false) } }
    }

    private fun fail(message: String) {
        failJob?.cancel()
        _state.update { it.copy(failed = message, failedOn = message.isNotBlank()) }
        failJob = scope.launch { delay(8_000); _state.update { it.copy(failedOn = false) } }
    }
}
