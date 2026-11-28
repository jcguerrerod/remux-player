package com.player.androidtv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance Jellyfin Trickplay engine for Apple TV style scrubbing.
 *
 * Downloads 10x10 tile sheets (320x180 per tile, 100 tiles per sheet = 1000 seconds per sheet).
 * Caches full decoded sheets in LRU memory cache (RGB_565 config).
 * Synchronously crops and returns the exact 320x180 tile bitmap for zero-latency 60fps scrubbing.
 * Asynchronously prefetches adjacent sheets for instantaneous seek transitions across hours.
 */
object JellyfinTrickplayManager {
    private const val TAG = "JellyfinTrickplay"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 16 tile sheets in memory (RGB_565 configuration keeps each ~11MB in RAM)
    // 16 sheets = 16,000 seconds = 4.4 hours of full movie timeline coverage!
    private val sheetCache = object : LruCache<String, Bitmap>(16) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    // Cache of individual cropped tile bitmaps (150 tiles)
    private val tileCache = object : LruCache<String, Bitmap>(150) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val inFlightRequests = ConcurrentHashMap<String, Boolean>()
    private var activeItemId: String? = null
    private var activeServerUrl: String? = null
    private var activeToken: String? = null

    fun setMediaContext(serverUrl: String?, itemId: String?, token: String?) {
        if (serverUrl.isNullOrBlank() || itemId.isNullOrBlank()) return
        val cleanServerUrl = serverUrl.trimEnd('/')
        if (activeItemId != itemId || activeServerUrl != cleanServerUrl) {
            activeServerUrl = cleanServerUrl
            activeItemId = itemId
            activeToken = token ?: ""
            tileCache.evictAll()
            sheetCache.evictAll()
            inFlightRequests.clear()
            Log.i(TAG, "Trickplay media context activated for item: $itemId on $cleanServerUrl (hasToken=${!activeToken.isNullOrBlank()})")
            prefetchSheet(0)
            prefetchSheet(1)
        }
    }

    /**
     * Synchronously returns a cropped tile bitmap if the corresponding sheet or tile is in cache.
     * If not in cache, triggers an async download and calls [onTileLoaded] when ready.
     * Returns null if not cached yet.
     */
    fun getTile(
        timeMs: Long,
        intervalMs: Long = 10000L,
        onTileLoaded: ((Bitmap) -> Unit)? = null
    ): Bitmap? {
        val itemId = activeItemId ?: return null
        val tileIndex = (timeMs / intervalMs).toInt().coerceAtLeast(0)
        val tilesPerSheet = 100 // 10x10 layout
        val sheetIndex = tileIndex / tilesPerSheet
        val tileInSheet = tileIndex % tilesPerSheet

        val tileKey = "$itemId#$tileIndex"
        val cachedTile = tileCache.get(tileKey)
        if (cachedTile != null && !cachedTile.isRecycled) {
            return cachedTile
        }

        val sheetKey = "$itemId#sheet_$sheetIndex"
        val cachedSheet = sheetCache.get(sheetKey)

        if (cachedSheet != null && !cachedSheet.isRecycled) {
            val cropped = cropTile(cachedSheet, tileInSheet)
            if (cropped != null) {
                tileCache.put(tileKey, cropped)
                return cropped
            }
        }

        // Sheet is not in RAM, trigger download
        downloadSheetAsync(sheetIndex) { sheetBmp ->
            val cropped = cropTile(sheetBmp, tileInSheet)
            if (cropped != null) {
                tileCache.put(tileKey, cropped)
                onTileLoaded?.invoke(cropped)
            }
        }

        // Prefetch adjacent sheets for smooth scrolling across boundaries
        prefetchSheet(sheetIndex + 1)
        if (sheetIndex > 0) prefetchSheet(sheetIndex - 1)

        return null
    }

    private fun cropTile(sheet: Bitmap, tileInSheet: Int): Bitmap? {
        val col = tileInSheet % 10
        val row = tileInSheet / 10
        val tileW = (sheet.width / 10).coerceAtLeast(1)
        val tileH = (sheet.height / 10).coerceAtLeast(1)
        val x = (col * tileW).coerceIn(0, (sheet.width - tileW).coerceAtLeast(0))
        val y = (row * tileH).coerceIn(0, (sheet.height - tileH).coerceAtLeast(0))

        return try {
            Bitmap.createBitmap(sheet, x, y, tileW, tileH)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to crop tile $tileInSheet: ${e.message}")
            null
        }
    }

    fun prefetchSheet(sheetIndex: Int) {
        val itemId = activeItemId ?: return
        val sheetKey = "$itemId#sheet_$sheetIndex"
        if (sheetCache.get(sheetKey) != null || inFlightRequests[sheetKey] == true) return
        downloadSheetAsync(sheetIndex, null)
    }

    private fun downloadSheetAsync(sheetIndex: Int, onComplete: ((Bitmap) -> Unit)?) {
        val itemId = activeItemId ?: return
        val serverUrl = activeServerUrl ?: return
        val token = activeToken ?: ""
        val sheetKey = "$itemId#sheet_$sheetIndex"

        if (inFlightRequests.putIfAbsent(sheetKey, true) == true) {
            return
        }

        scope.launch {
            try {
                // If running on emulator and host URL is 192.168.100.108, also support 10.0.2.2 fallback
                val candidateUrls = mutableListOf<String>()
                candidateUrls.add("$serverUrl/Videos/$itemId/Trickplay/320/$sheetIndex.jpg?MediaSourceId=$itemId&ApiKey=$token")
                if (serverUrl.contains("192.168.100.108")) {
                    val emuUrl = serverUrl.replace("192.168.100.108", "10.0.2.2")
                    candidateUrls.add("$emuUrl/Videos/$itemId/Trickplay/320/$sheetIndex.jpg?MediaSourceId=$itemId&ApiKey=$token")
                }

                var successBmp: Bitmap? = null
                for (urlStr in candidateUrls) {
                    try {
                        val url = URL(urlStr)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 3000
                        conn.readTimeout = 7000
                        conn.setRequestProperty("User-Agent", "RemuxPlayer-AndroidTV/2.0")
                        if (token.isNotEmpty()) {
                            conn.setRequestProperty("X-Emby-Token", token)
                            conn.setRequestProperty("Authorization", "MediaBrowser Client=\"REMUX Player\", Device=\"Android TV\", DeviceId=\"remux-player-atv\", Version=\"1.0.0\", Token=\"$token\"")
                        }

                        val code = conn.responseCode
                        if (code == 200) {
                            val opts = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                            val bmp = BitmapFactory.decodeStream(conn.inputStream, null, opts)
                            if (bmp != null) {
                                successBmp = bmp
                                Log.i(TAG, "Trickplay sheet $sheetIndex loaded (${bmp.width}x${bmp.height}) from $urlStr")
                                break
                            }
                        } else {
                            Log.w(TAG, "Trickplay sheet $sheetIndex HTTP $code on $urlStr")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed attempt on $urlStr: ${e.message}")
                    }
                }

                if (successBmp != null) {
                    sheetCache.put(sheetKey, successBmp)
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(successBmp)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Trickplay sheet $sheetIndex download failed: ${e.message}")
            } finally {
                inFlightRequests.remove(sheetKey)
            }
        }
    }
}
