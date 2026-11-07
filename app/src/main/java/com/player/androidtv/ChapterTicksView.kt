package com.player.androidtv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Overlay de marcas y muescas de capítulos estilo Apple TV en la barra de progreso.
 * Dibuja cortes sutiles de 2.5dp de ancho para separar visualmente los capítulos.
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

    private var chapterStartRatios: List<Float> = emptyList()

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (chapterStartRatios.isEmpty() || width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val usableWidth = width.toFloat()
        val notchHalfWidth = 1.25f * density // 2.5dp total de ancho para el corte
        val trackHalfHeight = 3.5f * density // 7dp de alto para cortar completamente la barra de 5dp

        val centerY = height / 2f
        val top = centerY - trackHalfHeight
        val bottom = centerY + trackHalfHeight

        for (ratio in chapterStartRatios) {
            val cx = ratio * usableWidth
            canvas.drawRect(cx - notchHalfWidth, top, cx + notchHalfWidth, bottom, notchPaint)
        }
    }
}
