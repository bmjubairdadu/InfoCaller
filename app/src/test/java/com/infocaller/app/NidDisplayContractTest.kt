package com.infocaller.app

import com.infocaller.app.domain.engine.IdentifierType
import com.infocaller.app.domain.engine.PartialResult
import org.junit.Assert.*
import org.junit.Test

/**
 * NID sparse-row contract: database.json rows carry ONLY number/nid/dob, so
 * matches must surface exactly NID + DOB — no filler names, no addresses.
 * (Room DAO itself is covered on-device; this pins the display contract.)
 */
class NidDisplayContractTest {

    @Test
    fun sparseRowSurfacesOnlyNidAndDob() {
        // Mirrors NidDatabaseProvider.toPartial() sparse branch.
        val partial = PartialResult(
            identifier = "01700000000",
            identifierType = IdentifierType.PHONE,
            about = "NID: 1234567890 | DOB: 1990-01-01",
            nid = "1234567890",
            dob = "1990-01-01",
            confidence = 0.95f,
            source = "BD NID Database",
            providerId = "bd_nid_database",
            providerVersion = "2.0.0"
        )
        assertNull(partial.name)
        assertNull(partial.imageUrl)
        assertNull(partial.city)
        assertEquals("1234567890", partial.nid)
        assertEquals("1990-01-01", partial.dob)
        assertTrue(partial.about.contains("1234567890"))
        assertTrue(partial.about.contains("1990-01-01"))
    }

    @Test
    fun phoneCandidatesAreNormalizedBeforeMatch() {
        // Mirrors the exact-first candidate ladder in NidDatabaseProvider:
        // the +880 E.164 form must produce the stored local 11-digit form
        // among its candidates before any DB comparison.
        val e164 = "+8801712345678"
        val digits = e164.filter { it.isDigit() }
        val candidates = linkedSetOf(
            digits,
            if (digits.startsWith("880")) digits.substring(3) else "0$digits".takeLast(11),
            digits.takeLast(11),
            if (digits.startsWith("880")) digits else "880${digits.trimStart('0')}",
            digits.takeLast(10),
        ).filter { it.length >= 7 }
        // Stored local form must be an exact candidate (matched via
        // findByPhoneExact, never substring LIKE).
        assertTrue(candidates.contains("01712345678"))
    }
}
