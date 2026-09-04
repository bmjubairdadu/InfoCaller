package com.infocaller.app.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.ContactsContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.data.local.entity.ContactEnrichmentEntity
import com.infocaller.app.data.remote.PhoneHash
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Community auto-sync:
 * - Runs on app open + periodic (internet required).
 * - Pulls ONLY hash-keyed community rows from Supabase.
 * - Matches against device contacts / call log numbers.
 * - Saves matched info into local enrichment cache (local DB + display).
 * - Never uploads device contacts automatically.
 */
class CommunitySyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!isOnline()) return@withContext Result.retry()

            val app = applicationContext as InfoCallerApplication
            val (baseUrl, anonKey) = supabaseConfig() ?: return@withContext Result.success()
            val dao = app.database.enrichmentDao()

            // 1. Pull latest community rows (hash + display_name + report_count + updated_at).
            val since = prefs().getLong(KEY_LAST_SYNC, 0L)
            val rows = fetchCommunityRows(baseUrl, anonKey, since)
            if (rows.isEmpty()) {
                // fetchCommunityRows returns [] on HTTP failure too — do NOT advance
                // the sync window, or the failed window is skipped forever.
                return@withContext Result.retry()
            }
            prefs().edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()

            // 2. Collect device numbers: contacts + recent calls (permission-gated).
            val deviceNumbers = collectDeviceNumbers()
            if (deviceNumbers.isEmpty()) return@withContext Result.success()
            val numberToHash = deviceNumbers.associateWith { n ->
                try { PhoneHash.sha256Hex(n) } catch (_: Exception) { "" }
            }.filterValues { it.isNotBlank() }
            val hashToNumber = numberToHash.entries.associate { (n, h) -> h to n }

            // 3. Match + save to local enrichment cache (batched reads, one write each).
            var matched = 0
            val now = System.currentTimeMillis()
            val expiry = now + 30L * 24 * 60 * 60 * 1000
            val matchedNumbers = rows.mapNotNull { hashToNumber[it.hash] }.distinct()
            if (matchedNumbers.isEmpty()) {
                return@withContext Result.success()
            }
            val existingByNumber: Map<String, ContactEnrichmentEntity> = try {
                dao.getEnrichmentsSync(matchedNumbers).associateBy { it.normalizedPhoneNumber }
            } catch (_: Exception) { emptyMap() }
            val hashToRow = rows.associateBy { it.hash }
            for (number in matchedNumbers) {
                val row = hashToRow[numberToHash[number]] ?: continue
                val existing = existingByNumber[number]
                val merged = ContactEnrichmentEntity(
                    normalizedPhoneNumber = number,
                    contactId = existing?.contactId,
                    publicName = row.name ?: existing?.publicName,
                    about = row.about() ?: existing?.about,
                    source = "Community (Supabase)",
                    confidence = "0.75",
                    lastChecked = now,
                    expiresAt = expiry
                )
                try {
                    dao.insertEnrichment(merged)
                    matched++
                } catch (_: Exception) { }
            }

            Log.i("CommunitySync", "Pulled=${rows.size} matched=$matched deviceNumbers=${deviceNumbers.size}")
            Result.success()
        } catch (e: Exception) {
            Log.e("CommunitySync", "Sync failed", e)
            Result.retry()
        }
    }

    private data class Row(val hash: String, val name: String?, val reportCount: Int)

    private fun Row.about(): String? {
        if (!name.isNullOrBlank() && reportCount > 0) return "$name | Community reports: $reportCount"
        if (!name.isNullOrBlank()) return "$name | Found in community database"
        if (reportCount > 0) return "Community reports: $reportCount"
        return null
    }

    private fun prefs() = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isOnline(): Boolean {
        return try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
    }

    private fun supabaseConfig(): Pair<String, String>? {
        return try {
            val url = com.infocaller.app.BuildConfig.SUPABASE_URL.trim().trimEnd('/')
            val key = com.infocaller.app.BuildConfig.SUPABASE_ANON_KEY.trim()
            if (url.isBlank() || key.isBlank()) null else url to key
        } catch (_: Exception) { null }
    }

    private fun fetchCommunityRows(baseUrl: String, anonKey: String, since: Long): List<Row> {
        // Order by updated_at desc, cap page size. First sync caps to 2000.
        val limit = 2000
        val url = if (since > 0) {
            val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(since))
            "$baseUrl/rest/v1/community_lookups?select=phone_hash,display_name,report_count,updated_at&updated_at=gt.$iso&order=updated_at.desc&limit=$limit"
        } else {
            "$baseUrl/rest/v1/community_lookups?select=phone_hash,display_name,report_count,updated_at&order=updated_at.desc&limit=$limit"
        }
        val req = Request.Builder().url(url)
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $anonKey")
            .addHeader("Accept", "application/json")
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) return emptyList()
        val body = resp.body?.string() ?: return emptyList()
        val arr = JSONArray(body)
        val out = ArrayList<Row>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hash = o.optString("phone_hash", "")
            if (hash.length != 64) continue
            out += Row(hash, o.optString("display_name", "").takeIf { it.isNotBlank() }, o.optInt("report_count", 0))
        }
        return out
    }

    private suspend fun collectDeviceNumbers(): Set<String> = withContext(Dispatchers.IO) {
        val out = LinkedHashSet<String>()
        val ctx = applicationContext
        try {
            // Contacts (requires READ_CONTACTS)
            if (PermissionManager.hasPermissions(ctx, PermissionManager.CONTACTS_PERMISSIONS)) {
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null
                )?.use { c ->
                    val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val n = PhoneNumberUtils.normalize(c.getString(idx) ?: "")
                        if (n.isNotBlank()) out += n
                    }
                }
            }
            // Recent calls (requires READ_CALL_LOG)
            if (PermissionManager.hasPermissions(ctx, PermissionManager.CALL_LOG_PERMISSIONS)) {
                val app = ctx.applicationContext as InfoCallerApplication
                app.deviceDataRepository.fetchRecentCallsSync().forEach {
                    val n = PhoneNumberUtils.normalize(it.number)
                    if (n.isNotBlank()) out += n
                }
            }
        } catch (_: Exception) { }
        out
    }

    companion object {
        private const val PREFS = "community_sync_prefs"
        private const val KEY_LAST_SYNC = "last_community_sync"

        fun schedulePeriodic(context: Context) {
            val req = androidx.work.PeriodicWorkRequestBuilder<CommunitySyncWorker>(6, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "CommunitySync",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun triggerNow(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<CommunitySyncWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "CommunitySyncNow",
                androidx.work.ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
