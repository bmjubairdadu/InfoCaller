package com.infocaller.app.util

import com.infocaller.app.domain.engine.IdentifierType

/**
 * Shared routing for the single details screen: email addresses must run an
 * EMAIL scan (phone normalization would strip them to digits), NID / NID|DOB
 * identifiers run a NID scan, @-prefixed or handle-shaped identifiers run a
 * USERNAME scan, everything else runs a phone scan.
 */
object IdentifierRouter {
    /** True email address (not a bare @handle): dotted domain, no leading @. */
    fun isEmail(identifier: String): Boolean {
        val t = identifier.trim()
        if (!t.contains("@") || t.startsWith("@") || t.contains(" ")) return false
        return t.substringAfter("@").contains(".")
    }

    fun routeType(identifier: String): String {
        val t = identifier.trim()
        if (t.isEmpty()) return IdentifierType.PHONE
        // A bare @handle is a username, not an email: real addresses have a
        // dotted domain and never start with @.
        if (isEmail(t)) return IdentifierType.EMAIL
        if (t.contains("|")) return IdentifierType.NID
        if (t.all { it.isDigit() }) {
            // BD phones: 11 digits (01...) locally, 13 (880...) in E.164.
            // 10/17-digit all-digit strings can only be NIDs; 13-digit strings
            // that are not 880-prefixed are NIDs too.
            if (t.length == 10 || t.length == 17) return IdentifierType.NID
            if (t.length == 13 && !t.startsWith("880")) return IdentifierType.NID
        }
        // Explicit @handle or handle-shaped tokens (letters/digits/._- with
        // no phone structure) run a username scan. Short numeric strings
        // still fall through to the dialer path.
        val handle = t.removePrefix("@")
        if (t.startsWith("@") && handle.length in 2..40) return IdentifierType.USERNAME
        if (handle.length in 3..40 && handle.any { it.isLetter() } &&
            handle.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } &&
            !handle.contains(" ") && !t.contains("+")
        ) return IdentifierType.USERNAME
        return IdentifierType.PHONE
    }
}
