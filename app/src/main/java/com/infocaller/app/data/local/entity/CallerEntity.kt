package com.infocaller.app.data.local.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.SpamStatus

@Keep
@Entity(tableName = "callers", indices = [Index(value = ["phoneNumber"])])
data class CallerEntity(
    @PrimaryKey val phoneNumber: String,
    val displayName: String?,
    val alias: String?,
    val photoUrl: String?,
    val organization: String?,
    val country: String?,
    val region: String?,
    val carrier: String?,
    val spamScore: Int,
    val reportCount: Int,
    val isVerified: Boolean,
    val spamStatus: String,
    val socialMediaLinks: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val source: String = "REMOTE"
) {
    fun toDomain(): Caller {
        return Caller(
            phoneNumber = phoneNumber,
            displayName = displayName,
            alias = alias,
            photoUrl = photoUrl,
            organization = organization,
            country = country,
            region = region,
            carrier = carrier,
            spamScore = spamScore,
            reportCount = reportCount,
            isVerified = isVerified,
            spamStatus = SpamStatus.valueOf(spamStatus),
            socialMediaLinks = socialMediaLinks?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        )
    }

    companion object {
        fun fromDomain(caller: Caller): CallerEntity {
            return CallerEntity(
                phoneNumber = caller.phoneNumber,
                displayName = caller.displayName,
                alias = caller.alias,
                photoUrl = caller.photoUrl,
                organization = caller.organization,
                country = caller.country,
                region = caller.region,
                carrier = caller.carrier,
                spamScore = caller.spamScore,
                reportCount = caller.reportCount,
                isVerified = caller.isVerified,
                spamStatus = caller.spamStatus.name,
                socialMediaLinks = if (caller.socialMediaLinks.isEmpty()) null else caller.socialMediaLinks.joinToString(","),
                lastUpdated = System.currentTimeMillis(),
                source = "LOCAL"
            )
        }
    }
}
