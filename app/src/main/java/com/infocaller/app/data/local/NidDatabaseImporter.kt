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

    data class DbRecord(val number: String, val nid: String, val dob: String, val database: String? = null, val tg: String? = null)

    suspend fun importIfNeeded(context: Context, db: AppDatabase, onProgress: (imported:Int, total:Int) -> Unit = {_,_->}) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IMPORTED, false)) {
            Log.d("NidImport", "Already imported ${prefs.getInt(KEY_COUNT,0)} records")
            return@withContext
        }
        try {
            val reader = openLocalDatabaseReader(context) ?: run { Log.w("NidImport", "No local database.json found in assets"); return@withContext }
            val gson = Gson()
            val jsonReader = com.google.gson.stream.JsonReader(reader)
            jsonReader.isLenient = true
            jsonReader.beginArray()
            val dao = db.nidDao()
            var imported = 0
            val buffer = mutableListOf<NidEntity>()
            while (jsonReader.hasNext()) {
                val r: DbRecord = gson.fromJson(jsonReader, DbRecord::class.java)
                buffer.add(NidEntity(number = r.number, nid = r.nid, dob = r.dob, database = r.database, tg = r.tg))
                if (buffer.size >= 500) { dao.insertAll(buffer.toList()); imported += buffer.size; onProgress(imported, imported); buffer.clear() }
            }
            if (buffer.isNotEmpty()) { dao.insertAll(buffer); imported += buffer.size; onProgress(imported, imported) }
            jsonReader.endArray(); jsonReader.close()
            prefs.edit().putBoolean(KEY_IMPORTED, true).putInt(KEY_COUNT, imported).apply()
            Log.i("NidImport", "Import done: $imported records from local database.json")
        } catch (e: Exception) {
            Log.e("NidImport", "Failed to import NID db", e)
        }
    }

    fun isImported(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_IMPORTED, false)
    fun getCount(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COUNT, 0)

    private fun openLocalDatabaseReader(context: Context): Reader? {
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
