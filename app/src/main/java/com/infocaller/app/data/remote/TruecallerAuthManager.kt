package com.infocaller.app.data.remote

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit


class TruecallerAuthManager(
    private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val gson = Gson()

    data class OtpRequestResult(
        val requestId: String,
        val method: String,
        val ttl: Int,
        val status: Int,
        val message: String? = null
    )
    data class VerifyResult(
        val success: Boolean,
        val installationId: String? = null,
        val status: Int = 0,
        val message: String? = null
    )

    private fun deviceIdReal(): String {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        var did = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (did.isNullOrBlank() || did == "9774d56d682e549c") {
            did = prefs.getString("tc_device_id", null)
            if (did.isNullOrBlank()) {
                did = rnd(16)
                prefs.edit().putString("tc_device_id", did).apply()
            }
        }
        return did
    }
    private fun rnd(len:Int): String { val c="abcdefghijklmnopqrstuvwxyz0123456789"; return (1..len).map{ c.random() }.joinToString("") }

    // Exactly Benojir: multiple endpoints, ANDROID_ID, osVersion "10", gzip handling via OkHttp auto
    suspend fun requestOtp(phone: String): OtpRequestResult? = withContext(Dispatchers.IO) {
        val norm = PhoneNumberUtils.normalize(phone)
        val cc = PhoneNumberUtils.getCountryCode(norm) ?: "BD"
        val sig = PhoneNumberUtils.getSignificantNumber(norm) ?: norm.filter{it.isDigit()}
        val dial = PhoneNumberUtils.getDialingCode(norm) ?: 880
        val secret = "lvc22mp3l1sfv6ujg83rd17btt"
        val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: deviceIdReal()
        
        val body = JsonObject().apply {
            addProperty("countryCode", cc); addProperty("dialingCode", dial)
            add("installationDetails", JsonObject().apply {
                add("app", JsonObject().apply { addProperty("buildVersion",5); addProperty("majorVersion",11); addProperty("minorVersion",7); addProperty("store","GOOGLE_PLAY") })
                add("device", JsonObject().apply {
                    addProperty("deviceId", deviceId); addProperty("language","en"); addProperty("manufacturer", android.os.Build.MANUFACTURER); addProperty("model", android.os.Build.MODEL)
                    addProperty("osName","Android"); addProperty("osVersion","10"); add("mobileServices", gson.toJsonTree(listOf("GMS")))
                })
                addProperty("language","en")
            })
            addProperty("phoneNumber", sig); addProperty("region","region-2"); addProperty("sequenceNo",2)
        }

        val endpoints = listOf(
            "https://account-asia-south1.truecaller.com/v2/sendOnboardingOtp",
            "https://account-noneu.truecaller.com/v2/sendOnboardingOtp"
        )

        var lastError: String? = null
        for (url in endpoints) {
            try {
                Log.d("TruecallerAuth", "Attempting requestOtp at $url")
                val req = Request.Builder().url(url)
                    .addHeader("clientsecret", secret)
                    .addHeader("user-agent","Truecaller/11.75.5 (Android;10)")
                    .addHeader("content-type","application/json; charset=UTF-8")
                    .addHeader("accept-encoding","gzip")
                    .post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
                
                val resp = client.newCall(req).await()
                val rawBytes = resp.use { it.body?.bytes() } ?: continue
                val txt = if (rawBytes.size > 1 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) decompressGzip(rawBytes) else String(rawBytes)
                
                Log.d("TruecallerAuth", "Response from $url: $txt")
                val j = try { gson.fromJson(txt, JsonObject::class.java) } catch(_:Exception){ 
                    lastError = "Invalid JSON: ${txt.take(100)}"
                    continue 
                }
                
                val status = j.get("status")?.asInt ?: 0
                val msg = j.get("message")?.asString
                
                if (status == 1 || status == 9) {
                    val rid = j.get("requestId")?.asString ?: ""
                    val method = j.get("method")?.asString?.lowercase() ?: "sms"
                    val ttl = j.get("tokenTtl")?.asInt ?: 300
                    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                        .putString("last_tc_request_id", rid)
                        .putString("last_tc_phone", PhoneNumberUtils.normalize(phone))
                        .apply()
                    return@withContext OtpRequestResult(rid, method, ttl, status, msg)
                }
                
                if (status == 3) {
                    val token = j.get("installationId")?.asString ?: j.get("accessToken")?.asString
                    if (token != null) {
                        TruecallerCloudStore.saveInstallationId(context, token)
                        return@withContext OtpRequestResult(token, "already_logged_in", 0, 3, msg)
                    }
                }
                
                if (status == 5 || status == 6) {
                    return@withContext OtpRequestResult("", "", 0, status, msg ?: "Too many requests. Try again after 1 hour.")
                }
                
                lastError = msg ?: txt.take(200)
            } catch (e: Exception) {
                Log.w("TruecallerAuth", "Failed endpoint $url: ${e.message}")
                lastError = e.message
            }
        }
        return@withContext OtpRequestResult("", "", 0, -1, lastError ?: "All endpoints failed")
    }

    private fun decompressGzip(compressed: ByteArray): String {
        val bis = java.io.ByteArrayInputStream(compressed)
        val gis = java.util.zip.GZIPInputStream(bis)
        val out = StringBuilder(); val buf = ByteArray(1024); var len: Int
        while (gis.read(buf).also { len = it } != -1) out.append(String(buf, 0, len))
        return out.toString()
    }
    suspend fun verifyOtp(phone: String, requestId: String, otp: String): VerifyResult = withContext(Dispatchers.IO) {
        if (otp.length !in 4..10 || otp.any { !it.isDigit() }) {
            return@withContext VerifyResult(false, null, 11, "Invalid OTP")
        }
        // If alreadyLoggedIn token passed as requestId
        if (requestId.length>20 && !requestId.contains("-")) {
            TruecallerCloudStore.saveInstallationId(context, requestId)
            return@withContext VerifyResult(true, requestId, 3, "Already logged in")
        }
        val norm = PhoneNumberUtils.normalize(phone)
        val cc = PhoneNumberUtils.getCountryCode(norm) ?: "BD"
        val sig = PhoneNumberUtils.getSignificantNumber(norm) ?: norm.filter{it.isDigit()}
        val dial = PhoneNumberUtils.getDialingCode(norm) ?: 880
        val secret = "lvc22mp3l1sfv6ujg83rd17btt"
        val postData = JsonObject().apply {
            addProperty("countryCode", cc); addProperty("dialingCode", dial); addProperty("phoneNumber", sig)
            addProperty("requestId", requestId); addProperty("token", otp.filter{it.isDigit()})
        }
        try {
            val req = Request.Builder().url("https://account-asia-south1.truecaller.com/v1/verifyOnboardingOtp")
                .addHeader("content-type","application/json; charset=UTF-8")
                .addHeader("accept-encoding","gzip")
                .addHeader("user-agent","Truecaller/11.75.5 (Android;10)")
                .addHeader("clientsecret", secret)
                .post(postData.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
            val (txt, code, isSuccessful) = client.newCall(req).await().use { resp ->
                val rawBytes = resp.body?.bytes()
                val t = if (rawBytes != null && rawBytes.size > 1 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) decompressGzip(rawBytes) else rawBytes?.let { String(it) }
                Triple(t, resp.code, resp.isSuccessful)
            }
            if (txt!=null) {
                val j = try { gson.fromJson(txt, JsonObject::class.java) } catch(_:Exception){ null }
                if (j!=null && j.has("status")) {
                    val status = j.get("status")?.asInt ?: 0
                    if (status==2) {
                        val suspended = j.get("suspended")?.asBoolean ?: false
                        if (suspended) return@withContext VerifyResult(false, null, 2, "Account suspended")
                        val installationId = j.get("installationId")?.takeIf{!it.isJsonNull}?.asString ?: j.get("accessToken")?.takeIf{!it.isJsonNull}?.asString
                        if (installationId != null) {
                            TruecallerCloudStore.saveInstallationId(context, installationId)
                            return@withContext VerifyResult(true, installationId, 2, "Verified")
                        }
                        return@withContext VerifyResult(false, null, 2, "Installation ID not found: $txt")
                    }
                    if (status==17) return@withContext completeOnboarding(phone, requestId, otp)
                    if (status==11 || status==40101) return@withContext VerifyResult(false, null, 11, "Invalid OTP")
                    if (status==7) return@withContext VerifyResult(false, null, 7, "Retries limit exceeded")
                    return@withContext VerifyResult(false, null, status, j.get("message")?.asString ?: txt)
                }
                // Non-JSON success (rare) still try installationId
                if (isSuccessful && txt.contains("installationId")) {
                    val j2 = try { gson.fromJson(txt, JsonObject::class.java) } catch(_:Exception){ null }
                    val iid = j2?.get("installationId")?.asString; if (iid != null) { TruecallerCloudStore.saveInstallationId(context, iid); return@withContext VerifyResult(true, iid, 2, "Verified") }
                }
                return@withContext VerifyResult(false, null, code, txt.take(500))
            }
        } catch(e:Exception){ Log.e("TruecallerAuth","verify error: ${e.message}", e) }
        VerifyResult(false, null, -1, "Network error")
    }

    private suspend fun completeOnboarding(phone:String, requestId:String, otp:String): VerifyResult = withContext(Dispatchers.IO){
        val norm = PhoneNumberUtils.normalize(phone)
        val cc = PhoneNumberUtils.getCountryCode(norm) ?: "BD"
        val sig = PhoneNumberUtils.getSignificantNumber(norm) ?: norm.filter{it.isDigit()}
        val dial = PhoneNumberUtils.getDialingCode(norm) ?: 880
        val body = JsonObject().apply {
            addProperty("countryCode",cc); addProperty("dialingCode",dial); addProperty("phoneNumber",sig)
            addProperty("requestId",requestId); addProperty("token",otp.filter{it.isDigit()})
        }
        try{
            val req = Request.Builder().url("https://account-noneu.truecaller.com/v1/completeOnboarding")
                .addHeader("content-type","application/json; charset=UTF-8")
                .addHeader("accept-encoding","gzip")
                .addHeader("user-agent","Truecaller/11.75.5 (Android;10)")
                .addHeader("clientsecret","lvc22mp3l1sfv6ujg83rd17btt")
                .post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
            val (txt, code) = client.newCall(req).await().use { resp ->
                val rawBytes = resp.body?.bytes()
                val t = if (rawBytes != null && rawBytes.size > 1 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) decompressGzip(rawBytes) else rawBytes?.let { String(it) }
                t to resp.code
            }
            if (txt == null) return@withContext VerifyResult(false, null, -1, "Empty response")
            val j = try { gson.fromJson(txt, JsonObject::class.java) } catch(_:Exception){ return@withContext VerifyResult(false, null, -1, txt.take(500)) }
            val installationId = j.get("installationId")?.takeIf{!it.isJsonNull}?.asString ?: j.get("accessToken")?.takeIf{!it.isJsonNull}?.asString
            if (installationId!=null) { TruecallerCloudStore.saveInstallationId(context, installationId); return@withContext VerifyResult(true, installationId, 2, "Onboarded") }
            return@withContext VerifyResult(false, null, code, j.get("message")?.asString ?: txt.take(500))
        } catch(e:Exception){ Log.e("TruecallerAuth","completeOnboarding error: ${e.message}", e); VerifyResult(false, null, -1, e.message) }
    }
}
