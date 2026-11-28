package com.player.coremedia.video

import com.player.coremedia.video.FrameRateMatchPlanner.DisplayModeCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateMatchPlannerTest {

    private val current4k60 = DisplayModeCandidate(modeId = 1, physicalWidth = 3840, physicalHeight = 2160, refreshRate = 60.0f)

    @Test
    fun `prefers exact 23_976 Hz over 24_000 Hz when both 4K modes are available`() {
        val modes = listOf(
            current4k60,
            DisplayModeCandidate(modeId = 2, physicalWidth = 3840, physicalHeight = 2160, refreshRate = 24.0f),
            DisplayModeCandidate(modeId = 3, physicalWidth = 3840, physicalHeight = 2160, refreshRate = 23.976025f),
            DisplayModeCandidate(modeId = 4, physicalWidth = 3840, physicalHeight = 2160, refreshRate = 59.94006f)
        )

        val result = FrameRateMatchPlanner.plan(23.976025f, current4k60, modes)
        assertTrue(result.shouldSwitchMode)
        assertEquals(3, result.targetMode?.modeId)
        assertEquals("23.976 Hz Match", result.badgeText)
    }

    @Test
    fun `falls back to 24_000 Hz when 23_976 Hz mode is unavailable`() {
        val modes = listOf(
            current4k60,
            DisplayModeCandidate(modeId = 2, physicalWidth = 3840, physicalHeight = 2160, refreshRate = 24.0f)
        )

        val result = FrameRateMatchPlanner.plan(23.976f, current4k60, modes)
        assertTrue(result.shouldSwitchMode)
        assertEquals(2, result.targetMode?.modeId)
        assertEquals("24.000 Hz Match", result.badgeText)
    }

    @Test
    fun `preserves native 4K resolution and ignores lower-resolution exact matches`() {
        val modes = listOf(
            current4k60,
            DisplayModeCandidate(modeId = 10, physicalWidth = 1920, physicalHeight = 1080, refreshRate = 23.976f)
        )

        val result = FrameRateMatchPlanner.plan(23.976f, current4k60, modes)
        assertFalse(result.shouldSwitchMode)
        assertNull(result.targetMode)
        assertEquals("24p Cinema Cadence", result.badgeText)
    }

    @Test
    fun `prefers 59_94 Hz over 60_000 Hz for 29_97 and 59_94 content`() {
        val modes = listOf(
            current4k60,
            DisplayModeCandidate(modeId = 4, physicalWidth = 3840, physicalHeight = 2160, refreshRate = 59.94006f)
        )

        val r2997 = FrameRateMatchPlanner.plan(29.97f, current4k60, modes)
        assertTrue(r2997.shouldSwitchMode)
        assertEquals(4, r2997.targetMode?.modeId)
        assertEquals("59.940 Hz Match", r2997.badgeText)

        val r5994 = FrameRateMatchPlanner.plan(59.94f, current4k60, modes)
        assertTrue(r5994.shouldSwitchMode)
        assertEquals(4, r5994.targetMode?.modeId)
    }

    @Test
    fun `does not request switch when already on exact matching mode`() {
        val current4k23 = DisplayModeCandidate(modeId = 3, physicalWidth = 3840, physicalHeight = 2160, refreshRate = 23.976f)
        val result = FrameRateMatchPlanner.plan(23.976f, current4k23, listOf(current4k23, current4k60))
        assertFalse(result.shouldSwitchMode)
        assertEquals("23.976 Hz Match", result.badgeText)
    }
}
