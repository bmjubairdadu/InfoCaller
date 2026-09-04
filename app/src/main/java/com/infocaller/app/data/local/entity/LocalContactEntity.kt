package com.infocaller.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_contacts",
    indices = [Index(value = ["phoneNumber"]), Index(value = ["isSynced"])]
)
data class LocalContactEntity(
    @PrimaryKey val id: Long,
    val lookupKey: String,
    val displayName: String,
    val phoneNumber: String,
    val photoUri: String? = null,
    val photoThumbnailUri: String? = null,
    val whatsappProfilePic: String? = null,
    val about: String? = null,
    val isBusiness: Boolean = false,
    val isSynced: Boolean = false,
    val lastSynced: Long = 0L
)
