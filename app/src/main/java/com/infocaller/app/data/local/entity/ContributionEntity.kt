package com.infocaller.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per contributed number. Dedup + resume:
 * - phoneHash is the PK (plain numbers never stored here).
 * - payloadFingerprint changes only when the permitted payload changes.
 * - status: PENDING -> UPLOADING -> DONE | FAILED (retry with backoff).
 */
@Entity(
    tableName = "contribution_queue",
    indices = [Index(value = ["status", "nextAttemptAt"])]
)
data class ContributionEntity(
    @PrimaryKey val phoneHash: String,
    val displayName: String? = null,
    val payloadFingerprint: String,
    val status: String = ContributionStatus.PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object ContributionStatus {
    const val PENDING = "PENDING"
    const val UPLOADING = "UPLOADING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}
