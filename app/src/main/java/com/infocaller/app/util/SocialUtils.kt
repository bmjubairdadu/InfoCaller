package com.infocaller.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.await

object SocialUtils {
    private val gson = Gson()

    fun toJson(profiles: List<SocialProfile>): String {
        return gson.toJson(profiles)
    }

    fun photosToJson(photos: List<com.infocaller.app.domain.model.PhotoCandidate>): String {
        return gson.toJson(photos)
    }

    fun photosFromJson(json: String?): List<com.infocaller.app.domain.model.PhotoCandidate> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<com.infocaller.app.domain.model.PhotoCandidate>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun bestHttpPhoto(
        primary: String?,
        candidatesJson: String?,
        socialsJson: String?,
        fallback: String? = null
    ): String? {
        primary?.takeIf { it.startsWith("http") }?.let { return it }
        photosFromJson(candidatesJson).firstOrNull { it.url.startsWith("http") }?.url?.let { return it }
        fromJson(socialsJson).mapNotNull { it.avatarUrl?.takeIf { u -> u.startsWith("http") } }.firstOrNull()?.let { return it }
        fallback?.takeIf { it.startsWith("http") }?.let { return it }
        return null
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

    fun altNamesFromJson(json: String?): Map<String, List<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getSocialIntent(context: Context, profile: SocialProfile): Intent? {
        val url = profile.profileUrl?.trim() ?: return null
        if (url.isBlank()) return null
        val platform = profile.platform.lowercase()
        if (platform == "whatsapp") {
            val waUri = try { Uri.parse(url) } catch (_: Exception) { return null }
            return Intent(Intent.ACTION_VIEW).apply {
                data = waUri
                setPackage("com.whatsapp")
            }
        }
        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
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
            "soundcloud" -> "com.soundcloud.android"
            "reddit" -> "com.reddit.frontpage"
            "github" -> "com.github.android"
            "gitlab" -> "com.gitlab.com"
            "pinterest" -> "com.pinterest"
            "medium" -> "com.medium.reader"
            "devto", "dev.to" -> "com.devto.dev"
            "kaggle" -> "com.kaggle.android"
            "steam" -> "com.valvesoftware.android.steam.community"
            "twitch" -> "tv.twitch.android.app"
            "chess.com", "chess" -> "com.chess"
            "discord" -> "com.discord"
            "pimeyes" -> null
            "gravatar" -> null
            "sync.me", "syncme" -> null
            else -> null
        }
        return Intent(Intent.ACTION_VIEW).apply {
            data = uri
            if (pkg != null) setPackage(pkg)
        }
    }

    fun openSocialProfile(context: Context, profile: SocialProfile) {
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

    fun logoModel(context: Context, platform: String): Any {
        try {
            val dir = java.io.File(context.filesDir, "social_logos")
            val key = platform.lowercase().replace(" ", "_")
            val cached = java.io.File(dir, "$key.png")
            if (cached.exists() && cached.length() > 1000) return cached
        } catch (_: Exception) { }
        return getLogoUrl(platform)
    }

    suspend fun prefetchLogos(context: Context, platforms: List<String>) {
        try {
            val dir = java.io.File(context.filesDir, "social_logos")
            if (!dir.exists()) dir.mkdirs()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
            platforms.map { it.lowercase() }.distinct().take(8).forEach { p ->
                try {
                    val cached = java.io.File(dir, "${p.replace(" ", "_")}.png")
                    if (cached.exists() && cached.length() > 1000) return@forEach
                    val req = okhttp3.Request.Builder().url(getLogoUrl(p))
                        .header("Accept", "image/png,image/jpeg,image/*").build()
                    client.newCall(req).await().use { resp ->
                        if (!resp.isSuccessful) return@forEach
                        if (resp.header("Content-Type")?.startsWith("image/") != true) return@forEach
                        val bytes = resp.body?.bytes() ?: return@forEach
                        if (bytes.size < 1000) return@forEach
                        try { cached.writeBytes(bytes) } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    fun getLogoUrl(platform: String): String {
        val raw = try { com.infocaller.app.BuildConfig.BRANDFETCH_CLIENT_ID } catch(_:Exception) { "" }
        val id = if (raw.isNullOrBlank()) "1idt4fOOzudt9xCz11q" else raw
        val domain = when (platform.lowercase()) {
            "whatsapp" -> "whatsapp.com"; "telegram" -> "telegram.org"; "facebook" -> "facebook.com"
            "instagram" -> "instagram.com"; "linkedin" -> "linkedin.com"; "twitter", "x" -> "x.com"
            "github" -> "github.com"; "skype" -> "skype.com"; "snapchat" -> "snapchat.com"
            "tiktok" -> "tiktok.com"; "viber" -> "viber.com"; "signal" -> "signal.org"
            "line" -> "line.me"; "messenger" -> "messenger.com"; "youtube" -> "youtube.com"
            "reddit" -> "reddit.com"; "behance" -> "behance.net"; "dribbble" -> "dribbble.com"
            "sync.me", "syncme" -> "sync.me"
            "gravatar" -> "gravatar.com"; "truecaller" -> "truecaller.com"
            "steam" -> "steampowered.com"; "twitch" -> "twitch.tv"
            "chess.com", "chess" -> "chess.com"
            "pinterest" -> "pinterest.com"; "medium" -> "medium.com"
            "devto", "dev.to" -> "dev.to"; "hashnode" -> "hashnode.com"
            "kaggle" -> "kaggle.com"; "soundcloud" -> "soundcloud.com"
            "spotify" -> "spotify.com"; "gitlab" -> "gitlab.com"
            "discord" -> "discord.com"
            else -> "${platform.lowercase().replace(" ", "")}.com"
        }
        return "https://cdn.brandfetch.io/domain/$domain?c=$id"
    }

    fun filteredUsedProfiles(profiles: List<SocialProfile>): List<SocialProfile> {
        return profiles.filter { p ->
            val url = p.profileUrl?.trim().orEmpty()
            if (url.isBlank()) return@filter false
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
