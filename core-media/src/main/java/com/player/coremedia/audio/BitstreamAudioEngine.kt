package com.player.coremedia.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer

/**
 * Motor de audio de alta fidelidad para Android TV.
 * Gestiona Bitstream Passthrough directo hacia receptores AV (eARC/HDMI)
 * con sincronización de reloj por hardware (FLAG_HW_AV_SYNC).
 */
class BitstreamAudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "BitstreamAudioEngine"

        // Constantes oficiales de codificación directa
        const val ENCODING_DOLBY_TRUEHD = 14
        const val ENCODING_IEC61937 = 13
        const val ENCODING_DTS_HD_MA = 8
        const val ENCODING_E_AC3_JOC = 18
    }

    private var audioTrack: AudioTrack? = null
    var isDirectPassthroughActive: Boolean = false
        private set

    /**
     * Consulta HDMI EDID para validar soporte antes de abrir el stream.
     */
    fun isBitstreamSupported(encoding: Int): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        for (device in outputs) {
            if (device.type == AudioDeviceInfo.TYPE_HDMI || device.type == AudioDeviceInfo.TYPE_HDMI_ARC) {
                if (device.encodings.contains(encoding)) {
                    Log.i(TAG, "HDMI EDID verificado: Códec $encoding soportado por ${device.productName}")
                    return true
                }
            }
        }
        Log.w(TAG, "HDMI EDID: Códec $encoding no admitido. Se requiere fallback a PCM.")
        return false
    }

    /**
     * Inicializa AudioTrack en modo Direct / IEC 61937.
     */
    fun initializeTrack(
        encoding: Int,
        sampleRate: Int = 192000,
        channelMask: Int = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
        sessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
    ) {
        val supported = isBitstreamSupported(encoding)
        val selectedEncoding = if (supported) encoding else AudioFormat.ENCODING_PCM_FLOAT
        isDirectPassthroughActive = supported

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, selectedEncoding)
        val bufferSize = if (minBufferSize > 0) minBufferSize * 4 else 65536

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .setFlags(AudioAttributes.FLAG_HW_AV_SYNC)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(selectedEncoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(sessionId)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack?.play()
        Log.i(TAG, "AudioTrack iniciado. Bitstream activo: $isDirectPassthroughActive")
    }

    /**
     * Envía paquete bitstream a la capa HAL de audio.
     */
    fun writePacket(buffer: ByteBuffer, size: Int, presentationTimestampUs: Long): Int {
        val track = audioTrack ?: return -1
        val written = track.write(
            buffer,
            size,
            AudioTrack.WRITE_NON_BLOCKING,
            presentationTimestampUs * 1000 // Convertir a nanosegundos para HW Sync
        )
        if (written < 0) {
            Log.e(TAG, "AudioTrack HAL write error: $written")
        }
        return written
    }

    fun release() {
        audioTrack?.apply {
            stop()
            flush()
            release()
        }
        audioTrack = null
    }
}
