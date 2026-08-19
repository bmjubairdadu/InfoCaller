package com.infocaller.app.data.remote

import com.google.gson.JsonObject
import com.infocaller.app.domain.engine.PartialResult
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile

/**
 * Robust parser for Truecaller V2 search responses.
 * Reimplemented from reference study.
 */
object TruecallerParser {

    fun mapResult(data: JsonObject, providerId: String, providerVersion: String): PartialResult {
        val name = data.get("name")?.asString
        val altName = data.get("altName")?.asString
        val image = data.get("image")?.asString
        val about = data.get("about")?.asString
        
        val addresses = data.getAsJsonArray("addresses")
        val primaryAddress = addresses?.firstOrNull()?.asJsonObject
        val city = primaryAddress?.get("city")?.asString
        val country = primaryAddress?.get("countryCode")?.asString
        val timezone = primaryAddress?.get("timeZone")?.asString
        
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
                val platform = service.replaceFirstChar { c -> c.uppercase() }
                socialProfiles.add(SocialProfile(
                    platform = platform,
                    username = id,
                    profileUrl = caption ?: id,
                    status = SocialLookupStatus.PUBLIC_MATCH
                ))
            }
        }
        
        val spam = data.get("spamInfo")?.asJsonObject
        val score = spam?.get("spamScore")?.asInt ?: 0
        val type = spam?.get("spamType")?.asString
        
        // Also check phones for carrier
        val carrier = data.getAsJsonArray("phones")?.firstOrNull()?.asJsonObject?.get("carrier")?.asString

        return PartialResult(
            name = name,
            alternateName = altName,
            imageUrl = image,
            about = about,
            city = city,
            country = country,
            timezone = timezone,
            email = email,
            carrier = carrier,
            socialProfiles = socialProfiles,
            spamScore = score,
            spamType = type,
            confidence = 0.95f,
            source = "Truecaller Intelligence",
            providerId = providerId,
            providerVersion = providerVersion
        )
    }
}
