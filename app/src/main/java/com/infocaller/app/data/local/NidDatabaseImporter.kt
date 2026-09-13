package com.infocaller.app.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.data.local.entity.NidEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.Reader

object NidDatabaseImporter {
    private const val PREFS = "app_prefs"
    private const val KEY_IMPORTED = "nid_db_imported_v2"
    private const val KEY_COUNT = "nid_db_count"

    // database/tg fields dropped (unused) — parser ignores them if present in JSON.
    data class DbRecord(val number: String, val nid: String, val dob: String)

    suspend fun importIfNeeded(context: Context, db: AppDatabase, onProgress: (imported:Int, total:Int) -> Unit = {_,_->}) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IMPORTED, false)) {
            // Self-heal: flag says done but table is empty (fresh install / cleared data).
            try {
                if (db.nidDao().count() > 0) {
                    Log.d("NidImport", "Already imported ${prefs.getInt(KEY_COUNT,0)} records")
                    return@withContext
                }
                Log.w("NidImport", "Import flag set but table empty — re-importing")
            } catch (_: Exception) { }
        }
        var reader: Reader? = null
        var jsonReader: com.google.gson.stream.JsonReader? = null
        try {
            reader = openLocalDatabaseReader(context) ?: run { Log.w("NidImport", "No local database.json found in assets"); return@withContext }
            val gson = Gson()
            jsonReader = com.google.gson.stream.JsonReader(reader)
            jsonReader.isLenient = true
            jsonReader.beginArray()
            val dao = db.nidDao()
            var imported = 0
            var skipped = 0
            val buffer = mutableListOf<NidEntity>()
            while (jsonReader.hasNext()) {
                try {
                    val r: DbRecord = gson.fromJson(jsonReader, DbRecord::class.java)
                    val number = normalizeNumber(r.number)
                    val nid = r.nid.filter { it.isDigit() }
                    val dob = r.dob.trim().take(12)
                    // Skip junk rows instead of crashing the whole 115k import.
                    if (number.length !in 10..13 || nid.length !in 10..17 || dob.length < 8) { skipped++; continue }
                    buffer.add(NidEntity(number = number, nid = nid, dob = dob))
                } catch (_: Exception) { skipped++; continue } catch (_: Error) { skipped++; continue }
                if (buffer.size >= 1000) {
                    try { dao.insertAll(buffer.toList()) } catch (_: Exception) { skipped += buffer.size } catch (_: Error) { skipped += buffer.size }
                    imported += buffer.size; onProgress(imported, imported); buffer.clear()
                }
            }
            if (buffer.isNotEmpty()) {
                try { dao.insertAll(buffer.toList()) } catch (_: Exception) { skipped += buffer.size } catch (_: Error) { skipped += buffer.size }
                imported += buffer.size; onProgress(imported, imported)
            }
            try { jsonReader.endArray() } catch (_: Exception) { }
            prefs.edit().putBoolean(KEY_IMPORTED, true).putInt(KEY_COUNT, imported).apply()
            Log.i("NidImport", "Import done: $imported records, skipped $skipped (bad rows)")
        } catch (e: Exception) {
            Log.e("NidImport", "Failed to import NID db", e)
        } finally {
            try { jsonReader?.close() } catch (_: Exception) { }
            try { reader?.close() } catch (_: Exception) { }
        }
    }

    /** Canonical 11-digit local form (016..., 017...) so lookups always hit. */
    fun normalizeNumber(raw: String?): String {
        return try {
            var d = raw?.filter { it.isDigit() }.orEmpty()
            if (d.startsWith("880") && d.length >= 12) d = "0" + d.substring(3)
            d.take(13)
        } catch (_: Exception) { "" } catch (_: Error) { "" }
    }

    fun isImported(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_IMPORTED, false)
    fun getCount(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COUNT, 0)

    private fun openLocalDatabaseReader(context: Context): Reader? {
        // Prefer gzipped bundle (database.json.gz ~2MB vs 12.7MB raw) — same records.
        try {
            val raw = context.assets.open("database.json.gz")
            val gz = java.io.BufferedInputStream(raw)
            try {
                return java.util.zip.GZIPInputStream(gz).bufferedReader()
            } catch (_: IOException) {
                try { gz.close() } catch (_: Exception) { }
                try { raw.close() } catch (_: Exception) { }
            }
        } catch (_: IOException) { } catch (_: Exception) { }
        return try {
            val inputStream = context.assets.open("database.json")
            val bufferedStream = BufferedInputStream(inputStream)

            bufferedStream.mark(3)
            val bom = ByteArray(3)
            if (bufferedStream.read(bom) == 3 &&
                bom[0] == 0xEF.toByte() &&
                bom[1] == 0xBB.toByte() &&
                bom[2] == 0xBF.toByte()) {
                Log.d("NidImport", "UTF-8 BOM detected and skipped")
            } else {
                bufferedStream.reset()
            }

            bufferedStream.bufferedReader()
        } catch (e: IOException) {
            Log.w("NidImport", "database.json not found in assets or could not be opened", e)
            null
        }
    }
}
