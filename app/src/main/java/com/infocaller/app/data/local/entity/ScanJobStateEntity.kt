package com.infocaller.app.data.local.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey

@Keep
@Entity(tableName = "scan_job_states")
data class ScanJobStateEntity(
    @PrimaryKey val phoneNumber: String,
    val completedProviders: String,
    val satisfiedCapabilities: String,
    val lastUpdated: Long = System.currentTimeMillis()
)
