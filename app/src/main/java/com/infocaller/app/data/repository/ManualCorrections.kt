package com.infocaller.app.data.repository

import android.content.Context
import com.infocaller.app.data.local.entity.ContactEnrichmentEntity
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.util.PhoneNumberUtils

object ManualCorrections {

    private const val PREFS = "manual_corrections"
    private const val MAX_NAME = 60
    private const val MAX_CITY = 60
    private const val MAX_CARRIER = 60

    private fun clean(raw: String?, max: Int): String? {
        val v = raw?.trim()?.replace(Regex("\\s+"), " ")?.take(max) ?: return null
        return v.ifBlank { null }
    }

    fun isValidUrl(raw: String?): String? {
        val v = raw?.trim() ?: return null
        if (!v.startsWith("http://") && !v.startsWith("https://")) return null
        if (v.length > 2000) return null
        return v
    }

    fun save(
        context: Context,
        number: String,
        name: String?,
        city: String?,
        carrier: String?,
        photoUrl: String?
    ): Boolean {
        return try {
            val normalized = PhoneNumberUtils.normalize(number)
            if (normalized.isBlank()) return false
            val cleanName = clean(name, MAX_NAME)
            val cleanCity = clean(city, MAX_CITY)
            val cleanCarrier = clean(carrier, MAX_CARRIER)
            val cleanPhoto = isValidUrl(photoUrl)

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("name_$normalized", cleanName)
                .putString("city_$normalized", cleanCity)
                .putString("carrier_$normalized", cleanCarrier)
                .putString("photo_$normalized", cleanPhoto)
                .putLong("at_$normalized", System.currentTimeMillis())
                .apply()
            true
        } catch (_: Exception) { false } catch (_: Error) { false }
    }

    fun read(context: Context, number: String): Map<String, String?> {
        return try {
            val normalized = PhoneNumberUtils.normalize(number)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            mapOf(
                "name" to prefs.getString("name_$normalized", null),
                "city" to prefs.getString("city_$normalized", null),
                "carrier" to prefs.getString("carrier_$normalized", null),
                "photo" to prefs.getString("photo_$normalized", null)
            )
        } catch (_: Exception) { emptyMap() } catch (_: Error) { emptyMap() }
    }

    fun applyTo(
        context: Context,
        number: String,
        existing: ContactEnrichmentEntity?,
        photoCandidates: List<PhotoCandidate>
    ): ContactEnrichmentEntity? {
        val normalized = PhoneNumberUtils.normalize(number)
        if (normalized.isBlank()) return existing
        val stored = read(context, normalized)
        val name = stored["name"]
        val city = stored["city"]
        val carrier = stored["carrier"]
        val photo = stored["photo"]
        if (name == null && city == null && carrier == null && photo == null) return existing

        val mergedName = name ?: existing?.publicName
        val mergedCity = city ?: existing?.city
        val mergedCarrier = carrier ?: existing?.carrier
        val mergedPhoto = photo ?: existing?.profileImageUrl
        val candidates = if (photo != null) {
            listOf(PhotoCandidate(provider = "User edit", url = photo)) + photoCandidates.take(5)
        } else photoCandidates

        return (existing ?: ContactEnrichmentEntity(
            normalizedPhoneNumber = normalized,
            expiresAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        )).copy(
            publicName = mergedName,
            publicNameSource = if (name != null) "User edit" else existing?.publicNameSource,
            city = mergedCity,
            carrier = mergedCarrier,
            profileImageUrl = mergedPhoto,
            profileImageSource = if (photo != null) "user" else existing?.profileImageSource,
            photoCandidatesJson = if (candidates.isNotEmpty()) {
                runCatching { com.infocaller.app.util.SocialUtils.photosToJson(candidates) }.getOrNull()
            } else existing?.photoCandidatesJson,
            lastChecked = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        )
    }

    fun toLookupResult(
        context: Context,
        number: String,
        existing: ContactEnrichmentEntity?
    ): LookupResult {
        val normalized = PhoneNumberUtils.normalize(number)
        val name = existing?.publicName
        val photo = existing?.profileImageUrl
        return LookupResult(
            phoneNumber = normalized,
            name = name,
            nameSource = if (name != null) "User edit" else null,
            imageUrl = photo,
            imageSource = if (photo != null) "user" else null,
            photoCandidates = if (photo != null) listOf(PhotoCandidate(provider = "User edit", url = photo)) else emptyList(),
            city = existing?.city,
            country = existing?.country ?: "Bangladesh",
            carrier = existing?.carrier,
            sources = listOf("User edit"),
            confidence = if (name != null) 0.95f else 0.8f
        )
    }
}
