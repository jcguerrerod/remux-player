package com.player.androidtv.ui

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * Shared tvOS Liquid Glass visual helpers (frosted backdrop blur on API 31+,
 * layered specular glass pills, and crisp modal entrance/exit transitions).
 */
object LiquidGlassHelper {

    private val DECEL_INTERPOLATOR = DecelerateInterpolator(1.6f)

    /**
     * Applies or clears a real-time frosted Gaussian blur on a UI layer (API 31+).
     * Safely no-ops on API 26-30 where translucent dark scrims provide depth instead.
     */
    fun setBackdropBlur(target: View, enabled: Boolean, radius: Float = 24f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val effect = if (enabled) {
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            } else {
                null
            }
            target.setRenderEffect(effect)
        }
    }

    /**
     * Styles a tvOS Liquid Glass capsule pill (navigation tabs, season selectors)
     * with translucent frosted glass, specular upper rim, and pure white focus fill.
     */
    fun styleGlassTabPill(
        view: TextView,
        focused: Boolean,
        selected: Boolean,
        cornerRadiusDp: Float = 18f
    ) {
        val density = view.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density

        if (focused) {
            val bg = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.WHITE, Color.parseColor("#F2F2F7"))
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setStroke((1.5f * density).toInt(), Color.WHITE)
            }
            view.background = bg
            view.setTextColor(Color.BLACK)
            return
        }

        val topFill = if (selected) Color.parseColor("#54FFFFFF") else Color.parseColor("#2EFFFFFF")
        val bottomFill = if (selected) Color.parseColor("#2BFFFFFF") else Color.parseColor("#14FFFFFF")
        val strokeColor = if (selected) Color.parseColor("#99FFFFFF") else Color.parseColor("#4DFFFFFF")

        val baseGlass = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(topFill, bottomFill)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setStroke((1f * density).toInt(), strokeColor)
        }

        val sheenHighlight = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#26FFFFFF"), Color.TRANSPARENT)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
        }

        val layers = LayerDrawable(arrayOf(baseGlass, sheenHighlight)).apply {
            setLayerInset(1, 1, 1, 1, (14 * density).toInt())
        }
        view.background = layers
        view.setTextColor(if (selected) Color.WHITE else Color.parseColor("#E5E5EA"))
    }

    /**
     * Smoothly reveals a Liquid Glass modal sheet with optional background view blur.
     */
    fun showModalOverlay(
        overlay: View,
        sheetCard: View,
        blurTarget: View? = null,
        onShown: (() -> Unit)? = null
    ) {
        blurTarget?.let { setBackdropBlur(it, enabled = true, radius = 26f) }
        overlay.animate().cancel()
        sheetCard.animate().cancel()

        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        sheetCard.scaleX = 0.94f
        sheetCard.scaleY = 0.94f
        sheetCard.alpha = 0f

        overlay.animate()
            .alpha(1f)
            .setDuration(190L)
            .setInterpolator(DECEL_INTERPOLATOR)
            .start()

        sheetCard.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(DECEL_INTERPOLATOR)
            .withEndAction { onShown?.invoke() }
            .start()
    }

    /**
     * Smoothly dismisses a Liquid Glass modal sheet and restores crisp backdrop.
     */
    fun hideModalOverlay(
        overlay: View,
        sheetCard: View,
        blurTarget: View? = null,
        onHidden: (() -> Unit)? = null
    ) {
        blurTarget?.let { setBackdropBlur(it, enabled = false) }
        overlay.animate().cancel()
        sheetCard.animate().cancel()

        sheetCard.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .alpha(0f)
            .setDuration(150L)
            .setInterpolator(DECEL_INTERPOLATOR)
            .start()

        overlay.animate()
            .alpha(0f)
            .setDuration(165L)
            .setInterpolator(DECEL_INTERPOLATOR)
            .withEndAction {
                overlay.visibility = View.GONE
                sheetCard.scaleX = 1f
                sheetCard.scaleY = 1f
                sheetCard.alpha = 1f
                onHidden?.invoke()
            }
            .start()
    }
}
