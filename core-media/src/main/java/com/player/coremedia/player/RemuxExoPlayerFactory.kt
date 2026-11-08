package com.player.coremedia.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo as ExoMediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Fábrica de ExoPlayer especializada en 4K UHD Blu-ray Remux para Android TV.
 * Implementa:
 * 1. Filtrado SIMD C++20 Zero-Copy de unidades NAL 63 (FEL - Full Enhancement Layer).
 * 2. Transmutación al vuelo de Dolby Vision P7 a P8.1 para decodificadores hardware compatibles.
 * 3. Fallback de capa base HEVC Main10 (BT.2020 / ST 2084 PQ) para decodificadores HDR estándar.
 * 4. Detección dinámica de capacidades HDMI Passthrough (FLAG_HW_AV_SYNC).
 */
@OptIn(UnstableApi::class)
object RemuxExoPlayerFactory {

    private const val TAG = "RemuxExoPlayerFactory"

    init {
        try {
            System.loadLibrary("native_pipeline")
            Log.i(TAG, "Native library native_pipeline loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Could not load native_pipeline", e)
        }
    }

    @JvmStatic
    private external fun nativeFilterHevcNaluInPlace(
        buffer: java.nio.ByteBuffer,
        position: Int,
        limit: Int
    ): Int

    fun create(
        context: Context,
        dvMode: DolbyVisionProcessingMode = DolbyVisionProcessingMode.P8_TRANSMUTE
    ): ExoPlayer {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val hasHdmiOutput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
                it.type == AudioDeviceInfo.TYPE_HDMI ||
                it.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_HDMI_EARC)
            } == true
        } else false

        val effectiveAudioCapabilities = if (!hasHdmiOutput) {
            Log.i(TAG, "Audio output: TV speakers detected (No HDMI/eARC receiver). Decoding DTS/DTS-HD to hardware PCM.")
            AudioCapabilities(
                intArrayOf(
                    AudioFormat.ENCODING_PCM_16BIT,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    AudioFormat.ENCODING_AC3,
                    AudioFormat.ENCODING_E_AC3
                ),
                8
            )
        } else {
            Log.i(TAG, "Audio output: External HDMI/eARC receiver detected. Enabling full passthrough.")
            AudioCapabilities.getCapabilities(context)
        }

        val supportsTrueHd = effectiveAudioCapabilities.supportsEncoding(AudioFormat.ENCODING_DOLBY_TRUEHD)
        val supportsDtsHd = effectiveAudioCapabilities.supportsEncoding(AudioFormat.ENCODING_DTS_HD_MA)
        val supportsDts = effectiveAudioCapabilities.supportsEncoding(AudioFormat.ENCODING_DTS)
        val supportsEac3 = effectiveAudioCapabilities.supportsEncoding(AudioFormat.ENCODING_E_AC3)

        Log.d(TAG, "Audio Passthrough (hasHdmi=$hasHdmiOutput): TrueHD=$supportsTrueHd, DTS-HD=$supportsDtsHd, DTS=$supportsDts, E-AC3=$supportsEac3")

        val trackSelector = DefaultTrackSelector(context)
        val paramsBuilder = trackSelector.buildUponParameters()
            .setExceedRendererCapabilitiesIfNecessary(false)
            // Prioritize audio formats fully compatible with TV hardware decoding (DD 5.1 / DD+)
            // to avoid buffer underruns on internal TV speakers when no TrueHD AVR is on HDMI.
            .setPreferredAudioMimeTypes(
                MimeTypes.AUDIO_E_AC3_JOC,
                MimeTypes.AUDIO_E_AC3,
                MimeTypes.AUDIO_AC3,
                MimeTypes.AUDIO_TRUEHD,
                MimeTypes.AUDIO_DTS_HD
            )
            .setSelectUndeterminedTextLanguage(true)

        trackSelector.setParameters(paramsBuilder.build())

        val isEmulator = Build.FINGERPRINT.contains("generic") ||
                Build.MODEL.contains("google_sdk") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu")

        val renderersFactory = object : DefaultRenderersFactory(context) {
            init {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            }

            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                val videoRenderer = object : MediaCodecVideoRenderer(
                    context,
                    getCodecAdapterFactory(),
                    mediaCodecSelector,
                    allowedVideoJoiningTimeMs,
                    true,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                ) {
                    private var isCurrentStreamDv = false
                    private var isCurrentStreamDvP7 = false

                    override fun supportsFormat(
                        mediaCodecSelector: MediaCodecSelector,
                        format: Format
                    ): Int {
                        val isDv = format.codecs?.contains("dvhe") == true ||
                                format.codecs?.contains("hev1.08") == true ||
                                format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION

                        if (isDv) {
                            if (dvMode == DolbyVisionProcessingMode.FORCE_HDR10) {
                                val hevcDecoders = mediaCodecSelector.getDecoderInfos(
                                    MimeTypes.VIDEO_H265,
                                    false,
                                    false
                                )
                                if (hevcDecoders.isNotEmpty()) {
                                    return androidx.media3.exoplayer.RendererCapabilities.create(C.FORMAT_HANDLED)
                                }
                            } else {
                                val dvDecoders = mediaCodecSelector.getDecoderInfos(
                                    MimeTypes.VIDEO_DOLBY_VISION,
                                    false,
                                    false
                                )
                                if (dvDecoders.isNotEmpty()) {
                                    return androidx.media3.exoplayer.RendererCapabilities.create(C.FORMAT_HANDLED)
                                }

                                val hevcDecoders = mediaCodecSelector.getDecoderInfos(
                                    MimeTypes.VIDEO_H265,
                                    false,
                                    false
                                )
                                if (hevcDecoders.isNotEmpty()) {
                                    return androidx.media3.exoplayer.RendererCapabilities.create(C.FORMAT_HANDLED)
                                }
                            }
                        }
                        return super.supportsFormat(mediaCodecSelector, format)
                    }

                    override fun getDecoderInfos(
                        mediaCodecSelector: MediaCodecSelector,
                        format: Format,
                        requiresSecureDecoder: Boolean
                    ): List<ExoMediaCodecInfo> {
                        val isDv = format.codecs?.contains("dvhe") == true ||
                                format.codecs?.contains("hev1.08") == true ||
                                format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION

                        if (isDv) {
                            if (dvMode == DolbyVisionProcessingMode.FORCE_HDR10) {
                                val hevcDecoders = mediaCodecSelector.getDecoderInfos(
                                    MimeTypes.VIDEO_H265,
                                    requiresSecureDecoder,
                                    false
                                )
                                if (hevcDecoders.isNotEmpty()) {
                                    return hevcDecoders.sortedBy { if (it.hardwareAccelerated) 0 else 1 }
                                }
                            } else {
                                val dvDecoders = mediaCodecSelector.getDecoderInfos(
                                    MimeTypes.VIDEO_DOLBY_VISION,
                                    requiresSecureDecoder,
                                    false
                                )
                                if (dvDecoders.isNotEmpty()) {
                                    return dvDecoders
                                }

                                val hevcDecoders = mediaCodecSelector.getDecoderInfos(
                                    MimeTypes.VIDEO_H265,
                                    requiresSecureDecoder,
                                    false
                                )
                                if (hevcDecoders.isNotEmpty()) {
                                    return hevcDecoders.sortedBy { if (it.hardwareAccelerated) 0 else 1 }
                                }
                            }
                        }
                        return super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
                    }

                    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
                        val format = formatHolder.format
                        if (format != null) {
                            val codecs = format.codecs ?: ""
                            val mime = format.sampleMimeType ?: ""
                            isCurrentStreamDv = mime == MimeTypes.VIDEO_DOLBY_VISION ||
                                    codecs.contains("dvhe") || codecs.contains("hev1.08")
                            val isP8 = isCurrentStreamDv && (codecs.contains("08") || codecs.contains(".08."))
                            isCurrentStreamDvP7 = isCurrentStreamDv && !isP8

                            if (isCurrentStreamDv) {
                                val builder = format.buildUpon()
                                when (dvMode) {
                                    DolbyVisionProcessingMode.FORCE_HDR10 -> {
                                        builder.setSampleMimeType(MimeTypes.VIDEO_H265)
                                        builder.setCodecs(if (!isEmulator) "hev1.2.4.L153.B0" else null)
                                    }
                                    DolbyVisionProcessingMode.P7_MEL_DIRECT -> {
                                        val dvDecoders = mediaCodecSelector.getDecoderInfos(
                                            MimeTypes.VIDEO_DOLBY_VISION,
                                            false,
                                            false
                                        )
                                        if (dvDecoders.isNotEmpty()) {
                                            builder.setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                                                .setCodecs(if (isP8) "dvhe.08.06" else "dvhe.07.06")
                                        } else {
                                            builder.setSampleMimeType(MimeTypes.VIDEO_H265)
                                                .setCodecs(if (!isEmulator) "hev1.2.4.L153.B0" else null)
                                        }
                                    }
                                    DolbyVisionProcessingMode.P8_TRANSMUTE -> {
                                        val dvDecoders = mediaCodecSelector.getDecoderInfos(
                                            MimeTypes.VIDEO_DOLBY_VISION,
                                            false,
                                            false
                                        )
                                        if (dvDecoders.isNotEmpty()) {
                                            builder.setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                                                .setCodecs("dvhe.08.06")
                                        } else {
                                            builder.setSampleMimeType(MimeTypes.VIDEO_H265)
                                                .setCodecs(if (!isEmulator) "hev1.2.4.L153.B0" else null)
                                        }
                                    }
                                }
                                formatHolder.format = builder.build()
                                Log.i(TAG, "[$dvMode] onInputFormatChanged DV adaptado a: ${formatHolder.format?.sampleMimeType} (${formatHolder.format?.codecs}), isP7=$isCurrentStreamDvP7")
                            } else {
                                Log.i(TAG, "onInputFormatChanged Formato Estándar/SDR: $mime (${format.codecs})")
                            }
                        }
                        return super.onInputFormatChanged(formatHolder)
                    }

                    override fun getMediaFormat(
                        format: Format,
                        codecMimeType: String,
                        codecMaxValues: CodecMaxValues,
                        codecOperatingRate: Float,
                        deviceNeedsNoPostProcessWorkaround: Boolean,
                        tunnelingAudioSessionId: Int
                    ): MediaFormat {
                        val targetFormat = if ((codecMimeType == MimeTypes.VIDEO_H265 || dvMode == DolbyVisionProcessingMode.FORCE_HDR10) &&
                            format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
                        ) {
                            format.buildUpon()
                                .setSampleMimeType(MimeTypes.VIDEO_H265)
                                .setCodecs(null)
                                .build()
                        } else {
                            format
                        }

                        val mediaFormat = super.getMediaFormat(
                            targetFormat,
                            codecMimeType,
                            codecMaxValues,
                            codecOperatingRate,
                            deviceNeedsNoPostProcessWorkaround,
                            tunnelingAudioSessionId
                        )

                        if (codecMimeType == MimeTypes.VIDEO_DOLBY_VISION && dvMode != DolbyVisionProcessingMode.FORCE_HDR10) {
                            mediaFormat.setString(MediaFormat.KEY_MIME, "video/dolby-vision")
                            if (dvMode == DolbyVisionProcessingMode.P7_MEL_DIRECT && isCurrentStreamDvP7) {
                                mediaFormat.setInteger("profile", 1) // Profile 7
                                mediaFormat.setInteger("color-standard", 6) // BT.2020
                                mediaFormat.setInteger("color-transfer", 6) // PQ ST 2084
                                mediaFormat.setInteger("vendor.dolby.profile", 7)
                                mediaFormat.setInteger("vendor.dolby.compatibility", 0)
                                Log.i(TAG, "Configurado decodificador nativo Dolby Vision Perfil 7 (MEL Direct)")
                            } else {
                                mediaFormat.setInteger("profile", 32) // Profile 8
                                mediaFormat.setInteger("color-standard", 6) // BT.2020
                                mediaFormat.setInteger("color-transfer", 6) // PQ ST 2084
                                mediaFormat.setInteger("vendor.dolby.profile", 8)
                                mediaFormat.setInteger("vendor.dolby.compatibility", 1)
                                Log.i(TAG, "Configurado decodificador nativo Dolby Vision Perfil 8.1")
                            }
                        } else if (codecMimeType == MimeTypes.VIDEO_H265) {
                            val isHdr = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
                                    format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG ||
                                    isCurrentStreamDv
                            val supportsMain10 = codecInfo?.profileLevels?.any {
                                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                            } == true

                            if (supportsMain10 && isHdr && !isEmulator) {
                                mediaFormat.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                                try {
                                    mediaFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                                    mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                                    mediaFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                                } catch (_: Exception) {}
                                Log.i(TAG, "Configurado decodificador HEVC para capa base HDR10 PQ (Main 10)")
                            } else {
                                Log.i(TAG, "Decodificador HEVC en modo compatible (Main 8-bit / SDR)")
                                mediaFormat.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                            }
                        } else {
                            // Para AVC / H.264 (MimeTypes.VIDEO_H264 / video/avc) y otros códecs:
                            // Respetar el mediaFormat estándar nativo (SDR Rec.709) sin forzar perfiles HEVC
                            Log.i(TAG, "Decodificador nativo para formato estándar: $codecMimeType")
                        }

                        return mediaFormat
                    }

                    override fun onQueueInputBuffer(buffer: androidx.media3.decoder.DecoderInputBuffer) {
                        val data = buffer.data
                        // Únicamente filtrar NAL 63 si es un stream Dolby Vision Perfil 7
                        if (isCurrentStreamDvP7 && data != null && data.remaining() > 4) {
                            if (dvMode == DolbyVisionProcessingMode.P8_TRANSMUTE || dvMode == DolbyVisionProcessingMode.FORCE_HDR10) {
                                filterHevcNaluInPlace(data)
                            }
                        }
                        super.onQueueInputBuffer(buffer)
                    }
                }

                out.add(videoRenderer)
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                val bufferSizeProvider = DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(250_000)      // 250ms minimum buffer
                    .setMaxPcmBufferDurationUs(1_000_000)    // 1000ms (1s) headroom for multichannel PCM
                    .setPcmBufferMultiplicationFactor(4)     // 4x buffer multiplier to protect against underruns
                    .setPassthroughBufferDurationUs(500_000) // 500ms passthrough buffer
                    .setOffloadBufferDurationUs(10_000_000)  // 10s offload buffer
                    .build()

                return DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(effectiveAudioCapabilities)
                    .setEnableFloatOutput(false)
                    .setAudioTrackBufferSizeProvider(bufferSizeProvider)
                    .build()
            }
        }

        // High-Throughput HTTP DataSource Pipeline with persistent Keep-Alive
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setKeepPostFor302Redirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Connection" to "keep-alive",
                    "Accept-Encoding" to "identity",
                    "User-Agent" to "RemuxPlayer-AndroidTV/2.0"
                )
            )

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        // Ultra-Buffer Allocator: 96 MB dedicated RAM buffer for high-bitrate 4K UHD Blu-ray Remux
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setTargetBufferBytes(96 * 1024 * 1024) // 96 MB dedicated buffer
            .setBufferDurationsMs(
                25000, // minBufferMs: buffer at least 25s ahead
                80000, // maxBufferMs: buffer up to 80s ahead
                1500,  // bufferForPlaybackMs: quick 1.5s start
                3000   // bufferForPlaybackAfterRebufferMs: 3.0s after rebuffer
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(30000, false) // 30s back buffer for instant -10s quick skips
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    /**
     * In-Place Zero-Copy HEVC NAL Unit high-performance SIMD C++20 filtering.
     * Completely discards NAL 63 (FEL - Full Enhancement Layer) from buffer
     * before handing it to the hardware MediaCodec decoder without Java copies.
     */
    private fun filterHevcNaluInPlace(buffer: java.nio.ByteBuffer): Int {
        if (!buffer.isDirect) return buffer.limit()
        val pos = buffer.position()
        val limit = buffer.limit()
        if (limit - pos <= 4) return limit

        try {
            val newLimit = nativeFilterHevcNaluInPlace(buffer, pos, limit)
            if (newLimit in (pos + 1)..limit) {
                if (newLimit != limit) {
                    buffer.limit(newLimit)
                }
                return newLimit
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error in native NALU filtering: ${e.message}")
        }
        return limit
    }
}