package com.player.androidtv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView

/**
 * Unified tvOS 18 Editorial Glass pill & sheet helper (Propuesta C).
 *
 * - Focused option: Crisp White pill (`#F5F6FA`) with dark ink text (`#0C0E12`)
 * - Selected / Active option (unfocused): Luminous frosted glass (`#36FFFFFF`) with white text
 * - Unselected option: Editorial Graphite Glass (`#B81E222B`) with soft secondary text (`#C7CAD4`)
 */
object LiquidGlassHelper {

    @Suppress("UNUSED_PARAMETER")
    fun applyFocusScale(view: View, focused: Boolean, scale: Float = 1.0f) {
        view.animate().cancel()
        view.scaleX = 1.0f
        view.scaleY = 1.0f
    }

    fun styleGlassTabPill(
        view: TextView,
        focused: Boolean,
        selected: Boolean,
        cornerRadiusDp: Float = 18f
    ) {
        val density = view.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density

        val fillColor = when {
            focused -> Color.parseColor("#F5F6FA")
            selected -> Color.parseColor("#36FFFFFF")
            else -> Color.parseColor("#B81E222B")
        }
        val strokeColor = when {
            focused -> Color.parseColor("#FFFFFFFF")
            selected -> Color.parseColor("#80FFFFFF")
            else -> Color.parseColor("#38FFFFFF")
        }
        val strokeWidthPx = if (focused || selected) (1.2f * density).toInt().coerceAtLeast(1) else (1f * density).toInt().coerceAtLeast(1)

        val pill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fillColor)
            setStroke(strokeWidthPx, strokeColor)
        }
        view.background = pill
        view.setTextColor(
            when {
                focused -> Color.parseColor("#0C0E12")
                selected -> Color.WHITE
                else -> Color.parseColor("#C7CAD4")
            }
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun showModalOverlay(
        overlay: View,
        sheetCard: View,
        anchorView: View? = null,
        onShown: (() -> Unit)? = null
    ) {
        overlay.animate().cancel()
        sheetCard.animate().cancel()
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        if (sheetCard is LiquidGlassLayout) {
            sheetCard.animateMaterialize(anchorView, onShown)
        } else {
            sheetCard.scaleX = 1f
            sheetCard.scaleY = 1f
            sheetCard.translationX = 0f
            sheetCard.translationY = 0f
            sheetCard.alpha = 1f
            sheetCard.visibility = View.VISIBLE
            onShown?.invoke()
        }
    }

    fun hideModalOverlay(
        overlay: View,
        sheetCard: View,
        onHidden: (() -> Unit)? = null
    ) {
        overlay.animate().cancel()
        sheetCard.animate().cancel()
        overlay.visibility = View.GONE
        if (sheetCard is LiquidGlassLayout) {
            sheetCard.animateDematerialize(onHidden)
        } else {
            sheetCard.visibility = View.GONE
            overlay.alpha = 1f
            sheetCard.scaleX = 1f
            sheetCard.scaleY = 1f
            sheetCard.alpha = 1f
            onHidden?.invoke()
        }
    }
}
