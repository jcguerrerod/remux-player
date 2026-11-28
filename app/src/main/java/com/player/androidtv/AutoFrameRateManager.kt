package com.player.androidtv

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import com.player.coremedia.video.FrameRateMatchPlanner
import kotlin.math.abs

/**
 * Pillar 3: Auto Frame Rate (AFR) & HDMI Dynamic Mode Switching
 *
 * Dynamically switches TV refresh rate (HDMI) to match native cinematic film cadence
 * (exact 23.976 Hz, 24.000 Hz, 29.97/59.94 Hz, 25/50 Hz), eliminating 3:2 pulldown judder
 * and 41-second fractional frame drops without altering video quality.
 */
object AutoFrameRateManager {

    private const val TAG = "AutoFrameRateManager"
    private var originalModeId: Int = 0
    private var isAfrApplied = false
    private var currentActiveFps: Float = 0f
    private var lastBadgeText: String = ""

    fun applyAfr(
        activity: Activity,
        contentFps: Float,
        surfaceView: SurfaceView? = null
    ): String {
        if (contentFps <= 0f) return ""
        if (abs(currentActiveFps - contentFps) < 0.002f && lastBadgeText.isNotBlank()) {
            return lastBadgeText
        }
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

            Log.d(TAG, "Content at $contentFps FPS. Current mode: ID ${currentMode.modeId} (${currentMode.refreshRate} Hz)")

            val currentCandidate = FrameRateMatchPlanner.DisplayModeCandidate(
                modeId = currentMode.modeId,
                physicalWidth = currentMode.physicalWidth,
                physicalHeight = currentMode.physicalHeight,
                refreshRate = currentMode.refreshRate
            )
            val candidates = supportedModes.map { mode ->
                FrameRateMatchPlanner.DisplayModeCandidate(
                    modeId = mode.modeId,
                    physicalWidth = mode.physicalWidth,
                    physicalHeight = mode.physicalHeight,
                    refreshRate = mode.refreshRate
                )
            }

            val plan = FrameRateMatchPlanner.plan(contentFps, currentCandidate, candidates)
            val target = plan.targetMode

            if (plan.shouldSwitchMode && target != null) {
                Log.i(TAG, "Switching HDMI to Mode ID ${target.modeId} (${target.refreshRate} Hz) for $contentFps fps")
                val params = activity.window.attributes
                params.preferredDisplayModeId = target.modeId
                activity.window.attributes = params
                isAfrApplied = true
            }

            applySurfaceFrameRateHint(surfaceView, contentFps)
            lastBadgeText = plan.badgeText
            return plan.badgeText

        } catch (e: Exception) {
            Log.w(TAG, "Could not switch Auto Frame Rate: ${e.message}")
            return ""
        }
    }

    private fun applySurfaceFrameRateHint(surfaceView: SurfaceView?, frameRate: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = surfaceView?.holder?.surface ?: return
        if (!surface.isValid) return
        try {
            val changeStrategy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Surface.CHANGE_FRAME_RATE_ALWAYS
            } else {
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            }
            surface.setFrameRate(
                frameRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                changeStrategy
            )
        } catch (e: Exception) {
            Log.d(TAG, "Surface.setFrameRate hint skipped: ${e.message}")
        }
    }

    fun restoreDefaultMode(activity: Activity, surfaceView: SurfaceView? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val surface = surfaceView?.holder?.surface
            if (surface != null && surface.isValid) {
                try {
                    surface.setFrameRate(
                        0f,
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                    )
                } catch (_: Exception) {}
            }
        }
        if (isAfrApplied && originalModeId != 0) {
            try {
                val params = activity.window.attributes
                params.preferredDisplayModeId = originalModeId
                activity.window.attributes = params
                Log.i(TAG, "Restored original HDMI mode: $originalModeId")
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring HDMI mode: ${e.message}")
            }
        }
        isAfrApplied = false
        currentActiveFps = 0f
        lastBadgeText = ""
    }
}
