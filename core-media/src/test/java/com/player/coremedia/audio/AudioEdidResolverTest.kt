package com.player.coremedia.audio

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEdidResolverTest {

    @Test
    fun `does not inject unsupported DTS or TrueHD on non-DTS TV or speakers`() {
        val resolved = AudioEdidResolver.resolve(
            baseEncodings = intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3),
            baseMaxChannels = 6,
            hdmiOutputs = listOf(
                AudioEdidResolver.HdmiOutputInfo(
                    encodings = intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3),
                    channelCounts = intArrayOf(2, 6)
                )
            )
        )

        assertTrue(resolved.hasHdmiOutput)
        assertTrue(resolved.hasHdmiEdidEncodings)
        assertTrue(resolved.supports(AudioFormat.ENCODING_PCM_FLOAT))
        assertTrue(resolved.supports(AudioFormat.ENCODING_E_AC3))
        assertFalse(resolved.supports(AudioFormat.ENCODING_DTS))
        assertFalse(resolved.supports(AudioFormat.ENCODING_DTS_HD))
        assertFalse(resolved.supports(AudioFormat.ENCODING_DOLBY_TRUEHD))
    }

    @Test
    fun `preserves full eARC bitstream and high-res PCM encodings and maps DTS_HD_MA`() {
        val resolved = AudioEdidResolver.resolve(
            baseEncodings = intArrayOf(AudioFormat.ENCODING_PCM_16BIT),
            baseMaxChannels = 2,
            hdmiOutputs = listOf(
                AudioEdidResolver.HdmiOutputInfo(
                    encodings = intArrayOf(
                        AudioFormat.ENCODING_DOLBY_TRUEHD,
                        AudioFormat.ENCODING_E_AC3_JOC,
                        AudioFormat.ENCODING_DTS,
                        AudioFormat.ENCODING_DTS_HD_MA,
                        AudioFormat.ENCODING_PCM_24BIT_PACKED
                    ),
                    channelCounts = intArrayOf(2, 6, 8)
                )
            )
        )

        assertTrue(resolved.supports(AudioFormat.ENCODING_DOLBY_TRUEHD))
        assertTrue(resolved.supports(AudioFormat.ENCODING_E_AC3_JOC))
        assertTrue(resolved.supports(AudioFormat.ENCODING_DTS))
        assertTrue(resolved.supports(AudioFormat.ENCODING_DTS_HD_MA))
        assertTrue(resolved.supports(AudioFormat.ENCODING_DTS_HD))
        assertTrue(resolved.supports(AudioFormat.ENCODING_PCM_24BIT_PACKED))
        assertTrue(resolved.supports(AudioFormat.ENCODING_PCM_FLOAT))
        assertEquals(8, resolved.maxChannelCount)
    }

    @Test
    fun `internal speakers without HDMI default cleanly to lossless PCM fallback`() {
        val resolved = AudioEdidResolver.resolve(
            baseEncodings = intArrayOf(AudioFormat.ENCODING_PCM_16BIT),
            baseMaxChannels = 2,
            hdmiOutputs = emptyList()
        )

        assertFalse(resolved.hasHdmiOutput)
        assertFalse(resolved.hasHdmiEdidEncodings)
        assertTrue(resolved.supports(AudioFormat.ENCODING_PCM_16BIT))
        assertTrue(resolved.supports(AudioFormat.ENCODING_PCM_FLOAT))
        assertEquals(2, resolved.maxChannelCount)
    }
}
