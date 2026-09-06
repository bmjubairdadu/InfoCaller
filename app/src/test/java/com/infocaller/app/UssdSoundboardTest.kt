package com.infocaller.app

import com.infocaller.app.util.SoundboardEntry
import com.infocaller.app.util.SoundboardStore
import com.infocaller.app.util.UssdEntry
import com.infocaller.app.util.UssdStore
import org.junit.Assert.*
import org.junit.Test

class UssdSoundboardTest {

    @Test
    fun testUssdDetection() {
        assertTrue(UssdStore.isUssd("*566#"))
        assertTrue(UssdStore.isUssd("*121*1*4#"))
        assertTrue(UssdStore.isUssd("#123#"))
        assertFalse(UssdStore.isUssd("+8801700000000"))
        assertFalse(UssdStore.isUssd("01700000000"))
        assertFalse(UssdStore.isUssd(""))
    }

    @Test
    fun testUssdEntryShape() {
        val e = UssdEntry(code = "*566#", label = "Balance")
        assertEquals("*566#", e.code)
        assertEquals("Balance", e.label)
    }

    @Test
    fun testDefaultUssdEntriesAreUssd() {
        val defaults = UssdStore.defaultEntries()
        assertTrue(defaults.isNotEmpty())
        defaults.forEach { assertTrue(UssdStore.isUssd(it.code)) }
    }

    @Test
    fun testDefaultSoundboardEntries() {
        val defaults = SoundboardStore.defaultEntries()
        assertTrue(defaults.size >= 3)
        assertTrue(defaults.any { it.kind == SoundboardStore.KIND_TTS })
        assertTrue(defaults.any { it.kind == SoundboardStore.KIND_TONE })
        defaults.forEach {
            assertTrue(it.name.isNotBlank())
            assertTrue(it.emoji.isNotBlank())
        }
    }

    @Test
    fun testSoundboardEntryShape() {
        val e = SoundboardEntry(name = "Hi", emoji = "\uD83D\uDC4B", kind = SoundboardStore.KIND_TTS, payload = "Hi there")
        assertEquals("Hi", e.name)
        assertEquals(SoundboardStore.KIND_TTS, e.kind)
        assertTrue(e.volume in 0f..1f)
    }
}
