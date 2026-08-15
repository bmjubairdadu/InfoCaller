package com.infocaller.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_contacts")
data class LocalContactEntity(
    @PrimaryKey val id: Long, // Native System Contact ID
    val lookupKey: String,
    val displayName: String,
    val phoneNumber: String,
    val whatsappProfilePic: String? = null,
    val about: String? = null,
    val isBusiness: Boolean = false,
    val isSynced: Boolean = false,
    val lastSynced: Long = 0L
)
