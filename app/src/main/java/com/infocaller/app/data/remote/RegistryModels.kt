package com.infocaller.app.data.remote

import com.google.gson.annotations.SerializedName

data class RegistryCallerRecord(
    @SerializedName("phoneHash") val phoneHash: String? = null,
    @SerializedName("normalizedPhone") val normalizedPhone: String? = null,
    @SerializedName("displayName") val displayName: String? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("carrier") val carrier: String? = null,
    @SerializedName("photoUrl") val photoUrl: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("confidence") val confidence: String? = null,
    @SerializedName("updatedAt") val updatedAt: Long? = null,
    @SerializedName("expiresAt") val expiresAt: Long? = null
)

data class RegistryShardResponse(
    @SerializedName("version") val version: String? = null,
    @SerializedName("records") val records: List<RegistryCallerRecord> = emptyList()
)
