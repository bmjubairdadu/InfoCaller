package com.infocaller.app.data.local.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey

@Keep
@Entity(tableName = "scan_job_states")
data class ScanJobStateEntity(
    @PrimaryKey val phoneNumber: String,
    val completedProviders: String, // JSON array of provider IDs (legacy CSV still read)
    val satisfiedCapabilities: String, // JSON array of Capability names (legacy CSV still read)
    val lastUpdated: Long = System.currentTimeMillis()
)
