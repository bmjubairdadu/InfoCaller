package com.infocaller.app.util

import com.infocaller.app.domain.engine.IdentifierType

object PhotoPolicy {
    private val LOGO_HOST_MARKERS = listOf(
        "brandfetch", "cdn.brandfetch",
        "telegram.org/img", "telegram.org/static",
        "twitch.tv/assets", "twitch.tv/logo",
        "steamcommunity.com/public/images",
        "facebook.com/rsrc", "fbcdn.net/rsrc",
        "gstatic.com", "googleusercontent.com/logo",
        "gravatar.com/avatar/",
    )

    private val LOGO_FILE_MARKERS = listOf(
        "rsrc.php",
        "placeholder", "default_avatar", "default-avatar",
        "no_photo", "no-photo", "anonymous",
        "logo", "brand", "sprite",
        "/icon", "icon-", "icon_", "-icon", "_icon",
        "t_logo", "twitch_logo",
    )

    private val HTML_PAGE_SUFFIXES = listOf(".html", ".htm")

    fun isLogoUrl(url: String?): Boolean {
        return try {
            val u = url?.trim().orEmpty()
            if (u.isBlank()) return true
            // Local cached photos (e.g. Eyecon photos downloaded with auth) are always valid.
            if (u.startsWith("file://")) return false
            if (!u.startsWith("http")) return true
            if (u.length < 20 || u.length > 2000) return true
            val lower = u.lowercase()
            if (lower.contains("sync.me")) return true
            if (lower.contains("truecaller.com/search") || lower.contains("truecaller.com/bd")) return true
            for (h in LOGO_HOST_MARKERS) {
                if (h == "gravatar.com/avatar/") continue
                if (lower.contains(h)) return true
            }
            if (lower.contains("gravatar.com/avatar/") &&
                (lower.contains("d=mp") || lower.contains("d=identicon") ||
                    lower.contains("d=blank") || lower.contains("d=retro") ||
                    lower.contains("d=monsterid") || lower.contains("d=wavatar") ||
                    lower.contains("d=404"))
            ) return true
            for (m in LOGO_FILE_MARKERS) {
                if (lower.contains(m)) {
                    return true
                }
            }
            for (s in HTML_PAGE_SUFFIXES) {
                if (lower.substringBefore("?").endsWith(s)) return true
            }
            if (lower.substringBefore("?").endsWith(".php") && !lower.contains("pic")) return true
            false
        } catch (_: Exception) { true } catch (_: Error) { true }
    }

    fun isUsablePhotoUrl(url: String?): Boolean {
        return try {
            if (isLogoUrl(url)) return false
            val u = url!!.trim()
            if (u.startsWith("file://")) return u.length > 7
            u.startsWith("http") && u.length >= 20
        } catch (_: Exception) { false } catch (_: Error) { false }
    }

    fun isAutoProvider(provider: String?, identifierType: String?): Boolean {
        return try {
            val p = provider?.trim()?.lowercase().orEmpty()
            if (p.isBlank()) return false
            if (p.startsWith("user:")) return true
            if (p.contains("truecaller") || p.contains("eyecon")) return true
            if (identifierType == IdentifierType.EMAIL) {
                if (p.contains("gravatar")) return true
                if (p.contains("github")) return true
                if (p.contains("gitlab")) return true
                if (p.contains("unavatar")) return true
                if (p.contains("email")) return true
            }
            false
        } catch (_: Exception) { false } catch (_: Error) { false }
    }

    fun isUserPicked(source: String?): Boolean {
        return try { source?.trim()?.lowercase()?.startsWith("user:") == true } catch (_: Exception) { false } catch (_: Error) { false }
    }

    fun userSourceOf(provider: String?): String {
        val p = try { provider?.trim()?.take(60)?.ifBlank { "manual" } ?: "manual" } catch (_: Exception) { "manual" } catch (_: Error) { "manual" }
        return "user:$p"
    }

    fun displaySource(source: String?): String {
        return try { source?.removePrefix("user:")?.removePrefix("User:")?.takeIf { it.isNotBlank() } ?: "verified" } catch (_: Exception) { "verified" } catch (_: Error) { "verified" }
    }
}
