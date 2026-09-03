package com.infocaller.app.data.local.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Keep
@Entity(tableName = "nid_records", indices = [Index(value = ["nid"], unique = false), Index(value = ["number"], unique = false), Index(value = ["dob"], unique = false)])
data class NidEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val nid: String,
    val dob: String,
    val database: String? = null,
    val tg: String? = null,
    // enriched fields from NID gov lookup (filled after NID+ DOB deep fetch)
    val nameEn: String? = null,
    val nameBn: String? = null,
    val fatherName: String? = null,
    val motherName: String? = null,
    val address: String? = null,
    val photoUrl: String? = null,
    val photoBase64: String? = null,
    val lastEnrichedAt: Long = 0
)
