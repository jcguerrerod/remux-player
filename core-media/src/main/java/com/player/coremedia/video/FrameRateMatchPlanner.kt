package com.player.coremedia.video

import java.util.Locale
import kotlin.math.abs

/**
 * Pure planner for HDMI display mode / refresh rate selection.
 * Distinguishes fractional NTSC rates (23.976, 29.97, 59.94 Hz) from integer rates (24, 30, 60 Hz)
 * to avoid periodic 41-second frame drops on UHD Blu-ray Remux playback.
 */
object FrameRateMatchPlanner {

    data class DisplayModeCandidate(
        val modeId: Int,
        val physicalWidth: Int,
        val physicalHeight: Int,
        val refreshRate: Float
    )

    data class PlanResult(
        val targetMode: DisplayModeCandidate?,
        val shouldSwitchMode: Boolean,
        val badgeText: String
    )

    private data class TargetTier(
        val targetHz: Float,
        val maxDiffHz: Float
    )

    fun plan(
        contentFps: Float,
        currentMode: DisplayModeCandidate,
        supportedModes: List<DisplayModeCandidate>
    ): PlanResult {
        if (contentFps <= 0f) {
            return PlanResult(targetMode = null, shouldSwitchMode = false, badgeText = "")
        }

        val nativeResModes = supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }

        val tiers = buildTargetTiers(contentFps)
        var bestMode: DisplayModeCandidate? = null

        for (tier in tiers) {
            val match = nativeResModes
                .map { mode -> mode to abs(mode.refreshRate - tier.targetHz) }
                .filter { (_, diff) -> diff <= tier.maxDiffHz }
                .minByOrNull { (_, diff) -> diff }
                ?.first
            if (match != null) {
                bestMode = match
                break
            }
        }

        if (bestMode != null) {
            val needsSwitch = bestMode.modeId != currentMode.modeId
            val label = "${String.format(Locale.US, "%.3f", bestMode.refreshRate)} Hz Match"
            return PlanResult(
                targetMode = bestMode,
                shouldSwitchMode = needsSwitch,
                badgeText = label
            )
        }

        val fallbackLabel = if (abs(contentFps - 23.976f) < 0.1f || abs(contentFps - 24.0f) < 0.1f) {
            "24p Cinema Cadence"
        } else {
            "${String.format(Locale.US, "%.2f", contentFps)} FPS"
        }
        return PlanResult(targetMode = null, shouldSwitchMode = false, badgeText = fallbackLabel)
    }

    private fun buildTargetTiers(contentFps: Float): List<TargetTier> {
        return when {
            abs(contentFps - 23.976f) < 0.025f -> listOf(
                TargetTier(23.976f, 0.02f),
                TargetTier(47.952f, 0.04f),
                TargetTier(24.0f, 0.05f),
                TargetTier(119.88f, 0.05f),
                TargetTier(120.0f, 0.15f)
            )
            abs(contentFps - 24.0f) < 0.025f -> listOf(
                TargetTier(24.0f, 0.02f),
                TargetTier(48.0f, 0.04f),
                TargetTier(23.976f, 0.05f),
                TargetTier(120.0f, 0.15f)
            )
            abs(contentFps - 25.0f) < 0.05f -> listOf(
                TargetTier(50.0f, 0.05f),
                TargetTier(25.0f, 0.05f),
                TargetTier(100.0f, 0.1f)
            )
            abs(contentFps - 29.97f) < 0.025f -> listOf(
                TargetTier(59.94f, 0.03f),
                TargetTier(29.97f, 0.03f),
                TargetTier(119.88f, 0.06f),
                TargetTier(60.0f, 0.12f),
                TargetTier(30.0f, 0.12f)
            )
            abs(contentFps - 30.0f) < 0.025f -> listOf(
                TargetTier(60.0f, 0.03f),
                TargetTier(30.0f, 0.03f),
                TargetTier(120.0f, 0.12f),
                TargetTier(59.94f, 0.12f)
            )
            abs(contentFps - 50.0f) < 0.05f -> listOf(
                TargetTier(50.0f, 0.05f),
                TargetTier(100.0f, 0.1f)
            )
            abs(contentFps - 59.94f) < 0.025f -> listOf(
                TargetTier(59.94f, 0.03f),
                TargetTier(119.88f, 0.06f),
                TargetTier(60.0f, 0.12f)
            )
            abs(contentFps - 60.0f) < 0.05f -> listOf(
                TargetTier(60.0f, 0.05f),
                TargetTier(120.0f, 0.12f),
                TargetTier(59.94f, 0.12f)
            )
            else -> listOf(
                TargetTier(contentFps, 0.15f),
                TargetTier(contentFps * 2f, 0.25f)
            )
        }
    }
}
