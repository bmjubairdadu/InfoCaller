package com.infocaller.app.data.local.dao

import androidx.room.*
import com.infocaller.app.data.local.entity.BlockedEventEntity
import com.infocaller.app.data.local.entity.BlockedPrefixEntity
import kotlinx.coroutines.flow.Flow

/**
 * Screening-rule storage. Local-only, adapted from humanjuan/iOG26's
 * blocked-prefixes + blocked-history pattern.
 */
@Dao
interface ScreeningDao {
    // --- Prefixes ---
    @Query("SELECT * FROM blocked_prefixes ORDER BY addedAt DESC")
    fun getAllPrefixes(): Flow<List<BlockedPrefixEntity>>

    @Query("SELECT * FROM blocked_prefixes ORDER BY addedAt DESC")
    suspend fun getAllPrefixesSync(): List<BlockedPrefixEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addPrefix(entity: BlockedPrefixEntity)

    @Query("DELETE FROM blocked_prefixes WHERE prefix = :prefix")
    suspend fun removePrefix(prefix: String)

    // --- Blocked-call history ---
    @Query("SELECT * FROM blocked_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEvents(limit: Int = 100): Flow<List<BlockedEventEntity>>

    @Insert
    suspend fun logEvent(entity: BlockedEventEntity)

    @Query("DELETE FROM blocked_events")
    suspend fun clearEvents()
}
