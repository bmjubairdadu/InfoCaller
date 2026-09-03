package com.infocaller.app

import com.infocaller.app.util.OSINTManager
import com.infocaller.app.domain.engine.Capability
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedOSINTTest {

    @Test
    fun testMultiFormatDorking() {
        val phoneNumber = "+8801700000000"
        val dorks = OSINTManager.generateExtendedDorkLinks(phoneNumber)
        
        // Verify burner check exists
        assertTrue(dorks.any { it.title == "Burner Check (SMS Online)" })
        
        // Verify format variations in URLs
        val burnerDork = dorks.find { it.title == "Burner Check (SMS Online)" }
        assertTrue(burnerDork?.url?.contains("8801700000000") == true)
    }

    @Test
    fun testNewCapabilities() {
        val caps = Capability.entries
        assertTrue(caps.contains(Capability.TELEGRAM_LINK))
        assertTrue(caps.contains(Capability.DISPOSABLE_CHECK))
    }
}
