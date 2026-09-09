package com.infocaller.app.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.infocaller.app.BuildConfig
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object AppUpdateManager {
    private const val REPO = "bmjubairdadu/InfoCaller"
    private const val PREFS = "app_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class Available(val version: String, val notes: String, val sizeBytes: Long, val url: String) : UpdateState
        data class Downloading(val progress: Int) : UpdateState
        data class Ready(val file: File) : UpdateState
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

    fun downloadUpdate(context: Context, info: ReleaseInfo) {
        try {
            _state.value = UpdateState.Downloading(0)
            val fileName = "InfoCaller-v${info.version}-update.apk"
            val req = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("InfoCaller v${info.version}")
                .setDescription("Downloading update…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_DOWNLOAD_ID, id).apply()
            registerCompletionReceiver(context.applicationContext)
            pollProgress(context.applicationContext, id, fileName)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Download failed")
        }
    }

    private fun registerCompletionReceiver(appContext: Context) {
        try {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(downloadReceiver, filter)
            }
        } catch (_: Exception) { }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, -1L)
                if (id != saved) return
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val q = DownloadManager.Query().setFilterById(id)
                dm.query(q)?.use { c ->
                    if (!c.moveToFirst()) return
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val uri = dm.getUriForDownloadedFile(id)
                        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                            "InfoCaller-update.apk")
                        openInstaller(context, uri)
                        _state.value = UpdateState.Ready(file)
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        _state.value = UpdateState.Failed("Download failed")
                    }
                }
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "Download failed")
            }
            try { context.unregisterReceiver(this) } catch (_: Exception) { }
        }
    }

    private fun pollProgress(appContext: Context, id: Long, fileName: String) {
        Thread({
            try {
                val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                while (_state.value is UpdateState.Downloading) {
                    Thread.sleep(800)
                    try {
                        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                            if (!c.moveToFirst()) return@use
                            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            when (status) {
                                DownloadManager.STATUS_RUNNING -> {
                                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                    val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                                    _state.value = UpdateState.Downloading(pct)
                                }
                                DownloadManager.STATUS_SUCCESSFUL -> return@Thread
                                DownloadManager.STATUS_FAILED -> {
                                    _state.value = UpdateState.Failed("Download failed")
                                    return@Thread
                                }
                                else -> Unit
                            }
                        }
                    } catch (_: Exception) { return@Thread }
                }
            } catch (_: Exception) { }
        }, "update-progress").apply { isDaemon = true }.start()
    }

    fun openInstaller(context: Context, apkUri: Uri) {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "InfoCaller-v${(_state.value as? UpdateState.Available)?.version}-update.apk")
            val uri = if (apkUri.scheme == "content") apkUri else try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (_: Exception) { apkUri }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Cannot open installer")
        }
    }

    fun reset() { _state.value = UpdateState.Idle }
}
