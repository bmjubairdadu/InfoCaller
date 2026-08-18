package com.infocaller.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.infocaller.app.BuildConfig
import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.data.local.entity.OperatorLogoEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class OperatorLogoManager(private val context: Context, private val database: AppDatabase) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
        
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize(sims: List<SimInfo>) {
        scope.launch {
            checkAndDownloadLogos(sims)
        }
    }

    private suspend fun checkAndDownloadLogos(sims: List<SimInfo>) = withContext(Dispatchers.IO) {
        val dao = database.operatorLogoDao()
        for (sim in sims) {
            val key = getOperatorKey(sim)
            val existing = dao.getLogo(key)
            
            val localFileExists = existing?.localFilePath?.let { File(it).exists() } == true
            
            if (existing == null || !localFileExists) {
                val brand = OperatorBrandResolver.resolveBrand(sim.carrierName, sim.displayName, sim.mcc, sim.mnc)
                if (brand.officialDomain != null) {
                    downloadLogo(sim, key, brand.officialDomain)
                }
            }
        }
    }

    private fun getOperatorKey(sim: SimInfo): String {
        return if (!sim.mcc.isNullOrBlank() && !sim.mnc.isNullOrBlank()) {
            "${sim.mcc}${sim.mnc}"
        } else {
            "${sim.carrierName.lowercase()}_${sim.countryIso.lowercase()}".replace(" ", "_")
        }
    }

    private suspend fun downloadLogo(sim: SimInfo, key: String, domain: String) {
        val dao = database.operatorLogoDao()
        val url = SimManager.buildBrandfetchLogoUrl(domain)

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "image/png,image/jpeg,image/*")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val contentType = response.header("Content-Type")
                if (contentType?.startsWith("text/html") == true) {
                    throw Exception("Received HTML instead of image")
                }

                val bytes = response.body?.bytes() ?: throw Exception("Empty response body")
                
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                
                if (options.outWidth > 0 && options.outHeight > 0) {
                    val file = saveLogoLocally(key, bytes)
                    if (file != null) {
                        dao.insertLogo(OperatorLogoEntity(
                            operatorKey = key,
                            operatorName = sim.carrierName,
                            country = sim.countryIso,
                            mcc = sim.mcc,
                            mnc = sim.mnc,
                            officialDomain = domain,
                            localFilePath = file.absolutePath,
                            source = "brandfetch",
                            verified = true,
                            updatedAt = System.currentTimeMillis()
                        ))
                        Log.d("OperatorLogoManager", "Successfully saved Brandfetch logo for $key ($domain)")
                    }
                } else {
                    throw Exception("Invalid image data decoded")
                }
            } else {
                throw Exception("HTTP Error: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("OperatorLogoManager", "Brandfetch failure for $key: ${e.message}")
            if (dao.getLogo(key) == null) {
                dao.insertLogo(OperatorLogoEntity(
                    operatorKey = key,
                    operatorName = sim.carrierName,
                    country = sim.countryIso,
                    mcc = sim.mcc,
                    mnc = sim.mnc,
                    officialDomain = domain,
                    localFilePath = null,
                    source = "failed",
                    verified = false,
                    updatedAt = System.currentTimeMillis()
                ))
            }
        }
    }

    private fun saveLogoLocally(key: String, bytes: ByteArray): File? {
        return try {
            val dir = File(context.filesDir, "operator_logos")
            if (!dir.exists()) dir.mkdirs()
            
            val file = File(dir, "logo_$key.png")
            FileOutputStream(file).use { it.write(bytes) }
            file
        } catch (e: Exception) {
            Log.e("OperatorLogoManager", "Failed to save file: ${e.message}")
            null
        }
    }
    
    suspend fun getLocalLogoPath(sim: SimInfo): String? = withContext(Dispatchers.IO) {
        val key = getOperatorKey(sim)
        val logo = database.operatorLogoDao().getLogo(key)
        return@withContext logo?.localFilePath?.takeIf { File(it).exists() }
    }
}
