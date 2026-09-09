package com.infocaller.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_prefixes")
data class BlockedPrefixEntity(
    @PrimaryKey val prefix: String,
    val addedAt: Long = System.currentTimeMillis()
)
