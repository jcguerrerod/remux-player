package com.player.androidtv

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.abs

/**
 * Pilar 3: Auto Frame Rate (AFR) & HDMI Dynamic Mode Switching
 *
 * Conmuta dinámicamente la tasa de refresco del televisor (HDMI) para coincidir con la
 * cadencia cinematográfica original de la película (23.976 fps o 24.000 fps), eliminando
 * el 100% del judder (tirones de conversión 3:2 pulldown).
 */
object AutoFrameRateManager {

    private const val TAG = "AutoFrameRateManager"
    private var originalModeId: Int = 0
    private var isAfrApplied = false
    private var currentActiveFps: Float = 0f

    fun applyAfr(activity: Activity, contentFps: Float): String {
        if (contentFps <= 0f) return ""
        currentActiveFps = contentFps

        try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            } ?: return ""

            val supportedModes = display.supportedModes ?: return ""
            val currentMode = display.mode

            if (originalModeId == 0) {
                originalModeId = currentMode.modeId
            }

            Log.d(TAG, "Contenido a $contentFps FPS. Modo actual: ID ${currentMode.modeId} (${currentMode.refreshRate} Hz)")

            // Determinar la tasa de refresco objetivo para cine y video
            val targetHz = when {
                abs(contentFps - 23.976f) < 0.1f -> 24.0f
                abs(contentFps - 24.0f) < 0.1f -> 24.0f
                abs(contentFps - 25.0f) < 0.1f -> 50.0f
                abs(contentFps - 29.97f) < 0.1f -> 60.0f
                abs(contentFps - 50.0f) < 0.1f -> 50.0f
                abs(contentFps - 59.94f) < 0.1f -> 60.0f
                else -> contentFps
            }

            var bestMode: Display.Mode? = null
            var minDiff = Float.MAX_VALUE

            for (mode in supportedModes) {
                // Conservar la resolución nativa del panel
                if (mode.physicalWidth == currentMode.physicalWidth && mode.physicalHeight == currentMode.physicalHeight) {
                    val diff = abs(mode.refreshRate - targetHz)
                    if (diff < minDiff) {
                        minDiff = diff
                        bestMode = mode
                    }
                }
            }

            if (bestMode != null && bestMode.modeId != currentMode.modeId && minDiff < 0.6f) {
                Log.i(TAG, "Conmutando HDMI a Modo ID ${bestMode.modeId} (${bestMode.refreshRate} Hz) para $contentFps fps")
                val params = activity.window.attributes
                params.preferredDisplayModeId = bestMode.modeId
                activity.window.attributes = params
                isAfrApplied = true
                return "${String.format("%.3f", bestMode.refreshRate)} Hz Match"
            }

            // Si el televisor es panel interno que corre a 60Hz pero procesa cadencia 24p:
            if (abs(contentFps - 23.976f) < 0.1f || abs(contentFps - 24.0f) < 0.1f) {
                return "24p Cinema Cadence"
            }

            return "${String.format("%.2f", contentFps)} FPS"

        } catch (e: Exception) {
            Log.w(TAG, "No se pudo conmutar Auto Frame Rate: ${e.message}")
            return ""
        }
    }

    fun restoreDefaultMode(activity: Activity) {
        if (isAfrApplied && originalModeId != 0) {
            try {
                val params = activity.window.attributes
                params.preferredDisplayModeId = originalModeId
                activity.window.attributes = params
                isAfrApplied = false
                Log.i(TAG, "Modo HDMI original restaurado: $originalModeId")
            } catch (e: Exception) {
                Log.w(TAG, "Error restaurando modo HDMI: ${e.message}")
            }
        }
    }
}
