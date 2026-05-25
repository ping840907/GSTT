package com.example.voiceime.dictionary

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dictionary",
    indices = [
        // Deduplication — also covers WHERE term = :term in exists()
        Index(value = ["term"], unique = true),
        // Covers: WHERE isCandidate = 0 ORDER BY usageCount DESC (getTopTerms / observeConfirmed)
        Index(value = ["isCandidate", "usageCount"]),
        // Covers: WHERE isCandidate = 1 ORDER BY addedAt DESC (observeCandidates)
        Index(value = ["isCandidate", "addedAt"])
    ]
)
data class DictionaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val term: String,
    val addedAt: Long = System.currentTimeMillis(),
    val usageCount: Int = 0,
    /** true = auto-detected candidate waiting for user review */
    val isCandidate: Boolean = false
)
