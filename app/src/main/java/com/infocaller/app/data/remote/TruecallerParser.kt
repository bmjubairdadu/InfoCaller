package com.infocaller.app.data.remote

import com.google.gson.JsonObject
import com.infocaller.app.domain.engine.PartialResult
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.domain.model.PhotoCandidate


object TruecallerParser {

    fun mapResult(data: JsonObject, providerId: String, providerVersion: String): PartialResult {
        val name = data.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        val altName = data.get("altName")?.takeIf { !it.isJsonNull }?.asString
        val image = data.get("image")?.takeIf { !it.isJsonNull }?.asString
            ?: data.get("avatar")?.takeIf { !it.isJsonNull }?.asString
            ?: data.get("picture")?.takeIf { !it.isJsonNull }?.asString
            ?: data.getAsJsonArray("phones")?.firstOrNull()?.asJsonObject?.get("image")?.takeIf { !it.isJsonNull }?.asString

        val addresses = data.getAsJsonArray("addresses")
        val primaryAddress = addresses?.firstOrNull()?.asJsonObject
        val city = primaryAddress?.get("city")?.takeIf { !it.isJsonNull }?.asString
        val country = primaryAddress?.get("countryCode")?.takeIf { !it.isJsonNull }?.asString ?: primaryAddress?.get("country")?.takeIf { !it.isJsonNull }?.asString
        val timezone = primaryAddress?.get("timeZone")?.takeIf { !it.isJsonNull }?.asString
        val spamInfo = data.getAsJsonObject("spamInfo")
        val spamType = spamInfo?.get("spamType")?.takeIf { !it.isJsonNull }?.asString
        val spamScore = spamInfo?.get("spamScore")?.takeIf { !it.isJsonNull }?.asInt
        
        val internetAddresses = data.getAsJsonArray("internetAddresses")
        val socialProfiles = mutableListOf<SocialProfile>()
        var email: String? = null
        
        internetAddresses?.forEach { 
            val addr = it.asJsonObject
            val service = addr.get("service")?.asString?.lowercase()
            val id = addr.get("id")?.asString
            val caption = addr.get("caption")?.asString
            
            if (service == "email") {
                email = id
            } else if (!service.isNullOrBlank() && !id.isNullOrBlank()) {
                val platform = service.replaceFirstChar { it.uppercase() }
                if (id.contains(".") && !id.startsWith("http")) {
                } else if (id.startsWith("http") && id.length < 25) {
                } else {
                    socialProfiles.add(SocialProfile(
                        platform = platform,
                        username = id,
                        profileUrl = if (id.startsWith("http")) id else caption ?: id,
                        status = SocialLookupStatus.PUBLIC_MATCH,
                        source = "Truecaller"
                    ))
                }
            }
        }
        
        val carrier = data.getAsJsonArray("phones")?.firstOrNull()?.asJsonObject?.get("carrier")?.takeIf { !it.isJsonNull }?.asString
        val lineType = data.getAsJsonArray("phones")?.firstOrNull()?.asJsonObject?.get("numberType")?.takeIf { !it.isJsonNull }?.asString
        val about = if (spamType != null) "Spam: $spamType${if (spamScore != null) " ($spamScore)" else ""}" else null

        val photoCandidates = mutableListOf<PhotoCandidate>()
        if (!image.isNullOrBlank()) {
            photoCandidates.add(PhotoCandidate(provider = "Truecaller", url = image, sourcePriority = 90, timestamp = System.currentTimeMillis()))
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
