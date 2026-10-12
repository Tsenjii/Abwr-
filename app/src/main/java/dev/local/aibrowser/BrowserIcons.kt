package dev.local.aibrowser

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Density-independent browser icons; no font-dependent Unicode glyphs. */
internal class BrowserIcon(private val name: String, private val tint: Int,
    private val count: Int = 0) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; strokeWidth = 1.8f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(x: Float, y: Float, a: Float, b: Float) = canvas.drawLine(x, y, a, b, paint)
        fun path(vararg points: Float) {
            val p = Path(); p.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1])
            canvas.drawPath(p, paint)
        }
        when (name) {
            "back" -> { path(13f, 5f, 6f, 12f, 13f, 19f); line(6f, 12f, 20f, 12f) }
            "forward" -> { path(11f, 5f, 18f, 12f, 11f, 19f); line(4f, 12f, 18f, 12f) }
            "check" -> path(4f, 12f, 9f, 17f, 20f, 6f)
            "circle" -> canvas.drawCircle(12f, 12f, 8f, paint)
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "reload" -> {
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), 35f, 290f, false, paint)
                path(20f, 4f, 20f, 10f, 14f, 10f)
            }
            "menu" -> {
                paint.style = Paint.Style.FILL
                for (y in listOf(5f, 12f, 19f)) canvas.drawCircle(12f, y, 1.8f, paint)
            }
            "tabs" -> {
                canvas.drawRoundRect(RectF(4f, 3f, 20f, 21f), 4f, 4f, paint)
                paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                paint.textSize = if (count < 10) 11f else 9f
                canvas.drawText(if (count > 99) "99+" else count.toString(), 12f, 16f, paint)
            }
            "home" -> { path(3f, 11f, 12f, 3f, 21f, 11f); path(5f, 10f, 5f, 21f, 10f, 21f, 10f, 14f, 14f, 14f, 14f, 21f, 19f, 21f, 19f, 10f) }
            "bookmark" -> path(6f, 3f, 18f, 3f, 18f, 21f, 12f, 17f, 6f, 21f, 6f, 3f)
            "history" -> {
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), -70f, 310f, false, paint)
                path(3f, 3f, 3f, 9f, 9f, 9f); path(12f, 7f, 12f, 12f, 16f, 14f)
            }
            "download" -> { path(7f, 10f, 12f, 15f, 17f, 10f); line(12f, 3f, 12f, 15f); path(4f, 16f, 4f, 21f, 20f, 21f, 20f, 16f) }
            "search" -> { canvas.drawCircle(10f, 10f, 6f, paint); line(15f, 15f, 21f, 21f) }
            "share" -> { for (p in listOf(5f to 12f, 19f to 5f, 19f to 19f)) canvas.drawCircle(p.first, p.second, 2.5f, paint); line(7f, 11f, 17f, 6f); line(7f, 13f, 17f, 18f) }
            "desktop" -> { canvas.drawRoundRect(RectF(3f, 4f, 21f, 17f), 2f, 2f, paint); line(12f, 17f, 12f, 21f); line(8f, 21f, 16f, 21f) }
            "container" -> { canvas.drawRoundRect(RectF(3f, 4f, 13f, 15f), 2f, 2f, paint); canvas.drawRoundRect(RectF(11f, 9f, 21f, 20f), 2f, 2f, paint) }
            "agent" -> { canvas.drawRoundRect(RectF(4f, 7f, 20f, 20f), 4f, 4f, paint); line(12f, 3f, 12f, 7f); line(8f, 12f, 8f, 14f); line(16f, 12f, 16f, 14f); line(9f, 17f, 15f, 17f) }
            "external" -> { path(13f, 3f, 21f, 3f, 21f, 11f); line(21f, 3f, 10f, 14f); path(9f, 5f, 4f, 5f, 4f, 20f, 19f, 20f, 19f, 15f) }
            "settings" -> { canvas.drawCircle(12f, 12f, 3f, paint); canvas.drawCircle(12f, 12f, 8f, paint)
                for (i in 0..7) { val a = i * Math.PI / 4; line((12+8*kotlin.math.cos(a)).toFloat(), (12+8*kotlin.math.sin(a)).toFloat(), (12+10*kotlin.math.cos(a)).toFloat(), (12+10*kotlin.math.sin(a)).toFloat()) } }
            "extension" -> { path(3f, 7f, 9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f, 21f, 7f, 21f, 13f, 18f, 13f, 18f, 17f, 21f, 17f, 21f, 21f, 3f, 21f, 3f, 7f) }
            "code" -> { path(8f, 6f, 2f, 12f, 8f, 18f); path(16f, 6f, 22f, 12f, 16f, 18f); line(14f, 4f, 10f, 20f) }
            "network" -> { line(4f, 6f, 20f, 6f); line(12f, 6f, 12f, 14f); line(4f, 14f, 20f, 14f); for (x in listOf(4f,12f,20f)) canvas.drawRoundRect(RectF(x-2, 15f, x+2, 21f), 1f, 1f, paint) }
            "trash" -> { line(4f, 6f, 20f, 6f); path(8f, 6f, 8f, 3f, 16f, 3f, 16f, 6f); path(6f, 6f, 7f, 21f, 17f, 21f, 18f, 6f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f) }
            "copy" -> { canvas.drawRoundRect(RectF(8f, 7f, 21f, 21f), 2f, 2f, paint); path(16f, 3f, 3f, 3f, 3f, 16f) }
            "link" -> { canvas.drawRoundRect(RectF(2f, 8f, 13f, 16f), 4f, 4f, paint); canvas.drawRoundRect(RectF(11f, 8f, 22f, 16f), 4f, 4f, paint) }
            "shield" -> { path(12f, 2f, 21f, 6f, 20f, 15f, 12f, 22f, 4f, 15f, 3f, 6f, 12f, 2f); path(7f, 12f, 11f, 16f, 17f, 9f) }
            "motion" -> { path(4f, 4f, 10f, 12f, 4f, 20f); path(12f, 4f, 18f, 12f, 12f, 20f) }
            else -> { canvas.drawCircle(12f, 12f, 8f, paint); line(12f, 7f, 12f, 12f); line(12f, 16f, 12f, 16f) }
        }
        paint.style = Paint.Style.STROKE
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
