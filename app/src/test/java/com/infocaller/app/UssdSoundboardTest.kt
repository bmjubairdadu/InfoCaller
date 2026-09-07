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
        assertTrue(defaults.none { it.kind == "tts" })
        assertTrue(defaults.any { it.kind == SoundboardStore.KIND_FILE || it.kind == SoundboardStore.KIND_TONE })
        assertTrue(defaults.any { it.name == "Dramatic gasp" })
        assertTrue(defaults.any { it.name == "Sad trombone" })
        assertTrue(defaults.any { it.name == "Wrong number" })
        defaults.forEach {
            assertTrue(it.name.isNotBlank())
            assertTrue(it.emoji.isNotBlank())
        }
    }

    @Test
    fun testSoundboardEntryShape() {
        val e = SoundboardEntry(name = "Laugh", emoji = "\uD83E\uDD2A", kind = SoundboardStore.KIND_FILE, payload = "content://media/laugh.mp3")
        assertEquals("Laugh", e.name)
        assertEquals(SoundboardStore.KIND_FILE, e.kind)
        assertTrue(e.volume in 0f..1f)
    }

    @Test
    fun testEmojiForName() {
        assertEquals("\uD83D\uDC4B", SoundboardStore.emojiForName("Hello"))
        assertEquals("\uD83D\uDE31", SoundboardStore.emojiForName("Dramatic gasp"))
        assertEquals("\uD83C\uDFC6", SoundboardStore.emojiForName("Victory"))
        assertTrue(SoundboardStore.emojiForName("Something random").isNotBlank())
    }
}
