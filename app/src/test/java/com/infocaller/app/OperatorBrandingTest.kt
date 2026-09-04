package com.infocaller.app

import com.infocaller.app.util.OperatorBrandResolver
import com.infocaller.app.util.SimManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorBrandingTest {

    @Test
    fun testUrlBuilder() {
        val domain = "grameenphone.com"
        val url = SimManager.buildBrandfetchLogoUrl(domain)
        
        // Expected: https://cdn.brandfetch.io/domain/grameenphone.com?c=1idt4fOOzudt9xCz11q
        assertTrue(url.contains("cdn.brandfetch.io/domain/grameenphone.com"))
        assertTrue(url.contains("?c="))
    }

    @Test
    fun testOperatorNormalization() {
        val res1 = OperatorBrandResolver.resolveBrand("Grameen Phone", null, null, null)
        val res2 = OperatorBrandResolver.resolveBrand("GP", null, null, null)
        val res3 = OperatorBrandResolver.resolveBrand("Grameenphone", null, "470", "01")
        
        assertEquals("Grameenphone", res1.operatorName)
        assertEquals("Grameenphone", res2.operatorName)
        assertEquals("Grameenphone", res3.operatorName)
        assertEquals("grameenphone.com", res3.officialDomain)
    }

    @Test
    fun testCountryAwareResolution() {
        val bd = OperatorBrandResolver.resolveBrand("Airtel", null, "470", "07")
        assertEquals("Airtel", bd.operatorName)
        assertEquals("bd.airtel.com", bd.officialDomain)

        val india = OperatorBrandResolver.resolveBrand("Airtel", null, "404", "10")
        assertEquals("Airtel India", india.operatorName)
        assertEquals("airtel.in", india.officialDomain)
    }

    @Test
    fun testInternationalOperators() {
        val verizon = OperatorBrandResolver.resolveBrand(null, "Verizon", "311", "480")
        assertEquals("Verizon", verizon.operatorName)
        assertEquals("verizon.com", verizon.officialDomain)

        val jio = OperatorBrandResolver.resolveBrand("Jio", null, "405", "840")
        assertEquals("Jio", jio.operatorName)
        assertEquals("jio.com", jio.officialDomain)
    }
}
