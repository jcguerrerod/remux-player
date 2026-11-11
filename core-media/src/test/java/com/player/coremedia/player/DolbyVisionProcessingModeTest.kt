package com.player.coremedia.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DolbyVisionProcessingModeTest {

    @Test
    fun `fromString resolves exact modes and falls back to P8_TRANSMUTE`() {
        assertEquals(
            DolbyVisionProcessingMode.P8_TRANSMUTE,
            DolbyVisionProcessingMode.fromString("P8_TRANSMUTE")
        )
        assertEquals(
            DolbyVisionProcessingMode.P7_MEL_DIRECT,
            DolbyVisionProcessingMode.fromString("p7_mel_direct")
        )
        assertEquals(
            DolbyVisionProcessingMode.FORCE_HDR10,
            DolbyVisionProcessingMode.fromString("FORCE_HDR10")
        )
        assertEquals(
            DolbyVisionProcessingMode.P8_TRANSMUTE,
            DolbyVisionProcessingMode.fromString(null)
        )
        assertEquals(
            DolbyVisionProcessingMode.P8_TRANSMUTE,
            DolbyVisionProcessingMode.fromString("UNKNOWN_VALUE")
        )
    }
}
