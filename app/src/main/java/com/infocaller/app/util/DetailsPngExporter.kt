package com.infocaller.app.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
 * Premium portrait caller-ID card (1080x1920) saved to Downloads/InfoCaller.
 *
 * Layout (top to bottom):
 *  1. Gradient header band with circular photo (gold ring) or initial avatar.
 *  2. Name (large, centered) + identifier (accent, centered).
 *  3. About quote in a glass pill (if present).
 *  4. Detail rows: label (dim, uppercase) over value (white), grouped in a
 *     rounded card. Social URLs render as platform names.
 *  5. Footer brand line + source line.
 *
 * Missing fields are skipped — no "N/A". Photo always renders: real photo
 * when a URL loads, otherwise a monogram circle from the name initials.
 */
object DetailsPngExporter {

    // Portrait 9:16.
    private const val WIDTH = 1080
    private const val HEIGHT = 1920

    // Palette.
    private const val BG_TOP = 0xFF101A30
    private const val BG_BOTTOM = 0xFF070B16
    private const val ACCENT = 0xFF4FC3F7
    private const val GOLD = 0xFFE8B84B
    private const val CARD = 0xFF16213B
    private const val CARD_EDGE = 0x33FFFFFF
    private const val NAME_COLOR = 0xFFFFFFFF
    private const val VALUE_COLOR = 0xFFF2F5FA
    private const val DIM_COLOR = 0xFF9AA7BD
    private const val QUOTE_COLOR = 0xFFD7E3F4

    suspend fun export(
        context: Context,
        phoneNumber: String,
        displayName: String?,
        caller: Caller?,
        enrichment: ContactEnrichmentEntity?,
        contactPhotoUri: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val card = buildCard(phoneNumber, displayName, caller, enrichment)
            if (card.rows.isEmpty() && card.name.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Nothing to export yet"))
            }
            val photo = loadPhoto(
                context,
                contactPhotoUri,
                // Auto-photo fix: same founded-photo resolution as Details so the
                // export never misses a candidates/avatar-only picture.
                SocialUtils.bestHttpPhoto(
                    enrichment?.profileImageUrl,
                    enrichment?.photoCandidatesJson,
                    enrichment?.socialProfilesJson,
                    caller?.photoUrl
                ),
                enrichment?.profileImageUrl,
                caller?.photoUrl,
            )
            val bitmap = renderPortrait(card, photo)
            photo?.takeIf { !it.isRecycled }?.recycle()
            val savedTo = savePng(context, bitmap, phoneNumber)
            try { bitmap.recycle() } catch (_: Exception) { }
            Result.success(savedTo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Card model ──────────────────────────────────────────────

    private data class Row(val label: String, val value: String)
    private data class Card(
        val name: String,
        val identifier: String,
        val about: String?,
        val rows: List<Row>,
        val socials: List<String>,
        val footer: String?,
    )

    private fun buildCard(
        phoneNumber: String,
        displayName: String?,
        caller: Caller?,
        e: ContactEnrichmentEntity?,
    ): Card {
        val name = displayName?.takeIf { it.isNotBlank() && !ContactUtils.isPlaceholderName(it) }
            ?: e?.publicName?.takeIf { it.isNotBlank() && !ContactUtils.isPlaceholderName(it) }
            ?: caller?.displayName?.takeIf { it.isNotBlank() && !ContactUtils.isPlaceholderName(it) }
            ?: ""
        val identifier = if (phoneNumber.isNotBlank()) {
            when {
                IdentifierRouter.isEmail(phoneNumber) -> phoneNumber
                IdentifierRouter.routeType(phoneNumber) == "USERNAME" -> phoneNumber.trim()
                else -> try { PhoneNumberUtils.formatAsYouType(phoneNumber) } catch (_: Exception) { phoneNumber }
            }
        } else ""
        val rows = ArrayList<Row>()
        fun add(label: String, value: String?) {
            val v = value?.trim()
            if (!v.isNullOrBlank()) rows += Row(label, v.take(160))
        }
        e?.alternateName?.takeIf { it.isNotBlank() && it != name }?.let { rows += Row("Also known as", it.take(80)) }
        val location = try {
            LocationUtils.formatCallerLocation(e?.city, e?.region, e?.country ?: caller?.country)
        } catch (_: Exception) { "" }
        if (location.isNotBlank()) rows += Row("Location", location.take(120))
        add("Carrier", e?.carrier ?: caller?.carrier)
        add("Line type", e?.lineType)
        add("Timezone", e?.timezone)
        add("Email", e?.email)
        if (e?.isBusiness == true) rows += Row("Type", "Verified Business")
        add("National ID", e?.nid)
        add("Date of birth", e?.dob)
        add("License plate", e?.plateNumber)
        add("IBAN", e?.iban)
        add("VAT ID", e?.vatId)
        add("MAC address", e?.macAddress)
        val socials = try {
            SocialUtils.fromJson(e?.socialProfilesJson)
                .mapNotNull { p ->
                    val url = p.profileUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val platform = p.platform.takeIf { it.isNotBlank() } ?: "Link"
                    "$platform · ${url.take(90)}"
                }.distinct().take(6)
        } catch (_: Exception) { emptyList() }
        val about = e?.about?.takeIf { it.isNotBlank() }?.take(280)
        val footer = e?.source?.takeIf { it.isNotBlank() }?.let { "Sources: $it".take(120) }
        return Card(name, identifier, about, rows.take(12), socials, footer)
    }

    private fun loadPhoto(context: Context, vararg urls: String?): Bitmap? {
        for (url in urls) {
            if (url.isNullOrBlank()) continue
            try {
                val bmp = if (url.startsWith("content://") || url.startsWith("file://")) {
                    context.contentResolver.openInputStream(android.net.Uri.parse(url))?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } else {
                    val conn = java.net.URL(url).openConnection()
                    conn.connectTimeout = 8000; conn.readTimeout = 8000
                    conn.getInputStream().use { BitmapFactory.decodeStream(it) }
                }
                if (bmp != null) return bmp
            } catch (_: Exception) { }
        }
        return null
    }

    private fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "?"
        val first = parts.first().first().uppercaseChar()
        val second = if (parts.size > 1) parts.last().first().uppercaseChar().toString() else ""
        return "$first$second"
    }

    private fun renderPortrait(card: Card, photo: Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Background: vertical gradient + soft radial glows.
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, HEIGHT.toFloat(),
                BG_TOP.toInt(), BG_BOTTOM.toInt(), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 26 }
        glow.color = ACCENT.toInt()
        canvas.drawCircle(WIDTH * 0.85f, HEIGHT * 0.12f, 320f, glow)
        glow.color = GOLD.toInt()
        glow.alpha = 18
        canvas.drawCircle(WIDTH * 0.1f, HEIGHT * 0.75f, 380f, glow)

        // Header band.
        val headerH = 640f
        val headerPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, headerH,
                0xFF1B2B4D.toInt(), 0xFF0E1830.toInt(), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), headerH, headerPaint)
        // Gold hairline under header.
        val hairline = Paint().apply { color = GOLD.toInt(); strokeWidth = 3f; alpha = 200 }
        canvas.drawLine(120f, headerH, (WIDTH - 120).toFloat(), headerH, hairline)

        var y = 150f

        // Circular photo with gold ring — ALWAYS drawn (photo or monogram).
        val photoD = 300f
        val cx = WIDTH / 2f
        val cy = y + photoD / 2f
        drawCircularPhoto(canvas, photo, cx, cy, photoD, card.name)
        y += photoD + 56f

        // Name block: name lines first, THEN the cursor advances past them,
        // THEN the number pill draws below — never drawn over the name.
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NAME_COLOR.toInt(); textSize = 68f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val shownName = card.name.ifBlank { "Unknown" }
        val nameLines = wrap(shownName, Paint(namePaint).apply { textAlign = Paint.Align.CENTER }, (WIDTH - 200).toFloat()).take(2)
        val nameLineH = 76f
        var nameY = y
        for (line in nameLines) {
            nameY += nameLineH
            canvas.drawText(line, cx, nameY, Paint(namePaint).apply { textAlign = Paint.Align.CENTER })
        }
        y = nameY + 28f

        // Identifier pill: number sits INSIDE its own rounded pill below the
        // name — own background, own padding, own vertical slot.
        if (card.identifier.isNotBlank()) {
            val idPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ACCENT.toInt(); textSize = 38f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val idText = card.identifier.take(48)
            val idW = idPaint.measureText(idText)
            val pillW = (idW + 96f).coerceAtMost(WIDTH - 220f)
            val pillH = 76f
            val pillLeft = cx - pillW / 2f
            val pillRect = RectF(pillLeft, y, pillLeft + pillW, y + pillH)
            val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1EFFFFFF }
            canvas.drawRoundRect(pillRect, 38f, 38f, pillBg)
            val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ACCENT.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f; alpha = 160
            }
            canvas.drawRoundRect(pillRect, 38f, 38f, pillEdge)
            val textY = y + pillH / 2f + 13f
            canvas.drawText(idText, cx, textY, idPaint)
            y += pillH + 28f
        } else {
            y += 12f
        }

        // About quote pill.
        if (!card.about.isNullOrBlank()) {
            y += 12f
            val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = QUOTE_COLOR.toInt(); textSize = 32f; textAlign = Paint.Align.CENTER
            }
            val qLines = wrap(card.about, quotePaint, (WIDTH - 320).toFloat()).take(3)
            val pillH = qLines.size * 46f + 56f
            val pill = RectF(110f, y, (WIDTH - 110).toFloat(), y + pillH)
            val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1EFFFFFF }
            canvas.drawRoundRect(pill, 36f, 36f, pillPaint)
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = 2f
            }
            canvas.drawRoundRect(pill, 36f, 36f, edge)
            var qy = y + 62f
            for (line in qLines) {
                canvas.drawText(line.take(60), cx, qy, quotePaint)
                qy += 46f
            }
            y += pillH + 36f
        }

        // Detail rows card.
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIM_COLOR.toInt(); textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = VALUE_COLOR.toInt(); textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val allLines = ArrayList<Triple<Boolean, String, Paint>>()
        for (row in card.rows) {
            allLines += Triple(true, row.label.uppercase(), labelPaint)
            for (wl in wrap(row.value, valuePaint, (WIDTH - 320).toFloat()).take(2)) {
                allLines += Triple(false, wl, valuePaint)
            }
        }
        for (s in card.socials) {
            allLines += Triple(true, "Social", labelPaint)
            for (wl in wrap(s, valuePaint, (WIDTH - 320).toFloat()).take(2)) {
                allLines += Triple(false, wl, valuePaint)
            }
        }
        if (allLines.isNotEmpty()) {
            var contentH = 56f
            for ((isLabel, _, _) in allLines) contentH += if (isLabel) 40f else 50f
            contentH += 40f
            // Clamp the card so footer always fits.
            val maxCardH = HEIGHT - y - 200f
            var shown: List<Triple<Boolean, String, Paint>> = allLines
            if (contentH > maxCardH && maxCardH > 300f) {
                var acc = 56f + 40f
                val cut = ArrayList<Triple<Boolean, String, Paint>>()
                for (entry in allLines) {
                    val h = if (entry.first) 40f else 50f
                    if (acc + h > maxCardH) break
                    cut += entry
                    acc += h
                }
                shown = cut
                contentH = acc
            }
            val cardRect = RectF(90f, y, (WIDTH - 90).toFloat(), y + contentH)
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD.toInt() }
            canvas.drawRoundRect(cardRect, 40f, 40f, cardPaint)
            val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = CARD_EDGE; style = Paint.Style.STROKE; strokeWidth = 2f
            }
            canvas.drawRoundRect(cardRect, 40f, 40f, cardEdge)
            var ry = y + 56f
            for ((_, text, p) in shown) {
                canvas.drawText(text, 150f, ry, p)
                // Label rows are shorter; recompute step from paint size.
                ry += if (p.textSize < 30f) 40f else 50f
            }
            y += contentH + 24f
        }

        // Footer brand line.
        val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIM_COLOR.toInt(); textSize = 28f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("InfoCaller · Caller Identity", cx, (HEIGHT - 120).toFloat(), footPaint)
        if (!card.footer.isNullOrBlank()) {
            val srcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = DIM_COLOR.toInt(); textSize = 24f; textAlign = Paint.Align.CENTER; alpha = 180
            }
            canvas.drawText(card.footer, cx, (HEIGHT - 76).toFloat(), srcPaint)
        }
        return bmp
    }

    private fun drawCircularPhoto(
        canvas: Canvas, photo: Bitmap?, cx: Float, cy: Float, d: Float, name: String,
    ) {
        val r = d / 2f
        // Gold ring + dark underlay.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD.toInt() }
        canvas.drawCircle(cx, cy, r + 10f, ring)
        val ringInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B1220.toInt() }
        canvas.drawCircle(cx, cy, r + 4f, ringInner)

        val circle = Bitmap.createBitmap(d.toInt(), d.toInt(), Bitmap.Config.ARGB_8888)
        val cc = Canvas(circle)
        if (photo != null && !photo.isRecycled && photo.width > 1 && photo.height > 1) {
            // Center-crop scale into the circle.
            val scale = maxOf(d / photo.width, d / photo.height)
            val sw = photo.width * scale
            val sh = photo.height * scale
            val dx = (d - sw) / 2f
            val dy = (d - sh) / 2f
            val mask = Paint(Paint.ANTI_ALIAS_FLAG)
            cc.drawCircle(r, r, r, mask)
            mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            cc.drawBitmap(photo, Rect(0, 0, photo.width, photo.height), RectF(dx, dy, dx + sw, dy + sh), mask)
        } else {
            // Monogram fallback — always a circle, never empty.
            val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF243B63.toInt() }
            cc.drawCircle(r, r, r, bgP)
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = d * 0.36f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            cc.drawText(initials(name.ifBlank { "?" }), r, r + t.textSize * 0.35f, t)
        }
        canvas.drawBitmap(circle, cx - r, cy - r, null)
        try { circle.recycle() } catch (_: Exception) { }
    }

    private fun wrap(text: String, paint: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.isNotEmpty() && out.size < 8) {
            var cut = paint.breakText(rest, true, maxW, null)
            if (cut <= 0) cut = rest.length
            // Prefer word boundary.
            if (cut < rest.length) {
                val space = rest.lastIndexOf(' ', cut)
                if (space > cut / 2) cut = space
            }
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        return out.ifEmpty { listOf("") }
    }

    private fun drawCenteredWrapped(
        canvas: Canvas, text: String, cx: Float, y: Float, paint: Paint, maxLines: Int, lineH: Float,
    ) {
        var cy = y
        val centered = Paint(paint).apply { textAlign = Paint.Align.CENTER }
        for (line in wrap(text, centered, (WIDTH - 260).toFloat()).take(maxLines)) {
            cy += lineH
            canvas.drawText(line, cx, cy, centered)
        }
    }

    private fun wrappedHeight(text: String, paint: Paint, maxLines: Int, lineH: Float): Float {
        val n = wrap(text, paint, (WIDTH - 260).toFloat()).take(maxLines).size.coerceAtLeast(1)
        return n * lineH
    }

    private fun savePng(context: Context, bitmap: Bitmap, phoneNumber: String): String {
        // Non-phone filenames use a sanitized identifier instead of digits.
        val digits = when {
            com.infocaller.app.util.IdentifierRouter.isEmail(phoneNumber) -> phoneNumber.substringBefore("@").filter { it.isLetterOrDigit() }.take(24).ifBlank { "email" }
            com.infocaller.app.util.IdentifierRouter.routeType(phoneNumber) == "USERNAME" -> phoneNumber.removePrefix("@").filter { it.isLetterOrDigit() }.take(24).ifBlank { "username" }
            else -> phoneNumber.filter { it.isDigit() }.takeLast(12).ifBlank { "unknown" }
        }
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
