package app.murmure.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        FolderEntity::class, NoteEntity::class, MentionEntity::class, NoteMention::class,
        NoteLink::class, TaskEntity::class, ReportEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MurmureDb : RoomDatabase() {
    abstract fun dao(): MurmureDao

    companion object {
        const val NAME = "murmure.db"

        /** Base chiffrée SQLCipher (AES-256), clé protégée par l'Android Keystore. */
        fun open(context: Context, passphrase: ByteArray): MurmureDb {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, MurmureDb::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
