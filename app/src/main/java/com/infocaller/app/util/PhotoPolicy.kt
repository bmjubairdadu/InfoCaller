package com.infocaller.app.util

import com.infocaller.app.domain.engine.IdentifierType

/**
 * Single source of truth for profile-photo policy.
 *
 * Rule (user spec):
 * - AUTO-SET primary photo only from: Truecaller, Eyecon, and Email (Gravatar/GitHub/GitLab).
 * - Any other source (Telegram, Twitch, Steam, Facebook, etc.) NEVER auto-sets.
 *   They only appear as options; the one the user taps becomes primary.
 * - Official brand logos must never be treated as a profile photo.
 */
object PhotoPolicy {
    private val LOGO_HOST_MARKERS = listOf(
        "brandfetch", "cdn.brandfetch",
        "telegram.org/img", "telegram.org/static",
        "twitch.tv/assets", "twitch.tv/logo",
        "steamcommunity.com/public/images",
        "facebook.com/rsrc", "fbcdn.net/rsrc",
        "gstatic.com", "googleusercontent.com/logo",
        "gravatar.com/avatar/", // handled specially below (only default-param forms rejected)
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

    /** True if this URL is an official logo / placeholder / HTML page — never a profile photo. */
    fun isLogoUrl(url: String?): Boolean {
        return try {
            val u = url?.trim().orEmpty()
            if (u.isBlank() || !u.startsWith("http")) return true
            if (u.length < 20 || u.length > 2000) return true
            val lower = u.lowercase()
            if (lower.contains("sync.me")) return true
            if (lower.contains("truecaller.com/search") || lower.contains("truecaller.com/bd")) return true
            for (h in LOGO_HOST_MARKERS) {
                // gravatar avatar host itself is fine; only its default-image params are junk.
                if (h == "gravatar.com/avatar/") continue
                if (lower.contains(h)) return true
            }
            // Gravatar default renders (no real photo on file).
            if (lower.contains("gravatar.com/avatar/") &&
                (lower.contains("d=mp") || lower.contains("d=identicon") ||
                    lower.contains("d=blank") || lower.contains("d=retro") ||
                    lower.contains("d=monsterid") || lower.contains("d=wavatar") ||
                    lower.contains("d=404"))
            ) return true
            for (m in LOGO_FILE_MARKERS) {
                if (lower.contains(m)) {
                    // Real CDN avatars never carry these tokens; site chrome always does.
                    // Exception: static-cdn.jtvnw.net = real Twitch avatars (no logo token anyway).
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

    /** True if this URL may be shown anywhere as a photo (options or primary). */
    fun isUsablePhotoUrl(url: String?): Boolean {
        return try {
            if (isLogoUrl(url)) return false
            val u = url!!.trim()
            u.startsWith("http") && u.length >= 20
        } catch (_: Exception) { false } catch (_: Error) { false }
    }

    /**
     * True if [provider] may AUTO-SET the primary photo without the user tapping.
     * Everything else stays as a tap-to-set option only.
     * Stored user picks use source "user:<provider>" and always win (see [isUserPicked]).
     */
    fun isAutoProvider(provider: String?, identifierType: String?): Boolean {
        return try {
            val p = provider?.trim()?.lowercase().orEmpty()
            if (p.isBlank()) return false
            if (p.startsWith("user:")) return true // user already chose it -> keep as primary
            if (p.contains("truecaller") || p.contains("eyecon")) return true
            if (identifierType == IdentifierType.EMAIL) {
                if (p.contains("gravatar")) return true
                if (p.contains("github")) return true
                if (p.contains("gitlab")) return true
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
