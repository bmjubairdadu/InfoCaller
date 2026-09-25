package com.infocaller.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object CopyHelper {
    fun copy(context: Context, label: String, text: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (_: Exception) { false }
    }
}
