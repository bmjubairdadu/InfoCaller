package com.infocaller.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contact_backups")
data class ContactBackupEntity(
    @PrimaryKey val contactId: Long,
    val phoneNumber: String,
    val originalName: String?,
    val originalPhotoUri: String?,
    val backupCreatedAt: Long = System.currentTimeMillis()
)
