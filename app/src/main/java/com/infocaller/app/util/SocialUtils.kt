package com.infocaller.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile

object SocialUtils {
    private val gson = Gson()

    fun toJson(profiles: List<SocialProfile>): String {
        return gson.toJson(profiles)
    }

    fun fromJson(json: String?): List<SocialProfile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<SocialProfile>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getSocialIntent(context: Context, profile: SocialProfile): Intent? {
        val url = profile.profileUrl ?: return null
        if (profile.platform.lowercase() == "whatsapp") {
            val waUri = if (url.contains("wa.me") || url.contains("api.whatsapp.com")) {
                if (url.contains("text=")) Uri.parse(url)
                else Uri.parse("${url}${if (url.contains("?")) "&" else "?"}text=${Uri.encode("Hello")}")
            } else Uri.parse(url)
            return Intent(Intent.ACTION_VIEW).apply { data = waUri; setPackage("com.whatsapp") }
        }
        val uri = Uri.parse(url)
        return when (profile.platform.lowercase()) {
            "telegram" -> Intent(Intent.ACTION_VIEW).apply { data = uri; setPackage("org.telegram.messenger") }
            "facebook" -> Intent(Intent.ACTION_VIEW).apply { data = uri; setPackage("com.facebook.katana") }
            "instagram" -> Intent(Intent.ACTION_VIEW).apply { data = uri; setPackage("com.instagram.android") }
            else -> Intent(Intent.ACTION_VIEW, uri)
        }
    }

    fun whatsappHelloIntent(context: Context, phoneE164: String, message: String = "Hello"): Intent {
        val digits = phoneE164.filter { it.isDigit() }
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
        return Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") }
    }

    fun openSocialProfile(context: Context, profile: SocialProfile) {
        val intent = getSocialIntent(context, profile) ?: return
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(profile.profileUrl))
            context.startActivity(browserIntent)
        }
    }
    
    fun isConfirmed(profile: SocialProfile): Boolean {
        return profile.status == SocialLookupStatus.CONFIRMED || 
               profile.status == SocialLookupStatus.PUBLIC_MATCH
    }

    fun getLogoUrl(platform: String): String {
        val id = try { com.infocaller.app.BuildConfig.BRANDFETCH_CLIENT_ID } catch(_:Exception) { "1idt4fOOzudt9xCz11q" }
        val domain = when (platform.lowercase()) {
            "whatsapp" -> "whatsapp.com"; "telegram" -> "telegram.org"; "facebook" -> "facebook.com"
            "instagram" -> "instagram.com"; "linkedin" -> "linkedin.com"; "twitter", "x" -> "x.com"
            "github" -> "github.com"; "skype" -> "skype.com"; "snapchat" -> "snapchat.com"
            "tiktok" -> "tiktok.com"; "viber" -> "viber.com"; "signal" -> "signal.org"
            "line" -> "line.me"; "messenger" -> "messenger.com"; "youtube" -> "youtube.com"
            "reddit" -> "reddit.com"; "behance" -> "behance.net"; "dribbble" -> "dribbble.com"
            else -> "${platform.lowercase()}.com"
        }
        return "https://cdn.brandfetch.io/domain/$domain?c=$id"
    }

    
    fun filteredUsedProfiles(profiles: List<SocialProfile>): List<SocialProfile> {
        return profiles.filter { p ->
            !p.profileUrl.isNullOrBlank() &&
            (p.status == SocialLookupStatus.CONFIRMED || p.status == SocialLookupStatus.PUBLIC_MATCH) &&
            p.platform.lowercase() !in setOf("generic","unknown")
        }.distinctBy { it.platform.lowercase() }
    }
}
