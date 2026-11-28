package com.player.coremedia.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build

/**
 * Resolves effective HDMI / Audio HAL capabilities from actual connected outputs (EDID)
 * and Android AudioCapabilities, avoiding blind codec injection while preserving lossless
 * high-resolution PCM fallback (24-bit/32-bit/float) when bitstream passthrough is absent.
 */
object AudioEdidResolver {

    data class HdmiOutputInfo(
        val encodings: IntArray,
        val channelCounts: IntArray = intArrayOf()
    )

    data class ResolvedAudioCapabilities(
        val encodings: IntArray,
        val maxChannelCount: Int,
        val hasHdmiOutput: Boolean,
        val hasHdmiEdidEncodings: Boolean
    ) {
        fun supports(encoding: Int): Boolean = encodings.contains(encoding)
    }

    fun isHdmiOutputDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_HDMI ||
            device.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                device.type == AudioDeviceInfo.TYPE_HDMI_EARC)
    }

    fun queryHdmiOutputs(context: Context): List<HdmiOutputInfo> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(::isHdmiOutputDevice)
            .map { device ->
                HdmiOutputInfo(
                    encodings = device.encodings,
                    channelCounts = device.channelCounts
                )
            }
    }

    fun resolve(
        baseEncodings: IntArray,
        baseMaxChannels: Int,
        hdmiOutputs: List<HdmiOutputInfo>
    ): ResolvedAudioCapabilities {
        val resolved = LinkedHashSet<Int>()

        // Always keep PCM_16BIT and PCM_FLOAT so locally decoded lossless tracks (FLAC/LPCM/TrueHD->PCM)
        // retain full dynamic range without 16-bit truncation.
        resolved.add(AudioFormat.ENCODING_PCM_16BIT)
        resolved.add(AudioFormat.ENCODING_PCM_FLOAT)

        for (enc in baseEncodings) {
            if (enc > 0) resolved.add(enc)
        }

        var hasHdmiEdidEncodings = false
        var hdmiMaxChannels = 0
        for (output in hdmiOutputs) {
            if (output.encodings.isNotEmpty()) {
                hasHdmiEdidEncodings = true
                for (enc in output.encodings) {
                    if (enc > 0) resolved.add(enc)
                }
            }
            for (ch in output.channelCounts) {
                if (ch > hdmiMaxChannels) hdmiMaxChannels = ch
            }
        }

        // Android 14+ EDID may report ENCODING_DTS_HD_MA (29) while Media3 1.3.1 keys off ENCODING_DTS_HD (8).
        if (resolved.contains(AudioFormat.ENCODING_DTS_HD_MA)) {
            resolved.add(AudioFormat.ENCODING_DTS_HD)
        }

        val hasHdmi = hdmiOutputs.isNotEmpty()
        val effectiveMaxChannels = maxOf(
            baseMaxChannels,
            hdmiMaxChannels,
            if (hasHdmi) 8 else 2
        )

        return ResolvedAudioCapabilities(
            encodings = resolved.toIntArray(),
            maxChannelCount = effectiveMaxChannels,
            hasHdmiOutput = hasHdmi,
            hasHdmiEdidEncodings = hasHdmiEdidEncodings
        )
    }
}
