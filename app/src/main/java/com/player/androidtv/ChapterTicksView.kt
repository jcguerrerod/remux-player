package com.player.androidtv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Overlay de marcas y muescas de capítulos estilo Apple TV en la barra de progreso.
 * Dibuja cortes sutiles de 2.5dp de ancho para separar visualmente los capítulos,
 * y la línea fija (ghost pin) en la posición original antes de adelantar/retroceder.
 */
class ChapterTicksView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#141416") // Color idéntico al fondo para simular corte físico entre capítulos
        style = Paint.Style.FILL
    }

    private val ghostPinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF") // Blanco translúcido 70% idéntico a Apple TV
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

        // Excluir el inicio en 0ms y el final
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
        val notchHalfWidth = 1.25f * density // 2.5dp total de ancho para el corte
        val trackHalfHeight = 3.5f * density // 7dp de alto para cortar completamente la barra de 5dp

        val centerY = height / 2f
        val top = centerY - trackHalfHeight
        val bottom = centerY + trackHalfHeight

        // 1. Marcas de cortes de capítulos
        for (ratio in chapterStartRatios) {
            val cx = ratio * usableWidth
            canvas.drawRect(cx - notchHalfWidth, top, cx + notchHalfWidth, bottom, notchPaint)
        }

        // 2. Línea fija / Ghost Pin de posición original antes del scrub (estilo Apple TV)
        originalPositionRatio?.let { origRatio ->
            val origX = origRatio * usableWidth
            val pinHalfWidth = 1.25f * density // 2.5dp ancho
            val pinHalfHeight = 10f * density  // 20dp alto (atraviesa la barra de 5dp con holgura)
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
