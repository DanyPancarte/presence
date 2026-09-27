package app.murmure.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmure.MurmureApp
import app.murmure.ai.Emotions
import app.murmure.ai.LocalBrain
import app.murmure.ai.Moment
import app.murmure.ai.MomentKind
import app.murmure.ui.components.Feedback
import app.murmure.voice.PendingAudio
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
    // ---- cerveau live ----
    val moments: List<Moment> = emptyList(),
    /** Dernier moment apparu (pour l'animation d'arrivée). */
    val freshMomentId: String? = null,
    val topic: String? = null,
    val valence: Float = 0f,
    val emotion: String = "neutre",
    val energy: String = "moyenne",
    val insight: String? = null,
    val thinking: Boolean = false,
) {
    val counts: Map<String, Int> get() = moments.groupingBy { it.kind }.eachCount()
}

class RecordViewModel : ViewModel() {
    private val app = MurmureApp.instance
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui.asStateFlow()

    private var session: VoiceSession? = null
    private var terms: Map<String, String> = emptyMap()
    private val aiTerms = LinkedHashMap<String, String>()
    private var aiMoments: List<Moment> = emptyList()
    private var folders: List<String> = emptyList()
    private var known: List<String> = emptyList()
    private var aiJob: Job? = null
    private var lastAiLen = 0
    private var lastAiAt = 0L
    var daily = false

    init {
        viewModelScope.launch {
            terms = app.repo.knownTerms()
            folders = app.repo.dao.folders().map { it.name }
            known = app.repo.dao.entities().map { it.name }
        }
    }

    fun setMood(m: Int?) = _ui.update { it.copy(mood = m, valence = m?.let { v -> (v - 3) / 2f } ?: 0f, emotion = m?.let { LocalBrain.moodEmotion(it) } ?: "neutre") }

    fun start() {
        if (_ui.value.started) return
        _ui.update { it.copy(started = true) }
        val s = VoiceSession(app, app.settings.current, app.client, viewModelScope)
        session = s
        RecordingService.start(app)
        Feedback.play(Feedback.Sound.START)
        s.start(PendingAudio.take())
        viewModelScope.launch {
            s.state.collect { v ->
                val text = v.text
                val changed = text != _ui.value.voice.text
                if (changed) onText(text)
                _ui.update { u -> u.copy(voice = v, levels = u.levels.drop(1) + v.level) }
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

    /** Passe locale à chaque changement de texte : surlignage, dossier, moments. */
    private fun onText(text: String) {
        val local = LocalBrain.detectMoments(text)
        val merged = LocalBrain.mergeMoments(local, aiMoments)
        val before = _ui.value.moments.map { it.id }.toSet()
        val fresh = merged.firstOrNull { it.id !in before }
        val moodMoment = merged.lastOrNull { it.kind == MomentKind.MOOD && it.id !in before }
        _ui.update { u ->
            u.copy(
                hits = LocalBrain.highlight(text, terms + aiTerms),
                declaredFolder = u.declaredFolder ?: LocalBrain.declaredFolder(text, folders) ?: merged.firstOrNull { it.folder != null }?.folder,
                moments = merged,
                freshMomentId = fresh?.id ?: u.freshMomentId,
                topic = u.topic ?: LocalBrain.localTopic(text, terms + aiTerms),
                emotion = moodMoment?.emotion ?: u.emotion,
                valence = moodMoment?.emotion?.let { Emotions.valence(it) } ?: u.valence,
            )
        }
        if (fresh != null) { Feedback.moment(app); Feedback.play(Feedback.Sound.TICK, 0.6f) }
    }

    /** Passe IA : toutes les ~7 s de nouveau texte (≥ 60 caractères). */
    private fun maybeAskAi(text: String) {
        val s = app.settings.current
        if (!s.hasAiKey || !s.liveHighlights || aiJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (text.length - lastAiLen < 60 || now - lastAiAt < 7_000) return
        lastAiLen = text.length; lastAiAt = now
        aiJob = viewModelScope.launch {
            _ui.update { it.copy(thinking = true) }
            runCatching { app.analyzer.liveAnalysis(s, text, folders, known) }.onSuccess { r ->
                r.keywords.forEach { k -> if (k.text.length >= 3) aiTerms.putIfAbsent(k.text, k.kind) }
                aiMoments = LocalBrain.mergeMoments(aiMoments.filter { old -> r.moments.none { it.id == old.id } }, r.moments)
                val current = _ui.value.voice.text
                val merged = LocalBrain.mergeMoments(LocalBrain.detectMoments(current), aiMoments)
                val before = _ui.value.moments.map { it.id }.toSet()
                val fresh = merged.firstOrNull { it.id !in before }
                _ui.update { u ->
                    u.copy(
                        hits = LocalBrain.highlight(current, terms + aiTerms),
                        declaredFolder = u.declaredFolder ?: r.folder,
                        moments = merged, freshMomentId = fresh?.id ?: u.freshMomentId,
                        topic = r.topic ?: u.topic, valence = r.valence.toFloat(), emotion = r.emotion, energy = r.energy,
                        insight = r.insight ?: u.insight,
                    )
                }
                if (fresh != null) { Feedback.moment(app); Feedback.play(Feedback.Sound.TICK, 0.6f) }
            }
            _ui.update { it.copy(thinking = false) }
        }
    }

    fun dismissMoment(id: String) {
        aiMoments = aiMoments.filter { it.id != id }
        _ui.update { u -> u.copy(moments = u.moments.filter { it.id != id }) }
    }

    fun stopAndSave() {
        val s = session ?: return
        if (_ui.value.saving) return
        _ui.update { it.copy(saving = true) }
        Feedback.play(Feedback.Sound.STOP)
        viewModelScope.launch {
            aiJob?.cancel()
            val text = s.stop()
            RecordingService.stop(app)
            if (text.isBlank()) { _ui.update { it.copy(saving = false, savedId = "") }; return@launch }
            val moments = LocalBrain.mergeMoments(LocalBrain.detectMoments(text), aiMoments)
            val id = app.repo.saveCapture(text, _ui.value.elapsedSec, daily, _ui.value.mood, moments)
            _ui.update { it.copy(saving = false, savedId = id) }
        }
    }

    fun cancel() {
        session?.cancel(); aiJob?.cancel(); RecordingService.stop(app)
    }

    override fun onCleared() {
        session?.cancel(); RecordingService.stop(app)
        super.onCleared()
    }

    companion object {
        const val DAILY_LIMIT_SEC = 5 * 60
    }
}
