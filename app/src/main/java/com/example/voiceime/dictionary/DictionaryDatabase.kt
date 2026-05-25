package com.example.voiceime.dictionary

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DictionaryEntry::class], version = 2, exportSchema = false)
abstract class DictionaryDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao

    companion object {
        fun create(context: Context): DictionaryDatabase =
            Room.databaseBuilder(context, DictionaryDatabase::class.java, "voice_ime_dictionary.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()

        // Adds composite indexes; existing data is preserved.
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dictionary_isCandidate_usageCount` ON `dictionary` (`isCandidate`, `usageCount`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dictionary_isCandidate_addedAt` ON `dictionary` (`isCandidate`, `addedAt`)")
            }
        }
    }
}
