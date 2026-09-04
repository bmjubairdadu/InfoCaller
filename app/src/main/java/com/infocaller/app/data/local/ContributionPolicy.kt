package com.infocaller.app.data.local

import java.security.MessageDigest

/**
 * Pure, JVM-testable contribution policy.
 *
 * Privacy contract (mirrors backend mergeRecords blacklist + Supabase RLS):
 * - MAY share (only when derived from PUBLIC enrichment, never from local address book):
 *   phone_hash, display_name (public enriched name), report/spam counters are server-computed.
 * - NEVER share: localName (user's private contact name), contactId, lookupKey,
 *   privateNote, photo URIs, messages, or any unrelated local metadata.
 */
object ContributionPolicy {
    const val CONSENT_VERSION = 1

    /** Fields the client is allowed to send. Everything else is dropped. */
    val ALLOWED_FIELDS = setOf("phone_hash", "display_name")

    /** Explicitly forbidden — must never leave the device. */
    val BLOCKED_FIELDS = setOf(
        "localName", "local_name", "displayName_local",
        "contactId", "contact_id", "lookupKey", "lookup_key",
        "privateNote", "private_note", "note",
        "photoUri", "photo_uri", "photoThumbnailUri",
        "messages", "sms", "callLog", "email_local"
    )

    enum class Decision { UNASKED, ACCEPTED, DECLINED }

    data class PermittedPayload(
        val phoneHash: String,
        val displayName: String?
    ) {
        fun toMap(): Map<String, String?> = mapOf(
            "phone_hash" to phoneHash,
            "display_name" to displayName
        )
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidHash(hash: String): Boolean =
        hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }

    fun isValidDisplayName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val t = name.trim()
        if (t.length !in 2..80) return false
        val lower = t.lowercase()
        // Reject obvious placeholders / digits-only strings.
        if (lower in setOf("unknown", "unknown caller", "unnamed contact")) return false
        if (t.filter { it.isDigit() }.length >= 7 && t.filter { it.isLetter() }.isEmpty()) return false
        if (lower.contains("infocaller") && t.length < 8) return false
        return true
    }

    /**
     * Build the ONLY payload shape allowed to leave the device.
     * @param normalizedE164 E.164 number (used only to derive hash; never sent).
     * @param publicName name from PUBLIC enrichment (owner-verified / registry / OSINT),
     *   NOT the user's local contact name. Null/invalid -> payload with null name
     *   (still useful as a "seen" signal? caller may skip instead).
     */
    fun buildPermitted(normalizedE164: String, publicName: String?): PermittedPayload? {
        if (!Regex("^\\+[1-9]\\d{7,14}$").matches(normalizedE164)) return null
        val hash = sha256Hex(normalizedE164)
        val clean = publicName?.trim()?.takeIf { isValidDisplayName(it) }
        return PermittedPayload(phoneHash = hash, displayName = clean)
    }

    /** Strip any forbidden keys from an outgoing map (defense in depth). */
    fun sanitizeOutgoing(map: Map<String, Any?>): Map<String, Any?> =
        map.filterKeys { it in ALLOWED_FIELDS && it !in BLOCKED_FIELDS }

    /** Stable hash of the payload to detect changes (dedup: skip re-upload when unchanged). */
    fun payloadFingerprint(payload: PermittedPayload): String =
        sha256Hex("${payload.phoneHash}|${payload.displayName ?: ""}")
}
