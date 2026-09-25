package com.infocaller.app.data.local.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Keep
@Entity(
    tableName = "enrichment_queue",
    indices = [Index(value = ["status", "nextAttemptAt"])]
)
data class EnrichmentQueueEntity(
    @PrimaryKey val identifier: String,
    val type: String,
    val contactId: Long? = null,
    val priority: Int,
    val requestedAt: Long = System.currentTimeMillis(),
    val lastAttemptAt: Long? = null,
    val nextAttemptAt: Long = 0,
    val attemptCount: Int = 0,
    val status: String,
    val reason: String? = null,
    val providerMask: Int = 0
)

object QueuePriority {
    const val LOW = 0
    const val MEDIUM = 1
    const val HIGH = 2
}

object QueueStatus {
    const val PENDING = "PENDING"
    const val PROCESSING = "PROCESSING"
    const val PARTIAL = "PARTIAL"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val RETRY_WAIT = "RETRY_WAIT"
}
