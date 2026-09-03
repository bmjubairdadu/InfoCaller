package com.infocaller.app.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.data.local.entity.NidEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot importer for assets/database.json (115k records) into Room nid_records.
 * Runs on first launch, shows progress via callback, then marks prefs imported.
 */
object NidDatabaseImporter {
    private const val PREFS = "app_prefs"
    private const val KEY_IMPORTED = "nid_db_imported_v2"
    private const val KEY_COUNT = "nid_db_count"

    data class DbRecord(val number: String, val nid: String, val dob: String, val database: String? = null, val tg: String? = null)

    suspend fun importIfNeeded(context: Context, db: AppDatabase, onProgress: (imported:Int, total:Int) -> Unit = {_,_->}) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IMPORTED, false)) {
            Log.d("NidImport", "Already imported ${prefs.getInt(KEY_COUNT,0)} records")
            return@withContext
        }
        try {
            val json = context.assets.open("database.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<DbRecord>>() {}.type
            val list: List<DbRecord> = Gson().fromJson(json, type)
            val total = list.size
            Log.i("NidImport", "Importing $total NID records into Room")
            val dao = db.nidDao()
            // chunk to avoid transaction too large
            var imported = 0
            list.chunked(1000).forEach { chunk ->
                val entities = chunk.map { r -> NidEntity(number = r.number, nid = r.nid, dob = r.dob, database = r.database, tg = r.tg) }
                dao.insertAll(entities)
                imported += chunk.size
                onProgress(imported, total)
            }
            prefs.edit().putBoolean(KEY_IMPORTED, true).putInt(KEY_COUNT, total).apply()
            Log.i("NidImport", "Import done: $imported records")
        } catch (e: Exception) {
            Log.e("NidImport", "Failed to import NID db", e)
        }
    }

    fun isImported(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_IMPORTED, false)
    fun getCount(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COUNT, 0)
}
