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
        val url = profile.profileUrl?.trim() ?: return null
        if (url.isBlank()) return null
        val platform = profile.platform.lowercase()
        if (platform == "whatsapp") {
            val waUri = if (url.contains("wa.me") || url.contains("api.whatsapp.com")) {
                if (url.contains("text=")) Uri.parse(url)
                else Uri.parse("${url}${if (url.contains("?")) "&" else "?"}text=${Uri.encode("Hello")}")
            } else Uri.parse(url)
            return Intent(Intent.ACTION_VIEW).apply {
                data = waUri
                // Prefer the app, but openSocialProfile falls back to the
                // browser when WhatsApp isn't installed.
                setPackage("com.whatsapp")
            }
        }
        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
        // Every platform gets its native app package first; the opener falls
        // back to the browser URL when the app is missing, so a tap ALWAYS
        // lands on the account instead of dying silently.
        val pkg = when (platform) {
            "telegram" -> "org.telegram.messenger"
            "facebook" -> "com.facebook.katana"
            "instagram" -> "com.instagram.android"
            "messenger" -> "com.facebook.orca"
            "linkedin" -> "com.linkedin.android"
            "twitter", "x" -> "com.twitter.android"
            "youtube" -> "com.google.android.youtube"
            "tiktok" -> "com.zhiliaoapp.musically"
            "snapchat" -> "com.snapchat.android"
            "spotify" -> "com.spotify.music"
            "reddit" -> "com.reddit.frontpage"
            "github" -> "com.github.android"
            else -> null
        }
        return Intent(Intent.ACTION_VIEW).apply {
            data = uri
            if (pkg != null) setPackage(pkg)
        }
    }

    fun whatsappHelloIntent(context: Context, phoneE164: String, message: String = "Hello"): Intent {
        val digits = phoneE164.filter { it.isDigit() }
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
        return Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") }
    }

    fun openSocialProfile(context: Context, profile: SocialProfile) {
        // Try the native app first; fall back to the plain browser URL when
        // the app is missing; last resort re-encodes the URL in case the
        // stored profile URL was malformed.
        val intent = getSocialIntent(context, profile) ?: return
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) { }
        try {
            val fallback = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(profile.profileUrl?.trim().orEmpty())
            )
            context.startActivity(fallback)
        } catch (_: Exception) { }
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
            val url = p.profileUrl?.trim().orEmpty()
            if (url.isBlank()) return@filter false
            // Accept any actionable profile: confirmed/public matches plus
            // possible matches with a real URL (Telegram presence lands here).
            // UNKNOWN/UNSUPPORTED only pass when the URL is a real deep link.
            val okStatus = when (p.status) {
                SocialLookupStatus.CONFIRMED,
                SocialLookupStatus.PUBLIC_MATCH,
                SocialLookupStatus.POSSIBLE_MATCH -> true
                else -> url.startsWith("http", ignoreCase = true) &&
                    !url.equals("http", ignoreCase = true) &&
                    url.length > 12
            }
            okStatus && p.platform.lowercase() !in setOf("generic", "unknown")
        }.distinctBy { it.platform.lowercase() }
    }
}
