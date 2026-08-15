package com.infocaller.app.data.local.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey

@Keep
@Entity(tableName = "contact_enrichment")
data class ContactEnrichmentEntity(
    @PrimaryKey val normalizedPhoneNumber: String,
    val contactId: Long? = null,
    val publicName: String? = null,
    val profileImageUrl: String? = null,
    val about: String? = null,
    val carrier: String? = null,
    val country: String? = null,
    val region: String? = null,
    val whatsappStatus: String? = null,
    val telegramStatus: String? = null,
    val googleResult: String? = null,
    val spamScore: Int = 0,
    val spamType: String? = null,
    val spamStatus: String? = null,
    val source: String? = null,
    val confidence: String? = null,
    val lastChecked: Long = System.currentTimeMillis(),
    val expiresAt: Long
)
