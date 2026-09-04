package com.infocaller.app.data.local

import android.content.Context
import com.infocaller.app.data.local.dao.ScreeningDao
import com.infocaller.app.data.local.entity.BlockedEventEntity
import com.infocaller.app.data.local.entity.BlockedPrefixEntity
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.util.ContactUtils
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.flow.Flow

/**
 * On-device call-screening decision engine.
 *
 * Check order adapted from humanjuan/iOG26 ("How Call Blocking Works"):
 *  1. anonymous/hidden number + setting enabled
 *  2. exact personal blocklist match
 *  3. blocked prefix match
 *  4. unknown (not in contacts) + setting enabled
 *
 * Everything runs locally: Room + ContactsContract only. No uploads.
 */
object CallScreeningRules {

    sealed class Decision {
        data object Allow : Decision()
        data class Block(val reason: String) : Decision()
    }

    private const val PREFS = "screening_prefs"
    private const val KEY_BLOCK_ANONYMOUS = "block_anonymous"
    private const val KEY_BLOCK_UNKNOWN = "block_unknown"

    fun isBlockAnonymousEnabled(context: Context): Boolean =
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BLOCK_ANONYMOUS, false)
        } catch (_: Exception) { false }

    fun setBlockAnonymousEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BLOCK_ANONYMOUS, enabled).apply()
        } catch (_: Exception) { }
    }

    fun isBlockUnknownEnabled(context: Context): Boolean =
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BLOCK_UNKNOWN, false)
        } catch (_: Exception) { false }

    fun setBlockUnknownEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BLOCK_UNKNOWN, enabled).apply()
        } catch (_: Exception) { }
    }

    fun getPrefixes(dao: ScreeningDao): Flow<List<BlockedPrefixEntity>> =
        dao.getAllPrefixes()

    fun getRecentEvents(dao: ScreeningDao, limit: Int = 100): Flow<List<BlockedEventEntity>> =
        dao.getRecentEvents(limit)

    suspend fun addPrefix(dao: ScreeningDao, rawPrefix: String): Boolean {
        val prefix = normalizePrefix(rawPrefix) ?: return false
        return try {
            dao.addPrefix(BlockedPrefixEntity(prefix))
            true
        } catch (_: Exception) { false }
    }

    suspend fun removePrefix(dao: ScreeningDao, rawPrefix: String) {
        val prefix = normalizePrefix(rawPrefix) ?: return
        try { dao.removePrefix(prefix) } catch (_: Exception) { }
    }

    /** Keep prefixes as digit strings, optional leading '+'. */
    fun normalizePrefix(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 2 || digits.length > 15) return null
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    fun isAnonymous(number: String): Boolean {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return true
        val lower = trimmed.lowercase()
        if (lower.contains("private") || lower.contains("unknown") ||
            lower.contains("hidden") || lower.contains("withheld") ||
            lower.contains("restricted") || lower.contains("payphone")
        ) return true
        return trimmed.filter { it.isDigit() }.isEmpty()
    }

    private fun matchesPrefix(normalized: String, prefix: String): Boolean {
        val digits = normalized.filter { it.isDigit() }
        val prefixDigits = prefix.filter { it.isDigit() }
        if (prefixDigits.isEmpty() || digits.isEmpty()) return false
        return digits.startsWith(prefixDigits)
    }

    /**
     * Full ordered decision. Never throws — screening must fail open.
     */
    suspend fun decide(
        context: Context,
        dao: ScreeningDao,
        isExactBlocked: suspend (String) -> Boolean,
        rawNumber: String
    ): Decision {
        return try {
            if (isBlockAnonymousEnabled(context) && isAnonymous(rawNumber)) {
                return Decision.Block("anonymous")
            }
            val normalized = PhoneNumberUtils.normalize(rawNumber)
            if (normalized.isNotBlank()) {
                if (isExactBlocked(normalized)) return Decision.Block("blocklist")
                val prefixes = try { dao.getAllPrefixesSync() } catch (_: Exception) { emptyList() }
                val hit = prefixes.firstOrNull { matchesPrefix(normalized, it.prefix) }
                if (hit != null) return Decision.Block("prefix:${hit.prefix}")
            }
            if (isBlockUnknownEnabled(context) && normalized.isNotBlank()) {
                val hasContactsPerm = PermissionManager.hasPermissions(
                    context, PermissionManager.CONTACTS_PERMISSIONS
                )
                if (hasContactsPerm) {
                    val known = try {
                        ContactUtils.isKnownContact(context, normalized)
                    } catch (_: Exception) { true }
                    if (!known) return Decision.Block("unknown")
                }
            }
            Decision.Allow
        } catch (_: Exception) {
            Decision.Allow
        }
    }

    suspend fun logBlocked(dao: ScreeningDao, rawNumber: String, reason: String) {
        try {
            val normalized = PhoneNumberUtils.normalize(rawNumber).ifBlank { rawNumber }
            dao.logEvent(BlockedEventEntity(phoneNumber = normalized, reason = reason))
        } catch (_: Exception) { }
    }
}
