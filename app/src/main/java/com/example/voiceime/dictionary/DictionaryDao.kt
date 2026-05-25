package com.example.voiceime.dictionary

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryDao {

    @Query("SELECT * FROM dictionary WHERE isCandidate = 0 ORDER BY usageCount DESC, addedAt DESC LIMIT 200")
    fun observeConfirmed(): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary WHERE isCandidate = 1 ORDER BY addedAt DESC LIMIT 200")
    fun observeCandidates(): Flow<List<DictionaryEntry>>

    @Query("SELECT term FROM dictionary WHERE isCandidate = 0 ORDER BY usageCount DESC LIMIT :limit")
    suspend fun getTopTerms(limit: Int = 50): List<String>

    @Query("SELECT COUNT(*) FROM dictionary WHERE term = :term")
    suspend fun exists(term: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: DictionaryEntry): Long

    @Query("UPDATE dictionary SET usageCount = usageCount + 1 WHERE term = :term")
    suspend fun incrementUsage(term: String)

    @Query("UPDATE dictionary SET isCandidate = 0 WHERE id = :id")
    suspend fun confirmCandidate(id: Long)

    @Delete
    suspend fun delete(entry: DictionaryEntry)

    @Query("DELETE FROM dictionary WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Upsert: if term already exists as candidate, confirm it; otherwise insert confirmed */
    @Transaction
    suspend fun addConfirmedTerm(term: String) {
        if (exists(term) == 0) {
            insert(DictionaryEntry(term = term, isCandidate = false))
        } else {
            // promote existing candidate to confirmed
            confirmCandidateByTerm(term)
        }
    }

    @Query("UPDATE dictionary SET isCandidate = 0 WHERE term = :term")
    suspend fun confirmCandidateByTerm(term: String)

    /** Add auto-detected term as candidate if not already present */
    @Transaction
    suspend fun addCandidateTerm(term: String) {
        if (exists(term) == 0) {
            insert(DictionaryEntry(term = term, isCandidate = true))
        }
    }
}
