package com.infocaller.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.infocaller.app.data.local.entity.ContributionEntity

@Dao
interface ContributionDao {
    @Query("SELECT * FROM contribution_queue WHERE phoneHash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): ContributionEntity?

    /** Next eligible item, oldest first — sequential one-by-one processing. */
    @Query(
        "SELECT * FROM contribution_queue " +
            "WHERE status IN ('PENDING','FAILED') AND nextAttemptAt <= :now " +
            "ORDER BY createdAt ASC LIMIT 1"
    )
    suspend fun getNextEligible(now: Long): ContributionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: ContributionEntity): Long

    @Query(
        "UPDATE contribution_queue SET status = :status, lastAttemptAt = :at, " +
            "nextAttemptAt = :next, attemptCount = attemptCount + 1, " +
            "lastError = :error, updatedAt = :at WHERE phoneHash = :hash"
    )
    suspend fun markAttempt(hash: String, status: String, at: Long, next: Long, error: String?)

    @Query(
        "UPDATE contribution_queue SET status = 'DONE', lastAttemptAt = :at, " +
            "updatedAt = :at, lastError = NULL WHERE phoneHash = :hash"
    )
    suspend fun markDone(hash: String, at: Long)

    @Query(
        "UPDATE contribution_queue SET status = 'PENDING', displayName = :name, " +
            "payloadFingerprint = :fp, nextAttemptAt = 0, updatedAt = :at " +
            "WHERE phoneHash = :hash AND payloadFingerprint != :fp"
    )
    suspend fun requeueIfChanged(hash: String, name: String?, fp: String, at: Long): Int

    @Query("SELECT COUNT(*) FROM contribution_queue WHERE status IN ('PENDING','FAILED','UPLOADING')")
    suspend fun pendingCount(): Int

    @Query("DELETE FROM contribution_queue WHERE status = 'DONE'")
    suspend fun pruneDone(): Int
}
