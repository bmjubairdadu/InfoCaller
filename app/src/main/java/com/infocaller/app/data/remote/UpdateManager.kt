package com.infocaller.app.data.remote

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Handles automatic updates via GitHub Releases.
 */
class UpdateManager(private val context: Context) {
    
    private val client = OkHttpClient()
    private val gson = Gson()
    private val repoUrl = "https://api.github.com/repos/infocaller/infocaller-app/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String?,
        val sha256: String?
    )

    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(repoUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            
            val body = response.body?.string() ?: return@withContext null
            val json = gson.fromJson(body, JsonObject::class.java)
            
            val tagName = json.get("tag_name")?.asString ?: return@withContext null
            val bodyText = json.get("body")?.asString
            
            // Assuming version code is in the release body or encoded in tag
            // e.g. "v1.2.3 (105)"
            val versionCodeMatch = "\\((\\d+)\\)".toRegex().find(bodyText ?: "")
            val remoteVersionCode = versionCodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            val currentVersionCode = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            
            if (remoteVersionCode > currentVersionCode) {
                val asset = json.getAsJsonArray("assets")?.firstOrNull { 
                    it.asJsonObject.get("name").asString.endsWith(".apk") 
                }?.asJsonObject
                
                val downloadUrl = asset?.get("browser_download_url")?.asString
                
                if (downloadUrl != null) {
                    return@withContext UpdateInfo(
                        versionName = tagName,
                        versionCode = remoteVersionCode,
                        downloadUrl = downloadUrl,
                        releaseNotes = bodyText,
                        sha256 = null // Verification can be added later
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Update check failed", e)
        }
        null
    }

    fun downloadAndInstall(info: UpdateInfo) {
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "InfoCaller_${info.versionName}.apk")
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("InfoCaller Update ${info.versionName}")
            .setDescription("Downloading new version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == downloadId) {
                    installApk(destination)
                    context.unregisterReceiver(this)
                }
            }
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
