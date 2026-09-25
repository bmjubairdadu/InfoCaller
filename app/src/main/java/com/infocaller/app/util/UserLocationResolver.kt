package com.infocaller.app.util

import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.infocaller.app.util.await
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UserLocationResolver {
    private const val PREFS = "user_location_prefs"
    private const val K_COUNTRY = "user_country"
    private const val K_REGION = "user_region"
    private const val K_CITY = "user_city"
    private const val K_AT = "user_loc_at"

    data class UserLocation(
        val country: String? = null,
        val region: String? = null,
        val city: String? = null,
    ) {
        fun isBlank() = country.isNullOrBlank() && region.isNullOrBlank() && city.isNullOrBlank()
        fun display(): String = LocationUtils.formatCallerLocation(city, region, country)
    }

    fun cached(context: Context): UserLocation? {
        return try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val age = System.currentTimeMillis() - sp.getLong(K_AT, 0L)
            if (age > 24L * 60 * 60 * 1000) return null
            val c = sp.getString(K_COUNTRY, null)
            val r = sp.getString(K_REGION, null)
            val ci = sp.getString(K_CITY, null)
            if (c.isNullOrBlank() && r.isNullOrBlank() && ci.isNullOrBlank()) null else UserLocation(c, r, ci)
        } catch (_: Exception) { null }
    }

    fun save(context: Context, loc: UserLocation) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_COUNTRY, loc.country)
                .putString(K_REGION, loc.region)
                .putString(K_CITY, loc.city)
                .putLong(K_AT, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) { }
    }

    suspend fun resolve(
        context: Context,
        httpClient: OkHttpClient? = null,
    ): UserLocation? = withContext(Dispatchers.IO) {
        cached(context)?.let { return@withContext it }

        tryGpsLocation(context)?.let { save(context, it); return@withContext it }

        tryIpLocation(httpClient)?.let { save(context, it); return@withContext it }

        trySimCountry(context)?.let { save(context, it); return@withContext it }

        null
    }

    private suspend fun tryGpsLocation(context: Context): UserLocation? {
        return try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return null

            var loc: android.location.Location? = null
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                val providers = try { lm?.getProviders(true) } catch (_: Exception) { null }.orEmpty()
                for (p in providers) {
                    try {
                        val cand = lm?.getLastKnownLocation(p)
                        if (cand != null && (loc == null || cand.time > loc!!.time)) loc = cand
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            val ll: android.location.Location = loc ?: return null
            val client2 = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
            val req = Request.Builder()
                .url("https://nominatim.openstreetmap.org/reverse?format=json&lat=${ll.latitude}&lon=${ll.longitude}&zoom=10&addressdetails=1&accept-language=en")
                .header("User-Agent", "InfoCaller/1.0 (Android)")
                .build()
            client2.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return UserLocation(country = null, region = null, city = null)
                val j2 = JSONObject(resp.body?.string().orEmpty())
                val addr = j2.optJSONObject("address") ?: return null
                UserLocation(
                    country = addr.optString("country", "").takeIf { it.isNotBlank() },
                    region = (addr.optString("state", "").takeIf { it.isNotBlank() }
                        ?: addr.optString("county", "").takeIf { it.isNotBlank() }),
                    city = (addr.optString("city", "").takeIf { it.isNotBlank() }
                        ?: addr.optString("town", "").takeIf { it.isNotBlank() }
                        ?: addr.optString("village", "").takeIf { it.isNotBlank() }),
                ).takeIf { !it.isBlank() }
            }
        } catch (_: Exception) { null }
    }

    private suspend fun tryIpLocation(client: OkHttpClient?): UserLocation? {
        return try {
            val c = client ?: OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
            val req = Request.Builder().url("https://ipapi.co/json/").header("User-Agent", "InfoCaller/1.0").build()
            c.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return null
                val j = JSONObject(resp.body?.string().orEmpty())
                val city = j.optString("city", "").takeIf { it.isNotBlank() }
                val region = j.optString("region", "").takeIf { it.isNotBlank() }
                val country = j.optString("country_name", "").takeIf { it.isNotBlank() }
                    ?: j.optString("country", "").takeIf { it.isNotBlank() }
                UserLocation(country = country, region = region, city = city).takeIf { !it.isBlank() }
            }
        } catch (_: Exception) { null }
    }

    private fun trySimCountry(context: Context): UserLocation? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
            val iso = try { tm.networkCountryIso } catch (_: Exception) { null }
                ?: try { tm.simCountryIso } catch (_: Exception) { null }
            if (iso.isNullOrBlank()) return null
            val country = try { java.util.Locale("", iso).displayCountry } catch (_: Exception) { iso.uppercase() }
            UserLocation(country = country.takeIf { it.isNotBlank() }).takeIf { !it.isBlank() }
        } catch (_: Exception) { null }
    }

    fun bindToSimSlots(context: Context, loc: UserLocation) {
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val editor = sp.edit()
            val sm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val ids = try {
                val sub = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? android.telephony.SubscriptionManager
                sub?.activeSubscriptionInfoList?.mapNotNull { it.subscriptionId.toString() } ?: emptyList()
            } catch (_: Exception) { emptyList() }
            val d = loc.display()
            if (d.isNotBlank()) {
                if (ids.isEmpty()) editor.putString("sim_loc_default", d)
                else ids.forEach { editor.putString("sim_loc_$it", d) }
                editor.apply()
            }
        } catch (_: Exception) { }
    }
}
