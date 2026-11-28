package com.player.androidtv.ui

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Crystalline Optical Diffusion for Liquid Glass (Android 11+, zero allocations after warm-up).
 *
 * Uses a light, silky 2-pass kernel on a 0.5x resolution buffer so the underlying artwork and
 * video contours remain clearly visible through the glass and the meniscus light-bending stands
 * out across the entire lens.
 */
object GlassBlur {
    private var bufA = IntArray(0)
    private var bufB = IntArray(0)

    /** Blurs [bmp] in place and returns its average normalized luminance (0..1). */
    fun blur(bmp: Bitmap, radius: Int = 3, passes: Int = 2): Float {
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        if (n == 0) return 0f
        if (bufA.size < n) {
            bufA = IntArray(n)
            bufB = IntArray(n)
        }
        val a = bufA
        val b = bufB
        bmp.getPixels(a, 0, w, 0, 0, w, h)

        val r = radius.coerceAtLeast(1)
        repeat(passes) {
            boxH(a, b, w, h, r)
            boxV(b, a, w, h, r)
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h)

        var lum = 0L
        var count = 0
        var i = 0
        while (i < n) {
            val p = a[i]
            lum += (((p shr 16) and 255) * 54 + ((p shr 8) and 255) * 183 + (p and 255) * 19) shr 8
            count++
            i += 7
        }
        return if (count == 0) 0f else (lum.toFloat() / count) / 255f
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val wm = w - 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[row + min(max(i, 0), wm)]
                sr += (p shr 16) and 255
                sg += (p shr 8) and 255
                sb += p and 255
            }
            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[row + max(x - r, 0)]
                val pIn = src[row + min(x + r + 1, wm)]
                sr += ((pIn shr 16) and 255) - ((pOut shr 16) and 255)
                sg += ((pIn shr 8) and 255) - ((pOut shr 8) and 255)
                sb += (pIn and 255) - (pOut and 255)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val hm = h - 1
        for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[min(max(i, 0), hm) * w + x]
                sr += (p shr 16) and 255
                sg += (p shr 8) and 255
                sb += p and 255
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[max(y - r, 0) * w + x]
                val pIn = src[min(y + r + 1, hm) * w + x]
                sr += ((pIn shr 16) and 255) - ((pOut shr 16) and 255)
                sg += ((pIn shr 8) and 255) - ((pOut shr 8) and 255)
                sb += (pIn and 255) - (pOut and 255)
            }
        }
    }
}
