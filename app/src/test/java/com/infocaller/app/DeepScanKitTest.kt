package com.infocaller.app

import com.infocaller.app.data.remote.*
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.OSINTManager
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class DeepScanKitTest {

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    @Test
    fun testMaigretSiteCount() {
        val p = MaigretSweepProviderImpl(client())
        assertEquals("maigret_sweep", p.id)
        assertTrue(p.capabilities.contains(Capability.SOCIAL_MATCH))
        assertEquals(CostClass.FREE, p.costClass)
    }

    @Test
    fun testEmailIntelCapabilities() {
        val p = HudsonRockEmailIntelProviderImpl(client())
        assertTrue(p.capabilities.contains(Capability.INFOSTEALER_LEAK))
        assertTrue(p.capabilities.contains(Capability.EMAIL))
    }

    @Test
    fun testPhonePivotCapabilities() {
        val p = SocialSearcherPhoneProviderImpl(client())
        assertTrue(p.capabilities.contains(Capability.SOCIAL_MATCH))
    }

    @Test
    fun testReverseImageCapabilities() {
        val p = ReverseImageSearchProviderImpl(client())
        assertTrue(p.capabilities.contains(Capability.PROFILE_PHOTO))
    }

    @Test
    fun testAvatarHarvesterCapabilities() {
        val p = MultiAvatarHarvesterProviderImpl(client())
        assertTrue(p.capabilities.contains(Capability.PROFILE_PHOTO))
    }

    @Test
    fun testPhotoPivotCapabilities() {
        val p = PimeyesPhotoPivotProviderImpl(client())
        assertTrue(p.capabilities.contains(Capability.PUBLIC_SEARCH))
    }

    @Test
    fun testAiAssistCapabilities() {
        val p = AiAssistDeepSearchProviderImpl(client())
        assertTrue(p.capabilities.contains(Capability.PUBLIC_SEARCH))
        assertEquals(CostClass.FREE, p.costClass)
    }

    @Test
    fun testReverseImageEmailSeed() {
        val p = ReverseImageSearchProviderImpl(client())
        val res = kotlinx.coroutines.runBlocking {
            p.lookup("someone@example.com", IdentifierType.EMAIL, LookupContext())
        }
        // Gravatar seed URL must embed, Lens/TinEye/Bing links in about.
        assertNotNull(res)
        assertTrue(res!!.imageUrl?.contains("gravatar.com") == true)
        assertTrue(res.about?.contains("lens.google.com") == true)
        assertTrue(res.about?.contains("tineye.com") == true)
    }

    @Test
    fun testAiAssistBuildsLinks() {
        val p = AiAssistDeepSearchProviderImpl(client())
        val res = kotlinx.coroutines.runBlocking {
            p.lookup("+8801700000000", IdentifierType.PHONE, LookupContext())
        }
        assertNotNull(res)
        assertTrue(res!!.about?.contains("perplexity.ai") == true)
        assertTrue(res.about?.contains("brave.com") == true)
    }

    @Test
    fun testOsintDeepLinksPresent() {
        val links = OSINTManager.generateExtendedDorkLinks("+8801700000000")
        val titles = links.map { it.title }
        assertTrue(titles.any { it.contains("Lens") })
        assertTrue(titles.any { it.contains("Pimeyes") })
        assertTrue(titles.any { it.contains("FaceCheck") })
        assertTrue(titles.any { it.contains("Perplexity") })
        val emailLinks = OSINTManager.generateEmailDorkLinks("a@b.com")
        assertTrue(emailLinks.any { it.title.contains("Perplexity") })
        val userLinks = OSINTManager.generateUsernameDorkLinks("someuser")
        assertTrue(userLinks.any { it.title.contains("Lens") })
    }
}
