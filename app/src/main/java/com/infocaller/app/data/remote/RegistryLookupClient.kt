package com.infocaller.app.data.remote

import com.infocaller.app.data.local.RegistryRecordCipher
import com.infocaller.app.data.local.dao.RegistryCacheDao
import com.infocaller.app.data.local.entity.RegistryCacheEntity
import com.infocaller.app.util.PhoneNumberUtils
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class RegistryLookupClient(
    private val cacheDao: RegistryCacheDao,
    private val cipher: RegistryRecordCipher,
    private val httpClient: OkHttpClient,
    private val baseUrl: String = "https://api.infocaller.app/"
) {
    private val gson = Gson()
    private val freshTtl = TimeUnit.HOURS.toMillis(24)
    private val staleWindow = TimeUnit.DAYS.toMillis(7)

    suspend fun lookup(phoneNumber: String): RegistryCallerRecord? = withContext(Dispatchers.IO) {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        if (!normalized.startsWith("+")) return@withContext null
        val now = System.currentTimeMillis()
        val cached = cacheDao.get(normalized)

        if (cached != null) {
            val local = runCatching { gson.fromJson(cipher.decrypt(cached.encryptedRecord), RegistryCallerRecord::class.java) }.getOrNull()
            if (local != null && cached.expiresAt > now) {
                if (cached.staleUntil > now) refreshInBackground(normalized, cached)
                return@withContext local
            }
            if (local != null && cached.staleUntil > now) {
                refreshInBackground(normalized, cached)
                return@withContext local // stale-while-revalidate
            }
        }
        fetchAndCache(normalized, cached)
    }

    private suspend fun fetchAndCache(number: String, cached: RegistryCacheEntity?): RegistryCallerRecord? {
        val shard = RegistryShardResolver.shardPath(number)
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/api/v1/registry/shard?path=" + java.net.URLEncoder.encode(shard, "UTF-8"))
            .apply {
                cached?.etag?.let { header("If-None-Match", it) }
                cached?.lastModified?.let { header("If-Modified-Since", it) }
            }.build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 304 && cached != null) {
                    val record = gson.fromJson(cipher.decrypt(cached.encryptedRecord), RegistryCallerRecord::class.java)
                    cacheDao.upsert(cached.copy(fetchedAt = System.currentTimeMillis(), expiresAt = System.currentTimeMillis() + freshTtl))
                    return record
                }
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val shardData = runCatching { gson.fromJson(body, RegistryShardResponse::class.java) }.getOrNull() ?: return null
                val record = shardData.records.firstOrNull { it.normalizedPhone == number }
                    ?: shardData.records.firstOrNull { it.phoneHash == number }
                    ?: return null
                val now = System.currentTimeMillis()
                cacheDao.upsert(RegistryCacheEntity(number, cipher.encrypt(gson.toJson(record)), now, now + freshTtl, now + freshTtl + staleWindow, response.header("ETag"), response.header("Last-Modified"), shardData.version, shard))
                record
            }
        } catch (_: Exception) { null }
    }

    private fun refreshInBackground(number: String, cached: RegistryCacheEntity) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { fetchAndCache(number, cached) }
    }
}
