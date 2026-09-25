package com.infocaller.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.infocaller.app.BuildConfig
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AppUpdateManager {
    private const val REPO = "bmjubairdadu/InfoCaller"
    private const val PREFS = "app_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val RELEASE_PAGE_URL = "https://github.com/bmjubairdadu/InfoCaller/releases/latest"

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class Available(val version: String, val notes: String, val sizeBytes: Long, val url: String) : UpdateState
        data class Failed(val reason: String) : UpdateState
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun currentVersion(): String = try { BuildConfig.VERSION_NAME } catch (_: Exception) { "0.0.0" }

    private fun shouldCheck(context: Context, force: Boolean): Boolean {
        if (force) return true
        return try {
            val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)
            System.currentTimeMillis() - last > CHECK_INTERVAL_MS
        } catch (_: Exception) { true }
    }

    private fun markChecked(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        } catch (_: Exception) { }
    }

    suspend fun checkForUpdate(context: Context, force: Boolean = false): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            try {
                if (!shouldCheck(context, force)) {
                    return@withContext (_state.value as? UpdateState.Available)?.let {
                        ReleaseInfo(it.version, it.notes, it.url, it.sizeBytes)
                    }
                }
                _state.value = UpdateState.Checking
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "InfoCaller/${currentVersion()} (Android)")
                    .build()
                val body = client.newCall(req).await().use { r ->
                    if (r.code == 403 || r.code == 429) {
                        _state.value = UpdateState.Idle
                        markChecked(context)
                        return@withContext null
                    }
                    if (!r.isSuccessful) {
                        _state.value = UpdateState.Idle
                        return@withContext null
                    }
                    r.body?.string()
                } ?: run {
                    _state.value = UpdateState.Idle
                    return@withContext null
                }
                markChecked(context)
                val info = parseRelease(body) ?: run {
                    _state.value = UpdateState.Idle
                    return@withContext null
                }
                if (isNewer(info.version, currentVersion())) {
                    _state.value = UpdateState.Available(info.version, info.notes, info.sizeBytes, info.apkUrl)
                    info
                } else {
                    _state.value = UpdateState.Idle
                    null
                }
            } catch (e: Exception) {
                Log.w("AppUpdate", "check failed", e)
                _state.value = UpdateState.Idle
                null
            }
        }

    internal fun parseRelease(body: String): ReleaseInfo? {
        return try {
            val j = JSONObject(body)
            if (j.optBoolean("draft", false) || j.optBoolean("prerelease", false)) return null
            val tag = j.optString("tag_name", "").trim().removePrefix("v")
            if (tag.isBlank()) return null
            val assets = j.optJSONArray("assets") ?: return null
            var bestUrl = ""
            var bestSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = a.optString("browser_download_url", "")
                if (url.isBlank()) continue
                val size = a.optLong("size", 0L)
                if (size >= bestSize) { bestSize = size; bestUrl = url }
            }
            if (bestUrl.isBlank()) return null
            ReleaseInfo(tag, j.optString("body", "").take(2000), bestUrl, bestSize)
        } catch (_: Exception) { null }
    }

    internal fun isNewer(latest: String, current: String): Boolean {
        return try {
            val l = latest.split(".", "-").mapNotNull { it.toIntOrNull() }
            val c = current.split(".", "-").mapNotNull { it.toIntOrNull() }
            val n = maxOf(l.size, c.size)
            for (i in 0 until n) {
                val lv = l.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (lv != cv) return lv > cv
            }
            false
        } catch (_: Exception) { false }
    }

    fun openReleasePage(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Cannot open browser")
        }
    }

    fun reset() { _state.value = UpdateState.Idle }
}
