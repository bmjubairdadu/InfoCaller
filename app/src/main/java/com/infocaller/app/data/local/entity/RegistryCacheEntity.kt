package com.infocaller.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Encrypted, per-number cache for the shared caller registry. */
@Entity(tableName = "registry_cache")
data class RegistryCacheEntity(
    @PrimaryKey val normalizedPhoneNumber: String,
    val encryptedRecord: String,
    val fetchedAt: Long,
    val expiresAt: Long,
    val staleUntil: Long,
    val etag: String? = null,
    val lastModified: String? = null,
    val registryVersion: String? = null,
    val shardPath: String
)
