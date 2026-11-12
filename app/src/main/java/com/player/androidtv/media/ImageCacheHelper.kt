package com.player.androidtv.media

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.player.coremedia.security.NetworkUrlSecurity
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Memory-bounded two-tier image decoder and LRU cache for TV card thumbnails and hero backdrops.
 */
class ImageCacheHelper {

    companion object {
        private const val TAG = "ImageCacheHelper"
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val READ_TIMEOUT_MS = 6000
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024 // 12 MB safety cap
    }

    private val maxCacheKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L)
        .toInt()
        .coerceIn(16 * 1024, 96 * 1024)

    private val backdropCache = object : LruCache<String, Bitmap>((maxCacheKb * 0.65f).toInt()) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = (bitmap.byteCount / 1024).coerceAtLeast(1)
    }

    private val cardCache = object : LruCache<String, Bitmap>((maxCacheKb * 0.35f).toInt()) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = (bitmap.byteCount / 1024).coerceAtLeast(1)
    }

    fun getCachedBackdrop(key: String): Bitmap? = if (key.isEmpty()) null else backdropCache.get(key)

    fun getCachedCard(key: String): Bitmap? = if (key.isEmpty()) null else cardCache.get(key)

    fun loadBackdrop(remoteUrl: String?, assetPath: String?, assets: AssetManager): Bitmap? {
        val key = remoteUrl ?: assetPath ?: return null
        backdropCache.get(key)?.let { return it }
        val decoded = decodeSampledBitmap(
            remoteUrl = remoteUrl,
            assetPath = assetPath,
            assets = assets,
            reqWidth = 1280,
            reqHeight = 720,
            preferredConfig = Bitmap.Config.RGB_565
        )
        if (decoded != null) {
            backdropCache.put(key, decoded)
        }
        return decoded
    }

    fun loadCardThumbnail(remoteUrl: String?, assetPath: String?, assets: AssetManager): Bitmap? {
        val key = remoteUrl ?: assetPath ?: return null
        cardCache.get(key)?.let { return it }
        val decoded = decodeSampledBitmap(
            remoteUrl = remoteUrl,
            assetPath = assetPath,
            assets = assets,
            reqWidth = 480,
            reqHeight = 270,
            preferredConfig = Bitmap.Config.RGB_565
        )
        if (decoded != null) {
            cardCache.put(key, decoded)
        }
        return decoded
    }

    fun clearAll() {
        backdropCache.evictAll()
        cardCache.evictAll()
    }

    private fun decodeSampledBitmap(
        remoteUrl: String?,
        assetPath: String?,
        assets: AssetManager,
        reqWidth: Int,
        reqHeight: Int,
        preferredConfig: Bitmap.Config
    ): Bitmap? {
        if (!remoteUrl.isNullOrBlank()) {
            val bytes = downloadBoundedBytes(remoteUrl)
            if (bytes != null) {
                return decodeFromByteArray(bytes, reqWidth, reqHeight, preferredConfig)
            }
        }
        if (!assetPath.isNullOrBlank()) {
            try {
                assets.open(assetPath).use { stream ->
                    val bytes = readStreamBounded(stream) ?: return null
                    return decodeFromByteArray(bytes, reqWidth, reqHeight, preferredConfig)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Asset image load failed: $assetPath", e)
            }
        }
        return null
    }

    private fun downloadBoundedBytes(remoteUrl: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Image HTTP $code for ${NetworkUrlSecurity.redactSensitiveUrl(remoteUrl)}")
                return null
            }
            conn.inputStream.use { readStreamBounded(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Remote image load failed: ${NetworkUrlSecurity.redactSensitiveUrl(remoteUrl)} (${e.javaClass.simpleName})")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun readStreamBounded(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_IMAGE_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun decodeFromByteArray(
        data: ByteArray,
        reqWidth: Int,
        reqHeight: Int,
        preferredConfig: Bitmap.Config
    ): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, reqWidth, reqHeight)
            inPreferredConfig = preferredConfig
            inMutable = false
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts)
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
