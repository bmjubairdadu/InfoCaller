package com.infocaller.app

import com.infocaller.app.data.local.ContributionPolicy
import org.junit.Assert.*
import org.junit.Test

class ContributionPolicyTest {

    @Test
    fun testPermittedPayloadUsesHashNotNumber() {
        val payload = ContributionPolicy.buildPermitted("+8801712345678", "Acme Corp")!!
        assertTrue(ContributionPolicy.isValidHash(payload.phoneHash))
        val map = payload.toMap()
        assertTrue(map.containsKey("phone_hash"))
        assertFalse(map.keys.any { it.contains("phone", ignoreCase = true) && it != "phone_hash" })
        assertFalse(map.containsKey("localName"))
        assertFalse(map.containsKey("contactId"))
        assertFalse(map.containsKey("privateNote"))
    }

    @Test
    fun testInvalidNumbersRejected() {
        assertNull(ContributionPolicy.buildPermitted("not-a-number", "Acme"))
        assertNull(ContributionPolicy.buildPermitted("+12", "Acme"))
        assertNull(ContributionPolicy.buildPermitted("", "Acme"))
    }

    @Test
    fun testLocalNamesNeverShared() {
        // Placeholder / digits-only / unknown names produce null display name.
        assertNull(ContributionPolicy.buildPermitted("+8801712345678", "Unknown")?.displayName)
        assertNull(ContributionPolicy.buildPermitted("+8801712345678", "01712345678")?.displayName)
        assertNull(ContributionPolicy.buildPermitted("+8801712345678", "x")?.displayName)
        // Valid public name passes.
        assertEquals("Acme Corp", ContributionPolicy.buildPermitted("+8801712345678", "Acme Corp")?.displayName)
    }

    @Test
    fun testSanitizeOutgoingDropsForbidden() {
        val out = ContributionPolicy.sanitizeOutgoing(
            mapOf(
                "phone_hash" to "a".repeat(64),
                "display_name" to "Acme",
                "localName" to "Mom",
                "contactId" to "42",
                "privateNote" to "secret",
                "phoneNumber" to "+8801712345678"
            )
        )
        assertEquals(setOf("phone_hash", "display_name"), out.keys)
    }

    @Test
    fun testFingerprintStableForDedup() {
        val a = ContributionPolicy.PermittedPayload("a".repeat(64), "Acme")
        val b = ContributionPolicy.PermittedPayload("a".repeat(64), "Acme")
        val c = ContributionPolicy.PermittedPayload("a".repeat(64), "Other")
        assertEquals(ContributionPolicy.payloadFingerprint(a), ContributionPolicy.payloadFingerprint(b))
        assertNotEquals(ContributionPolicy.payloadFingerprint(a), ContributionPolicy.payloadFingerprint(c))
    }

    @Test
    fun testAllowedFieldsNeverIncludeSecrets() {
        val forbidden = setOf(
            "localName", "contactId", "privateNote", "lookupKey",
            "photoUri", "phoneNumber", "phone", "number"
        )
        assertTrue(ContributionPolicy.ALLOWED_FIELDS.intersect(forbidden).isEmpty())
        assertTrue(ContributionPolicy.BLOCKED_FIELDS.containsAll(listOf("localName", "contactId", "privateNote")))
    }
}
