package com.player.androidtv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Apple TV style chapter notch and marker overlay for the progress bar.
 * Draws subtle 2.5dp physical cuts to segment chapters,
 * and a persistent ghost pin at the original position before scrubbing.
 */
class ChapterTicksView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0C0E14") // Exact HUD background color to physically segment progress bar
        style = Paint.Style.FILL
    }

    private val tickAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF") // Subtle micro accent at chapter junctions
        style = Paint.Style.FILL
    }

    private val ghostPinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF") // 70% translucent white identical to Apple TV
        style = Paint.Style.FILL
    }

    private var chapterStartRatios: List<Float> = emptyList()
    private var originalPositionRatio: Float? = null

    fun setChapters(chapters: List<Long>, durationMs: Long) {
        if (durationMs <= 0L || chapters.isEmpty()) {
            chapterStartRatios = emptyList()
            invalidate()
            return
        }

        // Exclude start at 0ms and end of media
        chapterStartRatios = chapters
            .filter { it > 1000L && it < durationMs - 1000L }
            .map { (it.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) }

        invalidate()
    }

    fun setOriginalPositionRatio(ratio: Float?) {
        originalPositionRatio = ratio?.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val usableWidth = width.toFloat()
        val notchHalfWidth = 1.25f * density // 2.5dp total width cut
        val trackHalfHeight = 3.5f * density // 7dp height to cleanly slice the 5dp track

        val centerY = height / 2f
        val top = centerY - trackHalfHeight
        val bottom = centerY + trackHalfHeight

        // 1. Chapter notch separators (Apple TV segmentation)
        for (ratio in chapterStartRatios) {
            val cx = ratio * usableWidth
            // Dark physical slice
            canvas.drawRect(cx - notchHalfWidth, top, cx + notchHalfWidth, bottom, notchPaint)
            // Subtle micro accent line
            val tickHalfHeight = 4.5f * density
            canvas.drawRect(cx - (0.5f * density), centerY - tickHalfHeight, cx + (0.5f * density), centerY + tickHalfHeight, tickAccentPaint)
        }

        // 2. Persistent Ghost Pin at original position before scrubbing (Apple TV style)
        originalPositionRatio?.let { origRatio ->
            val origX = origRatio * usableWidth
            val pinHalfWidth = 1.25f * density // 2.5dp width
            val pinHalfHeight = 10f * density  // 20dp height (extends smoothly through track)
            val pinTop = centerY - pinHalfHeight
            val pinBottom = centerY + pinHalfHeight
            canvas.drawRoundRect(
                origX - pinHalfWidth,
                pinTop,
                origX + pinHalfWidth,
                pinBottom,
                pinHalfWidth,
                pinHalfWidth,
                ghostPinPaint
            )
        }
    }
}
