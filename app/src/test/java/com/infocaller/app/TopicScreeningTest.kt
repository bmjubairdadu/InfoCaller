package com.infocaller.app

import com.infocaller.app.data.local.CallScreeningRules
import com.infocaller.app.data.remote.CommunitySpamCsvProviderImpl
import com.infocaller.app.data.remote.OfflineOperatorTablesProviderImpl
import org.junit.Assert.*
import org.junit.Test

class TopicScreeningTest {

    @Test
    fun testPrefixNormalization() {
        assertEquals("+1800", CallScreeningRules.normalizePrefix("+1 800"))
        assertEquals("8802", CallScreeningRules.normalizePrefix("8802"))
        assertNull(CallScreeningRules.normalizePrefix("1"))
        assertNull(CallScreeningRules.normalizePrefix(""))
        assertNull(CallScreeningRules.normalizePrefix("abc"))
    }

    @Test
    fun testAnonymousDetection() {
        assertTrue(CallScreeningRules.isAnonymous(""))
        assertTrue(CallScreeningRules.isAnonymous("Private"))
        assertTrue(CallScreeningRules.isAnonymous("Unknown"))
        assertTrue(CallScreeningRules.isAnonymous("Withheld"))
        assertFalse(CallScreeningRules.isAnonymous("+8801712345678"))
    }

    @Test
    fun testProviderIdsRegistered() {
        assertEquals("offline_operator_tables", OfflineOperatorTablesProviderImpl().id)
        assertEquals("community_spam_csv", CommunitySpamCsvProviderImpl().id)
        assertEquals(
            "https://raw.githubusercontent.com/tareknahas85-star/block-number-data/main/spamdb.csv",
            CommunitySpamCsvProviderImpl.DEFAULT_FEED_URL
        )
    }
}
