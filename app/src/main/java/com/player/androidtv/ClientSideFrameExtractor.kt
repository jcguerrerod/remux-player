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
 * Architecture B: Client-side extraction of real movie frames directly on the device
 * without relying on server-side pre-generation.
 *
 * Utilizes automatic HTTP Range requests via MediaMetadataRetriever to fetch only
 * nearest keyframe (I-Frame) packets from the Matroska container (MKV),
 * decoding frames at 320x180 with negligible CPU/RAM impact.
 */
object ClientSideFrameExtractor {

    private const val TAG = "ClientSideExtractor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var retriever: MediaMetadataRetriever? = null
    private var currentSourceUrl: String? = null
    private var isInitializing = false
    private var activeExtractionJob: Job? = null

    // LRU Cache of 60 real frames in RAM (~12 MB)
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
                headers["User-Agent"] = "RemuxPlayer-AndroidTV/2.0"
                mmr.setDataSource(url, headers)
                retriever = mmr
                isInitializing = false
                Log.i(TAG, "MediaMetadataRetriever successfully initialized for $url")
            } catch (e: Exception) {
                isInitializing = false
                Log.w(TAG, "Could not initialize MediaMetadataRetriever for stream: ${e.message}")
            }
        }
    }

    /**
     * Requests a real video frame at given millisecond position.
     * Returns immediately if cached in RAM, or asynchronously once decoded.
     */
    fun requestFrame(url: String, timeMs: Long, onFrameReady: (Bitmap) -> Unit): Boolean {
        // Quantize to 2-second windows to optimize skip caching
        val quantizedSec = (timeMs / 2000L) * 2L
        val cacheKey = "$url#$quantizedSec"

        val cached = frameCache.get(cacheKey)
        if (cached != null) {
            onFrameReady(cached)
            return true
        }

        // If stream URL changed, reconfigure data source
        if (currentSourceUrl != url) {
            setDataSource(url)
        }

        activeExtractionJob?.cancel()
        activeExtractionJob = scope.launch {
            try {
                // Wait briefly if connection is initializing
                var waitCount = 0
                while (isInitializing && waitCount < 10) {
                    delay(50)
                    waitCount++
                }

                val mmr = retriever ?: return@launch
                val timeUs = timeMs * 1000L

                // Use OPTION_CLOSEST_SYNC to read only I-Frames without heavy decoding overhead
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
                    // Passive preloading of next keyframe (+30 sec)
                    preloadOffset(mmr, url, timeUs + 30_000_000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame extraction at $timeMs ms skipped: ${e.message}")
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
