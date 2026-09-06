package com.infocaller.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap

/**
 * Programmatic notification icons (no drawable resources needed):
 * white phone handset = answer (tinted green by the action button),
 * white declined handset = decline (tinted red by the action button).
 */
object CallNotificationIcons {

    fun answer(context: Context): Bitmap = handset(green = true)

    fun decline(context: Context): Bitmap = handset(green = false)

    private fun handset(green: Boolean): Bitmap {
        val size = 96
        val bmp = createBitmap(size, size)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        val cx = size / 2f
        val cy = size / 2f
        // Earpiece + mouthpiece circles joined by a rotated bar.
        canvas.drawCircle(cx - 20f, cy - 22f, 15f, paint)
        canvas.drawCircle(cx + 20f, cy + 22f, 15f, paint)
        canvas.save()
        canvas.rotate(if (green) -40f else 135f, cx, cy)
        canvas.drawRoundRect(cx - 9f, cy - 30f, cx + 9f, cy + 30f, 9f, 9f, paint)
        canvas.restore()
        if (!green) {
            // Crossbar turns the handset into a declined-call glyph.
            val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                strokeWidth = 10f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(cx - 26f, cy + 26f, cx + 26f, cy - 26f, bar)
        }
        return bmp
    }
}
