package app.murmure.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MurmureDao {
    // ---------- Dossiers ----------
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun foldersFlow(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    suspend fun folders(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun folderByName(name: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(f: FolderEntity)

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun folderCount(): Int

    // ---------- Notes ----------
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun notesFlow(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    suspend fun notes(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun noteFlow(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: String): NoteEntity?

    @Upsert
    suspend fun upsertNote(n: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    @Query("SELECT id, title FROM notes WHERE status = 'filed' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentTitles(limit: Int): List<NoteTitle>

    // ---------- Entités ----------
    @Query("SELECT * FROM entities")
    fun entitiesFlow(): Flow<List<MentionEntity>>

    @Query("SELECT * FROM entities")
    suspend fun entities(): List<MentionEntity>

    @Query("SELECT * FROM entities WHERE kind = :kind AND `key` = :key LIMIT 1")
    suspend fun entity(kind: String, key: String): MentionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntity(e: MentionEntity)

    @Query("SELECT * FROM note_mentions")
    fun mentionsFlow(): Flow<List<NoteMention>>

    @Query("SELECT * FROM note_mentions")
    suspend fun mentions(): List<NoteMention>

    @Upsert
    suspend fun upsertMention(m: NoteMention)

    @Query("DELETE FROM note_mentions WHERE noteId = :noteId")
    suspend fun clearMentions(noteId: String)

    @Query("DELETE FROM entities WHERE id NOT IN (SELECT entityId FROM note_mentions)")
    suspend fun pruneEntities()

    // ---------- Liens ----------
    @Query("SELECT * FROM links")
    fun linksFlow(): Flow<List<NoteLink>>

    @Upsert
    suspend fun upsertLink(l: NoteLink)

    @Query("DELETE FROM links WHERE fromId = :id OR toId = :id")
    suspend fun clearLinks(id: String)

    @Query("DELETE FROM links WHERE fromId = :id")
    suspend fun clearOutgoingLinks(id: String)

    // ---------- Tâches ----------
    @Query("SELECT * FROM tasks WHERE status != 'dismissed' AND status != 'abandoned' ORDER BY CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END, dueDate, createdAt DESC")
    fun tasksFlow(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks")
    fun allTasksFlow(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: String): TaskEntity?

    @Upsert
    suspend fun upsertTask(t: TaskEntity)

    @Query("SELECT * FROM tasks")
    suspend fun allTasks(): List<TaskEntity>

    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    suspend fun setTaskStatus(id: String, status: String)

    @Query("DELETE FROM tasks WHERE noteId = :noteId AND status = 'suggested'")
    suspend fun clearSuggestedTasks(noteId: String)

    // ---------- Captures ----------
    @Query("SELECT * FROM captures ORDER BY createdAt DESC")
    fun capturesFlow(): Flow<List<CaptureEntity>>

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun capture(id: String): CaptureEntity?

    @Query("SELECT * FROM captures WHERE status = 'pending' ORDER BY createdAt DESC")
    fun pendingCapturesFlow(): Flow<List<CaptureEntity>>

    @Upsert
    suspend fun upsertCapture(c: CaptureEntity)

    @Query("DELETE FROM captures WHERE id = :id")
    suspend fun deleteCapture(id: String)

    @Query("SELECT * FROM notes WHERE captureId = :captureId")
    suspend fun notesOfCapture(captureId: String): List<NoteEntity>

    // ---------- Agenda ----------
    @Query("SELECT * FROM events WHERE status != 'dismissed' ORDER BY startAt")
    fun eventsFlow(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events")
    suspend fun allEvents(): List<EventEntity>

    @Upsert
    suspend fun upsertEvent(e: EventEntity)

    @Query("UPDATE events SET status = :status WHERE id = :id")
    suspend fun setEventStatus(id: String, status: String)

    @Query("DELETE FROM events WHERE captureId = :captureId AND status = 'suggested'")
    suspend fun clearSuggestedEvents(captureId: String)

    @Query("DELETE FROM tasks WHERE captureId = :captureId AND status = 'suggested'")
    suspend fun clearSuggestedTasksOfCapture(captureId: String)

    // ---------- Rapports IA ----------
    @Query("SELECT * FROM reports WHERE `key` = :key")
    fun reportFlow(key: String): Flow<ReportEntity?>

    @Upsert
    suspend fun upsertReport(r: ReportEntity)
}

data class NoteTitle(val id: String, val title: String)
