package app.murmure.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmure.MurmureApp
import app.murmure.ai.Emotions
import app.murmure.ai.EntityGuess
import app.murmure.insights.Insights
import app.murmure.ai.NoteProposal
import app.murmure.ai.TaskGuess
import app.murmure.core.Text
import app.murmure.data.NoteEntity
import app.murmure.data.Validation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskChoice(val task: TaskGuess, val accepted: Boolean)

data class ReviewUi(
    val loading: Boolean = true,
    val note: NoteEntity? = null,
    val proposal: NoteProposal? = null,
    val warning: String? = null,
    val title: String = "",
    val folder: String = "",
    val folderConfirmed: Boolean = false,
    val type: String = "idee",
    val emotion: String = "neutre",
    val energy: String = "moyenne",
    val entities: List<EntityGuess> = emptyList(),
    val keywords: List<String> = emptyList(),
    val tasks: List<TaskChoice> = emptyList(),
    val allFolders: List<String> = emptyList(),
    val filed: Boolean = false,
    val streakAfter: Int = 0,
)

class ReviewViewModel : ViewModel() {
    private val app = MurmureApp.instance
    private val _ui = MutableStateFlow(ReviewUi())
    val ui: StateFlow<ReviewUi> = _ui.asStateFlow()
    private var id: String? = null

    fun load(noteId: String) {
        if (id == noteId) return
        id = noteId
        viewModelScope.launch {
            val note = app.repo.dao.note(noteId) ?: return@launch
            val folders = app.repo.dao.folders().map { it.name }
            _ui.update { it.copy(note = note, allFolders = folders) }
            val existing = app.repo.proposalOf(note)
            if (existing != null) apply(existing, null)
            else runCatching { app.repo.analyze(noteId) }
                .onSuccess { apply(it.proposal, it.warning) }
                .onFailure { e -> _ui.update { it.copy(loading = false, warning = e.message) } }
        }
    }

    private fun apply(p: NoteProposal, warning: String?) = _ui.update {
        it.copy(
            loading = false, proposal = p, warning = warning,
            title = p.title,
            folder = p.folderSuggestions.firstOrNull()?.name ?: "Boîte de réception",
            type = p.type, emotion = p.emotion.label, energy = p.energy,
            entities = p.entities, keywords = p.keywords,
            tasks = p.tasks.map { t -> TaskChoice(t, true) },
        )
    }

    fun reanalyze() {
        val nid = id ?: return
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching { app.repo.analyze(nid) }
                .onSuccess { apply(it.proposal, it.warning) }
                .onFailure { e -> _ui.update { it.copy(loading = false, warning = e.message) } }
        }
    }

    fun setTitle(t: String) = _ui.update { it.copy(title = t) }
    fun setFolder(f: String) = _ui.update { it.copy(folder = Text.capitalize(f), folderConfirmed = true) }
    fun confirmFolder() = _ui.update { it.copy(folderConfirmed = true) }
    fun setType(t: String) = _ui.update { it.copy(type = t) }
    fun setEmotion(e: String) = _ui.update { it.copy(emotion = e) }
    fun setEnergy(e: String) = _ui.update { it.copy(energy = e) }
    fun removeEntity(e: EntityGuess) = _ui.update { it.copy(entities = it.entities - e) }
    fun removeKeyword(k: String) = _ui.update { it.copy(keywords = it.keywords - k) }
    fun toggleTask(i: Int) = _ui.update { u -> u.copy(tasks = u.tasks.mapIndexed { j, t -> if (j == i) t.copy(accepted = !t.accepted) else t }) }
    fun setDue(i: Int, due: String?) = _ui.update { u -> u.copy(tasks = u.tasks.mapIndexed { j, t -> if (j == i) t.copy(task = t.task.copy(due = due)) else t }) }

    fun file() {
        val u = _ui.value
        val nid = id ?: return
        val p = u.proposal ?: return
        viewModelScope.launch {
            app.repo.file(
                nid,
                Validation(
                    title = u.title.trim().ifBlank { p.title },
                    body = p.body, summary = p.summary, folderName = u.folder,
                    type = u.type, emotion = u.emotion,
                    intensity = p.emotion.intensity.toFloat(),
                    valence = if (u.emotion == p.emotion.label) p.emotion.valence.toFloat() else Emotions.valence(u.emotion),
                    energy = u.energy, entities = u.entities, keywords = u.keywords, links = p.links,
                    acceptedTasks = u.tasks.filter { it.accepted }.map { it.task },
                ),
            )
            val streak = Insights.streak(app.repo.dao.notes()).first
            _ui.update { it.copy(filed = true, streakAfter = streak) }
        }
    }

    fun later(onDone: () -> Unit) {
        val nid = id ?: return
        viewModelScope.launch {
            val u = _ui.value
            app.repo.defer(nid, u.proposal?.copy(title = u.title, tasks = u.tasks.filter { it.accepted }.map { it.task }))
            onDone()
        }
    }
}
