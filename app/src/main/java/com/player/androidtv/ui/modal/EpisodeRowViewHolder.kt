package com.player.androidtv.ui.modal

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.player.androidtv.databinding.ViewEpisodeRowBinding
import com.player.androidtv.ui.LiquidGlassHelper
import com.player.androidtv.ui.LiquidGlassLayout
import com.player.coremedia.jellyfin.DynamicRangeType
import com.player.coremedia.jellyfin.JellyfinMovie

/**
 * Encapsula una fila de episodio estilo tvOS 18 con miniatura 16:9, progreso interno,
 * indicador de visto e insignias tonales unificadas.
 */
class EpisodeRowViewHolder private constructor(
    val binding: ViewEpisodeRowBinding
) {
    val root: View = binding.root

    fun bind(
        episode: JellyfinMovie,
        displayTitle: String,
        savedPos: Long,
        savedDur: Long,
        isWatched: Boolean = false,
        cachedThumbnail: Bitmap? = null,
        onActivated: () -> Unit
    ) {
        binding.tvEpisodeTitle.text = displayTitle

        val epNumLabel = episode.episodeNumber?.let { "EP $it" }
            ?: episode.episodeCode.takeIf { it.isNotBlank() }
            ?: "EP"
        binding.tvEpisodeThumbFallback.text = epNumLabel
        setThumbnail(cachedThumbnail)

        val res = episode.resolutionBadge.ifBlank { if (episode.is4k) "4K UHD" else "HD" }
        val hdr = when (episode.dynamicRange) {
            DynamicRangeType.DOLBY_VISION -> "DOLBY VISION"
            DynamicRangeType.HDR10 -> "HDR10"
            DynamicRangeType.HLG -> "HLG"
            DynamicRangeType.SDR -> null
        }
        val specsParts = listOfNotNull(
            episode.durationText.takeIf { it.isNotBlank() },
            res.takeIf { it.isNotBlank() },
            hdr,
            episode.audioBadge.takeIf { it.isNotBlank() }
        )
        val specsText = specsParts.joinToString("  ·  ")
        if (specsText.isNotBlank()) {
            binding.tvEpisodeSpecs.text = specsText
            binding.tvEpisodeSpecs.visibility = View.VISIBLE
        } else {
            binding.tvEpisodeSpecs.visibility = View.GONE
        }

        if (episode.synopsis.isNotBlank()) {
            binding.tvEpisodeOverview.text = episode.synopsis
            binding.tvEpisodeOverview.visibility = View.VISIBLE
        } else {
            binding.tvEpisodeOverview.visibility = View.GONE
        }

        binding.epWatchedBadge.visibility = if (isWatched) View.VISIBLE else View.GONE

        if (savedDur > 0L && savedPos > 5000L && savedPos < savedDur - 30000L) {
            binding.epProgressContainer.visibility = View.VISIBLE
            val ratio = (savedPos.toFloat() / savedDur.toFloat()).coerceIn(0.04f, 1f)
            binding.epProgressContainer.post {
                val totalW = binding.epProgressContainer.width
                if (totalW > 0) {
                    val fillLp = binding.epProgressFill.layoutParams
                    fillLp.width = (totalW * ratio).toInt()
                    binding.epProgressFill.layoutParams = fillLp
                }
            }
        } else {
            binding.epProgressContainer.visibility = View.GONE
        }

        root.setOnFocusChangeListener { v, hasFocus ->
            LiquidGlassHelper.applyFocusScale(root, hasFocus, scale = 1.0f)
            if (hasFocus) {
                var p = v.parent
                while (p != null && p !is LiquidGlassLayout) {
                    p = p.parent
                }
                val glass = p as? LiquidGlassLayout
                if (glass != null) {
                    val vLoc = IntArray(2)
                    val gLoc = IntArray(2)
                    v.getLocationInWindow(vLoc)
                    glass.getLocationInWindow(gLoc)
                    val cx = (vLoc[0] - gLoc[0]) + v.width * 0.5f
                    val cy = (vLoc[1] - gLoc[1]) + v.height * 0.5f
                    glass.energize(cx, cy)
                }
            }
        }

        root.setOnClickListener { onActivated() }
    }

    fun setThumbnail(bitmap: Bitmap?) {
        if (bitmap != null) {
            binding.ivEpisodeThumbnail.setImageBitmap(bitmap)
            binding.ivEpisodeThumbnail.visibility = View.VISIBLE
            binding.tvEpisodeThumbFallback.visibility = View.GONE
        } else {
            binding.ivEpisodeThumbnail.setImageBitmap(null)
            binding.ivEpisodeThumbnail.visibility = View.GONE
            binding.tvEpisodeThumbFallback.visibility = View.VISIBLE
        }
    }

    companion object {
        fun inflate(parent: ViewGroup): EpisodeRowViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ViewEpisodeRowBinding.inflate(inflater, parent, false)
            return EpisodeRowViewHolder(binding)
        }
    }
}
