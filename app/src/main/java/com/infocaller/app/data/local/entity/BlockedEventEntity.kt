package com.infocaller.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local log of calls the screener refused.
 * Pattern adapted from humanjuan/iOG26 (blocked history) — on-device only.
 */
@Entity(tableName = "blocked_events")
data class BlockedEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)
