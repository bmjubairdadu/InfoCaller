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
        val uri = Uri.parse(url)
        
        return when (profile.platform.lowercase()) {
            "whatsapp" -> {
                Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    setPackage("com.whatsapp")
                }
            }
            "telegram" -> {
                Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    setPackage("org.telegram.messenger")
                }
            }
            "facebook" -> {
                Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    setPackage("com.facebook.katana")
                }
            }
            "instagram" -> {
                Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    setPackage("com.instagram.android")
                }
            }
            else -> Intent(Intent.ACTION_VIEW, uri)
        }
    }

    fun openSocialProfile(context: Context, profile: SocialProfile) {
        val intent = getSocialIntent(context, profile) ?: return
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to browser if app not installed
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(profile.profileUrl))
            context.startActivity(browserIntent)
        }
    }
    
    fun isConfirmed(profile: SocialProfile): Boolean {
        return profile.status == SocialLookupStatus.CONFIRMED || 
               profile.status == SocialLookupStatus.PUBLIC_MATCH
    }
}
