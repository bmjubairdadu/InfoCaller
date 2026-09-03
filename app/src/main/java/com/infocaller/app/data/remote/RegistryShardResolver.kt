package com.infocaller.app.data.remote

import com.infocaller.app.util.PhoneNumberUtils

/** Deterministic shard mapping. Keeps the client from downloading a monolithic registry. */
object RegistryShardResolver {
    fun shardPath(phoneNumber: String): String {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        require(normalized.startsWith("+")) { "Phone must be normalized to E.164" }
        val digits = normalized.drop(1)
        val countryCode = PhoneNumberUtils.getDialingCode(normalized)?.toString() ?: digits.take(3)
        val significant = PhoneNumberUtils.getSignificantNumber(normalized) ?: digits
        val prefix = significant.take(3).padEnd(3, '0')
        return "database/$countryCode/$prefix.json"
    }
}
