package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class ImageSocialVerifierProvider : LookupProvider {
    override val id = "image_social_verifier"
    override val name = "Image→Social Match"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.SOCIAL_MATCH)
    override val priority = 40
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        // Retired: DuckDuckGo scraping was pruned repo-wide. Kept registered
        // for interface stability; returns null so scans spend attempts on
        // live providers.
        return@withContext null
    }
}
