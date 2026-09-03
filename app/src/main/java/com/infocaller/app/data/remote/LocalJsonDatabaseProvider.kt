package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalJsonDatabaseProvider(private val context: Context) : LookupProvider {
    override val id: String = "local_json_db"
    @Deprecated("Legacy provider - replaced by Room-backed NidDatabaseProvider (indexed, 115k). Kept for fallback.")
    override val name: String = "BD NID Database (Legacy)"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.PUBLIC_SEARCH,
        Capability.PHONE_METADATA
    )
    override val priority: Int = 5 // demoted - Room provider now handles this
    override val costClass: CostClass = CostClass.FREE

    data class DbRecord(
        val number: String,
        val nid: String,
        val dob: String,
        val database: String? = null,
        val tg: String? = null
    )

    private val records: List<DbRecord> by lazy {
        try {
            context.assets.open("database.json").bufferedReader().use { reader ->
                val type = object : TypeToken<List<DbRecord>>() {}.type
                Gson().fromJson<List<DbRecord>>(reader, type)
            }
        } catch (e: Exception) {
            Log.e("LocalJsonDb", "Failed to load database from assets", e)
            emptyList()
        }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        val cleanIdentifier = identifier.filter { it.isDigit() }
        
        val match = when (type) {
            IdentifierType.PHONE -> {
                val target = if (cleanIdentifier.startsWith("880")) cleanIdentifier.substring(2) else cleanIdentifier
                records.find { it.number.filter { c -> c in '0'..'9' }.endsWith(target) }
            }
            IdentifierType.NID -> {
                records.find { it.nid == identifier }
            }
            IdentifierType.DOB -> {
                records.find { it.dob == identifier }
            }
            else -> null
        }

        if (match != null) {
            return@withContext PartialResult(
                identifier = match.number,
                nid = match.nid,
                dob = match.dob,
                about = "NID: ${match.nid}, DOB: ${match.dob}. Source: ${match.database ?: "Unknown"}",
                confidence = 1.0f,
                source = "Local BD Database",
                providerId = id,
                providerVersion = version
            )
        }
        null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> {
        return emptyMap()
    }
}
