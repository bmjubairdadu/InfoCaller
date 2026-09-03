package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Indian UPI Intelligence Provider.
 * Derived from BulkVPALookup.
 * Uses upibankvalidator.com to resolve names associated with common UPI handles.
 */
class UpiLookupProviderImpl : LookupProvider {
    override val id: String = "upi_intel"
    override val name: String = "UPI Indian Intel"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.PUBLIC_SEARCH, Capability.ALTERNATE_NAME)
    override val priority: Int = 42
    override val costClass: CostClass = CostClass.FREE

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val commonHandles = listOf("paytm", "ybl", "okicici", "okhdfcbank", "okaxis", "apl")

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalizedPhoneNumber = identifier
        if (!normalizedPhoneNumber.startsWith("+91")) return@withContext null
        
        val cleanNumber = normalizedPhoneNumber.removePrefix("+91").filter { it.isDigit() }
        if (cleanNumber.length != 10) return@withContext null

        for (handle in commonHandles) {
            val vpa = "$cleanNumber@$handle"
            val result = checkUpi(vpa)
            if (result != null) {
                return@withContext PartialResult(
                    name = result,
                    confidence = 0.85f,
                    source = "UPI ($handle)",
                    providerId = id,
                    providerVersion = version
                )
            }
        }
        
        return@withContext null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext emptyMap()
        emptyMap()
    }

    private fun checkUpi(vpa: String): String? {
        try {
            val url = "https://upibankvalidator.com/api/upiValidation?upi=$vpa"
            val jsonBody = JSONObject().apply { put("upi", vpa) }.toString()
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("User-Agent", "InfoCaller/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.optBoolean("isUpiRegistered", false)) {
                    val name = json.optString("name")
                    if (name.isNotBlank() && !name.contains("error", ignoreCase = true)) {
                        return name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("UpiLookup", "Failed for $vpa", e)
        }
        return null
    }
}
