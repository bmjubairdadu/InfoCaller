package com.infocaller.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocklist")
data class BlocklistEntity(
    @PrimaryKey val phoneNumber: String,
    val addedAt: Long = System.currentTimeMillis()
)
