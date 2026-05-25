package com.example.voiceime.dictionary

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dictionary",
    indices = [Index(value = ["term"], unique = true)]
)
data class DictionaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val term: String,
    val addedAt: Long = System.currentTimeMillis(),
    val usageCount: Int = 0,
    /** true = auto-detected candidate waiting for user review */
    val isCandidate: Boolean = false
)
