package com.infocaller.app.data.remote

import com.google.gson.JsonObject
import com.infocaller.app.domain.engine.PartialResult
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.domain.model.PhotoCandidate

object TruecallerParser {
    private fun safeString(obj: JsonObject?, key: String, maxLen: Int = 80): String? {
        return try {
            obj?.get(key)?.takeIf { !it.isJsonNull }?.let {
                if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString.trim().takeIf { s -> s.isNotBlank() }?.take(maxLen)
                else null
            }
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null } catch (_: StackOverflowError) { null }
    }

    private fun safeArray(obj: JsonObject?, key: String): com.google.gson.JsonArray? {
        return try {
            val el = obj?.get(key) ?: return null
            if (el.isJsonArray) el.asJsonArray else null
        } catch (_: Exception) { null } catch (_: Error) { null }
    }

    private fun safeObject(el: com.google.gson.JsonElement?): JsonObject? {
        return try {
            if (el != null && el.isJsonObject) el.asJsonObject else null
        } catch (_: Exception) { null } catch (_: Error) { null }
    }

    private fun usableImage(url: String?): Boolean {
        return try { com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(url) } catch (_: Exception) { false } catch (_: Error) { false }
    }

    fun mapResult(data: JsonObject, providerId: String, providerVersion: String): PartialResult {
        return try {
            mapResultSafe(data, providerId, providerVersion)
        } catch (_: Exception) {
            PartialResult(source = "Truecaller Authorized", providerId = providerId, providerVersion = providerVersion)
        } catch (_: Error) {
            PartialResult(source = "Truecaller Authorized", providerId = providerId, providerVersion = providerVersion)
        }
    }

    private fun mapResultSafe(data: JsonObject, providerId: String, providerVersion: String): PartialResult {
        val name = safeString(data, "name", 60)
        val altName = safeString(data, "altName", 60)
        // image can live in several shapes; never let a wrong JSON type crash the scan.
        var image: String? = safeString(data, "image", 2000)
        if (image == null) image = safeString(data, "avatar", 2000)
        if (image == null) image = safeString(data, "picture", 2000)
        if (image == null) {
            try {
                val phones = safeArray(data, "phones")
                val first = phones?.firstOrNull()?.let { safeObject(it) }
                image = safeString(first, "image", 2000)
            } catch (_: Exception) { } catch (_: Error) { }
        }
        if (!usableImage(image)) image = null

        var city: String? = null
        var country: String? = null
        var timezone: String? = null
        try {
            val addresses = safeArray(data, "addresses")
            val primaryAddress = addresses?.firstOrNull()?.let { safeObject(it) }
            city = safeString(primaryAddress, "city", 60)
            country = safeString(primaryAddress, "countryCode", 8)
                ?: safeString(primaryAddress, "country", 60)
            timezone = safeString(primaryAddress, "timeZone", 60)
                ?: safeString(primaryAddress, "timezone", 60)
        } catch (_: Exception) { } catch (_: Error) { }

        var spamType: String? = null
        var spamScore: Int? = null
        try {
            val spamInfo = try {
                val el = data.get("spamInfo")
                if (el != null && el.isJsonObject) el.asJsonObject else null
            } catch (_: Exception) { null } catch (_: Error) { null }
            spamType = safeString(spamInfo, "spamType", 40)
            spamScore = try {
                spamInfo?.get("spamScore")?.takeIf { !it.isJsonNull }?.let {
                    if (it.isJsonPrimitive) {
                        val p = it.asJsonPrimitive
                        when {
                            p.isNumber -> p.asInt.coerceIn(0, 100)
                            else -> null
                        }
                    } else null
                }
            } catch (_: Exception) { null } catch (_: Error) { null }
        } catch (_: Exception) { } catch (_: Error) { }

        val socialProfiles = mutableListOf<SocialProfile>()
        var email: String? = null
        try {
            val internetAddresses = safeArray(data, "internetAddresses")
            val count = minOf(internetAddresses?.size() ?: 0, 20)
            for (i in 0 until count) {
                try {
                    val addr = safeObject(internetAddresses?.get(i)) ?: continue
                    val service = safeString(addr, "service", 30)?.lowercase() ?: continue
                    val id = safeString(addr, "id", 300) ?: continue
                    val caption = safeString(addr, "caption", 300)
                    if (service.isBlank() || id.isBlank()) continue
                    if (service == "email") {
                        if (id.contains("@") && id.length <= 120) email = id
                    } else {
                        val platform = service.replaceFirstChar { it.uppercase() }.take(30)
                        if (id.contains(".") && !id.startsWith("http")) {
                            continue
                        } else if (id.startsWith("http") && id.length < 25) {
                            continue
                        } else {
                            val profileUrl = (if (id.startsWith("http")) id else caption ?: id).take(500)
                            if (!profileUrl.startsWith("http")) continue
                            socialProfiles.add(SocialProfile(
                                platform = platform,
                                username = id.take(120),
                                profileUrl = profileUrl,
                                status = SocialLookupStatus.PUBLIC_MATCH,
                                source = "Truecaller"
                            ))
                            if (socialProfiles.size >= 10) break
                        }
                    }
                } catch (_: Exception) { continue } catch (_: Error) { continue }
            }
        } catch (_: Exception) { } catch (_: Error) { }

        var carrier: String? = null
        var lineType: String? = null
        try {
            val phones = safeArray(data, "phones")
            val first = phones?.firstOrNull()?.let { safeObject(it) }
            carrier = safeString(first, "carrier", 40)
            lineType = safeString(first, "numberType", 30)
                ?: safeString(first, "lineType", 30)
        } catch (_: Exception) { } catch (_: Error) { }
        val about = if (spamType != null) "Spam: $spamType${if (spamScore != null) " ($spamScore)" else ""}" else null

        val photoCandidates = mutableListOf<PhotoCandidate>()
        if (usableImage(image)) {
            try {
                photoCandidates.add(PhotoCandidate(provider = "Truecaller", url = image!!.trim(), sourcePriority = 90, timestamp = System.currentTimeMillis()))
            } catch (_: Exception) { } catch (_: Error) { }
        }

        return PartialResult(
            name = name,
            alternateName = altName,
            imageUrl = image,
            photoCandidates = photoCandidates,
            about = about,
            city = city,
            country = country,
            timezone = timezone,
            email = email,
            carrier = carrier,
            lineType = lineType,
            socialProfiles = socialProfiles,
            confidence = if (spamType != null) 0.9f else 0.95f,
            source = "Truecaller Authorized",
            providerId = providerId,
            providerVersion = providerVersion
        )
    }
}
