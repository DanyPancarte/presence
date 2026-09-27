package app.murmure.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object NoteStatus {
    /** Enregistrée, en attente de validation humaine du classement. */
    const val PENDING = "pending"
    /** Classement validé par l'utilisateur. */
    const val FILED = "filed"
}

object TaskStatus {
    const val SUGGESTED = "suggested"
    const val OPEN = "open"
    const val DONE = "done"
    const val DISMISSED = "dismissed"
}

object EntityKind {
    const val PERSON = "person"
    const val PLACE = "place"
    const val ACTIVITY = "activity"
    const val PROJECT = "project"
    val all = listOf(PERSON, PLACE, ACTIVITY, PROJECT)
    fun label(k: String) = when (k) {
        PERSON -> "Personne"; PLACE -> "Lieu"; ACTIVITY -> "Activité"; PROJECT -> "Projet"; else -> "Concept"
    }
}

@Entity(tableName = "folders", indices = [Index(value = ["name"], unique = true)])
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorIndex: Int,
    val createdAt: Long,
    val emoji: String? = null,
)

@Entity(
    tableName = "notes",
    indices = [Index("folderId"), Index("createdAt"), Index("dayKey")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val rawTranscript: String,
    val summary: String = "",
    val folderId: String? = null,
    val status: String = NoteStatus.PENDING,
    val createdAt: Long,
    val updatedAt: Long,
    /** yyyy-MM-dd local, pour les agrégations quotidiennes. */
    val dayKey: String,
    val durationSec: Int = 0,
    val isDaily: Boolean = false,
    /** Humeur déclarée (1..5) — rituel quotidien. */
    val mood: Int? = null,
    // ---- Classification validée (jamais écrasée sans accord) ----
    val type: String? = null,
    val emotion: String? = null,
    val emotionIntensity: Float? = null,
    /** -1 (négatif) .. +1 (positif) */
    val valence: Float? = null,
    val energy: String? = null,
    val timeOfDay: String = "",
    /** Mots-clés séparés par « | ». */
    val keywords: String = "",
    /** Proposition IA en attente (JSON), séparée des champs validés. */
    val proposalJson: String? = null,
    val analyzed: Boolean = false,
)

@Entity(tableName = "entities", indices = [Index(value = ["kind", "key"], unique = true)])
data class MentionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    /** Nom normalisé (minuscules, sans accents) pour la déduplication. */
    val key: String,
)

@Entity(tableName = "note_mentions", primaryKeys = ["noteId", "entityId"], indices = [Index("entityId")])
data class NoteMention(
    val noteId: String,
    val entityId: String,
    val sentiment: Float = 0f,
)

@Entity(tableName = "links", primaryKeys = ["fromId", "toId"], indices = [Index("toId")])
data class NoteLink(
    val fromId: String,
    val toId: String,
)

@Entity(tableName = "tasks", indices = [Index("noteId"), Index("status")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val noteId: String?,
    val text: String,
    val dueDate: String? = null,
    val status: String = TaskStatus.SUGGESTED,
    val reason: String = "",
    val createdAt: Long,
)

@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey val key: String,
    val content: String,
    val createdAt: Long,
)
