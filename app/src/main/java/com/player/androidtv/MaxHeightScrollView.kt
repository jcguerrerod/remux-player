package com.player.androidtv

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * ScrollView with configurable maximum height to prevent Apple TV style
 * floating modals from extending beyond TV safe display margins.
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
