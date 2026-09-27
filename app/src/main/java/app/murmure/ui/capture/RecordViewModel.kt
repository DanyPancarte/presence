package app.murmure.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmure.MurmureApp
import app.murmure.ai.LocalBrain
import app.murmure.voice.Phase
import app.murmure.voice.RecordingService
import app.murmure.voice.VoiceSession
import app.murmure.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveUi(
    val voice: VoiceState = VoiceState(),
    val levels: List<Float> = List(42) { 0f },
    val hits: List<LocalBrain.Hit> = emptyList(),
    val declaredFolder: String? = null,
    val elapsedSec: Int = 0,
    val saving: Boolean = false,
    val savedId: String? = null,
    val mood: Int? = null,
    val started: Boolean = false,
)

class RecordViewModel : ViewModel() {
    private val app = MurmureApp.instance
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui.asStateFlow()

    private var session: VoiceSession? = null
    private var terms: Map<String, String> = emptyMap()
    private val aiTerms = LinkedHashMap<String, String>()
    private var folders: List<String> = emptyList()
    private var aiJob: Job? = null
    private var lastAiLen = 0
    var daily = false

    init {
        viewModelScope.launch {
            terms = app.repo.knownTerms()
            folders = app.repo.dao.folders().map { it.name }
        }
    }

    fun setMood(m: Int?) = _ui.update { it.copy(mood = m) }

    fun start() {
        if (_ui.value.started) return
        _ui.update { it.copy(started = true) }
        val s = VoiceSession(app, app.settings.current, app.client, viewModelScope)
        session = s
        RecordingService.start(app)
        s.start()
        viewModelScope.launch {
            s.state.collect { v ->
                val text = v.text
                val changed = text != _ui.value.voice.text
                _ui.update { u ->
                    u.copy(
                        voice = v,
                        levels = (u.levels.drop(1) + v.level),
                        hits = if (changed) LocalBrain.highlight(text, terms + aiTerms) else u.hits,
                        declaredFolder = u.declaredFolder ?: if (changed) LocalBrain.declaredFolder(text, folders) else null,
                    )
                }
                if (changed) maybeAskAi(text)
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(250)
                val v = _ui.value.voice
                if (v.phase == Phase.DONE || v.phase == Phase.FINALIZING) break
                val sec = ((System.currentTimeMillis() - v.startedAt) / 1000).toInt().coerceAtLeast(0)
                _ui.update { it.copy(elapsedSec = sec, levels = if (v.phase == Phase.LISTENING) it.levels else it.levels.drop(1) + 0f) }
                if (daily && sec >= DAILY_LIMIT_SEC) { stopAndSave(); break }
            }
        }
    }

    /** Mots-clés IA pendant la dictée : toutes les ~12 s de nouveau texte. */
    private fun maybeAskAi(text: String) {
        val s = app.settings.current
        if (!s.hasAiKey || !s.liveHighlights || aiJob?.isActive == true) return
        if (text.length - lastAiLen < 90) return
        lastAiLen = text.length
        aiJob = viewModelScope.launch {
            runCatching { app.analyzer.liveKeywords(s, text, folders) }.onSuccess { r ->
                r.keywords.forEach { k -> if (k.text.length >= 3) aiTerms.putIfAbsent(k.text, k.kind) }
                _ui.update { u ->
                    u.copy(
                        hits = LocalBrain.highlight(u.voice.text, terms + aiTerms),
                        declaredFolder = u.declaredFolder ?: r.folder?.takeIf { it.isNotBlank() && it != "null" },
                    )
                }
            }
            delay(6_000)
        }
    }

    fun stopAndSave() {
        val s = session ?: return
        if (_ui.value.saving) return
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            val text = s.stop()
            RecordingService.stop(app)
            if (text.isBlank()) {
                _ui.update { it.copy(saving = false, savedId = "") }
                return@launch
            }
            val dur = _ui.value.elapsedSec
            val id = app.repo.saveDraft(text, dur, daily, _ui.value.mood)
            _ui.update { it.copy(saving = false, savedId = id) }
        }
    }

    fun cancel() {
        session?.cancel()
        aiJob?.cancel()
        RecordingService.stop(app)
    }

    override fun onCleared() {
        session?.cancel()
        RecordingService.stop(app)
        super.onCleared()
    }

    companion object {
        const val DAILY_LIMIT_SEC = 5 * 60
    }
}
