package com.player.androidtv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Clean, Zero-Animation Frosted Dark Surface (X / Twitter Floating Dock Style).
 *
 * - Samples the underlying 1:1 region once on open/switch and applies a heavy, uniform frosted
 *   blur (`radius = 12, passes = 3`) so the background colors bleed through softly with zero
 *   distortion, zero chromatic fringe, and zero animations.
 * - Overlays a dark translucent smoky veil (`#A6121417`) and a subtle 1dp rim border (`#24FFFFFF`).
 */
class LiquidGlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val cornerRadiusPx = 24f * density
    private val sampleScale = 0.35f

    var backdropSource: View? = null

    private var backdrop: Bitmap? = null
    private var bufferA: Bitmap? = null
    private var bufferB: Bitmap? = null
    private var lastSampledLeft = Int.MIN_VALUE
    private var lastSampledTop = Int.MIN_VALUE
    private var lastSampledW = 0
    private var lastSampledH = 0

    private val clipPath = Path()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val darkVeilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8121418")
    }
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E614161A")
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#24FFFFFF")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var liveSurface: SurfaceView? = null
    private var liveBufferSize: (() -> Pair<Int, Int>?)? = null
    private var copyInFlight = false
    private var liveCopyBitmap: Bitmap? = null
    private var lastValidVideoBmp: Bitmap? = null
    private var lastValidVideoX = 0f
    private var lastValidVideoY = 0f

    init {
        setWillNotDraw(false)
        background = null
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                outline.alpha = 0.70f
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        if (backdropSource != null) {
            refreshBackdrop()
        }
        if (liveSurface != null && visibility == View.VISIBLE) {
            requestSingleVideoFrame()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = r - l
        val h = b - t
        if (visibility == View.VISIBLE && w > 0 && h > 0) {
            if (l != lastSampledLeft || t != lastSampledTop || w != lastSampledW || h != lastSampledH || backdrop == null) {
                if (backdropSource != null) {
                    refreshBackdrop()
                }
                if (liveSurface != null) {
                    requestSingleVideoFrame()
                }
            }
        }
    }

    private fun acquireBackBuffer(w: Int, h: Int): Bitmap {
        val candidate = if (backdrop === bufferA) bufferB else bufferA
        val bmp = if (candidate != null && !candidate.isRecycled && candidate.width == w && candidate.height == h) {
            candidate
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        if (backdrop === bufferA) {
            bufferB = bmp
        } else {
            bufferA = bmp
        }
        return bmp
    }

    private fun isFramePureBlackGlitch(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return true
        val stepX = max(1, w / 6)
        val stepY = max(1, h / 6)
        var nonBlackCount = 0
        var y = stepY / 2
        while (y < h) {
            var x = stepX / 2
            while (x < w) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 3 || g > 3 || b > 3) {
                    nonBlackCount++
                    if (nonBlackCount >= 2) return false
                }
                x += stepX
            }
            y += stepY
        }
        return true
    }

    private fun composeBackdrop(videoBmp: Bitmap?, videoOffsetX: Float, videoOffsetY: Float) {
        if (width <= 0 || height <= 0) return
        val parentView = parent as? View ?: return
        val parLoc = IntArray(2)
        parentView.getLocationInWindow(parLoc)
        val regionW = width
        val regionH = height
        val bw = max(1, (regionW * sampleScale).toInt())
        val bh = max(1, (regionH * sampleScale).toInt())
        try {
            val bmp = acquireBackBuffer(bw, bh)
            bmp.eraseColor(Color.BLACK)
            val c = Canvas(bmp)

            if (videoBmp != null && !videoBmp.isRecycled) {
                val vSrc = Rect(0, 0, videoBmp.width, videoBmp.height)
                val vDst = RectF(
                    videoOffsetX,
                    videoOffsetY,
                    videoOffsetX + videoBmp.width,
                    videoOffsetY + videoBmp.height
                )
                c.drawBitmap(videoBmp, vSrc, vDst, null)
            }

            val source = backdropSource
            if (source != null && source.width > 0) {
                val srcLoc = IntArray(2)
                source.getLocationInWindow(srcLoc)
                val leftCoord = parLoc[0] + left - srcLoc[0]
                val topCoord = parLoc[1] + top - srcLoc[1]
                val save = c.save()
                c.scale(bw / regionW.toFloat(), bh / regionH.toFloat())
                c.translate(-leftCoord.toFloat(), -topCoord.toFloat())
                source.draw(c)
                c.restoreToCount(save)
            }

            // Smooth uniform frosted blur (exactly like X / Twitter dark frosted floating bar)
            GlassBlur.blur(bmp, radius = 12, passes = 3)
            backdrop = bmp
            lastSampledLeft = left
            lastSampledTop = top
            lastSampledW = width
            lastSampledH = height
            invalidate()
        } catch (_: Throwable) {}
    }

    fun refreshBackdrop() {
        composeBackdrop(lastValidVideoBmp, lastValidVideoX, lastValidVideoY)
    }

    fun startLiveBackdrop(surfaceView: SurfaceView, bufferSize: () -> Pair<Int, Int>?) {
        liveSurface = surfaceView
        liveBufferSize = bufferSize
        mainHandler.post { requestSingleVideoFrame() }
    }

    fun stopLiveBackdrop() {
        copyInFlight = false
    }

    private fun requestSingleVideoFrame() {
        if (copyInFlight) return
        val sv = liveSurface ?: return
        val parentView = parent as? View ?: return
        if (width <= 0 || !isAttachedToWindow || sv.holder?.surface?.isValid != true || sv.width <= 0) {
            return
        }

        val svLoc = IntArray(2)
        val parLoc = IntArray(2)
        sv.getLocationInWindow(svLoc)
        parentView.getLocationInWindow(parLoc)
        val regL = (parLoc[0] + left - svLoc[0]).toFloat()
        val regT = (parLoc[1] + top - svLoc[1]).toFloat()
        val regW = width.toFloat()
        val regH = height.toFloat()

        val cl = max(0f, regL)
        val ct = max(0f, regT)
        val cr = min(sv.width.toFloat(), regL + regW)
        val cb = min(sv.height.toFloat(), regT + regH)
        if (cr - cl < 4f || cb - ct < 4f) return

        val sf = sv.holder?.surfaceFrame
        val bufW = sf?.width()?.takeIf { it > 0 } ?: sv.width
        val bufH = sf?.height()?.takeIf { it > 0 } ?: sv.height
        val fx = bufW / sv.width.toFloat()
        val fy = bufH / sv.height.toFloat()
        val src = Rect(
            (cl * fx).toInt(), (ct * fy).toInt(),
            (cr * fx).toInt().coerceAtMost(bufW), (cb * fy).toInt().coerceAtMost(bufH)
        )

        val copyW = max(1, ((cr - cl) * sampleScale).toInt())
        val copyH = max(1, ((cb - ct) * sampleScale).toInt())
        val copyBmp = if (liveCopyBitmap != null && !liveCopyBitmap!!.isRecycled &&
            liveCopyBitmap!!.width == copyW && liveCopyBitmap!!.height == copyH
        ) {
            liveCopyBitmap!!
        } else {
            Bitmap.createBitmap(copyW, copyH, Bitmap.Config.ARGB_8888).also { liveCopyBitmap = it }
        }

        try {
            copyInFlight = true
            PixelCopy.request(sv, src, copyBmp, { result ->
                copyInFlight = false
                if (result == PixelCopy.SUCCESS) {
                    val offsetX = (cl - regL) * sampleScale
                    val offsetY = (ct - regT) * sampleScale
                    val isGlitchBlack = lastValidVideoBmp != null && isFramePureBlackGlitch(copyBmp)
                    if (!isGlitchBlack) {
                        val cached = if (lastValidVideoBmp != null && !lastValidVideoBmp!!.isRecycled &&
                            lastValidVideoBmp!!.width == copyW && lastValidVideoBmp!!.height == copyH
                        ) {
                            lastValidVideoBmp!!
                        } else {
                            Bitmap.createBitmap(copyW, copyH, Bitmap.Config.ARGB_8888).also { lastValidVideoBmp = it }
                        }
                        Canvas(cached).drawBitmap(copyBmp, 0f, 0f, null)
                        lastValidVideoX = offsetX
                        lastValidVideoY = offsetY
                        composeBackdrop(cached, offsetX, offsetY)
                    } else if (backdrop == null) {
                        composeBackdrop(lastValidVideoBmp, lastValidVideoX, lastValidVideoY)
                    }
                } else if (backdrop == null) {
                    composeBackdrop(lastValidVideoBmp, lastValidVideoX, lastValidVideoY)
                }
            }, mainHandler)
        } catch (_: Throwable) {
            copyInFlight = false
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun triggerLiquidPulse(startPeak: Float = 1.0f, durationMs: Long = 0L, resampleVideo: Boolean = false) {
        if (backdropSource != null && width > 0 && height > 0) {
            refreshBackdrop()
        }
        if (liveSurface != null && width > 0 && height > 0) {
            requestSingleVideoFrame()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun energize(targetX: Float = 0f, targetY: Float = 0f) {
        // No-op: zero animations.
    }

    @Suppress("UNUSED_PARAMETER")
    fun animateMaterialize(
        anchorView: View? = null,
        onEnd: (() -> Unit)? = null
    ) {
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        translationY = 0f
        alpha = 1f
        visibility = View.VISIBLE
        if (backdropSource != null && width > 0 && height > 0) {
            refreshBackdrop()
        }
        if (liveSurface != null && width > 0 && height > 0) {
            requestSingleVideoFrame()
        }
        onEnd?.invoke()
    }

    fun animateDematerialize(onEnd: (() -> Unit)? = null) {
        animate().cancel()
        visibility = View.GONE
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        translationY = 0f
        alpha = 1f
        stopLiveBackdrop()
        onEnd?.invoke()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            super.dispatchDraw(canvas)
            return
        }

        val save = canvas.save()
        canvas.clipPath(clipPath)

        val bmp = backdrop
        if (bmp != null && !bmp.isRecycled) {
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(0f, 0f, w, h)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            canvas.drawRect(0f, 0f, w, h, darkVeilPaint)
        } else {
            canvas.drawRect(0f, 0f, w, h, fallbackPaint)
        }

        super.dispatchDraw(canvas)

        val ri = rimPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            ri, ri, w - ri, h - ri,
            cornerRadiusPx - ri, cornerRadiusPx - ri,
            rimPaint
        )

        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        stopLiveBackdrop()
        super.onDetachedFromWindow()
    }
}
