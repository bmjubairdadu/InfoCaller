package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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

    private suspend fun getSet(): Set<String>? {
        if (cachedSet != null && System.currentTimeMillis()-cacheAt < TTL) return cachedSet
        return try {
            val url = "https://raw.githubusercontent.com/ip1sms/disposable-phone-numbers/master/number-list.json"
            val request = Request.Builder().url(url).build()
            // Capped JSON read: unbounded body OOMs background scans.
            val body = client.newCall(request).await().use { response ->
                if (!response.isSuccessful) return cachedSet
                try { response.peekBody(500_000L).string() } catch (_: Exception) { return cachedSet } catch (_: Error) { return cachedSet }
            } ?: return cachedSet
            if (body.length > 500_000) return cachedSet
            val set = Regex("\"(\\d{6,15})\"").findAll(body).map { it.groupValues[1] }.toSet()
            cachedSet = set; cacheAt = System.currentTimeMillis(); set
        } catch (_: Exception) { cachedSet }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val cleanNumber = try { identifier.filter { it.isDigit() } } catch (_: Exception) { return@withContext null } catch (_: Error) { return@withContext null }
        if (cleanNumber.length < 7 || cleanNumber.length > 15) return@withContext null
        val set = getSet() ?: return@withContext null
        // O(1) exact/suffix check: the old set.any{} scanned ~4MB list per lookup (ANR).
        val hit = try {
            if (set.contains(cleanNumber)) true
            else suffixTrieHit(set, cleanNumber)
        } catch (_: Exception) { false } catch (_: Error) { false }
        if (hit) {
            return@withContext PartialResult(
                about = "Known disposable/temporary phone number.",
                confidence = 1.0f,
                source = "ip1sms Blacklist",
                providerId = id, providerVersion = version
            )
        }
        return@withContext null
    }

    // Suffix index built once per feed refresh: avoids O(n) scan per lookup.
    @Volatile private var suffixIndex: Set<String>? = null
    private fun suffixTrieHit(set: Set<String>, number: String): Boolean {
        return try {
            var idx = suffixIndex
            if (idx == null) {
                val built = HashSet<String>(set.size)
                for (s in set) {
                    try {
                        if (s.length in 6..15) {
                            built.add(s)
                            if (s.length >= 7) built.add(s.takeLast(7))
                            if (s.length >= 10) built.add(s.takeLast(10))
                        }
                    } catch (_: Exception) { } catch (_: Error) { }
                }
                suffixIndex = built
                idx = built
            }
            idx.contains(number) || idx.contains(number.takeLast(10)) || idx.contains(number.takeLast(7))
        } catch (_: Exception) { false } catch (_: Error) { false }
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext emptyMap()
        emptyMap()
    }
}
