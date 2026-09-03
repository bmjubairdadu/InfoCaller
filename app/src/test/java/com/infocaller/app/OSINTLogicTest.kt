package com.infocaller.app

import com.infocaller.app.util.OSINTManager
import com.infocaller.app.domain.engine.Capability
import org.junit.Assert.assertTrue
import org.junit.Test

class OSINTLogicTest {

    @Test
    fun testAdvancedDorkGeneration() {
        val phoneNumber = "+8801700000000"
        val dorks = OSINTManager.generateExtendedDorkLinks(phoneNumber)
        
        // Verify presence of new dorks
        val titles = dorks.map { it.title }
        assertTrue("IntelligenceX should be in dorks", titles.contains("IntelligenceX"))
        assertTrue("Dehashed should be in dorks", titles.contains("Dehashed (Preview)"))
        assertTrue("EPIOS should be in dorks", titles.contains("EPIOS (Google OSINT)"))
        
        // Verify URL encoding
        val intelx = dorks.find { it.title == "IntelligenceX" }
        assertTrue("URL should contain encoded number", intelx?.url?.contains("%2B8801700000000") == true)
    }

    @Test
    fun testCapabilityEnum() {
        val capabilities = Capability.entries
        assertTrue(capabilities.contains(Capability.INFOSTEALER_LEAK))
        assertTrue(capabilities.contains(Capability.SERVICE_PRESENCE))
        assertTrue(capabilities.contains(Capability.PORTING_HISTORY))
    }
}
