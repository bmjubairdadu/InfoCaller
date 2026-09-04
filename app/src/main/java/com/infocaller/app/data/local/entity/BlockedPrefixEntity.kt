package com.infocaller.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * User-defined blocked number prefixes (e.g. "+1800", "8802").
 * Pattern adapted from humanjuan/iOG26 (blocked prefixes) — implemented
 * here as a local-only Room table, no server involved.
 */
@Entity(tableName = "blocked_prefixes")
data class BlockedPrefixEntity(
    @PrimaryKey val prefix: String,
    val addedAt: Long = System.currentTimeMillis()
)
