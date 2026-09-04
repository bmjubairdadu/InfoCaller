package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Owner-claim repository: OTP-verify own number, publish/update/delete own profile.
 * No bulk contact upload. Backend base URL is configured by deployer (not a public default).
 */
class OwnerClaimRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun backendBaseUrl(): String {
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    fun setBackendBaseUrl(url: String) {
        prefs.edit().putString(KEY_BASE_URL, url.trim().trimEnd('/')).apply()
        api = null
    }

    fun ownerToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun verifiedPhone(): String? = prefs.getString(KEY_PHONE, null)
    fun isVerified(): Boolean = !ownerToken().isNullOrBlank() && !verifiedPhone().isNullOrBlank()
    fun clearSession() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_PHONE).apply()
    }

    @Volatile private var api: OwnerBackendApi? = null
    private fun api(): OwnerBackendApi {
        api?.let { return it }
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl(backendBaseUrl().trimEnd('/') + "/")
            .client(http)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
        return retrofit.create(OwnerBackendApi::class.java).also { api = it }
    }

    private fun auth(): String = "Bearer ${ownerToken() ?: ""}"

    suspend fun requestOtp(rawPhone: String): Result<String> = withContext(Dispatchers.IO) {
        val phone = PhoneNumberUtils.normalize(rawPhone)
        if (phone.isBlank()) return@withContext Result.failure(IllegalArgumentException("Invalid phone number"))
        try {
            val r = api().requestOtp(OwnerOtpRequest(phone))
            if (r.isSuccessful) {
                prefs.edit().putString(KEY_PENDING_PHONE, phone).apply()
                Result.success("OTP sent")
            } else Result.failure(IllegalStateException("OTP request failed (${r.code()})"))
        } catch (e: Exception) {
            Log.w("OwnerClaim", "requestOtp: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun verifyOtp(code: String): Result<String> = withContext(Dispatchers.IO) {
        val phone = prefs.getString(KEY_PENDING_PHONE, null) ?: return@withContext Result.failure(IllegalStateException("Request OTP first"))
        val digits = code.filter { it.isDigit() }
        if (digits.length < 4) return@withContext Result.failure(IllegalArgumentException("Invalid code"))
        try {
            val r = api().verifyOtp(OwnerOtpVerify(phone, digits))
            val body = r.body()
            if (r.isSuccessful && !body?.ownerToken.isNullOrBlank()) {
                prefs.edit().putString(KEY_TOKEN, body!!.ownerToken).putString(KEY_PHONE, phone).apply()
                Result.success(phone)
            } else Result.failure(IllegalStateException("Invalid or expired code"))
        } catch (e: Exception) {
            Log.w("OwnerClaim", "verifyOtp: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun loadMyProfile(): Result<String> = withContext(Dispatchers.IO) {
        if (!isVerified()) return@withContext Result.failure(IllegalStateException("Verify your number first"))
        try {
            val r = api().myProfile(auth())
            if (r.isSuccessful) Result.success(r.body()?.toString() ?: "{}")
            else if (r.code() == 401) { clearSession(); Result.failure(IllegalStateException("Session expired. Verify again.")) }
            else Result.failure(IllegalStateException("Load failed (${r.code()})"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun publishOwnProfile(req: OwnerProfileRequest): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isVerified()) return@withContext Result.failure(IllegalStateException("Verify your number first"))
        val phone = verifiedPhone() ?: return@withContext Result.failure(IllegalStateException("Verify your number first"))
        if (!req.consentGranted) return@withContext Result.failure(IllegalArgumentException("Explicit consent is required"))
        if (req.displayName.trim().length !in 2..80) return@withContext Result.failure(IllegalArgumentException("Name must be 2-80 chars"))
        try {
            val body = req.copy(phone = phone)
            val r = api().createProfile(auth(), body)
            when {
                r.isSuccessful -> Result.success(Unit)
                r.code() == 409 -> {
                    // Already claimed: update instead.
                    val p = api().updateProfile(auth(), OwnerProfilePatch(
                        displayName = body.displayName, photoUrl = body.photoUrl,
                        businessName = body.businessName, businessCategory = body.businessCategory,
                        country = body.country, isBusiness = body.isBusiness,
                        visibility = body.visibility, consentGranted = true
                    ))
                    if (p.isSuccessful) Result.success(Unit) else Result.failure(IllegalStateException("Update failed (${p.code()})"))
                }
                r.code() == 401 -> { clearSession(); Result.failure(IllegalStateException("Session expired. Verify again.")) }
                else -> Result.failure(IllegalStateException("Publish failed (${r.code()})"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updateVisibility(visibility: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isVerified()) return@withContext Result.failure(IllegalStateException("Verify first"))
        if (visibility !in listOf("public", "unlisted", "private")) return@withContext Result.failure(IllegalArgumentException("Bad visibility"))
        try {
            val r = api().updateProfile(auth(), OwnerProfilePatch(visibility = visibility))
            if (r.isSuccessful) Result.success(Unit) else Result.failure(IllegalStateException("Update failed (${r.code()})"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun revokeConsent(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isVerified()) return@withContext Result.failure(IllegalStateException("Verify first"))
        try {
            val r = api().updateProfile(auth(), OwnerProfilePatch(consentGranted = false))
            if (r.isSuccessful) Result.success(Unit) else Result.failure(IllegalStateException("Revoke failed (${r.code()})"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deleteProfile(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isVerified()) return@withContext Result.failure(IllegalStateException("Verify first"))
        try {
            val r = api().deleteProfile(auth())
            if (r.isSuccessful) { clearSession(); Result.success(Unit) }
            else Result.failure(IllegalStateException("Delete failed (${r.code()})"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun reportSpam(rawPhone: String, reason: String): Result<Unit> = withContext(Dispatchers.IO) {
        val phone = PhoneNumberUtils.normalize(rawPhone)
        if (phone.isBlank()) return@withContext Result.failure(IllegalArgumentException("Invalid phone"))
        val ok = reason in listOf("spam", "scam", "telemarketing", "abuse", "other")
        if (!ok) return@withContext Result.failure(IllegalArgumentException("Bad reason"))
        try {
            val r = api().spamReport(OwnerSpamReport(phone, reason))
            if (r.isSuccessful) Result.success(Unit) else Result.failure(IllegalStateException("Report failed (${r.code()})"))
        } catch (e: Exception) { Result.failure(e) }
    }

    companion object {
        private const val PREFS = "owner_claim_prefs"
        private const val KEY_BASE_URL = "owner_backend_url"
        private const val KEY_TOKEN = "owner_token"
        private const val KEY_PHONE = "owner_phone"
        private const val KEY_PENDING_PHONE = "owner_pending_phone"
        // No public default: deployer must set their own backend URL in My Profile screen.
        const val DEFAULT_BASE_URL = "https://owner.example.invalid/"
    }
}
