package com.player.androidtv.ui.modal

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.player.androidtv.PlaybackActivity.MovieChapter
import com.player.androidtv.PlaybackActivity.VideoDynamicRange
import com.player.androidtv.R
import com.player.androidtv.ui.LiquidGlassHelper
import com.player.androidtv.ui.LiquidGlassLayout
import com.player.coremedia.player.DolbyVisionProcessingMode
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackModalController(
    private val context: Context,
    private val modalOverlay: View,
    private val modalCard: View,
    private val tvModalTitle: TextView,
    private val tvModalBadge: TextView,
    private val llModalListContainer: LinearLayout,
    private val density: Float,
    private val topBar: View? = null,
    private val headerRow: View? = null,
    private val onModalStateChanged: ((isOpen: Boolean) -> Unit)? = null
) {
    enum class ModalType { NONE, SUBTITLES, AUDIO, CHAPTERS, DV_MODE }

    var activeModalType: ModalType = ModalType.NONE
        private set

    var currentAnchorButton: View? = null
        private set

    val isModalOpen: Boolean
        get() = activeModalType != ModalType.NONE && modalOverlay.visibility == View.VISIBLE

    fun showModal(
        title: String,
        badge: String,
        anchorButton: View,
        targetWidthDp: Int = 260
    ) {
        tvModalTitle.text = title
        if (badge.isNotBlank()) {
            tvModalBadge.text = badge
            tvModalBadge.visibility = View.VISIBLE
        } else {
            tvModalBadge.visibility = View.GONE
        }

        currentAnchorButton?.isActivated = false
        currentAnchorButton = anchorButton
        currentAnchorButton?.isActivated = true

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels

        val loc = IntArray(2)
        anchorButton.getLocationOnScreen(loc)
        val btnTop = loc[1]
        val isLeftAnchored = (anchorButton.id == R.id.btnChapters || anchorButton.id == R.id.btnDvMode)
        val defaultBottom = if (isLeftAnchored) (82 * density).toInt() else (142 * density).toInt()
        val calculatedBottom = if (btnTop > 0) {
            screenH - btnTop + (10 * density).toInt()
        } else {
            defaultBottom
        }
        val targetBottomMargin = calculatedBottom.coerceIn((68 * density).toInt(), (240 * density).toInt())

        val lp = modalCard.layoutParams as FrameLayout.LayoutParams
        lp.width = (targetWidthDp * density).toInt()

        if (isLeftAnchored) {
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            val btnLeft = loc[0]
            val targetMarginStart = (btnLeft - (8 * density).toInt()).coerceAtLeast((32 * density).toInt())

            modalCard.animate().cancel()
            lp.marginStart = targetMarginStart
            lp.marginEnd = 0
            lp.bottomMargin = targetBottomMargin
            modalCard.translationX = 0f
            modalCard.layoutParams = lp
        } else {
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            val fallbackMarginEnd = if (anchorButton.id == R.id.btnSubtitles) (100 * density).toInt() else (48 * density).toInt()
            val btnRight = loc[0] + anchorButton.width
            val calculatedEnd = screenW - btnRight
            val minEnd = (16 * density).toInt()
            val maxEnd = (260 * density).toInt()
            val targetMarginEnd = if (calculatedEnd in minEnd..maxEnd) calculatedEnd else fallbackMarginEnd

            modalCard.animate().cancel()
            lp.marginStart = 0
            lp.marginEnd = targetMarginEnd
            lp.bottomMargin = targetBottomMargin
            modalCard.translationX = 0f
            modalCard.layoutParams = lp
        }

        topBar?.animate()?.cancel()
        topBar?.alpha = 0f
        modalOverlay.bringToFront()

        if (modalOverlay.visibility != View.VISIBLE) {
            LiquidGlassHelper.showModalOverlay(modalOverlay, modalCard, anchorView = anchorButton) {
                onModalStateChanged?.invoke(true)
            }
        }
    }

    fun closeModal(onClosed: (() -> Unit)? = null) {
        if (!isModalOpen) return
        val lastAnchor = currentAnchorButton
        activeModalType = ModalType.NONE

        currentAnchorButton?.isActivated = false
        currentAnchorButton = null

        topBar?.animate()?.cancel()
        topBar?.alpha = 1f

        LiquidGlassHelper.hideModalOverlay(modalOverlay, modalCard) {
            llModalListContainer.removeAllViews()
            if (onClosed != null) {
                onClosed.invoke()
            } else {
                lastAnchor?.requestFocus()
            }
            onModalStateChanged?.invoke(false)
        }
    }

    fun openAudioModal(
        exo: ExoPlayer,
        anchorButton: View,
        currentAudioBadgeText: String,
        onAudioSelected: ((Format) -> Unit)? = null
    ) {
        activeModalType = ModalType.AUDIO
        showModal(
            title = "Audio",
            badge = currentAudioBadgeText,
            anchorButton = anchorButton,
            targetWidthDp = 260
        )

        llModalListContainer.removeAllViews()
        val inflater = LayoutInflater.from(context)

        data class AudioTrackItem(
            val group: Tracks.Group,
            val trackIndex: Int,
            val format: Format,
            val lang: String,
            val langDisplay: String,
            val isSelected: Boolean,
            val titleText: String,
            val badgePrimary: String,
            val badgeSecondary: String,
            val priority: Int
        )

        val trackItems = mutableListOf<AudioTrackItem>()

        for (group in exo.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = format.language?.uppercase(Locale.ROOT) ?: "UND"
                    val isSelected = group.isTrackSelected(i)
                    val langDisplay = getLanguageDisplayName(lang)
                    val mime = format.sampleMimeType ?: ""

                    val isCommentary = (format.roleFlags and C.ROLE_FLAG_COMMENTARY) != 0 ||
                            format.label?.contains("commentary", ignoreCase = true) == true ||
                            format.label?.contains("comentario", ignoreCase = true) == true

                    val ch = when (format.channelCount) {
                        8 -> "7.1"
                        6 -> "5.1"
                        2 -> "2.0"
                        1 -> "1.0"
                        else -> if (format.channelCount > 0) "${format.channelCount} Ch" else ""
                    }

                    val titleText = if (isCommentary) "$langDisplay (Commentary)" else langDisplay
                    val badgePrimary: String
                    val badgeSecondary = if (isCommentary) "COMMENTARY" else ch
                    var score = getLanguagePriority(lang)

                    val labelStr = format.label ?: ""
                    when {
                        mime.contains("truehd", ignoreCase = true) || mime.contains("true-hd", ignoreCase = true) -> {
                            badgePrimary = if (labelStr.contains("atmos", ignoreCase = true)) "TRUEHD ATMOS" else "DOLBY TRUEHD"
                            score += 15
                        }
                        mime.contains("dts", ignoreCase = true) -> {
                            badgePrimary = when {
                                labelStr.contains("dts:x", ignoreCase = true) || labelStr.contains("dtsx", ignoreCase = true) -> "DTS:X"
                                mime.contains("dts.hd", ignoreCase = true) || mime.contains("dtshd", ignoreCase = true) || labelStr.contains("hd", ignoreCase = true) -> "DTS-HD MA"
                                else -> "DTS"
                            }
                            score += 14
                        }
                        mime.contains("eac3-joc", ignoreCase = true) -> {
                            badgePrimary = "DD+ ATMOS"
                            score += 12
                        }
                        mime.contains("eac3", ignoreCase = true) -> {
                            badgePrimary = if (labelStr.contains("atmos", ignoreCase = true)) "DD+ ATMOS" else "DOLBY DIGITAL+"
                            score += 8
                        }
                        mime.contains("ac3", ignoreCase = true) -> {
                            badgePrimary = "DOLBY AUDIO"
                            score += 5
                        }
                        else -> {
                            badgePrimary = "PCM"
                            score += 10
                        }
                    }
                    score += format.channelCount * 2

                    trackItems.add(
                        AudioTrackItem(group, i, format, lang, langDisplay, isSelected, titleText, badgePrimary, badgeSecondary, score)
                    )
                }
            }
        }

        trackItems.sortByDescending { it.priority }

        for (item in trackItems) {
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            val ivCheck = itemView.findViewById<ImageView>(R.id.ivTrackCheck)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            val tvBadgePrimary = itemView.findViewById<TextView>(R.id.tvTrackBadge)
            val tvBadgeSecondary = itemView.findViewById<TextView>(R.id.tvTrackBadgeSecondary)

            ivCheck.visibility = if (item.isSelected) View.VISIBLE else View.INVISIBLE
            itemView.isSelected = item.isSelected
            tvTitle.text = item.titleText

            tvBadgePrimary.text = item.badgePrimary
            tvBadgePrimary.visibility = View.VISIBLE

            if (item.badgeSecondary.isNotBlank()) {
                tvBadgeSecondary.text = item.badgeSecondary
                tvBadgeSecondary.visibility = View.VISIBLE
            } else {
                tvBadgeSecondary.visibility = View.GONE
            }

            itemView.setOnFocusChangeListener { v, hasFocus ->
                tvTitle.isSelected = hasFocus
                if (hasFocus) {
                    (modalCard as? LiquidGlassLayout)?.energize(v.x + v.width * 0.5f, v.y + v.height * 0.5f)
                }
            }

            val override = TrackSelectionOverride(item.group.mediaTrackGroup, listOf(item.trackIndex))
            itemView.setOnClickListener {
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .addOverride(override)
                    .build()
                onAudioSelected?.invoke(item.format)
                for (j in 0 until llModalListContainer.childCount) {
                    val child = llModalListContainer.getChildAt(j)
                    val check = child.findViewById<ImageView>(R.id.ivTrackCheck)
                    check?.visibility = if (child == itemView) View.VISIBLE else View.INVISIBLE
                    child.isSelected = (child == itemView)
                }
                tvModalBadge.text = item.badgePrimary
                tvModalBadge.visibility = View.VISIBLE
            }

            llModalListContainer.addView(itemView)
        }

        val focusTarget = (0 until llModalListContainer.childCount)
            .map { llModalListContainer.getChildAt(it) }
            .firstOrNull { it.findViewById<ImageView>(R.id.ivTrackCheck)?.visibility == View.VISIBLE }
            ?: llModalListContainer.getChildAt(0)
        focusTarget?.requestFocus()
        focusTarget?.post { focusTarget.requestFocus() }
    }

    fun openSubtitlesModal(
        exo: ExoPlayer,
        anchorButton: View,
        onSubtitleSelected: (Format?) -> Unit
    ) {
        activeModalType = ModalType.SUBTITLES
        val currentSubBadge = getSelectedSubtitleBadge(exo)

        showModal(
            title = "Subtitles",
            badge = currentSubBadge,
            anchorButton = anchorButton,
            targetWidthDp = 276
        )

        llModalListContainer.removeAllViews()
        val inflater = LayoutInflater.from(context)

        var hasActiveTextTrack = false
        for (group in exo.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT && group.isSelected) {
                hasActiveTextTrack = true
                break
            }
        }

        val offItem = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
        val ivCheckOff = offItem.findViewById<ImageView>(R.id.ivTrackCheck)
        val tvTitleOff = offItem.findViewById<TextView>(R.id.tvTrackTitle)
        val tvSubOff = offItem.findViewById<TextView>(R.id.tvTrackSubtext)
        tvTitleOff.text = "Off"
        tvSubOff.visibility = View.GONE
        ivCheckOff.visibility = if (!hasActiveTextTrack) View.VISIBLE else View.INVISIBLE
        offItem.isSelected = !hasActiveTextTrack

        offItem.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                (modalCard as? LiquidGlassLayout)?.energize(v.x + v.width * 0.5f, v.y + v.height * 0.5f)
            }
        }
        offItem.setOnClickListener {
            onSubtitleSelected(null)
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()

            for (j in 0 until llModalListContainer.childCount) {
                val child = llModalListContainer.getChildAt(j)
                child.findViewById<ImageView>(R.id.ivTrackCheck)?.visibility =
                    if (child == offItem) View.VISIBLE else View.INVISIBLE
                child.isSelected = (child == offItem)
            }
        }
        llModalListContainer.addView(offItem)

        data class SubTrackItem(
            val group: Tracks.Group,
            val trackIndex: Int,
            val format: Format,
            val langDisplay: String,
            val isSelected: Boolean,
            val titleText: String,
            val badgePrimary: String,
            val badgeSecondary: String,
            val priority: Int
        )

        val subItems = mutableListOf<SubTrackItem>()

        for (group in exo.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = format.language?.uppercase(Locale.ROOT) ?: "UND"
                    val isSelected = hasActiveTextTrack && group.isTrackSelected(i)
                    val langDisplay = getLanguageDisplayName(lang)
                    val mime = format.sampleMimeType ?: ""

                    val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                            format.label?.contains("forced", ignoreCase = true) == true ||
                            format.label?.contains("forzado", ignoreCase = true) == true

                    val isSdh = (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 ||
                            format.label?.contains("sdh", ignoreCase = true) == true

                    val badgePrimary = when {
                        mime.contains("pgs", ignoreCase = true) -> "PGS"
                        mime.contains("x-subrip", ignoreCase = true) || mime.contains("subrip", ignoreCase = true) -> "SRT"
                        mime.contains("ssa", ignoreCase = true) || mime.contains("ass", ignoreCase = true) -> "ASS"
                        mime.contains("vtt", ignoreCase = true) -> "VTT"
                        else -> "SUB"
                    }

                    val badgeSecondary = when {
                        isForced -> "FORCED"
                        isSdh -> "CC"
                        else -> ""
                    }

                    var score = getLanguagePriority(lang)
                    if (mime.contains("pgs", ignoreCase = true)) score += 20
                    if (isForced) score += 5

                    val titleText = when {
                        isForced -> "$langDisplay (Forced)"
                        isSdh -> "$langDisplay (CC)"
                        else -> langDisplay
                    }

                    subItems.add(
                        SubTrackItem(group, i, format, langDisplay, isSelected, titleText, badgePrimary, badgeSecondary, score)
                    )
                }
            }
        }

        subItems.sortByDescending { it.priority }

        for (item in subItems) {
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            val ivCheck = itemView.findViewById<ImageView>(R.id.ivTrackCheck)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            val tvBadgePrimary = itemView.findViewById<TextView>(R.id.tvTrackBadge)
            val tvBadgeSecondary = itemView.findViewById<TextView>(R.id.tvTrackBadgeSecondary)

            ivCheck.visibility = if (item.isSelected) View.VISIBLE else View.INVISIBLE
            itemView.isSelected = item.isSelected
            tvTitle.text = item.titleText

            tvBadgePrimary.text = item.badgePrimary
            tvBadgePrimary.visibility = View.VISIBLE

            if (item.badgeSecondary.isNotBlank()) {
                tvBadgeSecondary.text = item.badgeSecondary
                tvBadgeSecondary.visibility = View.VISIBLE
            } else {
                tvBadgeSecondary.visibility = View.GONE
            }

            itemView.setOnFocusChangeListener { v, hasFocus ->
                tvTitle.isSelected = hasFocus
                if (hasFocus) {
                    (modalCard as? LiquidGlassLayout)?.energize(v.x + v.width * 0.5f, v.y + v.height * 0.5f)
                }
            }

            val override = TrackSelectionOverride(item.group.mediaTrackGroup, listOf(item.trackIndex))
            itemView.setOnClickListener {
                onSubtitleSelected(item.format)
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .addOverride(override)
                    .build()

                for (j in 0 until llModalListContainer.childCount) {
                    val child = llModalListContainer.getChildAt(j)
                    val check = child.findViewById<ImageView>(R.id.ivTrackCheck)
                    check?.visibility = if (child == itemView) View.VISIBLE else View.INVISIBLE
                    child.isSelected = (child == itemView)
                }
            }

            llModalListContainer.addView(itemView)
        }

        val focusTarget = (0 until llModalListContainer.childCount)
            .map { llModalListContainer.getChildAt(it) }
            .firstOrNull { it.findViewById<ImageView>(R.id.ivTrackCheck)?.visibility == View.VISIBLE }
            ?: offItem
        focusTarget.requestFocus()
        focusTarget.post { focusTarget.requestFocus() }
    }

    fun openChaptersModal(
        chapters: List<MovieChapter>,
        currentPositionMs: Long,
        anchorButton: View,
        onChapterSelected: (MovieChapter) -> Unit
    ) {
        activeModalType = ModalType.CHAPTERS

        val activeChapIndex = chapters.indexOfLast { currentPositionMs >= it.startTimeMs }.coerceAtLeast(0)
        val activeBadge = "Ch. ${activeChapIndex + 1} of ${chapters.size}"

        showModal(
            title = "Chapters",
            badge = activeBadge,
            anchorButton = anchorButton,
            targetWidthDp = 276
        )

        llModalListContainer.removeAllViews()
        val inflater = LayoutInflater.from(context)

        for ((idx, chap) in chapters.withIndex()) {
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            val ivCheck = itemView.findViewById<ImageView>(R.id.ivTrackCheck)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            val tvSub = itemView.findViewById<TextView>(R.id.tvTrackSubtext)
            val tvBadge = itemView.findViewById<TextView>(R.id.tvTrackBadge)

            val isCurrent = idx == activeChapIndex
            ivCheck.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            itemView.isSelected = isCurrent

            tvTitle.text = "${idx + 1}. ${chap.title}"
            tvTitle.isSelected = isCurrent
            tvSub.text = formatTimeMs(chap.startTimeMs)
            tvSub.visibility = View.VISIBLE

            tvBadge.visibility = View.GONE

            itemView.setOnFocusChangeListener { v, hasFocus ->
                tvTitle.isSelected = hasFocus
                if (hasFocus) {
                    (modalCard as? LiquidGlassLayout)?.energize(v.x + v.width * 0.5f, v.y + v.height * 0.5f)
                }
            }

            itemView.setOnClickListener {
                onChapterSelected(chap)
                closeModal { anchorButton.requestFocus() }
            }

            llModalListContainer.addView(itemView)
        }

        val focusTarget = (0 until llModalListContainer.childCount)
            .map { llModalListContainer.getChildAt(it) }
            .getOrNull(activeChapIndex) ?: llModalListContainer.getChildAt(0)
        focusTarget?.requestFocus()
        focusTarget?.post { focusTarget.requestFocus() }
    }

    fun openDvModeModal(
        currentDynamicRange: VideoDynamicRange,
        currentDvProfile: String,
        currentDvMode: DolbyVisionProcessingMode,
        movieTitle: String,
        anchorButton: View,
        onModeSelected: (DolbyVisionProcessingMode) -> Unit
    ) {
        activeModalType = ModalType.DV_MODE

        if (currentDynamicRange == VideoDynamicRange.HDR10) {
            showModal(title = "HDR Video", badge = "HDR10 DIRECT", anchorButton = anchorButton, targetWidthDp = 270)
            renderSingleStaticMode("HDR10 Direct Play", "SMPTE ST 2084 PQ • BT.2020 10-bit\nHardware decoded directly by TV panel", "ST 2084")
            return
        }

        if (currentDynamicRange == VideoDynamicRange.HLG) {
            showModal(title = "HDR Video", badge = "HLG DIRECT", anchorButton = anchorButton, targetWidthDp = 270)
            renderSingleStaticMode("HLG Direct Play", "Hybrid Log-Gamma • BT.2100\nBroadcast HDR decoded directly by TV panel", "BT.2100")
            return
        }

        if (currentDynamicRange == VideoDynamicRange.SDR) {
            showModal(title = "Direct Play (SDR)", badge = "SDR BT.709", anchorButton = anchorButton, targetWidthDp = 270)
            renderSingleStaticMode("Direct Play 1080p SDR (Native Rec.709)", "Standard Dynamic Range 8-bit\nNative BT.709 color gamut", "SDR")
            return
        }

        val isNativeP8 = currentDvProfile == "P8.1" && movieTitle.contains("Nightmare", ignoreCase = true)
        if (isNativeP8) {
            showModal(title = "Dolby Vision", badge = "NATIVE DV P8.1", anchorButton = anchorButton, targetWidthDp = 270)
            renderSingleStaticMode("Native Dolby Vision Profile 8.1 (Direct Play HW)", "Single-layer HEVC with dynamic RPU metadata", "P8.1 DIRECT")
            return
        }

        showModal(
            title = "Dolby Vision",
            badge = currentDvMode.badgeText,
            anchorButton = anchorButton
        )

        llModalListContainer.removeAllViews()
        val inflater = LayoutInflater.from(context)

        for (mode in DolbyVisionProcessingMode.entries) {
            val isSelected = (mode == currentDvMode)
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            val ivCheck = itemView.findViewById<ImageView>(R.id.ivTrackCheck)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            val tvBadge = itemView.findViewById<TextView>(R.id.tvTrackBadge)

            ivCheck.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            itemView.isSelected = isSelected

            tvTitle.text = mode.displayName
            tvTitle.isSelected = isSelected

            tvBadge.text = mode.badgeText
            tvBadge.visibility = View.VISIBLE

            itemView.setOnFocusChangeListener { v, hasFocus ->
                tvTitle.isSelected = hasFocus
                if (hasFocus) {
                    (modalCard as? LiquidGlassLayout)?.energize(v.x + v.width * 0.5f, v.y + v.height * 0.5f)
                }
            }

            itemView.setOnClickListener {
                onModeSelected(mode)
                closeModal { anchorButton.requestFocus() }
            }

            llModalListContainer.addView(itemView)
        }

        val focusTarget = (0 until llModalListContainer.childCount)
            .map { llModalListContainer.getChildAt(it) }
            .firstOrNull { it.findViewById<ImageView>(R.id.ivTrackCheck)?.visibility == View.VISIBLE }
            ?: llModalListContainer.getChildAt(0)
        focusTarget?.requestFocus()
        focusTarget?.post { focusTarget.requestFocus() }
    }

    private fun renderSingleStaticMode(title: String, subtitle: String, badge: String) {
        llModalListContainer.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
        itemView.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.VISIBLE
        val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
        val tvSub = itemView.findViewById<TextView>(R.id.tvTrackSubtext)
        val tvBadge = itemView.findViewById<TextView>(R.id.tvTrackBadge)

        tvTitle.text = title
        tvTitle.isSelected = true
        tvSub.text = subtitle
        tvSub.visibility = View.VISIBLE
        tvBadge.text = badge
        tvBadge.visibility = View.VISIBLE

        itemView.setOnClickListener { closeModal() }
        llModalListContainer.addView(itemView)
        itemView.requestFocus()
    }

    fun autoSelectPgsSubtitles(exo: ExoPlayer, tracks: Tracks) {
        var currentSelectedLang: String? = null
        var isCurrentSelectedPgs = false

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val format = group.getTrackFormat(i)
                        currentSelectedLang = format.language?.lowercase(Locale.ROOT)
                        isCurrentSelectedPgs = format.sampleMimeType?.contains("pgs", ignoreCase = true) == true
                        break
                    }
                }
            }
        }

        if (isCurrentSelectedPgs) return

        var bestPgsGroup: Tracks.Group? = null
        var bestPgsIndex = -1
        val targetLang = currentSelectedLang ?: "eng"

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isPgs = format.sampleMimeType?.contains("pgs", ignoreCase = true) == true
                    if (isPgs) {
                        val lang = format.language?.lowercase(Locale.ROOT)
                        if (lang == targetLang) {
                            bestPgsGroup = group
                            bestPgsIndex = i
                            break
                        } else if (bestPgsGroup == null) {
                            bestPgsGroup = group
                            bestPgsIndex = i
                        }
                    }
                }
                if (bestPgsGroup != null && bestPgsIndex != -1 &&
                    bestPgsGroup.getTrackFormat(bestPgsIndex).language?.lowercase(Locale.ROOT) == targetLang
                ) {
                    break
                }
            }
        }

        if (bestPgsGroup != null && bestPgsIndex != -1) {
            val override = TrackSelectionOverride(bestPgsGroup.mediaTrackGroup, listOf(bestPgsIndex))
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .addOverride(override)
                .build()
        }
    }

    private fun getSelectedSubtitleBadge(exo: ExoPlayer): String {
        for (group in exo.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val format = group.getTrackFormat(i)
                        val mime = format.sampleMimeType ?: ""
                        return when {
                            mime.contains("pgs", ignoreCase = true) -> "PGS"
                            mime.contains("x-subrip", ignoreCase = true) || mime.contains("subrip", ignoreCase = true) -> "SRT"
                            mime.contains("ssa", ignoreCase = true) || mime.contains("ass", ignoreCase = true) -> "ASS"
                            mime.contains("vtt", ignoreCase = true) -> "VTT"
                            else -> "SUB"
                        }
                    }
                }
            }
        }
        return "OFF"
    }

    private fun getLanguageDisplayName(langCode: String): String {
        val l = langCode.trim().uppercase(Locale.ROOT)
        return when {
            l == "SPA" || l == "ES" || l == "LAT" || l == "ES-419" || l == "SPANISH" -> "Spanish"
            l == "ENG" || l == "EN" || l == "ENGLISH" -> "English"
            l == "FRA" || l == "FR" || l == "FRE" || l == "FRENCH" -> "French"
            l == "DEU" || l == "GER" || l == "DE" || l == "GERMAN" -> "German"
            l == "ITA" || l == "IT" || l == "ITALIAN" -> "Italian"
            l == "POR" || l == "PT" || l == "PORTUGUESE" -> "Portuguese"
            l == "JPN" || l == "JA" || l == "JAPANESE" -> "Japanese"
            l == "ZHO" || l == "ZH" || l == "CHI" || l == "CHINESE" -> "Chinese"
            l == "KOR" || l == "KO" || l == "KOREAN" -> "Korean"
            l == "DAN" || l == "DA" || l == "DANISH" -> "Danish"
            l == "UND" || l.isBlank() -> "Main Track"
            else -> Locale(l).displayLanguage.takeIf { it.isNotBlank() } ?: langCode
        }
    }

    private fun getLanguagePriority(lang: String): Int {
        val l = lang.trim().uppercase(Locale.ROOT)
        return when {
            l == "SPA" || l == "ES" || l == "LAT" || l == "ES-419" || l == "SPANISH" -> 100
            l == "ENG" || l == "EN" || l == "ENGLISH" -> 90
            l == "FRA" || l == "FR" || l == "FRE" || l == "FRENCH" -> 50
            l == "DEU" || l == "GER" || l == "DE" || l == "GERMAN" -> 40
            l == "ITA" || l == "IT" || l == "ITALIAN" -> 35
            l == "POR" || l == "PT" || l == "PORTUGUESE" -> 30
            l == "JPN" || l == "JA" || l == "JAPANESE" -> 25
            l == "ZHO" || l == "ZH" || l == "CHI" || l == "CHINESE" -> 20
            l == "KOR" || l == "KO" || l == "KOREAN" -> 18
            l == "DAN" || l == "DA" || l == "DANISH" -> 15
            else -> 10
        }
    }

    private fun formatTimeMs(timeMs: Long): String {
        val totalSec = (timeMs / 1000).coerceAtLeast(0)
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
