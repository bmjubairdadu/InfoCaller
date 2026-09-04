package com.infocaller.app.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.infocaller.app.data.local.entity.ContactEnrichmentEntity
import com.infocaller.app.domain.model.Caller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the caller-details card (all retrieved fields, saved + unsaved) to
 * a .png in the Downloads folder. Missing fields are skipped — no "N/A".
 */
object DetailsPngExporter {

    suspend fun export(
        context: Context,
        phoneNumber: String,
        displayName: String?,
        caller: Caller?,
        enrichment: ContactEnrichmentEntity?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val lines = buildLines(phoneNumber, displayName, caller, enrichment)
            if (lines.size <= 1) {
                return@withContext Result.failure(IllegalStateException("Nothing to export yet"))
            }
            val photo = loadPhoto(context, enrichment?.profileImageUrl, caller?.photoUrl)
            val bitmap = renderCard(lines, photo)
            photo?.takeIf { !it.isRecycled }?.recycle()
            val savedTo = savePng(context, bitmap, phoneNumber)
            try { bitmap.recycle() } catch (_: Exception) { }
            Result.success(savedTo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildLines(
        phoneNumber: String,
        displayName: String?,
        caller: Caller?,
        e: ContactEnrichmentEntity?
    ): List<String> {
        val out = ArrayList<String>()
        val name = displayName?.takeIf { it.isNotBlank() && !ContactUtils.isPlaceholderName(it) }
            ?: e?.publicName?.takeIf { it.isNotBlank() && !ContactUtils.isPlaceholderName(it) }
            ?: caller?.displayName?.takeIf { it.isNotBlank() && !ContactUtils.isPlaceholderName(it) }
        if (name != null) out += name
        if (phoneNumber.isNotBlank()) out += PhoneNumberUtils.formatAsYouType(phoneNumber)
        e?.alternateName?.takeIf { it.isNotBlank() && it != name }?.let { out += "aka $it" }
        e?.about?.takeIf { it.isNotBlank() }?.let { out += it.take(300) }
        val location = LocationUtils.formatCallerLocation(e?.city, e?.region, e?.country ?: caller?.country)
        if (location.isNotBlank()) out += "Location: $location"
        (e?.carrier ?: caller?.carrier)?.takeIf { it.isNotBlank() }?.let { out += "Carrier: $it" }
        e?.lineType?.takeIf { it.isNotBlank() }?.let { out += "Line: $it" }
        e?.timezone?.takeIf { it.isNotBlank() }?.let { out += "Timezone: $it" }
        e?.email?.takeIf { it.isNotBlank() }?.let { out += "Email: $it" }
        if (e?.isBusiness == true) out += "Verified Business"
        e?.nid?.takeIf { it.isNotBlank() }?.let { out += "NID: $it" }
        e?.dob?.takeIf { it.isNotBlank() }?.let { out += "DOB: $it" }
        e?.plateNumber?.takeIf { it.isNotBlank() }?.let { out += "Plate: $it" }
        e?.iban?.takeIf { it.isNotBlank() }?.let { out += "IBAN: $it" }
        e?.vatId?.takeIf { it.isNotBlank() }?.let { out += "VAT: $it" }
        e?.macAddress?.takeIf { it.isNotBlank() }?.let { out += "MAC: $it" }
        val socials = SocialUtils.fromJson(e?.socialProfilesJson)
            .mapNotNull { it.profileUrl?.takeIf { u -> u.isNotBlank() } }
            .distinct().take(8)
        socials.forEach { out += it.take(120) }
        e?.source?.takeIf { it.isNotBlank() }?.let { out += "Sources: $it".take(200) }
        return out
    }

    private fun loadPhoto(context: Context, vararg urls: String?): Bitmap? {
        for (url in urls) {
            if (url.isNullOrBlank()) continue
            try {
                val bmp = if (url.startsWith("content://") || url.startsWith("file://")) {
                    context.contentResolver.openInputStream(android.net.Uri.parse(url))?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                } else {
                    val conn = java.net.URL(url).openConnection()
                    conn.connectTimeout = 8000; conn.readTimeout = 8000
                    conn.getInputStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                }
                if (bmp != null) return bmp
            } catch (_: Exception) { }
        }
        return null
    }

    private fun renderCard(lines: List<String>, photo: Bitmap?): Bitmap {
        val width = 1080
        val pad = 64
        val titleSize = 54f
        val bodySize = 34f
        val lineGap = 18
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = titleSize; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCCCCCC.toInt(); textSize = bodySize }
        // Measure wrapped lines.
        val wrapped = ArrayList<String>()
        val maxTextWidth = width - pad * 2
        lines.forEachIndexed { index, line ->
            val p = if (index == 0) titlePaint else bodyPaint
            var rest = line
            while (rest.isNotEmpty()) {
                var cut = p.breakText(rest, true, maxTextWidth.toFloat(), null)
                if (cut <= 0) cut = rest.length
                wrapped += rest.substring(0, cut)
                rest = rest.substring(cut)
                if (wrapped.size > 60) break
            }
        }
        val photoH = if (photo != null) 340 else 0
        val titleH = (titleSize + lineGap).toInt()
        val bodyH = ((bodySize + lineGap) * (wrapped.size - 1).coerceAtLeast(0)).toInt()
        val height = (pad * 2 + photoH + (if (photoH > 0) 40 else 0) + titleH + bodyH + 60).coerceAtLeast(400)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF0B1220.toInt())
        var y = pad.toFloat()
        if (photo != null) {
            val size = 300
            val scaled = Bitmap.createScaledBitmap(photo, size, size, true)
            val cx = (width - size) / 2f
            // Circular crop.
            val circle = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cc = Canvas(circle)
            val cp = Paint(Paint.ANTI_ALIAS_FLAG)
            cc.drawCircle(size / 2f, size / 2f, size / 2f, cp)
            cp.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            cc.drawBitmap(scaled, 0f, 0f, cp)
            canvas.drawBitmap(circle, cx, y, null)
            if (scaled !== photo) try { scaled.recycle() } catch (_: Exception) { }
            try { circle.recycle() } catch (_: Exception) { }
            y += size + 40
        }
        wrapped.forEachIndexed { index, line ->
            val p = if (index == 0) titlePaint else bodyPaint
            y += (if (index == 0) titleSize else bodySize) + 8
            // Center the title, left-align the body.
            if (index == 0) {
                val tw = p.measureText(line)
                canvas.drawText(line, (width - tw) / 2f, y, p)
            } else {
                canvas.drawText(line, pad.toFloat(), y, p)
            }
            y += lineGap - 8
            paint.alpha = 255
        }
        return bmp
    }

    private fun savePng(context: Context, bitmap: Bitmap, phoneNumber: String): String {
        val digits = phoneNumber.filter { it.isDigit() }.takeLast(12).ifBlank { "unknown" }
        val fileName = "InfoCaller_$digits.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/InfoCaller")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create download entry")
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("PNG encode failed")
                }
            } ?: throw IllegalStateException("Could not open download stream")
            "Downloads/InfoCaller/$fileName"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "InfoCaller"
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("PNG encode failed")
                }
            }
            file.absolutePath
        }
    }
}
