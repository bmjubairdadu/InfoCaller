package com.infocaller.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "operator_logos")
data class OperatorLogoEntity(
    @PrimaryKey val operatorKey: String,
    val operatorName: String,
    val country: String,
    val mcc: String?,
    val mnc: String?,
    val officialDomain: String? = null,
    val localFilePath: String?,
    val source: String,
    val verified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
