package com.player.androidtv.ui.card

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import com.player.androidtv.R
import com.player.androidtv.databinding.ViewShelfCardBinding

/**
 * Encapsula la vista de una tarjeta del carrusel (Shelf Card) con diseño Apple TV Liquid Glass,
 * animación elástica de elevación, centrado de scroll automático y barra de progreso.
 */
class ShelfCardViewHolder private constructor(
    val binding: ViewShelfCardBinding,
    private val density: Float
) {
    val root: View = binding.root

    fun bind(
        index: Int,
        title: String,
        subtitle: String,
        cachedThumbnail: Bitmap?,
        scrollView: HorizontalScrollView?,
        onFocused: () -> Unit,
        onActivated: () -> Unit
    ) {
        val lp = root.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            (200 * density).toInt(),
            (112 * density).toInt()
        )
        lp.width = (200 * density).toInt()
        lp.height = (112 * density).toInt()
        val marginStartDp = if (index == 0) 0 else 16
        lp.marginStart = (marginStartDp * density).toInt()
        lp.marginEnd = 0
        lp.topMargin = (6 * density).toInt()
        lp.bottomMargin = (6 * density).toInt()
        root.layoutParams = lp
        root.nextFocusUpId = R.id.btnPlayDirect

        binding.tvFallbackTitle.text = title
        binding.tvTitle.text = title
        binding.tvSubtitle.text = subtitle

        setThumbnail(cachedThumbnail)

        root.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            if (hasFocus) {
                v.animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .translationZ(16 * density)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                onFocused()
                if (scrollView != null) {
                    v.post {
                        val targetScrollX = if (index == 0) 0 else (v.left - (scrollView.width / 2) + (v.width / 2)).coerceAtLeast(0)
                        scrollView.smoothScrollTo(targetScrollX, 0)
                    }
                }
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationZ(0f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        root.setOnClickListener { onActivated() }
        root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onActivated()
                true
            } else {
                false
            }
        }
    }

    fun setThumbnail(bitmap: Bitmap?) {
        if (bitmap != null) {
            binding.ivThumbnail.setImageBitmap(bitmap)
            binding.ivThumbnail.visibility = View.VISIBLE
            binding.fallbackBox.visibility = View.GONE
        } else {
            binding.ivThumbnail.setImageBitmap(null)
            binding.ivThumbnail.visibility = View.GONE
            binding.fallbackBox.visibility = View.VISIBLE
        }
    }

    fun applyPlaybackProgress(posMs: Long, durMs: Long, isWatched: Boolean = false) {
        binding.watchedBadge.visibility = if (isWatched) View.VISIBLE else View.GONE
        if (durMs > 0L && posMs > 5000L && posMs < durMs - 30000L) {
            binding.progressContainer.visibility = View.VISIBLE
            val ratio = (posMs.toFloat() / durMs.toFloat()).coerceIn(0.02f, 1f)
            binding.progressContainer.post {
                val totalW = binding.progressContainer.width
                if (totalW > 0) {
                    val fillLp = binding.progressFill.layoutParams
                    fillLp.width = (totalW * ratio).toInt()
                    binding.progressFill.layoutParams = fillLp
                }
            }
        } else {
            binding.progressContainer.visibility = View.GONE
        }
    }

    val thumbnailImageView: ImageView
        get() = binding.ivThumbnail

    companion object {
        fun inflate(parent: ViewGroup): ShelfCardViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ViewShelfCardBinding.inflate(inflater, parent, false)
            val density = parent.resources.displayMetrics.density
            return ShelfCardViewHolder(binding, density)
        }
    }
}
