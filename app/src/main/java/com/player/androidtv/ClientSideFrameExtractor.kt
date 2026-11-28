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
 * 2. Multi-strategy frame decoding: Unscaled native + CPU downscaling to avoid hardware decoder scaling bugs.
 * 3. Direction-aware lookahead pre-extraction (10s, 30s, 60s along scrub trajectory).
 * 4. Thread-safe serialized MediaMetadataRetriever access with native mutex.
 * 5. Instant nearest-neighbor frame retrieval to eliminate blank scrub cards.
 * 6. Blank/black frame detection and rejection to guarantee real movie content.
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

            // Background prune: purge empty/corrupt black files (<= 1500 bytes) and thumbnails older than 7 days
            scope.launch {
                try {
                    val cutoff = System.currentTimeMillis() - (7L * 24 * 3600 * 1000L)
                    dir.listFiles()?.forEach { file ->
                        if (file.length() <= 1500 || file.lastModified() < cutoff) {
                            file.delete()
                        }
                    }
                } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * Inspects sample pixels across the bitmap to determine if it is a blank/black frame.
     * Android MediaMetadataRetriever often returns solid black when seek fails or on EOF.
     */
    fun isBlackFrame(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true
        val stepX = (w / 6).coerceAtLeast(1)
        val stepY = (h / 6).coerceAtLeast(1)
        var nonBlackCount = 0
        for (x in stepX until w step stepX) {
            for (y in stepY until h step stepY) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 12 || g > 12 || b > 12) {
                    nonBlackCount++
                    if (nonBlackCount >= 2) return false
                }
            }
        }
        return true
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
    fun setDataSource(url: String, extraHeaders: Map<String, String>? = null) {
        if (url.isBlank() || (currentSourceUrl == url && (retriever != null || isInitializing))) return

        currentSourceUrl = url
        isInitializing = true

        // Fast disk indexing for instant nearest-neighbor cache hit
        scope.launch {
            try {
                val hash = hashUrl(url)
                val dir = cacheDir
                if (dir != null && dir.exists()) {
                    dir.listFiles()?.forEach { file ->
                        val name = file.name
                        if (name.startsWith("thumb_${hash}_") && name.endsWith(".jpg") && file.length() > 1500) {
                            val secStr = name.removePrefix("thumb_${hash}_").removeSuffix(".jpg")
                            secStr.toLongOrNull()?.let { sec ->
                                trackTimestamp(url, sec)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        scope.launch {
            try {
                retrieverMutex.withLock {
                    val old = retriever
                    retriever = null
                    old?.release()

                    val mmr = MediaMetadataRetriever()
                    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                        val headers = HashMap<String, String>()
                        headers["Connection"] = "keep-alive"
                        headers["Accept-Encoding"] = "identity"
                        headers["User-Agent"] = "RemuxPlayer-AndroidTV/2.0"
                        extraHeaders?.forEach { (k, v) -> headers[k] = v }
                        mmr.setDataSource(url, headers)
                    } else {
                        mmr.setDataSource(url)
                    }
                    retriever = mmr
                    isInitializing = false
                    Log.i(TAG, "MediaMetadataRetriever initialized for stream: $url")
                }
            } catch (e: Exception) {
                isInitializing = false
                Log.w(TAG, "Could not initialize MediaMetadataRetriever: ${e.message}")
            }
        }
    }

    /**
     * Injects an externally decoded frame (e.g. server-side chapter thumbnail or surface snapshot) into the cache.
     */
    fun registerFrame(url: String, timeMs: Long, bitmap: Bitmap) {
        if (isBlackFrame(bitmap)) return
        val quantizedSec = (timeMs / 2000L) * 2L
        val cacheKey = "$url#$quantizedSec"
        frameCache.put(cacheKey, bitmap)
        trackTimestamp(url, quantizedSec)
        saveToDisk(url, quantizedSec, bitmap)
    }

    /**
     * Instant nearest-neighbor frame retrieval from RAM cache.
     * Prevents empty/black scrub preview cards while the exact keyframe is being decoded.
     */
    fun getClosestCachedFrame(url: String, timeMs: Long): Bitmap? {
        val targetSec = (timeMs / 2000L) * 2L
        val exactKey = "$url#$targetSec"
        val exact = frameCache.get(exactKey)
        if (exact != null && !isBlackFrame(exact)) return exact

        val list = cachedTimestamps[url] ?: return null
        synchronized(list) {
            var closestSec: Long? = null
            var minDiff = Long.MAX_VALUE
            for (sec in list) {
                val diff = Math.abs(sec - targetSec)
                if (diff < minDiff && diff <= 120) { // Nearest neighbor within 2 minutes
                    minDiff = diff
                    closestSec = sec
                }
            }
            if (closestSec != null) {
                val found = frameCache.get("$url#$closestSec")
                if (found != null && !isBlackFrame(found)) return found

                // Check disk cache
                val diskFile = getDiskFile(url, closestSec)
                if (diskFile != null && diskFile.exists() && diskFile.length() > 1500) {
                    try {
                        val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                        if (bmp != null && !isBlackFrame(bmp)) {
                            frameCache.put("$url#$closestSec", bmp)
                            return bmp
                        }
                    } catch (_: Exception) {}
                }
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
        if (ramBitmap != null && !isBlackFrame(ramBitmap)) {
            onFrameReady(ramBitmap)
            prefetchAround(url, timeMs, direction)
            return true
        }

        // Tier 2: Local Disk Cache (2-4ms)
        val diskFile = getDiskFile(url, quantizedSec)
        if (diskFile != null && diskFile.exists() && diskFile.length() > 1500) {
            scope.launch {
                val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bmp != null && !isBlackFrame(bmp)) {
                    frameCache.put(cacheKey, bmp)
                    trackTimestamp(url, quantizedSec)
                    withContext(Dispatchers.Main) {
                        onFrameReady(bmp)
                    }
                    prefetchAround(url, timeMs, direction)
                } else {
                    diskFile.delete() // Purge corrupt or solid black file
                }
            }
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
                    prefetchAround(url, targetMs, direction)
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

        val mmr = retriever
        if (mmr == null) {
            Log.w(TAG, "extractAndCache: retriever is null for $url")
            return
        }
        val timeUs = timeMs * 1000L
        Log.i(TAG, "extractAndCache starting for $timeMs ms (timeUs=$timeUs)")

        val bitmap = retrieverMutex.withLock {
            var extracted: Bitmap? = null

            // Strategy 1: Native unscaled frame with OPTION_CLOSEST_SYNC + CPU scaling
            // Decodes native resolution directly to avoid emulator/driver downscaling artifacts
            try {
                val raw = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (raw != null) {
                    val isBlack = isBlackFrame(raw)
                    Log.i(TAG, "Strategy 1: raw=${raw.width}x${raw.height}, isBlack=$isBlack")
                    if (!isBlack) {
                        extracted = Bitmap.createScaledBitmap(raw, 320, 180, true)
                        if (raw != extracted) raw.recycle()
                    }
                } else {
                    Log.i(TAG, "Strategy 1: returned null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Strategy 1 failed at $timeMs ms: ${e.message}")
            }

            // Strategy 2: Native unscaled frame with OPTION_CLOSEST + CPU scaling
            if (extracted == null) {
                try {
                    val raw = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (raw != null) {
                        val isBlack = isBlackFrame(raw)
                        Log.i(TAG, "Strategy 2: raw=${raw.width}x${raw.height}, isBlack=$isBlack")
                        if (!isBlack) {
                            extracted = Bitmap.createScaledBitmap(raw, 320, 180, true)
                            if (raw != extracted) raw.recycle()
                        }
                    } else {
                        Log.i(TAG, "Strategy 2: returned null")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Strategy 2 failed at $timeMs ms: ${e.message}")
                }
            }

            // Strategy 3: Default frame extraction without seek option + CPU scaling
            if (extracted == null) {
                try {
                    val raw = mmr.getFrameAtTime(timeUs)
                    if (raw != null) {
                        val isBlack = isBlackFrame(raw)
                        Log.i(TAG, "Strategy 3: raw=${raw.width}x${raw.height}, isBlack=$isBlack")
                        if (!isBlack) {
                            extracted = Bitmap.createScaledBitmap(raw, 320, 180, true)
                            if (raw != extracted) raw.recycle()
                        }
                    } else {
                        Log.i(TAG, "Strategy 3: returned null")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Strategy 3 failed at $timeMs ms: ${e.message}")
                }
            }

            // Strategy 4: Scaled frame on supported hardware (API 27+)
            if (extracted == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    val scaled = mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 320, 180)
                    if (scaled != null) {
                        val isBlack = isBlackFrame(scaled)
                        Log.i(TAG, "Strategy 4: scaled=${scaled.width}x${scaled.height}, isBlack=$isBlack")
                        if (!isBlack) {
                            extracted = scaled
                        }
                    } else {
                        Log.i(TAG, "Strategy 4: returned null")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Strategy 4 failed at $timeMs ms: ${e.message}")
                }
            }

            // Strategy 5: Scaled frame with OPTION_CLOSEST_SYNC (API 27+)
            if (extracted == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    val scaled = mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                    if (scaled != null) {
                        val isBlack = isBlackFrame(scaled)
                        Log.i(TAG, "Strategy 5: scaled=${scaled.width}x${scaled.height}, isBlack=$isBlack")
                        if (!isBlack) {
                            extracted = scaled
                        }
                    } else {
                        Log.i(TAG, "Strategy 5: returned null")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Strategy 5 failed at $timeMs ms: ${e.message}")
                }
            }

            extracted
        }

        Log.i(TAG, "extractAndCache finished: bitmap!=null is ${bitmap != null}")
        if (bitmap != null && !isBlackFrame(bitmap)) {
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
        if (isBlackFrame(bitmap)) return // Never persist empty/black frames
        val diskFile = getDiskFile(url, quantizedSec) ?: return
        if (diskFile.exists() && diskFile.length() > 1500) return
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
        scope.launch {
            val isRemote = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
            val offsets = if (isRemote) {
                if (direction >= 0) longArrayOf(15_000L, 30_000L) else longArrayOf(-15_000L, -30_000L)
            } else {
                if (direction >= 0) longArrayOf(10_000L, 30_000L, 60_000L) else longArrayOf(-10_000L, -30_000L, -60_000L)
            }

            for (offset in offsets) {
                val target = timeMs + offset
                if (target < 0) continue
                val qSec = (target / 2000L) * 2L
                val key = "$url#$qSec"
                if (frameCache.get(key) != null) continue

                val diskFile = getDiskFile(url, qSec)
                if (diskFile != null && diskFile.exists() && diskFile.length() > 1500) {
                    val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                    if (bmp != null && !isBlackFrame(bmp)) {
                        frameCache.put(key, bmp)
                        trackTimestamp(url, qSec)
                    } else {
                        diskFile.delete()
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
