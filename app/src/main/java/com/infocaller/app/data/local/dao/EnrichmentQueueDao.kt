package com.infocaller.app.data.local.dao

import androidx.room.*
import com.infocaller.app.data.local.entity.EnrichmentQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EnrichmentQueueDao {
    @Query("SELECT * FROM enrichment_queue WHERE normalizedPhoneNumber = :number")
    suspend fun getQueueItemSync(number: String): EnrichmentQueueEntity?

    @Query("SELECT * FROM enrichment_queue WHERE status IN ('PENDING', 'RETRY_WAIT', 'PARTIAL') AND nextAttemptAt <= :currentTime ORDER BY priority DESC, requestedAt ASC LIMIT :limit")
    suspend fun getEligibleItems(currentTime: Long, limit: Int): List<EnrichmentQueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(item: EnrichmentQueueEntity)

    @Delete
    suspend fun delete(item: EnrichmentQueueEntity)

    @Query("UPDATE enrichment_queue SET status = :status, lastAttemptAt = :time WHERE normalizedPhoneNumber = :number")
    suspend fun updateStatus(number: String, status: String, time: Long)

    @Query("SELECT COUNT(*) FROM enrichment_queue WHERE status != 'COMPLETED'")
    fun getPendingCount(): Flow<Int>
}
