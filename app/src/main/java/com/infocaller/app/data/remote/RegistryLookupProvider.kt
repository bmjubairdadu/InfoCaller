package com.infocaller.app.data.remote

import com.infocaller.app.data.local.RegistryRecordCipher
import com.infocaller.app.data.local.dao.RegistryCacheDao
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RegistryLookupProvider(
    private val cacheDao: RegistryCacheDao,
    private val cipher: RegistryRecordCipher,
    httpClient: OkHttpClient,
    private val baseUrl: String = "https://api.infocaller.app/"
) : LookupProvider {
    override val id = "shared_registry"
    override val name = "InfoCaller Shared Registry"
    override val version = "2.0.0"
    override val capabilities = setOf(Capability.PHONE_METADATA, Capability.PROFILE_PHOTO, Capability.CARRIER, Capability.COUNTRY, Capability.BUSINESS)
    override val priority = 900
    override val costClass = CostClass.LOW

    private val client = RegistryLookupClient(cacheDao, cipher, httpClient, baseUrl)

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalized = PhoneNumberUtils.normalize(identifier)
        if (!normalized.startsWith("+")) return@withContext null
        val data = client.lookup(normalized) ?: return@withContext null
        PartialResult(
            name = data.displayName,
            imageUrl = data.photoUrl,
            country = data.country,
            carrier = data.carrier,
            isBusiness = null,
            confidence = when (data.confidence?.uppercase()) { "HIGH" -> 0.9f; "MEDIUM" -> 0.7f; else -> 0.5f },
            source = data.source ?: name,
            providerId = id,
            providerVersion = version
        )
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = emptyMap()
}
