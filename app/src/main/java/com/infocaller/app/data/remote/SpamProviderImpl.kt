package com.infocaller.app.data.remote

import com.infocaller.app.data.local.dao.CallerDao
import com.infocaller.app.domain.engine.*

class SpamProviderImpl(private val callerDao: CallerDao) : SpamProvider {
    override val id: String = "community_spam"
    override val name: String = "Community Spam DB"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.SPAM_CHECK)

    override suspend fun lookup(normalizedPhoneNumber: String, context: LookupContext): PartialResult? {
        val isSpam = callerDao.isSpam(normalizedPhoneNumber)
        return if (isSpam) {
            PartialResult(spamScore = 100, confidence = 1.0f, source = name, providerId = id, providerVersion = version)
        } else {
            PartialResult(spamScore = 0, confidence = 1.0f, source = name, providerId = id, providerVersion = version)
        }
    }
}
