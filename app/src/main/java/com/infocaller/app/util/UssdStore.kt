package com.infocaller.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

data class UssdEntry(
    val code: String,
    val label: String = "",
    val id: Long = System.currentTimeMillis()
)

object UssdStore {
    private const val PREFS = "ussd_prefs"
    private const val KEY_ENTRIES = "ussd_entries_v1"

    fun defaultEntries(): List<UssdEntry> = OSINTManager.getCommonUssdCodes().map {
        UssdEntry(code = it.url, label = it.title)
    }

    fun load(context: Context): List<UssdEntry> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES, null) ?: return defaultEntries()
            if (raw.isBlank()) return defaultEntries()
            val arr = JSONArray(raw)
            val out = ArrayList<UssdEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = o.optString("code", "").trim()
                if (code.isEmpty()) continue
                out += UssdEntry(code = code, label = o.optString("label", ""), id = o.optLong("id", System.currentTimeMillis() + i))
            }
            if (out.isEmpty()) defaultEntries() else out
        } catch (_: Exception) {
            defaultEntries()
        }
    }

    fun save(context: Context, entries: List<UssdEntry>) {
        try {
            val arr = JSONArray()
            entries.forEach {
                val o = JSONObject()
                o.put("code", it.code.trim())
                o.put("label", it.label.trim())
                o.put("id", it.id)
                arr.put(o)
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENTRIES, arr.toString()).apply()
        } catch (_: Exception) { }
    }

    fun isUssd(input: String): Boolean {
        val t = input.trim()
        if (t.isEmpty()) return false
        return t.startsWith("*") || t.startsWith("#") || (t.contains("*") && t.endsWith("#"))
    }

    fun run(context: Context, rawCode: String) {
        val code = rawCode.trim()
        if (code.isEmpty()) return
        try {
            val encoded = code.replace("#", Uri.encode("#"))
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = "tel:$encoded".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val encoded = code.replace("#", Uri.encode("#"))
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = "tel:$encoded".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) { }
        }
    }
}
