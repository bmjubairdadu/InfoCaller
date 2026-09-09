package com.infocaller.app.util

import com.infocaller.app.domain.engine.IdentifierType

object IdentifierRouter {
    fun isEmail(identifier: String): Boolean {
        val t = identifier.trim()
        if (!t.contains("@") || t.startsWith("@") || t.contains(" ")) return false
        return t.substringAfter("@").contains(".")
    }

    fun routeType(identifier: String): String {
        val t = identifier.trim()
        if (t.isEmpty()) return IdentifierType.PHONE
        if (isEmail(t)) return IdentifierType.EMAIL
        val handle = t.removePrefix("@")
        if (t.startsWith("@") && handle.length in 2..40) return IdentifierType.USERNAME
        if (handle.length in 3..40 && handle.any { it.isLetter() } &&
            handle.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } &&
            !handle.contains(" ") && !t.contains("+")
        ) return IdentifierType.USERNAME
        return IdentifierType.PHONE
    }
}
