package com.infocaller.app.util

import com.infocaller.app.domain.engine.IdentifierType

/**
 * Shared routing for the single details screen: email addresses must run an
 * EMAIL scan (phone normalization would strip them to digits), NID / NID|DOB
 * identifiers run a NID scan, everything else runs a phone scan.
 */
object IdentifierRouter {
    fun routeType(identifier: String): String {
        val t = identifier.trim()
        if (t.isEmpty()) return IdentifierType.PHONE
        if (t.contains("@")) return IdentifierType.EMAIL
        if (t.contains("|")) return IdentifierType.NID
        if (t.all { it.isDigit() }) {
            // BD phones: 11 digits (01...) locally, 13 (880...) in E.164.
            // 10/17-digit all-digit strings can only be NIDs; 13-digit strings
            // that are not 880-prefixed are NIDs too.
            if (t.length == 10 || t.length == 17) return IdentifierType.NID
            if (t.length == 13 && !t.startsWith("880")) return IdentifierType.NID
        }
        return IdentifierType.PHONE
    }
}
