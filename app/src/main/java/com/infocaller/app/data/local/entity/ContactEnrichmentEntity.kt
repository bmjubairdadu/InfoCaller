package com.infocaller.app.data.local.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey

@Keep
@Entity(tableName = "contact_enrichment")
data class ContactEnrichmentEntity(
    @PrimaryKey val normalizedPhoneNumber: String,
    val contactId: Long? = null,
    
    // Core Fields with Metadata
    val publicName: String? = null,
    val publicNameSource: String? = null,
    val publicNameConfidence: Float? = null,
    
    val alternateName: String? = null,
    
    val profileImageUrl: String? = null,
    val profileImageSource: String? = null,
    
    val about: String? = null,
    val aboutSource: String? = null,
    
    val email: String? = null,
    val emailSource: String? = null,
    
    val city: String? = null,
    val country: String? = null,
    val carrier: String? = null,
    val lineType: String? = null,
    val region: String? = null,
    val timezone: String? = null,
    
    val whatsappStatus: String? = null,
    val telegramStatus: String? = null,
    
    // Forensic Identifiers
    val plateNumber: String? = null,
    val plateNumberSource: String? = null,
    val iban: String? = null,
    val ibanSource: String? = null,
    val vatId: String? = null,
    val vatIdSource: String? = null,
    val macAddress: String? = null,
    val macAddressSource: String? = null,
    val nid: String? = null,
    val dob: String? = null,

    val googleResult: String? = null,
    val isBusiness: Boolean? = null,
    val socialProfilesJson: String? = null,
    val photoCandidatesJson: String? = null,
    val alternateNamesJson: String? = null,
    val lastScannedAt: Long = 0,
    
    val source: String? = null,
    val confidence: String? = null,
    val lastChecked: Long = System.currentTimeMillis(),
    val expiresAt: Long
)
