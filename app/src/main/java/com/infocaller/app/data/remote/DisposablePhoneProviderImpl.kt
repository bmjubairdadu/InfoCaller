package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Disposable Phone Number Provider.
 * Checks against public blacklists (e.g., ip1sms, tempophone).
 */
class DisposablePhoneProviderImpl : LookupProvider {
    override val id: String = "disposable_check"
    override val name: String = "Disposable Guard"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.PHONE_METADATA)
    override val priority: Int = 95
    override val costClass: CostClass = CostClass.FREE

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()
    @Volatile private var cachedSet: Set<String>? = null
    @Volatile private var cacheAt = 0L
    private val TTL = 24*3600*1000L

    private fun getSet(): Set<String>? {
        if (cachedSet != null && System.currentTimeMillis()-cacheAt < TTL) return cachedSet
        return try {
            val url = "https://raw.githubusercontent.com/ip1sms/disposable-phone-numbers/master/number-list.json"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return cachedSet
            val body = response.body?.string() ?: return cachedSet
            // extract digits between quotes
            val set = Regex("\"(\\d{6,15})\"").findAll(body).map { it.groupValues[1] }.toSet()
            cachedSet = set; cacheAt = System.currentTimeMillis(); set
        } catch (_: Exception) { cachedSet }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val cleanNumber = identifier.filter { it.isDigit() }
        val set = getSet() ?: return@withContext null
        // check full number and suffix match for disposable prefixes
        if (set.contains(cleanNumber) || set.any { cleanNumber.endsWith(it) || it.endsWith(cleanNumber) }) {
            return@withContext PartialResult(
                about = "Known disposable/temporary phone number.",
                confidence = 1.0f,
                source = "ip1sms Blacklist",
                providerId = id, providerVersion = version
            )
        }
        return@withContext null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext emptyMap()
        emptyMap()
    }
}
