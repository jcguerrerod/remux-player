package com.player.androidtv

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * ScrollView con altura máxima configurable para evitar que los menús modales
 * estilo Apple TV se expandan fuera de los márgenes seguros de la pantalla del televisor.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private val maxHeightPx: Int = (260 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val customHeightSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, customHeightSpec)
    }
}
