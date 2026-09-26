package com.dany.presence.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ---- Entities: one per module. Times are epoch millis. -------------------------------------

@Entity data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val avancement: Int,          // 0..100 — 97 is the enemy
    val touche: Long = System.currentTimeMillis(),
    val fini: Boolean = false,
)

@Entity data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val texte: String,
    val quand: Long = System.currentTimeMillis(),
)

@Entity data class MoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val valeur: Int,              // -2..2
    val note: String = "",
    val quand: Long = System.currentTimeMillis(),
)

@Entity data class MedLog(
    @PrimaryKey val jour: String, // yyyy-MM-dd
    val prisA: Long?,             // null = not taken
)

@Entity data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val montant: Double,
    val quoi: String,
    val quand: Long = System.currentTimeMillis(),
)

@Entity data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val quoi: String,
    val quand: Long,
)

@Entity data class OpenQuestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val texte: String,
    val quand: Long = System.currentTimeMillis(),
    val fermee: Boolean = false,
)

@Entity data class Setting(@PrimaryKey val cle: String, val valeur: String)

// ---- DAOs -----------------------------------------------------------------------------------

@Dao interface PresenceDao {
    @Query("SELECT * FROM Task WHERE fini = 0 ORDER BY avancement DESC, touche DESC") fun tasks(): Flow<List<Task>>
    @Query("SELECT * FROM Task WHERE fini = 0 ORDER BY avancement DESC, touche DESC") suspend fun tasksNow(): List<Task>
    @Query("SELECT * FROM Task WHERE fini = 0 AND nom LIKE '%' || :q || '%' LIMIT 1") suspend fun findTask(q: String): Task?
    @Insert suspend fun insert(t: Task): Long
    @Update suspend fun update(t: Task)

    @Query("SELECT * FROM Note ORDER BY quand DESC LIMIT 20") fun notes(): Flow<List<Note>>
    @Query("SELECT * FROM Note ORDER BY quand DESC LIMIT 5") suspend fun notesNow(): List<Note>
    @Insert suspend fun insert(n: Note): Long

    @Query("SELECT * FROM MoodEntry ORDER BY quand DESC LIMIT 14") fun moods(): Flow<List<MoodEntry>>
    @Query("SELECT * FROM MoodEntry ORDER BY quand DESC LIMIT 3") suspend fun moodsNow(): List<MoodEntry>
    @Insert suspend fun insert(m: MoodEntry): Long

    @Query("SELECT * FROM MedLog WHERE jour = :jour") suspend fun med(jour: String): MedLog?
    @Query("SELECT * FROM MedLog WHERE jour = :jour") fun medFlow(jour: String): Flow<MedLog?>
    @Query("SELECT * FROM MedLog ORDER BY jour DESC LIMIT 7") fun meds(): Flow<List<MedLog>>
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun upsert(m: MedLog)

    @Query("SELECT * FROM Expense WHERE quand >= :since ORDER BY quand DESC") fun expenses(since: Long): Flow<List<Expense>>
    @Query("SELECT COALESCE(SUM(montant), 0) FROM Expense WHERE quand >= :since") suspend fun spent(since: Long): Double
    @Insert suspend fun insert(e: Expense): Long

    @Query("SELECT * FROM Event WHERE quand >= :from ORDER BY quand ASC LIMIT 6") fun events(from: Long): Flow<List<Event>>
    @Query("SELECT * FROM Event WHERE quand >= :from ORDER BY quand ASC LIMIT 4") suspend fun eventsNow(from: Long): List<Event>
    @Insert suspend fun insert(e: Event): Long

    @Query("SELECT * FROM OpenQuestion WHERE fermee = 0 ORDER BY quand DESC LIMIT 3") suspend fun openQuestions(): List<OpenQuestion>
    @Insert suspend fun insert(q: OpenQuestion): Long
    @Query("UPDATE OpenQuestion SET fermee = 1 WHERE id = :id") suspend fun closeQuestion(id: Long)

    @Query("SELECT valeur FROM Setting WHERE cle = :cle") suspend fun setting(cle: String): String?
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun put(s: Setting)
}

@Database(entities = [Task::class, Note::class, MoodEntry::class, MedLog::class, Expense::class, Event::class, OpenQuestion::class, Setting::class], version = 1, exportSchema = false)
abstract class PresenceDb : RoomDatabase() {
    abstract fun dao(): PresenceDao

    companion object {
        @Volatile private var inst: PresenceDb? = null
        fun get(ctx: Context): PresenceDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, PresenceDb::class.java, "presence.db")
                .fallbackToDestructiveMigration(true).build().also { inst = it }
        }
    }
}
