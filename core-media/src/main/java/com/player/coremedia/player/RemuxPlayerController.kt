package com.player.coremedia.player

import android.content.Context
import android.view.Surface
import com.player.coremedia.audio.BitstreamAudioEngine
import com.player.coremedia.jellyfin.JellyfinDirectPlayClient
import kotlinx.coroutines.*

/**
 * Controlador de alto nivel para la reproducción de 4K Blu-ray Remux en Android TV.
 */
class RemuxPlayerController(
    private val context: Context,
    private val jellyfinClient: JellyfinDirectPlayClient
) {
    private val nativeBridge = NativePlayerBridge()
    private val audioEngine = BitstreamAudioEngine(context)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var progressJob: Job? = null
    private var currentItemId: String? = null
    private var currentToken: String? = null

    fun setSurface(surface: Surface?) {
        nativeBridge.setSurface(surface)
    }

    fun startPlayback(
        streamUrl: String,
        itemId: String,
        mediaSourceId: String,
        token: String,
        audioEncoding: Int = BitstreamAudioEngine.ENCODING_DOLBY_TRUEHD
    ) {
        currentItemId = itemId
        currentToken = token

        // 1. Inicializar AudioTrack en modo Direct / IEC 61937
        audioEngine.initializeTrack(encoding = audioEncoding)

        // 2. Abrir pipeline nativo (Demuxer + HEVC NAL parser + MediaCodec)
        val opened = nativeBridge.openUrl(streamUrl)
        if (opened) {
            nativeBridge.play()

            // 3. Notificar inicio de sesión a Jellyfin
            scope.launch {
                try {
                    jellyfinClient.startPlaybackSession(itemId, mediaSourceId, token)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 4. Iniciar reporte de progreso en bucle (cada 10 segundos)
            startProgressReporting(itemId, token)
        }
    }

    private fun startProgressReporting(itemId: String, token: String) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(10_000)
                val ptsUs = nativeBridge.getCurrentPtsUs()
                val positionTicks = ptsUs * 10 // 1 microsegundo = 10 ticks de Jellyfin/Emby
                try {
                    jellyfinClient.reportProgress(itemId, positionTicks, token)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun pause() {
        nativeBridge.pause()
    }

    fun resume() {
        nativeBridge.play()
    }

    fun stop() {
        progressJob?.cancel()
        val ptsUs = nativeBridge.getCurrentPtsUs()
        val positionTicks = ptsUs * 10
        val itemId = currentItemId
        val token = currentToken

        if (itemId != null && token != null) {
            scope.launch {
                try {
                    jellyfinClient.reportStop(itemId, positionTicks, token)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        nativeBridge.pause()
        audioEngine.release()
    }

    fun release() {
        stop()
        nativeBridge.release()
        scope.cancel()
    }
}
