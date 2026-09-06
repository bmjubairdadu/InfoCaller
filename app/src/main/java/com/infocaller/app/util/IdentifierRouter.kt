package com.infocaller.app.util

import com.infocaller.app.domain.engine.IdentifierType

/**
 * Shared routing for the single details screen: email addresses run an
 * EMAIL scan, @-prefixed or handle-shaped identifiers run a USERNAME scan,
 * everything else runs a PHONE scan. NID / DOB are DISPLAY-ONLY fields from
 * database.json matches — searching by them is disabled, so no NID route
 * exists here. Phone-number scans auto-attach NID/DOB from the local table.
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
        // NOTE: no NID route — search is phone-number only. All-digit strings
        // of any length fall through to the dialer path; NID/DOB surface as
        // display fields on phone matches.
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
