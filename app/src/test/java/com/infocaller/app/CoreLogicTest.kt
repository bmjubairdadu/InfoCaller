package com.infocaller.app

import com.infocaller.app.domain.engine.ConfidenceEngine
import com.infocaller.app.domain.engine.PartialResult
import com.infocaller.app.util.PhoneNumberUtils
import org.junit.Test
import org.junit.Assert.*

class CoreLogicTest {

    @Test
    fun testPhoneNormalization() {
        val cases = mapOf(
            "01785917145" to "+8801785917145",
            "+8801785917145" to "+8801785917145",
            "8801785917145" to "+8801785917145",
            "01785-917145" to "+8801785917145",
            "01785 917145" to "+8801785917145",
            "*121#" to "*121#",
            "#123#" to "#123#",
            "*121*1*1#" to "*121*1*1#"
        )
        
        cases.forEach { (input, expected) ->
            assertEquals("Failed for $input", expected, PhoneNumberUtils.normalize(input))
        }
    }

    @Test
    fun testConfidenceEngineMerging() {
        val results = listOf(
            PartialResult(name = "John Doe", confidence = 0.5f, source = "Search"),
            PartialResult(name = "John Doe", confidence = 0.4f, source = "Social")
        )
        
        val merged = ConfidenceEngine.merge("+8801731421373", results)
        
        assertEquals("John Doe", merged.name)
        assertTrue("Confidence should be boosted for dual match", merged.confidence > 0.5f)
    }

    @Test
    fun testContactNamePriority() {
        val results = listOf(
            PartialResult(name = "Public Identity", confidence = 0.9f)
        )
        
        val merged = ConfidenceEngine.merge("+8801731421373", results, existingContactName = "Mom")
        
        assertEquals("Mom", merged.name)
        assertEquals(1.0f, merged.confidence)
    }
}
