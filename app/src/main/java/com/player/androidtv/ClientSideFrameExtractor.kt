package com.player.androidtv

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Arquitectura B: Extracción de fotogramas reales de la película en el propio dispositivo (Client-side)
 * sin depender de generación en el servidor.
 *
 * Utiliza peticiones HTTP Range automáticas de MediaMetadataRetriever para leer únicamente
 * los paquetes del Keyframe (I-Frame) más cercano en el contenedor Matroska (MKV),
 * decodificando la imagen real en 320x180 a bajo costo de CPU/RAM.
 */
object ClientSideFrameExtractor {

    private const val TAG = "ClientSideExtractor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var retriever: MediaMetadataRetriever? = null
    private var currentSourceUrl: String? = null
    private var isInitializing = false
    private var activeExtractionJob: Job? = null

    // Caché LRU de 60 fotogramas reales en memoria RAM (~12 MB)
    private val frameCache = object : LruCache<String, Bitmap>(60) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    @Synchronized
    fun setDataSource(url: String) {
        if (url.isBlank() || (currentSourceUrl == url && retriever != null)) return

        currentSourceUrl = url
        isInitializing = true

        scope.launch {
            try {
                val old = retriever
                retriever = null
                old?.release()

                val mmr = MediaMetadataRetriever()
                val headers = HashMap<String, String>()
                headers["Connection"] = "keep-alive"
                headers["User-Agent"] = "RemuxPlayer-TV/1.0"
                mmr.setDataSource(url, headers)
                retriever = mmr
                isInitializing = false
                Log.i(TAG, "MediaMetadataRetriever inicializado con éxito para $url")
            } catch (e: Exception) {
                isInitializing = false
                Log.w(TAG, "No se pudo inicializar MediaMetadataRetriever para el stream: ${e.message}")
            }
        }
    }

    /**
     * Solicita un fotograma real en milisegundos.
     * Retorna inmediatamente si está en caché RAM, o asíncronamente tan pronto como se decodifique.
     */
    fun requestFrame(url: String, timeMs: Long, onFrameReady: (Bitmap) -> Unit): Boolean {
        // Cuantizar a ventanas de 2 segundos para optimizar saltos
        val quantizedSec = (timeMs / 2000L) * 2L
        val cacheKey = "$url#$quantizedSec"

        val cached = frameCache.get(cacheKey)
        if (cached != null) {
            onFrameReady(cached)
            return true
        }

        // Si cambia la URL del stream, reconfigurar
        if (currentSourceUrl != url) {
            setDataSource(url)
        }

        activeExtractionJob?.cancel()
        activeExtractionJob = scope.launch {
            try {
                // Esperar brevemente si se está inicializando la conexión
                var waitCount = 0
                while (isInitializing && waitCount < 10) {
                    delay(50)
                    waitCount++
                }

                val mmr = retriever ?: return@launch
                val timeUs = timeMs * 1000L

                // Usar OPTION_CLOSEST_SYNC para leer solo I-Frames sin costo de decodificación masivo
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    mmr.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        320,
                        180
                    )
                } else {
                    mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }

                if (bitmap != null) {
                    frameCache.put(cacheKey, bitmap)
                    withContext(Dispatchers.Main) {
                        onFrameReady(bitmap)
                    }
                    // Precarga pasiva del siguiente fotograma (+30 seg)
                    preloadOffset(mmr, url, timeUs + 30_000_000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Extracción de fotograma en $timeMs ms omitida: ${e.message}")
            }
        }

        return false
    }

    private fun preloadOffset(mmr: MediaMetadataRetriever, url: String, nextTimeUs: Long) {
        scope.launch {
            try {
                val nextSec = (nextTimeUs / 2_000_000L) * 2L
                val key = "$url#$nextSec"
                if (frameCache.get(key) == null) {
                    val preloaded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        mmr.getScaledFrameAtTime(
                            nextTimeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            320,
                            180
                        )
                    } else {
                        mmr.getFrameAtTime(nextTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    if (preloaded != null) {
                        frameCache.put(key, preloaded)
                    }
                }
            } catch (ignored: Exception) {}
        }
    }

    fun release() {
        activeExtractionJob?.cancel()
        scope.launch {
            try {
                retriever?.release()
                retriever = null
                currentSourceUrl = null
            } catch (ignored: Exception) {}
        }
    }
}
