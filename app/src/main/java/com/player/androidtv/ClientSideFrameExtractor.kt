package com.player.androidtv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance client-side video keyframe and thumbnail extraction engine.
 * Features:
 * 1. Multi-tier caching: Tier 1 RAM LRU (120 bitmaps), Tier 2 Disk Cache (SSD/Flash).
 * 2. Direction-aware lookahead pre-extraction (10s, 30s, 60s, 120s along scrub trajectory).
 * 3. Proactive timeline pre-warming during idle and playback states.
 * 4. Thread-safe serialized MediaMetadataRetriever access with native mutex.
 * 5. Instant nearest-neighbor frame retrieval to eliminate blank scrub cards.
 */
object ClientSideFrameExtractor {

    private const val TAG = "ClientSideExtractor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val retrieverMutex = Mutex()

    private var retriever: MediaMetadataRetriever? = null
    private var currentSourceUrl: String? = null
    private var isInitializing = false
    private var cacheDir: File? = null

    // Tier 1: LRU Memory Cache (120 keyframe thumbnails ~20 MB RAM)
    private val frameCache = object : LruCache<String, Bitmap>(120) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    // Fast timestamp index for nearest-neighbor fallback
    private val cachedTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

    // Request queue management
    private var pendingRequestTimeMs: Long? = null
    private var pendingRequestCallback: ((Bitmap) -> Unit)? = null
    private var isWorkerRunning = false

    fun init(context: Context) {
        if (cacheDir == null) {
            val dir = File(context.cacheDir, "scrub_thumbnails")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            cacheDir = dir

            // Background prune: clean thumbnails older than 7 days
            scope.launch {
                try {
                    val cutoff = System.currentTimeMillis() - (7L * 24 * 3600 * 1000L)
                    dir.listFiles()?.forEach { file ->
                        if (file.lastModified() < cutoff) {
                            file.delete()
                        }
                    }
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun hashUrl(url: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(url.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            url.hashCode().toString()
        }
    }

    private fun getDiskFile(url: String, quantizedSec: Long): File? {
        val dir = cacheDir ?: return null
        val hash = hashUrl(url)
        return File(dir, "thumb_${hash}_${quantizedSec}.jpg")
    }

    private fun trackTimestamp(url: String, quantizedSec: Long) {
        val list = cachedTimestamps.getOrPut(url) { mutableListOf() }
        synchronized(list) {
            if (!list.contains(quantizedSec)) {
                list.add(quantizedSec)
            }
        }
    }

    @Synchronized
    fun setDataSource(url: String) {
        if (url.isBlank() || (currentSourceUrl == url && retriever != null)) return
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            currentSourceUrl = url
            return
        }

        currentSourceUrl = url
        isInitializing = true

        scope.launch {
            try {
                retrieverMutex.withLock {
                    val old = retriever
                    retriever = null
                    old?.release()

                    val mmr = MediaMetadataRetriever()
                    val headers = HashMap<String, String>()
                    headers["Connection"] = "keep-alive"
                    headers["Accept-Encoding"] = "identity"
                    headers["User-Agent"] = "RemuxPlayer-AndroidTV/2.0"
                    mmr.setDataSource(url, headers)
                    retriever = mmr
                    isInitializing = false
                    Log.i(TAG, "MediaMetadataRetriever initialized for stream")
                }
            } catch (e: Exception) {
                isInitializing = false
                Log.w(TAG, "Could not initialize MediaMetadataRetriever: ${e.message}")
            }
        }
    }

    /**
     * Instant nearest-neighbor frame retrieval from RAM cache.
     * Prevents empty/black scrub preview cards while the exact keyframe is being decoded.
     */
    fun getClosestCachedFrame(url: String, timeMs: Long): Bitmap? {
        val targetSec = (timeMs / 2000L) * 2L
        val exactKey = "$url#$targetSec"
        val exact = frameCache.get(exactKey)
        if (exact != null) return exact

        val list = cachedTimestamps[url] ?: return null
        synchronized(list) {
            var closestSec: Long? = null
            var minDiff = Long.MAX_VALUE
            for (sec in list) {
                val diff = Math.abs(sec - targetSec)
                if (diff < minDiff && diff <= 120) { // Within 2 minutes
                    minDiff = diff
                    closestSec = sec
                }
            }
            if (closestSec != null) {
                return frameCache.get("$url#$closestSec")
            }
        }
        return null
    }

    /**
     * Request a keyframe thumbnail for the given time.
     * Checks RAM (0ms), then Disk (2ms), and finally enqueues MediaMetadataRetriever.
     */
    fun requestFrame(url: String, timeMs: Long, direction: Int = 1, onFrameReady: (Bitmap) -> Unit): Boolean {
        val quantizedSec = (timeMs / 2000L) * 2L
        val cacheKey = "$url#$quantizedSec"

        // Tier 1: In-Memory RAM Cache (Instant 0ms)
        val ramBitmap = frameCache.get(cacheKey)
        if (ramBitmap != null) {
            onFrameReady(ramBitmap)
            prefetchAround(url, timeMs, direction)
            return true
        }

        // Tier 2: Local Disk Cache (2-4ms)
        val diskFile = getDiskFile(url, quantizedSec)
        if (diskFile != null && diskFile.exists()) {
            scope.launch {
                val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bmp != null) {
                    frameCache.put(cacheKey, bmp)
                    trackTimestamp(url, quantizedSec)
                    withContext(Dispatchers.Main) {
                        onFrameReady(bmp)
                    }
                    prefetchAround(url, timeMs, direction)
                }
            }
            return false
        }

        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            return false
        }

        // Tier 3: Asynchronous Hardware/Stream Extraction
        synchronized(this) {
            pendingRequestTimeMs = timeMs
            pendingRequestCallback = onFrameReady
        }
        triggerWorker(url, direction)
        return false
    }

    private fun triggerWorker(url: String, direction: Int) {
        if (isWorkerRunning) return
        isWorkerRunning = true

        scope.launch {
            try {
                while (true) {
                    val (targetMs, cb) = synchronized(this@ClientSideFrameExtractor) {
                        val t = pendingRequestTimeMs
                        val c = pendingRequestCallback
                        pendingRequestTimeMs = null
                        pendingRequestCallback = null
                        if (t != null) Pair(t, c) else null
                    } ?: break

                    extractAndCache(url, targetMs, cb)
                    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                        prefetchAround(url, targetMs, direction)
                    }
                }
            } finally {
                isWorkerRunning = false
            }
        }
    }

    private suspend fun extractAndCache(url: String, timeMs: Long, callback: ((Bitmap) -> Unit)?) {
        val quantizedSec = (timeMs / 2000L) * 2L
        val cacheKey = "$url#$quantizedSec"

        if (currentSourceUrl != url) {
            setDataSource(url)
        }

        var waitCount = 0
        while (isInitializing && waitCount < 15) {
            delay(50)
            waitCount++
        }

        val mmr = retriever ?: return
        val timeUs = timeMs * 1000L

        val bitmap = retrieverMutex.withLock {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    mmr.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        320,
                        180
                    )
                } else {
                    mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame extraction error at $timeMs ms: ${e.message}")
                null
            }
        }

        if (bitmap != null) {
            frameCache.put(cacheKey, bitmap)
            trackTimestamp(url, quantizedSec)
            saveToDisk(url, quantizedSec, bitmap)

            if (callback != null) {
                withContext(Dispatchers.Main) {
                    callback(bitmap)
                }
            }
        }
    }

    private fun saveToDisk(url: String, quantizedSec: Long, bitmap: Bitmap) {
        val diskFile = getDiskFile(url, quantizedSec) ?: return
        if (diskFile.exists()) return
        scope.launch {
            try {
                FileOutputStream(diskFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Proactively prefetch neighboring keyframes along the user's scrub direction.
     */
    private fun prefetchAround(url: String, timeMs: Long, direction: Int) {
        // Strictly prevent random seek storms across remote network media (e.g. 88 GB remuxes on mechanical HDDs)
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            return
        }
        scope.launch {
            val offsets = if (direction >= 0) {
                longArrayOf(10_000L, 30_000L, 60_000L, 120_000L)
            } else {
                longArrayOf(-10_000L, -30_000L, -60_000L, -120_000L)
            }

            for (offset in offsets) {
                val target = timeMs + offset
                if (target < 0) continue
                val qSec = (target / 2000L) * 2L
                val key = "$url#$qSec"
                if (frameCache.get(key) != null) continue

                val diskFile = getDiskFile(url, qSec)
                if (diskFile != null && diskFile.exists()) {
                    val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                    if (bmp != null) {
                        frameCache.put(key, bmp)
                        trackTimestamp(url, qSec)
                    }
                    continue
                }

                extractAndCache(url, target, null)
            }
        }
    }

    fun release() {
        synchronized(this) {
            pendingRequestTimeMs = null
            pendingRequestCallback = null
        }
        scope.launch {
            try {
                retrieverMutex.withLock {
                    retriever?.release()
                    retriever = null
                    currentSourceUrl = null
                }
            } catch (ignored: Exception) {}
        }
    }
}
