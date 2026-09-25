package com.infocaller.app.util

import java.util.regex.Pattern

object SmsOtpParser {
    private val labeledPattern =
        Pattern.compile("(?:code|otp|verification|verify|pin|password)[^\\d]{0,20}(\\d{4,10})(?!\\d)", Pattern.CASE_INSENSITIVE)
    private val genericPatterns = listOf(
        Pattern.compile("(?:code|is|verification)\\s*(?:is)?\\s*(\\d{4,10})(?!\\d)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)"),
        Pattern.compile("(?<!\\d)(\\d{4,10})(?!\\d)")
    )

    fun extractOtp(body: String?): String? {
        if (body.isNullOrBlank()) return null
        labeledPattern.matcher(body).let { m ->
            if (m.find()) {
                val g = m.group(1)
                if (!g.isNullOrBlank() && g.length in 4..10) return g
            }
        }
        for (pattern in genericPatterns) {
            val matcher = pattern.matcher(body)
            if (matcher.find()) {
                val found = matcher.group(1)
                if (!found.isNullOrBlank() && found.length in 4..10) return found
            }
        }
        return null
    }
}
