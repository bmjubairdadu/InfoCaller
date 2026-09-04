package com.infocaller.app.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.ContactsContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.data.local.ContributionConsentStore
import com.infocaller.app.data.local.ContributionPolicy
import com.infocaller.app.data.local.entity.ContributionEntity
import com.infocaller.app.data.local.entity.ContributionStatus
import com.infocaller.app.data.remote.PhoneHash
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Privacy-safe one-by-one contribution queue.
 *
 * - Runs ONLY when ContributionConsentStore.isAccepted() == true.
 * - Seed pass: reads eligible device numbers, runs them through the EXISTING
 *   PublicLookupEngine (unchanged caller-ID path), caches in Room, then queues
 *   ONLY permitted fields (phone_hash + public display_name).
 * - Sequential: processes exactly ONE queued item per pass, then returns
 *   Result.success() so WorkManager reschedules; retry/resume via backoff
 *   columns (nextAttemptAt) + boot re-enqueue.
 * - Dedup: PK on phoneHash; requeueIfChanged() only when payload changed.
 * - Uploads go to the OWNER BACKEND contribute endpoint (server-side holds
 *   service_role / GitHub write tokens). No secrets in the APK — the client
 *   calls POST {backend}/api/v1/community/contribute with {phone_hash, display_name}.
 *   Backend base URL is deployer-configured (OwnerClaimRepository prefs).
 * - NEVER sends: plain phone numbers, local contact names, contactId/lookupKey,
 *   notes, photo URIs, messages, or other local metadata.
 */
class ContributionWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!ContributionConsentStore.isAccepted(applicationContext)) {
                Log.i(TAG, "Consent not accepted — skipping contribution")
                return@withContext Result.success()
            }
            if (!isOnline()) return@withContext Result.retry()
            val app = applicationContext as InfoCallerApplication

            // Seed pass (bounded): import new device numbers into the queue.
            try {
                seedFromDevice(app)
            } catch (e: Exception) {
                Log.w(TAG, "Seed pass failed (non-fatal): ${e.message}")
            }

            val dao = app.database.contributionDao()
            val now = System.currentTimeMillis()
            val item = try { dao.getNextEligible(now) } catch (e: Exception) {
                Log.w(TAG, "Queue read failed: ${e.message}")
                return@withContext Result.retry()
            } ?: run {
                try { dao.pruneDone() } catch (_: Exception) { }
                Log.d(TAG, "Queue empty — all caught up")
                return@withContext Result.success()
            }

            if (!ContributionConsentStore.isAccepted(applicationContext)) {
                return@withContext Result.success()
            }
            processOne(app, item)
        } catch (e: Exception) {
            Log.e(TAG, "Contribution pass failed", e)
            Result.retry()
        }
    }

    // ── Seed: device numbers -> enrich via existing engine -> queue permitted payload ──
    private suspend fun seedFromDevice(app: InfoCallerApplication) {
        if (!PermissionManager.hasPermissions(applicationContext, PermissionManager.CONTACTS_PERMISSIONS)) return
        val dao = app.database.contributionDao()
        val numbers = collectDeviceNumbers(MAX_SEED_PER_PASS)
        if (numbers.isEmpty()) return
        var queued = 0
        for (number in numbers) {
            if (!ContributionConsentStore.isAccepted(applicationContext)) break
            // Skip if already tracked with identical payload.
            val hash = try { PhoneHash.sha256Hex(number) } catch (_: Exception) { continue }
            val existing = try { dao.getByHash(hash) } catch (_: Exception) { null }
            // Enrich via the EXISTING caller-ID path (Room cache + PublicLookupEngine).
            val publicName = resolvePublicName(app, number)
            val payload = ContributionPolicy.buildPermitted(number, publicName) ?: continue
            if (payload.displayName.isNullOrBlank()) continue // nothing permitted to share yet
            val fp = ContributionPolicy.payloadFingerprint(payload)
            if (existing == null) {
                dao.insertIgnore(
                    ContributionEntity(
                        phoneHash = payload.phoneHash,
                        displayName = payload.displayName,
                        payloadFingerprint = fp,
                        status = ContributionStatus.PENDING
                    )
                )
                queued++
            } else if (existing.payloadFingerprint != fp && existing.status == ContributionStatus.DONE) {
                dao.requeueIfChanged(hash, payload.displayName, fp, System.currentTimeMillis())
                queued++
            }
            if (queued >= MAX_SEED_PER_PASS) break
        }
        Log.d(TAG, "Seed pass: scanned=${numbers.size} queued=$queued")
    }

    /** Public enriched name only — never the user's local contact name. */
    private suspend fun resolvePublicName(app: InfoCallerApplication, normalized: String): String? {
        return try {
            // 1. Room enrichment cache (populated by CallScreeningService / EnrichmentEngine).
            val cached = try { app.database.enrichmentDao().getEnrichmentSync(normalized) } catch (_: Exception) { null }
            val cachedName = cached?.publicName?.takeIf { ContributionPolicy.isValidDisplayName(it) }
            if (cachedName != null) return cachedName
            // 2. Live lookup through the EXISTING engine (unchanged architecture).
            var found: String? = null
            try {
                val result = app.lookupEngine.performLookup(normalized, com.infocaller.app.domain.engine.IdentifierType.PHONE)
                val candidate = result.name?.takeIf { ContributionPolicy.isValidDisplayName(it) }
                if (candidate != null) {
                    found = candidate
                    try { app.repository.saveLookupResult(result) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            found
        } catch (_: Exception) { null }
    }

    private suspend fun collectDeviceNumbers(limit: Int): List<String> {
        val out = LinkedHashSet<String>()
        return try {
            applicationContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && out.size < limit * 3) {
                    val n = PhoneNumberUtils.normalize(c.getString(idx) ?: "")
                    if (n.isNotBlank() && n.startsWith("+")) out.add(n)
                }
            }
            out.take(limit)
        } catch (_: Exception) { out.take(limit) }
    }

    // ── Upload exactly one item ──
    private suspend fun processOne(app: InfoCallerApplication, item: ContributionEntity): Result {
        val dao = app.database.contributionDao()
        val now = System.currentTimeMillis()
        try {
            dao.markAttempt(item.phoneHash, ContributionStatus.UPLOADING, now, now, null)
        } catch (_: Exception) { }

        val baseUrl = ownerBackendBaseUrl()
        if (baseUrl.isNullOrBlank()) {
            // No backend configured yet — keep queued, retry later (no data leaves device).
            dao.markAttempt(item.phoneHash, ContributionStatus.FAILED, now, now + RETRY_BASE_MS, "backend_not_configured")
            Log.i(TAG, "Backend not configured — contribution stays queued")
            return Result.success()
        }
        // Defense in depth: only allowed keys, never plain numbers.
        val outgoing = ContributionPolicy.sanitizeOutgoing(
            mapOf("phone_hash" to item.phoneHash, "display_name" to item.displayName)
        )
        if (!ContributionPolicy.isValidHash(item.phoneHash)) {
            dao.markAttempt(item.phoneHash, ContributionStatus.FAILED, now, Long.MAX_VALUE, "bad_hash_dropped")
            return Result.success()
        }
        val ok = postContribution(baseUrl, outgoing)
        return if (ok) {
            dao.markDone(item.phoneHash, System.currentTimeMillis())
            Log.i(TAG, "Contributed ${item.phoneHash.take(8)}…")
            // More items may remain — reschedule promptly via backoff-free chain.
            scheduleNextSoon(applicationContext)
            Result.success()
        } else {
            val next = now + backoffFor(item.attemptCount + 1)
            dao.markAttempt(item.phoneHash, ContributionStatus.FAILED, now, next, "upload_failed")
            Log.w(TAG, "Upload failed, retry at +${(next - now) / 1000}s")
            Result.retry()
        }
    }

    private fun ownerBackendBaseUrl(): String? {
        return try {
            val prefs = applicationContext.getSharedPreferences("owner_claim_prefs", Context.MODE_PRIVATE)
            prefs.getString("owner_backend_url", null)?.trim()?.trimEnd('/')?.takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            }
        } catch (_: Exception) { null }
    }

    private fun postContribution(baseUrl: String, payload: Map<String, Any?>): Boolean {
        return try {
            val json = JSONObject()
            payload.forEach { (k, v) -> if (v == null) json.put(k, JSONObject.NULL) else json.put(k, v.toString()) }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$baseUrl/api/v1/community/contribute")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "InfoCaller/2.0 (contribution)")
                .build()
            val resp = http.newCall(req).execute()
            val code = resp.code
            resp.close()
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "postContribution: ${e.message}")
            false
        }
    }

    private fun backoffFor(attempt: Int): Long {
        val exp = (1L shl minOf(attempt, 8)) * RETRY_BASE_MS
        return minOf(exp, MAX_BACKOFF_MS)
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
    }

    companion object {
        private const val TAG = "ContributionWorker"
        private const val UNIQUE_PERIODIC = "ContributionQueue"
        private const val UNIQUE_ONE_TIME = "ContributionQueueNow"
        private const val MAX_SEED_PER_PASS = 25
        private const val RETRY_BASE_MS = 60_000L
        private const val MAX_BACKOFF_MS = 6 * 60 * 60 * 1000L

        fun scheduleOnConsent(context: Context) {
            val req = androidx.work.PeriodicWorkRequestBuilder<ContributionWorker>(3, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                req
            )
            triggerNow(context)
        }

        fun triggerNow(context: Context) {
            if (!ContributionConsentStore.isAccepted(context)) return
            val req = androidx.work.OneTimeWorkRequestBuilder<ContributionWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_TIME,
                androidx.work.ExistingWorkPolicy.APPEND,
                req
            )
        }

        fun scheduleNextSoon(context: Context) {
            if (!ContributionConsentStore.isAccepted(context)) return
            try {
                val req = androidx.work.OneTimeWorkRequestBuilder<ContributionWorker>()
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    )
                    .setInitialDelay(8, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_ONE_TIME,
                    androidx.work.ExistingWorkPolicy.APPEND,
                    req
                )
            } catch (_: Exception) { }
        }

        fun cancel(context: Context) {
            try {
                androidx.work.WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
                androidx.work.WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_ONE_TIME)
            } catch (_: Exception) { }
        }

        /** Re-enqueue after reboot — only if consent was previously accepted. */
        fun resumeIfConsented(context: Context) {
            if (!ContributionConsentStore.isAccepted(context)) return
            scheduleOnConsent(context)
        }
    }
}
