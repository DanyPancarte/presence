package app.murmure.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmure.MurmureApp
import app.murmure.ai.Emotions
import app.murmure.ai.LocalBrain
import app.murmure.ai.EntityGuess
import app.murmure.ai.Moment
import app.murmure.ai.NoteProposal
import app.murmure.ai.SessionProposal
import app.murmure.ai.TaskGuess
import app.murmure.core.Text
import app.murmure.data.CaptureEntity
import app.murmure.data.SessionValidation
import app.murmure.data.Validation
import app.murmure.insights.Insights
import app.murmure.ui.components.Feedback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NoteDraft(
    val proposal: NoteProposal,
    val title: String,
    val folder: String,
    val type: String,
    val emotion: String,
    val energy: String,
    val entities: List<EntityGuess>,
    val keywords: List<String>,
    val expanded: Boolean = false,
    val kept: Boolean = true,
)

data class TaskChoice(val task: TaskGuess, val accepted: Boolean)
data class EventChoice(val event: Moment, val accepted: Boolean)

data class ReviewUi(
    val loading: Boolean = true,
    val capture: CaptureEntity? = null,
    val proposal: SessionProposal? = null,
    val warning: String? = null,
    val notes: List<NoteDraft> = emptyList(),
    val tasks: List<TaskChoice> = emptyList(),
    val events: List<EventChoice> = emptyList(),
    val mood: Int? = null,
    val moodEmotion: String? = null,
    val insight: String? = null,
    val allFolders: List<String> = emptyList(),
    val filed: Boolean = false,
    val streakAfter: Int = 0,
) {
    val keptNotes get() = notes.filter { it.kept }
    val total get() = keptNotes.size + tasks.count { it.accepted } + events.count { it.accepted } + (if (mood != null) 1 else 0)
}

class ReviewViewModel : ViewModel() {
    private val app = MurmureApp.instance
    private val _ui = MutableStateFlow(ReviewUi())
    val ui: StateFlow<ReviewUi> = _ui.asStateFlow()
    private var id: String? = null

    fun load(captureId: String) {
        if (id == captureId) return
        id = captureId
        viewModelScope.launch {
            val c = app.repo.dao.capture(captureId) ?: return@launch
            val folders = app.repo.dao.folders().map { it.name }
            _ui.update { it.copy(capture = c, allFolders = folders) }
            val existing = app.repo.sessionProposalOf(c)
            if (existing != null) apply(existing, null)
            else runCatching { app.repo.analyzeCapture(captureId) }
                .onSuccess { apply(it.proposal, it.warning) }
                .onFailure { e -> _ui.update { it.copy(loading = false, warning = e.message) } }
        }
    }

    private fun apply(p: SessionProposal, warning: String?) = _ui.update { u ->
        u.copy(
            loading = false, proposal = p, warning = warning,
            notes = p.notes.mapIndexed { i, n ->
                NoteDraft(
                    proposal = n, title = n.title, folder = n.folderSuggestions.firstOrNull()?.name ?: "Boîte de réception",
                    type = n.type, emotion = n.emotion.label, energy = n.energy, entities = n.entities, keywords = n.keywords,
                    expanded = i == 0 && p.notes.size == 1,
                )
            },
            tasks = p.tasks.map { TaskChoice(it, true) },
            events = p.events.map { EventChoice(it, true) },
            mood = u.capture?.mood ?: p.mood,
            moodEmotion = p.moodEmotion ?: u.capture?.mood?.let { LocalBrain.moodEmotion(it) },
            insight = p.insight,
        )
    }

    fun reanalyze() {
        val cid = id ?: return
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val c = _ui.value.capture ?: return@launch
            app.repo.dao.upsertCapture(c.copy(proposalJson = null))
            runCatching { app.repo.analyzeCapture(cid) }
                .onSuccess { apply(it.proposal, it.warning) }
                .onFailure { e -> _ui.update { it.copy(loading = false, warning = e.message) } }
        }
    }

    private fun note(i: Int, f: (NoteDraft) -> NoteDraft) = _ui.update { u -> u.copy(notes = u.notes.mapIndexed { j, n -> if (j == i) f(n) else n }) }
    fun setTitle(i: Int, t: String) = note(i) { it.copy(title = t) }
    fun setFolder(i: Int, f: String) = note(i) { it.copy(folder = Text.capitalize(f)) }
    fun setType(i: Int, t: String) = note(i) { it.copy(type = t) }
    fun setEmotion(i: Int, e: String) = note(i) { it.copy(emotion = e) }
    fun setEnergy(i: Int, e: String) = note(i) { it.copy(energy = e) }
    fun toggleExpanded(i: Int) = note(i) { it.copy(expanded = !it.expanded) }
    fun toggleKept(i: Int) = note(i) { it.copy(kept = !it.kept) }
    fun removeEntity(i: Int, e: EntityGuess) = note(i) { it.copy(entities = it.entities - e) }
    fun removeKeyword(i: Int, k: String) = note(i) { it.copy(keywords = it.keywords - k) }

    fun toggleTask(i: Int) = _ui.update { u -> u.copy(tasks = u.tasks.mapIndexed { j, t -> if (j == i) t.copy(accepted = !t.accepted) else t }) }
    fun setDue(i: Int, due: String?) = _ui.update { u -> u.copy(tasks = u.tasks.mapIndexed { j, t -> if (j == i) t.copy(task = t.task.copy(due = due)) else t }) }
    fun toggleEvent(i: Int) = _ui.update { u -> u.copy(events = u.events.mapIndexed { j, e -> if (j == i) e.copy(accepted = !e.accepted) else e }) }
    fun setMood(m: Int?) = _ui.update { it.copy(mood = m, moodEmotion = m?.let { v -> LocalBrain.moodEmotion(v) }) }

    fun file() {
        val u = _ui.value
        val cid = id ?: return
        viewModelScope.launch {
            app.repo.fileCapture(
                cid,
                SessionValidation(
                    notes = u.notes.filter { it.kept }.map { d ->
                        val p = d.proposal
                        Validation(
                            title = d.title.trim().ifBlank { p.title }, body = p.body, summary = p.summary, folderName = d.folder,
                            type = d.type, emotion = d.emotion, intensity = p.emotion.intensity.toFloat(),
                            valence = if (d.emotion == p.emotion.label) p.emotion.valence.toFloat() else Emotions.valence(d.emotion),
                            energy = d.energy, entities = d.entities, keywords = d.keywords, links = p.links, acceptedTasks = emptyList(),
                        )
                    },
                    tasks = u.tasks.filter { it.accepted }.map { it.task },
                    events = u.events.filter { it.accepted }.map { it.event },
                    mood = u.mood, moodEmotion = u.moodEmotion,
                ),
            )
            Feedback.play(Feedback.Sound.SUCCESS)
            val streak = Insights.streak(app.repo.dao.notes()).first
            _ui.update { it.copy(filed = true, streakAfter = streak) }
        }
    }

    fun later(onDone: () -> Unit) {
        val cid = id ?: return
        viewModelScope.launch {
            val u = _ui.value
            app.repo.deferCapture(cid, u.proposal?.copy(tasks = u.tasks.filter { it.accepted }.map { it.task }, events = u.events.filter { it.accepted }.map { it.event }))
            onDone()
        }
    }

    fun discard(onDone: () -> Unit) {
        val cid = id ?: return
        viewModelScope.launch { app.repo.deleteCapture(cid); onDone() }
    }
}
