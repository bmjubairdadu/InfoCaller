package com.infocaller.app.data.local.model

import androidx.room.Embedded
import androidx.room.Relation
import com.infocaller.app.data.local.entity.LocalContactEntity
import com.infocaller.app.data.local.entity.ContactEnrichmentEntity

data class EnrichedContact(
    @Embedded val contact: LocalContactEntity,
    @Relation(
        parentColumn = "phoneNumber",
        entityColumn = "normalizedPhoneNumber"
    )
    val enrichment: ContactEnrichmentEntity?
)
