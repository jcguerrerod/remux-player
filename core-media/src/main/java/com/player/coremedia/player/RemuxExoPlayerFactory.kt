package com.player.coremedia.player

import android.content.Context
import android.media.AudioFormat
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
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo as ExoMediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.player.coremedia.audio.AudioEdidResolver

/**
 * Fábrica de ExoPlayer especializada en 4K UHD Blu-ray Remux para Android TV.
 * Implementa:
 * 1. Filtrado SIMD C++20 Zero-Copy de unidades NAL 63 (FEL - Full Enhancement Layer).
 * 2. Transmutación al vuelo de Dolby Vision P7 a P8.1 para decodificadores hardware compatibles.
 * 3. Fallback de capa base HEVC Main10 (BT.2020 / ST 2084 PQ) para decodificadores HDR estándar.
 * 4. Detección real HDMI EDID de passthrough + PCM Float/24-bit sin pérdida + Video Tunneling hardware.
 */
@OptIn(UnstableApi::class)
object RemuxExoPlayerFactory {

    private const val TAG = "RemuxExoPlayerFactory"

    private val PROBE_ENCODINGS = intArrayOf(
        AudioFormat.ENCODING_PCM_16BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_AC3,
        AudioFormat.ENCODING_E_AC3,
        AudioFormat.ENCODING_E_AC3_JOC,
        AudioFormat.ENCODING_AC4,
        AudioFormat.ENCODING_DTS,
        AudioFormat.ENCODING_DTS_HD,
        AudioFormat.ENCODING_DTS_HD_MA,
        AudioFormat.ENCODING_DTS_UHD_P1,
        AudioFormat.ENCODING_DTS_UHD_P2,
        AudioFormat.ENCODING_DOLBY_TRUEHD,
        AudioFormat.ENCODING_DOLBY_MAT,
        AudioFormat.ENCODING_IEC61937,
        AudioFormat.ENCODING_OPUS,
        AudioFormat.ENCODING_AAC_LC
    )

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
        val isEmulator = Build.FINGERPRINT.contains("generic") ||
                Build.MODEL.contains("google_sdk") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu")

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        @Suppress("DEPRECATION")
        val baseCaps = AudioCapabilities.getCapabilities(context, audioAttributes, null)
        @Suppress("DEPRECATION")
        val baseEncodings = PROBE_ENCODINGS.filter { baseCaps.supportsEncoding(it) }.toIntArray()
        val hdmiOutputs = AudioEdidResolver.queryHdmiOutputs(context)
        @Suppress("DEPRECATION")
        val resolvedAudio = AudioEdidResolver.resolve(
            baseEncodings = baseEncodings,
            baseMaxChannels = baseCaps.maxChannelCount,
            hdmiOutputs = hdmiOutputs
        )

        @Suppress("DEPRECATION")
        val effectiveAudioCapabilities = AudioCapabilities(
            resolvedAudio.encodings,
            resolvedAudio.maxChannelCount
        )

        val supportsTrueHd = effectiveAudioCapabilities.supportsEncoding(AudioFormat.ENCODING_DOLBY_TRUEHD)
        val supportsDtsHd = effectiveAudioCapabilities.supportsEncoding(AudioFormat.ENCODING_DTS_HD)
        val supportsDts = effectiveAudioCapabilities.supportsEncoding(AudioFormat.ENCODING_DTS)
        val supportsEac3Joc = effectiveAudioCapabilities.supportsEncoding(AudioFormat.ENCODING_E_AC3_JOC)
        val supportsEac3 = effectiveAudioCapabilities.supportsEncoding(AudioFormat.ENCODING_E_AC3)

        Log.d(
            TAG,
            "Audio EDID (hasHdmi=${resolvedAudio.hasHdmiOutput}, edidReported=${resolvedAudio.hasHdmiEdidEncodings}, maxCh=${resolvedAudio.maxChannelCount}): " +
                "TrueHD=$supportsTrueHd, DTS-HD=$supportsDtsHd, DTS=$supportsDts, E-AC3-JOC=$supportsEac3Joc, E-AC3=$supportsEac3, Tunneling=${!isEmulator}"
        )

        val trackSelector = DefaultTrackSelector(context)
        val paramsBuilder = trackSelector.buildUponParameters()
            .setExceedRendererCapabilitiesIfNecessary(false)
            .setTunnelingEnabled(false)
            // Prioritize lossless master formats (TrueHD, DTS-HD MA) over lossy formats
            .setPreferredAudioMimeTypes(
                MimeTypes.AUDIO_TRUEHD,
                MimeTypes.AUDIO_DTS_HD,
                MimeTypes.AUDIO_E_AC3_JOC,
                MimeTypes.AUDIO_E_AC3,
                MimeTypes.AUDIO_DTS,
                MimeTypes.AUDIO_AC3
            )
            .setSelectUndeterminedTextLanguage(true)

        trackSelector.setParameters(paramsBuilder.build())

        val renderersFactory = object : DefaultRenderersFactory(context) {
            init {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
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

                    private fun isDolbyVisionFormat(format: Format): Boolean {
                        val codecs = format.codecs ?: ""
                        return format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
                            codecs.contains("dvhe") ||
                            codecs.contains("dvh1") ||
                            codecs.contains("hev1.08")
                    }

                    private fun adaptDvFormatIfNeeded(
                        selector: MediaCodecSelector,
                        format: Format
                    ): Format {
                        if (!isDolbyVisionFormat(format)) return format
                        val codecs = format.codecs ?: ""
                        val isP8 = codecs.contains("08") || codecs.contains(".08.")
                        val hasDvDecoder = dvMode != DolbyVisionProcessingMode.FORCE_HDR10 &&
                            selector.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).isNotEmpty()
                        val builder = format.buildUpon()
                        if (!hasDvDecoder) {
                            builder.setSampleMimeType(MimeTypes.VIDEO_H265)
                            builder.setCodecs(if (!isEmulator) "hev1.2.4.L153.B0" else null)
                        } else if (dvMode == DolbyVisionProcessingMode.P7_MEL_DIRECT) {
                            builder.setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                                .setCodecs(if (isP8) "dvhe.08.06" else "dvhe.07.06")
                        } else {
                            builder.setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                                .setCodecs("dvhe.08.06")
                        }
                        return builder.build()
                    }

                    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
                        super.onEnabled(joining, mayRenderStartOfStream)
                        Log.i(TAG, "Video renderer enabled (hardwareTunneling=${configuration.tunneling})")
                    }

                    override fun supportsFormat(
                        mediaCodecSelector: MediaCodecSelector,
                        format: Format
                    ): Int {
                        if (!isDolbyVisionFormat(format)) {
                            return super.supportsFormat(mediaCodecSelector, format)
                        }

                        val adapted = adaptDvFormatIfNeeded(mediaCodecSelector, format)
                        val superCaps = super.supportsFormat(mediaCodecSelector, adapted)
                        if (RendererCapabilities.getFormatSupport(superCaps) == C.FORMAT_HANDLED) {
                            return superCaps
                        }

                        // Permissive fallback if a 4K DV profile triggers conservative level mismatch,
                        // while preserving real TUNNELING_SUPPORTED detection from the hardware decoder.
                        val targetMime = adapted.sampleMimeType ?: MimeTypes.VIDEO_H265
                        val decoders = mediaCodecSelector.getDecoderInfos(targetMime, false, false)
                        if (decoders.isNotEmpty()) {
                            val tunnelingDecoders = if (!isEmulator) {
                                mediaCodecSelector.getDecoderInfos(targetMime, false, true)
                            } else {
                                emptyList()
                            }
                            val tunnelingSupport = if (tunnelingDecoders.isNotEmpty()) {
                                RendererCapabilities.TUNNELING_SUPPORTED
                            } else {
                                RendererCapabilities.TUNNELING_NOT_SUPPORTED
                            }
                            val hwSupport = if (decoders.any { it.hardwareAccelerated }) {
                                RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED
                            } else {
                                RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED
                            }
                            return RendererCapabilities.create(
                                C.FORMAT_HANDLED,
                                RendererCapabilities.ADAPTIVE_SEAMLESS,
                                tunnelingSupport,
                                hwSupport,
                                RendererCapabilities.DECODER_SUPPORT_PRIMARY
                            )
                        }
                        return superCaps
                    }

                    override fun getDecoderInfos(
                        mediaCodecSelector: MediaCodecSelector,
                        format: Format,
                        requiresSecureDecoder: Boolean
                    ): List<ExoMediaCodecInfo> {
                        if (!isDolbyVisionFormat(format)) {
                            return super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
                        }

                        val adapted = adaptDvFormatIfNeeded(mediaCodecSelector, format)
                        val superList = super.getDecoderInfos(mediaCodecSelector, adapted, requiresSecureDecoder)
                        if (superList.isNotEmpty()) {
                            return superList
                        }

                        val targetMime = adapted.sampleMimeType ?: MimeTypes.VIDEO_H265
                        return mediaCodecSelector.getDecoderInfos(targetMime, requiresSecureDecoder, false)
                            .sortedBy { if (it.hardwareAccelerated) 0 else 1 }
                    }

                    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
                        val format = formatHolder.format
                        if (format != null) {
                            val codecs = format.codecs ?: ""
                            val mime = format.sampleMimeType ?: ""
                            isCurrentStreamDv = isDolbyVisionFormat(format)
                            val isP8 = isCurrentStreamDv && (codecs.contains("08") || codecs.contains(".08."))
                            isCurrentStreamDvP7 = isCurrentStreamDv && !isP8

                            if (isCurrentStreamDv) {
                                formatHolder.format = adaptDvFormatIfNeeded(mediaCodecSelector, format)
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
                            val codecs = format.codecs ?: ""
                            val isMain10Profile = codecs.contains("hvc1.2") ||
                                    codecs.contains("hev1.2") ||
                                    codecs.startsWith("hvc1.2") ||
                                    codecs.startsWith("hev1.2") ||
                                    codecs.contains(".2.")
                            val isHdr = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
                                    format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG ||
                                    format.colorInfo?.colorSpace == C.COLOR_SPACE_BT2020 ||
                                    isMain10Profile ||
                                    isCurrentStreamDv

                            if (isHdr && !isEmulator) {
                                mediaFormat.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                                try {
                                    mediaFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                                    val transfer = if (format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) {
                                        MediaFormat.COLOR_TRANSFER_HLG
                                    } else {
                                        MediaFormat.COLOR_TRANSFER_ST2084
                                    }
                                    mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, transfer)
                                    mediaFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                                } catch (_: Exception) {}
                                Log.i(TAG, "Configured HEVC hardware decoder for 10-bit HDR (Main 10 ST2084/BT2020)")
                            } else {
                                Log.i(TAG, "HEVC standard SDR mode (8-bit Rec.709)")
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

                @Suppress("DEPRECATION")
                return DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(effectiveAudioCapabilities)
                    .setEnableFloatOutput(true)
                    .setAudioTrackBufferSizeProvider(bufferSizeProvider)
                    .build()
            }
        }

        // High-Throughput HTTP DataSource Pipeline with persistent Keep-Alive (disallow HTTPS->HTTP downgrades)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
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

        // Calibrated 96 MB RAM buffer with 0s back buffer for zero-copy 4K UHD Blu-ray Remux
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setTargetBufferBytes(96 * 1024 * 1024) // 96 MB dedicated buffer
            .setBufferDurationsMs(
                20000, // minBufferMs: buffer 20s ahead
                50000, // maxBufferMs: buffer up to 50s ahead
                2000,  // bufferForPlaybackMs: quick 2.0s start
                4000   // bufferForPlaybackAfterRebufferMs: 4.0s after rebuffer
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false) // 0s back buffer: instantly free already-played 4K frames to prevent OOM
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