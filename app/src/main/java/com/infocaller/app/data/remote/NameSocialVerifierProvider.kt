package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class NameSocialVerifierProvider : LookupProvider {
    override val id = "name_social_verifier"
    override val name = "Name→Social Match"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE)
    override val priority = 42
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        // Retired: DuckDuckGo scraping was pruned repo-wide (blocks + title-guess
        // hallucinations). USERNAME scans are served by Sherlock / WhatsMyName /
        // GitHub / profile extractors; FULL_NAME pivots no longer fan out here.
        return@withContext null
    }
}
