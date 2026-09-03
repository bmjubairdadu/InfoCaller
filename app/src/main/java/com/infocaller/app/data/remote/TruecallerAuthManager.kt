package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Truecaller OTP -> Cloud Secret (installationId) flow.
 * Mirrors truecallerjs: login(phone) -> OTP SMS -> verifyOtp(phone, requestId, otp) -> {installationId, status:2}
 * The installationId IS the cloud secret - auto-created by Truecaller on verify, stored as Bearer token.
 * Then search5-noneu uses: Authorization: Bearer <installationId>
 */
class TruecallerAuthManager(
    private val context: Context,
    private val backendApi: BackendApiService? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val gson = Gson()

    data class OtpRequestResult(
        val requestId: String,
        val method: String, // sms/call/whatsapp
        val ttl: Int,
        val status: Int,
        val message: String? = null
    )
    data class VerifyResult(
        val success: Boolean,
        val installationId: String? = null, // <-- cloud secret auto-created
        val status: Int = 0,
        val message: String? = null
    )

    private fun deviceId(): String {
        val p = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        var id = p.getString("tc_device_id", null)
        if (id == null) { id = java.util.UUID.randomUUID().toString().replace("-", "").take(16); p.edit().putString("tc_device_id", id).apply() }
        return id
    }
    private fun randomDevice(): Pair<String,String> {
        val list = listOf("Xiaomi" to "M2010J19SG","Samsung" to "SM-A525F","OnePlus" to "CPH2449","Realme" to "RMX2185")
        return list.random()
    }
    private fun rnd(len:Int): String { val c="abcdefghijklmnopqrstuvwxyz0123456789"; return (1..len).map{ c.random() }.joinToString("") }

    suspend fun requestOtp(phone: String): OtpRequestResult? = withContext(Dispatchers.IO) {
        val norm = PhoneNumberUtils.normalize(phone)
        val cc = PhoneNumberUtils.getCountryCode(norm) ?: "BD"
        val sig = PhoneNumberUtils.getSignificantNumber(norm) ?: norm.filter{it.isDigit()}
        val dial = PhoneNumberUtils.getDialingCode(norm) ?: 880
        val secret = TruecallerCloudStore.getClientSecret(context).ifBlank { "lvc22mp3l1sfv6ujg83rd17btt" }
        val (manuf, model) = randomDevice()
        val body = JsonObject().apply {
            addProperty("countryCode", cc); addProperty("dialingCode", dial)
            add("installationDetails", JsonObject().apply {
                add("app", JsonObject().apply { addProperty("buildVersion",5); addProperty("majorVersion",11); addProperty("minorVersion",7); addProperty("store","GOOGLE_PLAY") })
                add("device", JsonObject().apply {
                    addProperty("deviceId", rnd(16)); addProperty("language","en"); addProperty("manufacturer",manuf); addProperty("model",model)
                    addProperty("osName","Android"); addProperty("osVersion","10"); add("mobileServices", gson.toJsonTree(listOf("GMS")))
                })
                addProperty("language","en")
            })
            addProperty("phoneNumber", sig); addProperty("region","region-2"); addProperty("sequenceNo",2)
        }
        val endpoints = listOf("https://account-asia-south1.truecaller.com/v2/sendOnboardingOtp","https://account-noneu.truecaller.com/v2/sendOnboardingOtp")
        for (url in endpoints) {
            try {
                val req = Request.Builder().url(url)
                    .addHeader("clientsecret", secret)
                    .addHeader("user-agent","Truecaller/11.75.5 (Android;10)")
                    .addHeader("accept-encoding","gzip")
                    .addHeader("content-type","application/json; charset=UTF-8")
                    .post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
                val resp = client.newCall(req).execute()
                val txt = resp.body?.string() ?: continue
                val j = try { gson.fromJson(txt, JsonObject::class.java) } catch(_:Exception){ continue }
                val status = j.get("status")?.asInt ?: 0
                val msg = j.get("message")?.asString
                if (status==1 || status==9) {
                    val rid = j.get("requestId")?.asString ?: ""; val method = j.get("method")?.asString?.lowercase() ?: "sms"; val ttl = j.get("tokenTtl")?.asInt ?: 300
                    Log.i("TruecallerAuth","OTP requested via $method rid=${rid.take(8)}")
                    return@withContext OtpRequestResult(rid, method, ttl, status, msg)
                }
                if (status==3) {
                    val token = j.get("installationId")?.asString ?: j.get("accessToken")?.asString
                    if (token!=null) { // already logged in - secret already exists
                        TruecallerCloudStore.saveInstallationId(context, token)
                        return@withContext OtpRequestResult(token, "already_logged_in", 0, 3, msg)
                    }
                }
                if (url==endpoints.last()) return@withContext OtpRequestResult("", "", 0, status, msg)
            } catch(e:Exception){ Log.w("TruecallerAuth","requestOtp $url: ${e.message}") }
        }
        null
    }

    suspend fun verifyOtp(phone: String, requestId: String, otp: String): VerifyResult = withContext(Dispatchers.IO) {
        // Already installationId case
        if (requestId.length>20 && !requestId.contains("-")) {
            TruecallerCloudStore.saveInstallationId(context, requestId)
            return@withContext VerifyResult(true, requestId, 3, "Already logged in")
        }
        val norm = PhoneNumberUtils.normalize(phone)
        val cc = PhoneNumberUtils.getCountryCode(norm) ?: "BD"
        val sig = PhoneNumberUtils.getSignificantNumber(norm) ?: norm.filter{it.isDigit()}
        val dial = PhoneNumberUtils.getDialingCode(norm) ?: 880
        val secret = TruecallerCloudStore.getClientSecret(context).ifBlank { "lvc22mp3l1sfv6ujg83rd17btt" }
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
            val resp = client.newCall(req).execute()
            val txt = resp.body?.string()
            if (txt!=null) {
                val j = try { gson.fromJson(txt, JsonObject::class.java) } catch(_:Exception){ null }
                if (j!=null) {
                    val status = j.get("status")?.asInt ?: 0
                    val installationId = j.get("installationId")?.takeIf{!it.isJsonNull}?.asString ?: j.get("accessToken")?.takeIf{!it.isJsonNull}?.asString
                    if (status==2 && installationId!=null && !resp.let{ it.code==401}) {
                        // CLOUD SECRET AUTO-CREATED by Truecaller on successful verify
                        TruecallerCloudStore.saveInstallationId(context, installationId)
                        Log.i("TruecallerAuth","Cloud secret created: ${installationId.take(12)}... status=2")
                        // Optional backend sync (best-effort, no-op if backend not configured)
                        try { backendApi?.let { /* sync encrypted installationId if you add /api/v1/auth/truecaller/sync */ } } catch(_:Exception){}
                        return@withContext VerifyResult(true, installationId, 2, j.get("message")?.asString ?: "Verified")
                    }
                    if (status==17) return@withContext completeOnboarding(phone, requestId, otp)
                    if (status==11) return@withContext VerifyResult(false, null, 11, "Invalid OTP")
                    if (status==7) return@withContext VerifyResult(false, null, 7, "Retries exceeded")
                    return@withContext VerifyResult(false, null, status, j.get("message")?.asString ?: "Verification failed")
                }
            }
        } catch(e:Exception){ Log.e("TruecallerAuth","verify error: ${e.message}") }
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
            addProperty("firstName","Info"); addProperty("lastName","User")
        }
        try{
            val req = Request.Builder().url("https://account-noneu.truecaller.com/v1/completeOnboarding")
                .addHeader("content-type","application/json; charset=UTF-8")
                .addHeader("accept-encoding","gzip")
                .addHeader("user-agent","Truecaller/11.75.5 (Android;10)")
                .addHeader("clientsecret","lvc22mp3l1sfv6ujg83rd17btt")
                .post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
            val resp = client.newCall(req).execute()
            val txt = resp.body?.string() ?: return@withContext VerifyResult(false, null, -1, "Empty response")
            val j = gson.fromJson(txt, JsonObject::class.java)
            val installationId = j.get("installationId")?.takeIf{!it.isJsonNull}?.asString ?: j.get("accessToken")?.takeIf{!it.isJsonNull}?.asString
            if (installationId!=null) { TruecallerCloudStore.saveInstallationId(context, installationId); return@withContext VerifyResult(true, installationId, 2, "Onboarded") }
            return@withContext VerifyResult(false, null, -1, j.get("message")?.asString ?: "Sign-up failed")
        } catch(e:Exception){ VerifyResult(false, null, -1, e.message) }
    }
}
