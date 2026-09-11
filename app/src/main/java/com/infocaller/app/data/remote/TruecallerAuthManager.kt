package com.infocaller.app.data.remote

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
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
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
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

    private fun generateUniqueDeviceIdFor(phone: String): String {
        val raw = (phone.filter { it.isDigit() } + "-" + System.nanoTime() + "-" + Math.random()).toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun clearTruecallerDeviceStateFor(phone: String? = null) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.remove("truecaller_token")
        editor.remove("tc_device_id")
        editor.remove("last_tc_request_id")
        editor.remove("last_tc_method")
        editor.remove("last_tc_phone")
        editor.remove("last_tc_host")
        val seedPhone = phone?.filter { it.isDigit() }
        if (!seedPhone.isNullOrBlank()) {
            val phoneKey = seedPhone.takeLast(11)
            editor.remove("tc_device_id_$phoneKey")
            editor.remove("tc_otp_sequence_$phoneKey")
        }
        editor.apply()
    }

    private fun freshDeviceIdFor(normalizedPhone: String): String {
        return try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val key = "tc_device_id_" + normalizedPhone.filter { it.isDigit() }.takeLast(11)
            var did = prefs.getString(key, null)
            if (did.isNullOrBlank() || did == "9774d56d682e549c") {
                did = generateUniqueDeviceIdFor(normalizedPhone)
                prefs.edit().putString(key, did).apply()
            }
            prefs.edit().putString("tc_device_id", did).apply()
            did
        } catch (_: Exception) {
            deviceIdReal()
        }
    }

    suspend fun requestOtp(phone: String): OtpRequestResult? = withContext(Dispatchers.IO) {
        val norm = PhoneNumberUtils.normalize(phone)
        val cc = PhoneNumberUtils.getCountryCode(norm) ?: "BD"
        val sig = PhoneNumberUtils.getSignificantNumber(norm) ?: norm.filter{it.isDigit()}
        val dial = PhoneNumberUtils.getDialingCode(norm) ?: 880
        val secret = "lvc22mp3l1sfv6ujg83rd17btt"
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lastPhone = prefs.getString("last_tc_phone", null)?.let { PhoneNumberUtils.normalize(it) }
        if (lastPhone != null && lastPhone != norm) {
            clearTruecallerDeviceStateFor(lastPhone)
        }
        val deviceId = freshDeviceIdFor(norm)
        val phoneKey = norm.filter { it.isDigit() }.takeLast(11)
        val sequenceKey = "tc_otp_sequence_$phoneKey"
        val seqNo = (prefs.getInt(sequenceKey, 0) + 1).coerceAtMost(2)

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
            addProperty("phoneNumber", sig); addProperty("region","region-2"); addProperty("sequenceNo",seqNo)
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
                    val host = try { java.net.URI(url).host } catch (_: Exception) { null }
                    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                        .putString("last_tc_request_id", rid)
                        .putString("last_tc_phone", PhoneNumberUtils.normalize(phone))
                        .putString("last_tc_method", method)
                        .putString("last_tc_host", host)
                        .putInt(sequenceKey, seqNo)
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
                    clearTruecallerDeviceStateFor(norm)
                    return@withContext OtpRequestResult("", "", 0, status, msg ?: "Too many requests. Try again after 1 hour.")
                }

                lastError = msg ?: txt.take(200)
            } catch (e: CancellationException) {
                throw e
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

        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val preferredHost = prefs.getString("last_tc_host", null)
        val candidateHosts = listOfNotNull(
            preferredHost,
            "account-asia-south1.truecaller.com",
            "account-noneu.truecaller.com"
        ).distinct()

        Log.d("TruecallerAuth", "verifyOtp: candidateHosts=$candidateHosts, requestId=$requestId, otp=$otp")

        var lastResult: VerifyResult? = null
        for (host in candidateHosts) {
            try {
                Log.d("TruecallerAuth", "verifyOtp: Trying host $host")
                val req = Request.Builder().url("https://$host/v1/verifyOnboardingOtp")
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
                Log.d("TruecallerAuth", "verifyOtp: Response from $host (code=$code): $txt")
                if (txt != null) {
                    val j = try { gson.fromJson(txt, JsonObject::class.java) } catch(_:Exception){ null }
                    if (j != null && j.has("status")) {
                        val status = j.get("status")?.asInt ?: 0
                        if (status == 2) {
                            val suspended = j.get("suspended")?.asBoolean ?: false
                            if (suspended) return@withContext VerifyResult(false, null, 2, "Account suspended")
                            val installationId = j.get("installationId")?.takeIf{!it.isJsonNull}?.asString ?: j.get("accessToken")?.takeIf{!it.isJsonNull}?.asString
                            if (installationId != null) {
                                TruecallerCloudStore.saveInstallationId(context, installationId)
                                return@withContext VerifyResult(true, installationId, 2, "Verified")
                            }
                            return@withContext VerifyResult(false, null, 2, "Installation ID not found: $txt")
                        }
                        if (status == 17) return@withContext completeOnboarding(phone, requestId, otp, host)
                        if (status == 11 || status == 40101) {
                            // status 11 = gateway not yet confirmed the drop-call.
                            // Never short-circuit — try every remaining host.
                            lastResult = VerifyResult(false, null, 11, "Invalid OTP")
                            Log.d("TruecallerAuth", "verifyOtp: status 11 on $host — trying next host")
                            continue
                        }
                        if (status == 7) return@withContext VerifyResult(false, null, 7, "Retries limit exceeded")
                        return@withContext VerifyResult(false, null, status, j.get("message")?.asString ?: txt)
                    }
                    if (isSuccessful && txt.contains("installationId")) {
                        val j2 = try { gson.fromJson(txt, JsonObject::class.java) } catch(_:Exception){ null }
                        val iid = j2?.get("installationId")?.asString
                        if (iid != null) {
                            TruecallerCloudStore.saveInstallationId(context, iid)
                            return@withContext VerifyResult(true, iid, 2, "Verified")
                        }
                    }
                    lastResult = VerifyResult(false, null, code, txt.take(500))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("TruecallerAuth", "verify error on $host: ${e.message}", e)
                lastResult = VerifyResult(false, null, -1, e.message ?: "Network error")
            }
        }
        return@withContext lastResult ?: VerifyResult(false, null, -1, "Network error")
    }

    private suspend fun completeOnboarding(phone: String, requestId: String, otp: String, preferredHost: String? = null): VerifyResult = withContext(Dispatchers.IO) {
        val norm = PhoneNumberUtils.normalize(phone)
        val cc = PhoneNumberUtils.getCountryCode(norm) ?: "BD"
        val sig = PhoneNumberUtils.getSignificantNumber(norm) ?: norm.filter{it.isDigit()}
        val dial = PhoneNumberUtils.getDialingCode(norm) ?: 880
        val body = JsonObject().apply {
            addProperty("countryCode", cc); addProperty("dialingCode", dial); addProperty("phoneNumber", sig)
            addProperty("requestId", requestId); addProperty("token", otp.filter{it.isDigit()})
            addProperty("name", "User"); addProperty("firstName", "User"); addProperty("lastName", "")
        }
        val candidateHosts = listOfNotNull(
            preferredHost,
            "account-noneu.truecaller.com",
            "account-asia-south1.truecaller.com"
        ).distinct()

        for (host in candidateHosts) {
            try {
                Log.d("TruecallerAuth", "completeOnboarding trying host $host")
                val req = Request.Builder().url("https://$host/v1/completeOnboarding")
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
                Log.d("TruecallerAuth", "completeOnboarding response from $host (code=$code): $txt")
                if (txt != null) {
                    val j = try { gson.fromJson(txt, JsonObject::class.java) } catch(_:Exception){ null }
                    val installationId = j?.get("installationId")?.takeIf{!it.isJsonNull}?.asString ?: j?.get("accessToken")?.takeIf{!it.isJsonNull}?.asString
                    if (installationId != null) {
                        TruecallerCloudStore.saveInstallationId(context, installationId)
                        return@withContext VerifyResult(true, installationId, 2, "Onboarded")
                    }
                    if (j?.has("message") == true) {
                        return@withContext VerifyResult(false, null, code, j.get("message")?.asString ?: txt.take(500))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("TruecallerAuth", "completeOnboarding error on $host: ${e.message}", e)
            }
        }
        VerifyResult(false, null, -1, "Complete onboarding failed")
    }
}
