package com.player.androidtv

import android.app.Activity
import com.player.androidtv.settings.AppSettings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.player.androidtv.databinding.ActivityPlaybackBinding
import com.player.androidtv.ui.LiquidGlassHelper
import com.player.androidtv.ui.LiquidGlassLayout
import com.player.androidtv.ui.modal.PlaybackModalController
import com.player.coremedia.jellyfin.JellyfinDirectPlayClient
import com.player.coremedia.jellyfin.JellyfinMetadataMapper
import com.player.coremedia.player.DolbyVisionProcessingMode
import com.player.coremedia.player.RemuxExoPlayerFactory
import com.player.coremedia.security.NetworkUrlSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "remux_player_prefs"
        private const val PREF_KEY_DV_MODE = "dv_processing_mode"
        private const val HUD_HIDE_DELAY_MS = 3500L
    }

    private lateinit var binding: ActivityPlaybackBinding
    private lateinit var modalController: PlaybackModalController

    private var player: ExoPlayer? = null
    private var userChangedSubtitles = false

    // ViewBinding accessors for zero-overhead migration
    private val playerView get() = binding.playerView
    private val hudOverlay get() = binding.hudOverlay
    private val topBar get() = binding.topBar
    private val llPlayerHeaderRow get() = binding.llPlayerHeaderRow
    private val tvPlayerYear get() = binding.tvPlayerYear
    private val tvPlayerTitle get() = binding.tvPlayerTitle
    private val tvPlayerChapter get() = binding.tvPlayerChapter
    private val tvCurrentTime get() = binding.tvScrubTime
    private val tvRemainingTime get() = binding.tvRemainingTime
    private val timelineSeekBar get() = binding.timelineSeekBar
    private val chapterTicksView get() = binding.chapterTicksView
    private val llActionsRow get() = binding.llActionsRow
    private val llScrubPreviewContainer get() = binding.llScrubPreviewContainer
    private val tvScrubChapterTitle get() = binding.tvScrubChapterTitle
    private val cvScrubCard get() = binding.cvScrubCard
    private val ivScrubThumbnail get() = binding.ivScrubThumbnail
    private val vScrubPinLine get() = binding.vScrubPinLine
    private val llScrubTimeBadgeContainer get() = binding.llScrubTimeBadgeContainer
    private val tvScrubTime get() = binding.tvScrubTime
    private val tvScrubSpeedLeft get() = binding.tvScrubSpeedLeft
    private val tvScrubSpeedRight get() = binding.tvScrubSpeedRight
    private val tvScrubSpeedAboveCard get() = binding.tvScrubSpeedAboveCard
    private val centerPlayPauseIndicator get() = binding.centerPlayPauseIndicator
    private val ivCenterPlayPauseIcon get() = binding.ivCenterPlayPauseIcon
    private val btnBack get() = binding.btnBack
    private val btnDvMode get() = binding.btnDvMode
    private val btnSubtitles get() = binding.btnSubtitles
    private val btnAudioTrack get() = binding.btnAudioTrack
    private val btnChapters get() = binding.btnChapters
    private val loadingSpinner get() = binding.loadingSpinner
    private val badgeResolution get() = binding.badgeResolution
    private val badgeHdr get() = binding.badgeHdr
    private val badgeAudio get() = binding.badgeAudio
    private val badgeChannels get() = binding.badgeChannels
    private val badgeAfr get() = binding.badgeAfr
    private val modalOverlay get() = binding.modalOverlay
    private val modalCard get() = binding.modalCard
    private val tvModalTitle get() = binding.tvModalTitle
    private val tvModalBadge get() = binding.tvModalBadge
    private val llModalListContainer get() = binding.llModalListContainer

    private var hideQuickSkipBadgeRunnable: Runnable? = null
    private var pendingScrubDirection = 0
    private val scrubTransitionRunnable = Runnable {
        if (pendingScrubDirection != 0 && !isScrubbing) {
            hideQuickSkipBadgeRunnable?.let { handler.removeCallbacks(it) }
            val dir = pendingScrubDirection
            handleScrubKey(dir, repeatCount = 0)
        }
    }

    private val chapterThumbnailMap = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()


    data class MovieChapter(
        val title: String,
        val startTimeMs: Long
    )
    private var currentChapters: List<MovieChapter> = emptyList()

    // PGS Theatrical Master Reference Subtitles (1920x1080 Canvas):
    // Fontsize: 50px (50 / 1080 = 0.0463f)
    // MarginV: 155px (155 / 1080 = 0.1435f) -> Positioned above bottom letterbox in 2.40:1 Cinemascope
    // Luminance: SMPTE Video White #EBEBEB, Solid black outline (no drop shadow) identical to PGS Blu-ray
    private val subTextSizeFraction = 50f / 1080f
    private val subBottomPaddingFraction = 155f / 1080f

    // Apple TV Floating Popups
    enum class VideoDynamicRange { SDR, HDR10, HLG, DOLBY_VISION }
    private var currentDynamicRange = VideoDynamicRange.SDR
    private var currentDvProfile = "P8.1"

    private var streamUrl: String = ""
    private var movieTitle: String = ""
    private var movieYear: String = "1981"
    private var movieDuration: String = "1h 55m"
    private var movieGenre: String = "Movie · Action · Adventure"
    private var movieSynopsis: String = ""
    private var movieDirector: String = "Steven Spielberg"
    private var movieActors: String = "Harrison Ford, Karen Allen"
    private var movieSize: String = "56.73 GB"
    private var movieVideoCodec: String = "DOLBY VISION P8.1"
    private var movieAudioCodec: String = "DOLBY ATMOS"
    private var explicitIs4k: Boolean? = null
    private var explicitHdrType: String? = null
    private var explicitDvProfile: String? = null
    private var explicitAudioBadge: String? = null
    private var explicitChannelsBadge: String? = null
    private var movieLogoUrl: String? = null
    private var episodeSubtitle: String? = null
    private val logoImageCache by lazy { com.player.androidtv.media.ImageCacheHelper() }
    private var explicitChapters: List<MovieChapter> = emptyList()

    private var jellyfinServerUrl: String? = null
    private var jellyfinToken: String? = null
    private var jellyfinItemId: String? = null
    private var jellyfinMediaSourceId: String? = null
    private var jellyfinSeriesId: String? = null
    private var jellyfinSeriesName: String? = null
    private var hasAppliedInitialTrackSelection = false
    private var jellyfinClient: JellyfinDirectPlayClient? = null
    private var sessionReportedStarted = false
    private var lastServerProgressReportAtMs = 0L
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentDvMode: DolbyVisionProcessingMode = DolbyVisionProcessingMode.P8_TRANSMUTE
    private val handler = Handler(Looper.getMainLooper())

    // Apple TV Scrubbing Engine (1x, 2x, 3x, 4x, and rewind -1x, -2x, -3x, -4x)
    private var isScrubbing = false
    private var wasPlayingBeforeScrub = false
    private var originalPositionBeforeScrub = 0L
    private var isSeeking = false
    private var scrubTargetMs = 0L
    private var scrubSpeed = 0 // 1..5 or -1..-5

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isScrubbing && !isSeeking) {
                player?.let { p ->
                    val current = p.currentPosition
                    val duration = p.duration

                    if (duration > 0) {
                        if (currentChapters.isEmpty()) {
                            initMovieChapters(duration)
                        }
                        val progress = ((current * 1000) / duration).toInt()
                        timelineSeekBar.progress = progress
                        tvCurrentTime.text = formatTimeMs(current)
                        val remaining = (duration - current).coerceAtLeast(0)
                        tvRemainingTime.text = "-${formatTimeMs(remaining)}"

                        // Synchronize current chapter on screen
                        val chap = getCurrentChapter(current)
                        if (chap != null) {
                            tvPlayerChapter.text = chap.title
                            tvPlayerChapter.visibility = View.VISIBLE
                        } else {
                            tvPlayerChapter.visibility = View.GONE
                        }

                        // Align time container directly underneath timeline thumb needle
                        val ratio = (current.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                        val usableWidth = (timelineSeekBar.width - timelineSeekBar.paddingStart - timelineSeekBar.paddingEnd).coerceAtLeast(1)
                        val relThumbX = timelineSeekBar.paddingStart + (ratio * usableWidth)
                        val badgeWidth = llScrubTimeBadgeContainer.width.takeIf { it > 0 } ?: (50 * resources.displayMetrics.density).toInt()
                        val maxOffset = (timelineSeekBar.width - badgeWidth - 75 * resources.displayMetrics.density).coerceAtLeast(0f)
                        llScrubTimeBadgeContainer.translationX = (relThumbX - badgeWidth / 2f).coerceIn(0f, maxOffset)
                    } else {
                        tvCurrentTime.text = formatTimeMs(current)
                        tvRemainingTime.text = "--:--:--"
                    }
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private val hideHudRunnable = Runnable {
        if (player?.isPlaying == true && modalOverlay.visibility != View.VISIBLE && !isScrubbing) {
            hudOverlay.animate().alpha(0f).setDuration(300).withEndAction {
                hudOverlay.visibility = View.GONE
                timelineSeekBar.requestFocus()
            }.start()
        }
    }

    private fun executeQuickSkip(direction: Int) {
        val p = player ?: return
        val duration = if (p.duration > 0) p.duration else 6900000L
        val step = 10000L * (if (direction > 0) 1 else -1)
        val currentPos = if (p.currentPosition > 0) p.currentPosition else (timelineSeekBar.progress * duration / 1000L)
        val targetPos = (currentPos + step).coerceIn(0L, duration)
        p.seekTo(targetPos)
        showHud()
        timelineSeekBar.progress = ((targetPos * 1000) / duration).toInt()
        tvCurrentTime.text = formatTimeMs(targetPos)
        val remaining = (duration - targetPos).coerceAtLeast(0)
        tvRemainingTime.text = "-${formatTimeMs(remaining)}"

        hideQuickSkipBadgeRunnable?.let { handler.removeCallbacks(it) }
        val targetBadge = if (direction > 0) tvScrubSpeedRight else tvScrubSpeedLeft
        val otherBadge = if (direction > 0) tvScrubSpeedLeft else tvScrubSpeedRight
        otherBadge.visibility = View.GONE
        targetBadge.text = if (direction > 0) "+10s" else "-10s"
        targetBadge.alpha = 1f
        targetBadge.visibility = View.VISIBLE

        val ratio = (targetPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val usableWidth = (timelineSeekBar.width - timelineSeekBar.paddingStart - timelineSeekBar.paddingEnd).coerceAtLeast(1)
        val relThumbX = timelineSeekBar.paddingStart + (ratio * usableWidth)
        val badgeWidth = llScrubTimeBadgeContainer.width.takeIf { it > 0 } ?: (70 * resources.displayMetrics.density).toInt()
        val maxOffset = (timelineSeekBar.width - badgeWidth - 75 * resources.displayMetrics.density).coerceAtLeast(0f)
        llScrubTimeBadgeContainer.translationX = (relThumbX - badgeWidth / 2f).coerceIn(0f, maxOffset)

        hideQuickSkipBadgeRunnable = Runnable {
            if (!isScrubbing) {
                targetBadge.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction {
                        targetBadge.visibility = View.GONE
                    }
                    .start()
            }
        }
        handler.postDelayed(hideQuickSkipBadgeRunnable!!, 1200)
        savePlaybackProgress()
        scheduleHudHide()
    }

    private fun initMovieChapters(durationMs: Long) {
        currentChapters = when {
            explicitChapters.isNotEmpty() -> explicitChapters
            durationMs > 0 -> {
                val numChapters = 10
                val step = durationMs / numChapters
                (0 until numChapters).map { i ->
                    MovieChapter("Chapter ${i + 1}", i * step)
                }
            }
            else -> emptyList()
        }

        chapterTicksView.setChapters(currentChapters.map { it.startTimeMs }, durationMs)
        preloadJellyfinChapterThumbnailsAsync()
    }

    private fun getCurrentChapter(timeMs: Long): MovieChapter? {
        if (currentChapters.isEmpty()) return null
        return currentChapters.lastOrNull { timeMs >= it.startTimeMs } ?: currentChapters.firstOrNull()
    }

    private fun jumpToPreviousChapter() {
        if (currentChapters.isEmpty()) return
        val current = if (isScrubbing) scrubTargetMs else (player?.currentPosition ?: 0L)
        val activeIndex = currentChapters.indexOfLast { current >= it.startTimeMs }
        val targetChapter = if (activeIndex > 0) {
            val currentChap = currentChapters[activeIndex]
            if (current - currentChap.startTimeMs > 3000L) {
                currentChap
            } else {
                currentChapters[activeIndex - 1]
            }
        } else {
            currentChapters.firstOrNull()
        } ?: return

        executeChapterSeek(targetChapter)
    }

    private fun jumpToNextChapter() {
        if (currentChapters.isEmpty()) return
        val current = if (isScrubbing) scrubTargetMs else (player?.currentPosition ?: 0L)
        val activeIndex = currentChapters.indexOfLast { current >= it.startTimeMs }
        if (activeIndex in 0 until currentChapters.size - 1) {
            executeChapterSeek(currentChapters[activeIndex + 1])
        }
    }

    private fun executeChapterSeek(targetChapter: MovieChapter) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: return
        val targetMs = targetChapter.startTimeMs.coerceIn(0L, duration)

        if (isScrubbing) {
            scrubTargetMs = targetMs
            timelineSeekBar.progress = ((scrubTargetMs * 1000) / duration).toInt()
            tvScrubTime.text = formatTimeMs(scrubTargetMs)
            tvCurrentTime.text = formatTimeMs(scrubTargetMs)
            val remaining = (duration - scrubTargetMs).coerceAtLeast(0)
            tvRemainingTime.text = "-${formatTimeMs(remaining)}"
            updateTimelineTimeFocusStyles(true)
            tvScrubChapterTitle.text = targetChapter.title
            tvScrubChapterTitle.visibility = View.VISIBLE
            updateScrubPreviewPosition(scrubTargetMs, duration)
            loadScrubThumbnail(scrubTargetMs)
        } else {
            isSeeking = true
            p.seekTo(targetMs)
            timelineSeekBar.progress = ((targetMs * 1000) / duration).toInt()
            tvCurrentTime.text = formatTimeMs(targetMs)
            val remaining = (duration - targetMs).coerceAtLeast(0)
            tvRemainingTime.text = "-${formatTimeMs(remaining)}"
            tvPlayerChapter.text = targetChapter.title
            tvPlayerChapter.visibility = View.VISIBLE
            showHud()
        }
    }

    private fun savePlaybackProgress() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur > 0L && movieTitle.isNotEmpty()) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val normTitle = movieTitle.lowercase().trim()
            if (pos > dur - 30000L) {
                prefs.edit()
                    .remove("progress_${movieTitle}")
                    .remove("duration_${movieTitle}")
                    .remove("progress_${normTitle}")
                    .remove("duration_${normTitle}")
                    .apply()
            } else if (pos > 5000L) {
                val editor = prefs.edit()
                    .putLong("progress_${movieTitle}", pos)
                    .putLong("duration_${movieTitle}", dur)
                    .putLong("progress_${normTitle}", pos)
                    .putLong("duration_${normTitle}", dur)
                    .remove("reset_${normTitle}")
                jellyfinItemId?.let { editor.remove("reset_$it") }
                editor.apply()
            }
        }
        reportProgressToJellyfin(pos, !p.isPlaying)
    }

    private fun reportProgressToJellyfin(positionMs: Long, isPaused: Boolean) {
        val client = jellyfinClient ?: return
        val itemId = jellyfinItemId ?: return
        val token = jellyfinToken ?: return
        val now = System.currentTimeMillis()
        if (!isPaused && now - lastServerProgressReportAtMs < 5000L) return
        lastServerProgressReportAtMs = now
        val ticks = JellyfinMetadataMapper.msToTicks(positionMs)
        ioScope.launch {
            client.reportProgress(itemId, ticks, token, isPaused)
        }
    }

    private fun setHudScrubbingMode(scrubbing: Boolean) {
        if (scrubbing) {
            topBar.visibility = View.GONE
            llPlayerHeaderRow.visibility = View.GONE
            llActionsRow.visibility = View.GONE
            llScrubPreviewContainer.visibility = View.VISIBLE
            llScrubPreviewContainer.alpha = 1f
            timelineSeekBar.thumb = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            updateTimelineTimeFocusStyles(true)
        } else {
            cvScrubCard.animate().cancel()
            vScrubPinLine.animate().cancel()
            tvScrubSpeedAboveCard.animate().cancel()
            tvScrubSpeedAboveCard.visibility = View.GONE
            tvScrubChapterTitle.animate().cancel()
            llScrubTimeBadgeContainer.animate().cancel()
            llScrubPreviewContainer.visibility = View.GONE
            topBar.visibility = View.VISIBLE
            llPlayerHeaderRow.visibility = View.VISIBLE
            llActionsRow.visibility = View.VISIBLE
            topBar.alpha = 1f
            llPlayerHeaderRow.alpha = 1f
            llActionsRow.alpha = 1f
            timelineSeekBar.thumb = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.seekbar_thumb_apple)
            chapterTicksView.setOriginalPositionRatio(null)
            updateTimelineTimeFocusStyles(timelineSeekBar.hasFocus())
        }
    }

    private val scrubTickerRunnable = object : Runnable {
        override fun run() {
            if (!isScrubbing || scrubSpeed == 0) return
            val p = player ?: return
            val duration = p.duration
            if (duration <= 0) return

            val speedMagnitude = Math.abs(scrubSpeed)
            val sign = if (scrubSpeed > 0) 1 else -1
            val mult = AppSettings.getScrubSpeedPreset(this@PlaybackActivity).multiplier
            val baseStep = when (speedMagnitude) {
                1 -> 6000L
                2 -> 15000L
                3 -> 35000L
                4 -> 75000L
                else -> 160000L // 5x
            }
            val stepMs = (baseStep * mult).toLong() * sign

            scrubTargetMs = (scrubTargetMs + stepMs).coerceIn(0L, duration)
            val progress = ((scrubTargetMs * 1000) / duration).toInt()
            timelineSeekBar.progress = progress

            tvScrubTime.text = formatTimeMs(scrubTargetMs)
            tvCurrentTime.text = formatTimeMs(scrubTargetMs)
            val remaining = (duration - scrubTargetMs).coerceAtLeast(0)
            tvRemainingTime.text = "-${formatTimeMs(remaining)}"
            updateTimelineTimeFocusStyles(true)

            val chap = getCurrentChapter(scrubTargetMs)
            if (chap != null) {
                if (tvScrubChapterTitle.text != chap.title) {
                    tvScrubChapterTitle.text = chap.title
                    tvScrubChapterTitle.alpha = 0.5f
                    tvScrubChapterTitle.animate().alpha(1f).setDuration(120).start()
                }
                tvScrubChapterTitle.visibility = View.VISIBLE
            } else {
                tvScrubChapterTitle.visibility = View.GONE
            }

            updateScrubPreviewPosition(scrubTargetMs, duration)
            loadScrubThumbnail(scrubTargetMs)

            val interval = when (speedMagnitude) {
                1 -> 250L
                2 -> 220L
                3 -> 200L
                4 -> 180L
                else -> 150L
            }
            handler.postDelayed(this, interval)
        }
    }

    private fun commitScrubSeek() {
        if (!isScrubbing) return
        handler.removeCallbacks(scrubTickerRunnable)
        handler.removeCallbacks(scrubTransitionRunnable)
        hideQuickSkipBadgeRunnable?.let { handler.removeCallbacks(it) }
        chapterTicksView.setOriginalPositionRatio(null)
        val target = scrubTargetMs
        isSeeking = true
        player?.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        player?.seekTo(target)
        player?.play()
        savePlaybackProgress()
        tvScrubSpeedLeft.visibility = View.GONE
        tvScrubSpeedRight.visibility = View.GONE
        tvScrubChapterTitle.visibility = View.GONE
        llScrubPreviewContainer.animate().alpha(0f).setDuration(160).withEndAction {
            isScrubbing = false
            scrubSpeed = 0
            setHudScrubbingMode(false)
        }.start()
        scheduleHudHide()
    }

    private fun cancelScrubbing() {
        if (!isScrubbing) return
        handler.removeCallbacks(scrubTickerRunnable)
        handler.removeCallbacks(scrubTransitionRunnable)
        hideQuickSkipBadgeRunnable?.let { handler.removeCallbacks(it) }
        chapterTicksView.setOriginalPositionRatio(null)
        tvScrubSpeedLeft.visibility = View.GONE
        tvScrubSpeedRight.visibility = View.GONE
        tvScrubChapterTitle.visibility = View.GONE
        llScrubPreviewContainer.animate().alpha(0f).setDuration(160).withEndAction {
            isScrubbing = false
            scrubSpeed = 0
            setHudScrubbingMode(false)
        }.start()
        player?.let { p ->
            p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            p.seekTo(originalPositionBeforeScrub)
            val dur = p.duration
            if (dur > 0) {
                timelineSeekBar.progress = ((originalPositionBeforeScrub * 1000) / dur).toInt()
                tvCurrentTime.text = formatTimeMs(originalPositionBeforeScrub)
            }
            if (wasPlayingBeforeScrub) {
                p.play()
            }
        }
        scheduleHudHide()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modalController = PlaybackModalController(
            context = this,
            modalOverlay = binding.modalOverlay,
            modalCard = binding.modalCard,
            tvModalTitle = binding.tvModalTitle,
            tvModalBadge = binding.tvModalBadge,
            llModalListContainer = binding.llModalListContainer,
            density = resources.displayMetrics.density,
            topBar = binding.topBar,
            headerRow = binding.llPlayerHeaderRow,
            onModalStateChanged = { isOpen ->
                if (!isOpen) {
                    scheduleHudHide()
                }
            }
        )

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentDvMode = DolbyVisionProcessingMode.fromString(
            prefs.getString(PREF_KEY_DV_MODE, DolbyVisionProcessingMode.P8_TRANSMUTE.name)
        )

        parseIntent()
        ClientSideFrameExtractor.init(applicationContext)
        if (streamUrl.isNotEmpty()) {
            val headers = HashMap<String, String>()
            jellyfinToken?.takeIf { it.isNotBlank() }?.let { tok ->
                headers["X-Emby-Token"] = tok
                headers["Authorization"] = "MediaBrowser Client=\"RemuxPlayer\", Device=\"AndroidTV\", DeviceId=\"remux-android-tv\", Version=\"2.0.0\", Token=\"$tok\""
            }
            ClientSideFrameExtractor.setDataSource(streamUrl, headers)
        }
        bindViews()
        setupListeners()

        val initialSeek = intent.getLongExtra("seek", C.TIME_UNSET)
        initExoPlayer(seekToPos = initialSeek)
    }

    private fun preloadJellyfinChapterThumbnailsAsync() {
        val sUrl = jellyfinServerUrl ?: return
        val itemId = jellyfinItemId ?: return
        val token = jellyfinToken ?: ""
        if (currentChapters.isEmpty() || streamUrl.isEmpty()) return

        ioScope.launch {
            for ((idx, chap) in currentChapters.withIndex()) {
                if (chapterThumbnailMap.containsKey(idx)) continue
                val tokenParam = if (token.isNotBlank()) "?api_key=$token&maxWidth=320&quality=80" else "?maxWidth=320&quality=80"
                val chapterUrl = "$sUrl/Items/$itemId/Images/Chapter/$idx$tokenParam"
                try {
                    val conn = java.net.URL(chapterUrl).openConnection() as java.net.HttpURLConnection
                    if (token.isNotBlank()) {
                        conn.setRequestProperty("X-Emby-Token", token)
                    }
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    if (conn.responseCode == 200) {
                        conn.inputStream.use { stream ->
                            val bmp = BitmapFactory.decodeStream(stream)
                            if (bmp != null) {
                                chapterThumbnailMap[idx] = bmp
                                ClientSideFrameExtractor.registerFrame(streamUrl, chap.startTimeMs, bmp)
                                runOnUiThread {
                                    if (isScrubbing) {
                                        val cur = getCurrentChapter(scrubTargetMs)
                                        if (cur?.startTimeMs == chap.startTimeMs) {
                                            ivScrubThumbnail.setImageBitmap(bmp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
            }
        }
    }

    private val pendingChapterFetches = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    private fun fetchChapterThumbnailAsync(chapIdx: Int) {
        val sUrl = jellyfinServerUrl ?: return
        val itemId = jellyfinItemId ?: return
        val token = jellyfinToken ?: ""
        if (!pendingChapterFetches.add(chapIdx)) return

        ioScope.launch {
            try {
                val tokenParam = if (token.isNotBlank()) "?api_key=$token&maxWidth=360&quality=85" else "?maxWidth=360&quality=85"
                val chapterUrl = "$sUrl/Items/$itemId/Images/Chapter/$chapIdx$tokenParam"
                val conn = java.net.URL(chapterUrl).openConnection() as java.net.HttpURLConnection
                if (token.isNotBlank()) {
                    conn.setRequestProperty("X-Emby-Token", token)
                }
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    conn.inputStream.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) {
                            chapterThumbnailMap[chapIdx] = bmp
                            runOnUiThread {
                                if (isScrubbing) {
                                    val cur = getCurrentChapter(scrubTargetMs)
                                    if (cur != null && currentChapters.indexOf(cur) == chapIdx) {
                                        ivScrubThumbnail.setImageBitmap(bmp)
                                    }
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("PlaybackActivity", "fetchChapterThumbnailAsync failed for chapter $chapIdx: ${e.message}")
            } finally {
                pendingChapterFetches.remove(chapIdx)
            }
        }
    }

    private fun parseIntent() {
        val incomingUri: Uri? = intent.data
        if (incomingUri != null) {
            val rawIncoming = incomingUri.toString()
            if (!NetworkUrlSecurity.validateMediaPlaybackUri(rawIncoming)) {
                Log.w("PlaybackActivity", "Blocked unsafe inbound playback URI")
                finish()
                return
            }
            streamUrl = rawIncoming
            movieTitle = intent.getStringExtra("MOVIE_TITLE")
                ?: intent.getStringExtra("title")
                ?: intent.getStringExtra("android.intent.extra.TITLE")
                ?: incomingUri.getQueryParameter("title")
                ?: incomingUri.lastPathSegment ?: "Direct Play Video"

            intent.getStringExtra("MOVIE_YEAR")?.let { if (it.isNotBlank()) movieYear = it }
            intent.getStringExtra("MOVIE_DURATION")?.let { if (it.isNotBlank()) movieDuration = it }
            intent.getStringExtra("MOVIE_GENRE")?.let { if (it.isNotBlank()) movieGenre = it }
            intent.getStringExtra("MOVIE_SYNOPSIS")?.let { if (it.isNotBlank()) movieSynopsis = it }
            intent.getStringExtra("MOVIE_DIRECTOR")?.let { if (it.isNotBlank()) movieDirector = it }
            intent.getStringExtra("MOVIE_ACTORS")?.let { if (it.isNotBlank()) movieActors = it }
            intent.getStringExtra("MOVIE_SIZE")?.let { if (it.isNotBlank()) movieSize = it }
            intent.getStringExtra("MOVIE_VIDEO_CODEC")?.let { if (it.isNotBlank()) movieVideoCodec = it }
            intent.getStringExtra("MOVIE_AUDIO_CODEC")?.let { if (it.isNotBlank()) movieAudioCodec = it }
            if (intent.hasExtra("MOVIE_IS_4K")) {
                explicitIs4k = intent.getBooleanExtra("MOVIE_IS_4K", false)
            }
            explicitHdrType = intent.getStringExtra("MOVIE_HDR_TYPE")
            explicitDvProfile = intent.getStringExtra("MOVIE_DV_PROFILE")
            explicitAudioBadge = intent.getStringExtra("MOVIE_AUDIO_BADGE")
            explicitChannelsBadge = intent.getStringExtra("MOVIE_CHANNELS_BADGE")
            movieLogoUrl = intent.getStringExtra("MOVIE_LOGO_URL")
            episodeSubtitle = intent.getStringExtra("EPISODE_SUBTITLE")
            explicitChapters = JellyfinMetadataMapper.deserializeChapters(
                intent.getStringExtra("MOVIE_CHAPTERS_JSON")
            ).map { MovieChapter(it.title, it.startTimeMs) }

            jellyfinServerUrl = intent.getStringExtra("JELLYFIN_SERVER_URL")?.let { raw ->
                (NetworkUrlSecurity.validateServerUrl(raw) as? NetworkUrlSecurity.ServerUrlResult.Valid)?.normalizedUrl
            }
            jellyfinToken = intent.getStringExtra("JELLYFIN_TOKEN")
            jellyfinItemId = intent.getStringExtra("JELLYFIN_ITEM_ID")
            jellyfinMediaSourceId = intent.getStringExtra("JELLYFIN_MEDIA_SOURCE_ID")
            jellyfinSeriesId = intent.getStringExtra("JELLYFIN_SERIES_ID")
            jellyfinSeriesName = intent.getStringExtra("JELLYFIN_SERIES_NAME")

            if (jellyfinServerUrl.isNullOrBlank() && streamUrl.isNotBlank()) {
                try {
                    val uri = Uri.parse(streamUrl)
                    if (uri.scheme != null && uri.host != null) {
                        val portPart = if (uri.port != -1) ":${uri.port}" else ""
                        jellyfinServerUrl = "${uri.scheme}://${uri.host}$portPart"
                    }
                    val segments = uri.pathSegments
                    val videosIdx = segments.indexOfFirst { it.equals("Videos", ignoreCase = true) }
                    if (videosIdx != -1 && videosIdx + 1 < segments.size) {
                        jellyfinItemId = segments[videosIdx + 1]
                    }
                    if (jellyfinToken.isNullOrBlank()) {
                        jellyfinToken = uri.getQueryParameter("api_key")
                            ?: uri.getQueryParameter("Token")
                            ?: com.player.androidtv.security.TokenVault.readAndMigrateToken(getSharedPreferences("remux_player_prefs", MODE_PRIVATE), "jellyfin_access_token")
                    }
                } catch (e: Exception) {
                    Log.w("PlaybackActivity", "Failed to parse Jellyfin parameters: ${e.message}")
                }
            }

            if (!jellyfinServerUrl.isNullOrBlank() && !jellyfinToken.isNullOrBlank() && !jellyfinItemId.isNullOrBlank()) {
                jellyfinClient = JellyfinDirectPlayClient(jellyfinServerUrl!!)
            }
        } else {
            val fallback = OfflineDemoCatalog.getMovies("http://192.168.100.108:8096").first()
            movieTitle = fallback.title
            movieYear = fallback.year
            movieDuration = fallback.durationText
            movieGenre = fallback.genreText
            movieSynopsis = fallback.synopsis
            movieDirector = fallback.director
            movieActors = fallback.actors
            movieSize = fallback.sizeText
            movieVideoCodec = fallback.videoCodecLabel
            movieAudioCodec = fallback.audioCodecLabel
            explicitIs4k = fallback.is4k
            explicitHdrType = fallback.dynamicRange.name
            explicitDvProfile = fallback.dvProfile
            explicitAudioBadge = fallback.audioBadge
            explicitChannelsBadge = fallback.channelsBadge
            movieLogoUrl = fallback.logoUrl
            explicitChapters = fallback.chapters.map { MovieChapter(it.title, it.startTimeMs) }
            jellyfinServerUrl = "http://192.168.100.108:8096"
            jellyfinItemId = fallback.id
        }
        val matchingFallback = OfflineDemoCatalog.getMovies(jellyfinServerUrl ?: "http://192.168.100.108:8096")
            .firstOrNull { it.id.equals(jellyfinItemId, ignoreCase = true) || it.title.contains(movieTitle, ignoreCase = true) || movieTitle.contains(it.title, ignoreCase = true) }
        if (movieLogoUrl.isNullOrBlank()) {
            movieLogoUrl = matchingFallback?.logoUrl
                ?: OfflineDemoCatalog.getTvShows(jellyfinServerUrl ?: "http://192.168.100.108:8096")
                    .firstOrNull { movieTitle.contains(it.title, ignoreCase = true) }?.logoUrl
        }
        if (explicitChapters.isNotEmpty()) {
            currentChapters = explicitChapters
        } else if (matchingFallback != null && matchingFallback.chapters.isNotEmpty()) {
            currentChapters = matchingFallback.chapters.map { MovieChapter(it.title, it.startTimeMs) }
        }
        if (currentChapters.isNotEmpty()) {
            preloadJellyfinChapterThumbnailsAsync()
        }
        if (!jellyfinServerUrl.isNullOrBlank() && !jellyfinItemId.isNullOrBlank()) {
            JellyfinTrickplayManager.setMediaContext(jellyfinServerUrl, jellyfinItemId, jellyfinToken)
        }
    }

    private fun bindViews() {
        timelineSeekBar.background = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            timelineSeekBar.defaultFocusHighlightEnabled = false
        }
        cvScrubCard.clipToOutline = true
        ivScrubThumbnail.clipToOutline = true
        binding.modalScrollView.apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        playerView.subtitleView?.setFixedTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            AppSettings.getSubtitleSize(this).textSizeSp
        )

        tvPlayerYear.text = movieYear
        val subLine = episodeSubtitle?.takeIf { it.isNotBlank() }
        val logoToLoad = movieLogoUrl?.takeIf { it.isNotBlank() }?.let { rawLogo ->
            val tok = jellyfinToken?.takeIf { it.isNotBlank() }
            if (tok != null && !rawLogo.contains("api_key=", ignoreCase = true)) {
                "$rawLogo&api_key=$tok"
            } else {
                rawLogo
            }
        }
        if (logoToLoad != null) {
            if (subLine != null) {
                tvPlayerTitle.text = subLine
                tvPlayerTitle.textSize = 13.5f
                tvPlayerTitle.setTextColor(android.graphics.Color.parseColor("#D1D1D6"))
                tvPlayerTitle.visibility = View.VISIBLE
            } else {
                tvPlayerTitle.text = movieTitle
                tvPlayerTitle.visibility = View.GONE
            }
            binding.ivPlayerLogo.visibility = View.INVISIBLE
            ioScope.launch {
                val bmp = logoImageCache.loadLogo(logoToLoad)
                runOnUiThread {
                    if (bmp != null) {
                        binding.ivPlayerLogo.setImageBitmap(bmp)
                        binding.ivPlayerLogo.visibility = View.VISIBLE
                        if (subLine == null) {
                            tvPlayerTitle.visibility = View.GONE
                        }
                    } else {
                        binding.ivPlayerLogo.visibility = View.GONE
                        tvPlayerTitle.text = movieTitle
                        tvPlayerTitle.visibility = View.VISIBLE
                    }
                }
            }
        } else {
            binding.ivPlayerLogo.visibility = View.GONE
            tvPlayerTitle.text = movieTitle
            tvPlayerTitle.visibility = View.VISIBLE
        }

        val hdrHint = explicitHdrType?.uppercase(Locale.US) ?: ""
        val isExplicitSdr = hdrHint == "SDR" ||
                (hdrHint.isEmpty() && (movieVideoCodec.contains("1080P", ignoreCase = true) ||
                        movieVideoCodec.contains("AVC", ignoreCase = true) ||
                        movieVideoCodec.contains("SDR", ignoreCase = true)))
        val isExplicitHlg = hdrHint == "HLG" ||
                (hdrHint.isEmpty() && movieVideoCodec.contains("HLG", ignoreCase = true))
        val isExplicitHdr10 = hdrHint == "HDR10" ||
                (hdrHint.isEmpty() && (movieVideoCodec.contains("HDR10", ignoreCase = true) ||
                        (movieVideoCodec.contains("HDR", ignoreCase = true) && !movieVideoCodec.contains("VISION", ignoreCase = true))))
        val isExplicitDv = hdrHint == "DOLBY_VISION" ||
                (hdrHint.isEmpty() && (movieVideoCodec.contains("VISION", ignoreCase = true) ||
                        movieVideoCodec.contains("DV", ignoreCase = true)))
        val is4k = explicitIs4k ?: (!isExplicitSdr && (movieVideoCodec.contains("4K", ignoreCase = true) ||
                movieVideoCodec.contains("2160", ignoreCase = true) ||
                isExplicitDv || isExplicitHdr10 || isExplicitHlg))

        val initialResLabel = if (is4k) "4K UHD" else "HD"
        if (!explicitDvProfile.isNullOrBlank()) {
            currentDvProfile = explicitDvProfile!!
        }

        val initialHdrLabel: String? = when {
            isExplicitSdr -> {
                currentDynamicRange = VideoDynamicRange.SDR
                btnDvMode.visibility = View.GONE
                btnChapters.nextFocusRightId = View.NO_ID
                null
            }
            isExplicitHlg -> {
                currentDynamicRange = VideoDynamicRange.HLG
                btnDvMode.visibility = View.VISIBLE
                btnDvMode.text = "HLG"
                btnChapters.nextFocusRightId = R.id.btnDvMode
                "HLG"
            }
            isExplicitHdr10 -> {
                currentDynamicRange = VideoDynamicRange.HDR10
                btnDvMode.visibility = View.VISIBLE
                btnDvMode.text = "HDR10"
                btnChapters.nextFocusRightId = R.id.btnDvMode
                if (movieVideoCodec.contains("HDR10+", ignoreCase = true)) "HDR10+" else "HDR10"
            }
            isExplicitDv -> {
                currentDynamicRange = VideoDynamicRange.DOLBY_VISION
                btnDvMode.visibility = View.VISIBLE
                btnDvMode.text = when (currentDvMode) {
                    DolbyVisionProcessingMode.P8_TRANSMUTE -> "Dolby Vision P8.1"
                    DolbyVisionProcessingMode.P7_MEL_DIRECT -> "Dolby Vision P7"
                    DolbyVisionProcessingMode.FORCE_HDR10 -> "HDR10"
                }
                btnChapters.nextFocusRightId = R.id.btnDvMode
                if (currentDvMode == DolbyVisionProcessingMode.FORCE_HDR10) "HDR10" else "DOLBY VISION"
            }
            else -> {
                btnDvMode.visibility = View.GONE
                btnChapters.nextFocusRightId = View.NO_ID
                null
            }
        }

        val initialAudioLabel = explicitAudioBadge?.takeIf { it.isNotBlank() } ?: "AUDIO BITSTREAM"
        applyPlayerLogoBadges(initialResLabel, initialHdrLabel, initialAudioLabel)

        val initialChannels = explicitChannelsBadge?.takeIf { it.isNotBlank() }
        if (initialChannels != null) {
            badgeChannels.text = initialChannels
            badgeChannels.visibility = View.VISIBLE
        } else {
            badgeChannels.visibility = View.GONE
        }
    }

    private fun applyPlayerLogoBadges(resolutionLabel: String?, hdrLabel: String?, audioLabel: String?) {
        val res = resolutionLabel.orEmpty()
        val resPillText = when {
            res.contains("4K", ignoreCase = true) || res.contains("2160", ignoreCase = true) -> "4K"
            res.contains("HD", ignoreCase = true) || res.contains("1080", ignoreCase = true) || res.contains("720", ignoreCase = true) -> "HD"
            res.isNotBlank() -> res.uppercase(Locale.ROOT)
            else -> ""
        }
        binding.ivPlayerResolution.visibility = View.GONE
        badgeResolution.text = resPillText
        badgeResolution.visibility = if (resPillText.isNotBlank()) View.VISIBLE else View.GONE

        val hdr = hdrLabel.orEmpty()
        badgeHdr.text = hdr
        val hdrLogo = when {
            hdr.contains("VISION", ignoreCase = true) || hdr.equals("DV", ignoreCase = true) -> R.drawable.ic_logo_dolby_vision
            hdr.contains("HDR10+", ignoreCase = true) || hdr.contains("HDR10PLUS", ignoreCase = true) -> R.drawable.ic_logo_hdr10_plus
            hdr.contains("HDR10", ignoreCase = true) || hdr.equals("HDR", ignoreCase = true) -> R.drawable.ic_logo_hdr10
            else -> null
        }
        if (hdrLogo != null) {
            binding.ivPlayerHdr.setImageResource(hdrLogo)
            binding.ivPlayerHdr.visibility = View.VISIBLE
            badgeHdr.visibility = View.GONE
        } else if (hdr.isNotBlank()) {
            binding.ivPlayerHdr.visibility = View.GONE
            badgeHdr.visibility = View.VISIBLE
        } else {
            binding.ivPlayerHdr.visibility = View.GONE
            badgeHdr.visibility = View.GONE
        }

        val audio = audioLabel.orEmpty()
        badgeAudio.text = audio
        binding.ivPlayerAudioCarrier.visibility = View.GONE
        val audioLogo = when {
            audio.contains("ATMOS", ignoreCase = true) -> R.drawable.ic_logo_dolby_atmos
            audio.contains("TRUEHD", ignoreCase = true) || audio.contains("TRUE-HD", ignoreCase = true) -> R.drawable.ic_logo_dolby_truehd
            audio.contains("DTS:X", ignoreCase = true) || audio.contains("DTSX", ignoreCase = true) || audio.contains("DTS-X", ignoreCase = true) -> R.drawable.ic_logo_dts_x
            audio.contains("DTS-HD", ignoreCase = true) || audio.contains("DTSHD", ignoreCase = true) || audio.contains("DTS HD", ignoreCase = true) -> R.drawable.ic_logo_dts_hd_ma
            audio.contains("DTS", ignoreCase = true) -> R.drawable.ic_logo_dts
            audio.contains("DIGITAL PLUS", ignoreCase = true) || audio.contains("EAC3", ignoreCase = true) || audio.contains("DD+", ignoreCase = true) || audio.contains("DOLBY", ignoreCase = true) -> R.drawable.ic_logo_dolby_digital_plus
            else -> null
        }
        if (audioLogo != null) {
            binding.ivPlayerAudio.setImageResource(audioLogo)
            binding.ivPlayerAudio.visibility = View.VISIBLE
            badgeAudio.visibility = View.GONE
        } else if (audio.isNotBlank() && !audio.equals("AUDIO BITSTREAM", ignoreCase = true)) {
            binding.ivPlayerAudio.visibility = View.GONE
            badgeAudio.visibility = View.VISIBLE
        } else {
            binding.ivPlayerAudio.visibility = View.GONE
            badgeAudio.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnChapters.setOnClickListener {
            openChaptersModal()
        }

        tvPlayerChapter.setOnClickListener {
            openChaptersModal()
        }

        btnDvMode.setOnClickListener {
            openDvModeModal()
        }

        btnSubtitles.setOnClickListener {
            openSubtitlesModal()
        }

        btnAudioTrack.setOnClickListener {
            openAudioModal()
        }

        modalOverlay.setOnClickListener {
            closeModal()
        }

        timelineSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.let { p ->
                        val duration = p.duration
                        if (duration > 0) {
                            val newPos = (duration * progress) / 1000
                            tvCurrentTime.text = formatTimeMs(newPos)
                            val remaining = (duration - newPos).coerceAtLeast(0)
                            tvRemainingTime.text = "-${formatTimeMs(remaining)}"

                            if (isScrubbing) {
                                scrubTargetMs = newPos
                                updateScrubPreviewPosition(scrubTargetMs, duration)
                                loadScrubThumbnail(scrubTargetMs)
                            } else {
                                val ratio = (progress.toFloat() / 1000f).coerceIn(0f, 1f)
                                val usableWidth = (timelineSeekBar.width - timelineSeekBar.paddingStart - timelineSeekBar.paddingEnd).coerceAtLeast(1)
                                val relThumbX = timelineSeekBar.paddingStart + (ratio * usableWidth)
                                val badgeWidth = llScrubTimeBadgeContainer.width.takeIf { it > 0 } ?: (50 * resources.displayMetrics.density).toInt()
                                val maxOffset = (timelineSeekBar.width - badgeWidth - 75 * resources.displayMetrics.density).coerceAtLeast(0f)
                                llScrubTimeBadgeContainer.translationX = (relThumbX - badgeWidth / 2f).coerceIn(0f, maxOffset)
                            }
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isScrubbing = true
                handler.removeCallbacks(scrubTickerRunnable)
        handler.removeCallbacks(scrubTransitionRunnable)
        hideQuickSkipBadgeRunnable?.let { handler.removeCallbacks(it) }
                player?.let { p ->
                    wasPlayingBeforeScrub = p.isPlaying
                    originalPositionBeforeScrub = p.currentPosition
                    p.pause()
                    val dur = p.duration
                    if (dur > 0) {
                        chapterTicksView.setOriginalPositionRatio(originalPositionBeforeScrub.toFloat() / dur.toFloat())
                    }
                }
                setHudScrubbingMode(true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                player?.let { p ->
                    val duration = p.duration
                    if (duration > 0 && seekBar != null) {
                        val newPos = (duration * seekBar.progress) / 1000
                        p.seekTo(newPos)
                        if (wasPlayingBeforeScrub) {
                            p.play()
                        }
                    }
                }
                chapterTicksView.setOriginalPositionRatio(null)
                isScrubbing = false
                scrubSpeed = 0
                tvScrubSpeedLeft.visibility = View.GONE
                tvScrubSpeedRight.visibility = View.GONE
                setHudScrubbingMode(false)
                scheduleHudHide()
            }
        })

        timelineSeekBar.setOnFocusChangeListener { _, hasFocus ->
            updateTimelineTimeFocusStyles(hasFocus)
        }
        updateTimelineTimeFocusStyles(timelineSeekBar.hasFocus())
    }

    private fun updateTimelineTimeFocusStyles(isFocused: Boolean) {
        val bold = isFocused || isScrubbing
        val tf = if (bold) Typeface.create("sans-serif", Typeface.BOLD)
                 else Typeface.create("sans-serif", Typeface.NORMAL)
        tvCurrentTime.typeface = tf
        tvRemainingTime.typeface = tf
        if (bold) {
            tvRemainingTime.setTextColor(Color.WHITE)
            tvCurrentTime.setTextColor(Color.WHITE)
            tvCurrentTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            tvRemainingTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            tvCurrentTime.setShadowLayer(8f, 0f, 2f, Color.parseColor("#99000000"))
            tvRemainingTime.setShadowLayer(8f, 0f, 2f, Color.parseColor("#99000000"))
        } else {
            tvRemainingTime.setTextColor(Color.parseColor("#8E8E93"))
            tvCurrentTime.setTextColor(Color.parseColor("#AEAEB2"))
            tvCurrentTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            tvRemainingTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            tvCurrentTime.setShadowLayer(0f, 0f, 0f, 0)
            tvRemainingTime.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    /**
     * Theatrical cinematic subtitles calibrated identically to 1080p PGS Blu-ray master:
     * - SMPTE / Rec. 709 reference canvas (1920x1080)
     * - Primary color: 90% video white (#EBEBEB) to eliminate OLED/HDR glare
     * - Shadow: Semitransparent black 50% (#80000000)
     * - Typographic scale: 55px (55 / 1080 = 0.051f)
     * - Bottom margin: 90px (90 / 1080 = 0.0833f) within 90% Title Safe Area
     */
    private fun updateSubtitleStyle() {
        val cinemaTypeface = try {
            Typeface.createFromAsset(assets, "fonts/cinema_subtitles.ttf")
        } catch (e: Exception) {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        playerView.subtitleView?.apply {
            val captionStyle = CaptionStyleCompat(
                Color.parseColor("#EBEBEB"),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                cinemaTypeface
            )
            setStyle(captionStyle)
            setApplyEmbeddedStyles(false)
            setFractionalTextSize(subTextSizeFraction)
            setBottomPaddingFraction(subBottomPaddingFraction)
        }
    }

    private fun initExoPlayer(seekToPos: Long = C.TIME_UNSET, autoPlay: Boolean = true) {
        hasAppliedInitialTrackSelection = false
        val effectiveDvMode = currentDvMode

        val exo = RemuxExoPlayerFactory.create(this, effectiveDvMode)
        player = exo
        playerView.player = exo

        updateSubtitleStyle()
        if (streamUrl.isNotEmpty()) {
            val headers = HashMap<String, String>()
            jellyfinToken?.takeIf { it.isNotBlank() }?.let { tok ->
                headers["X-Emby-Token"] = tok
                headers["Authorization"] = "MediaBrowser Client=\"RemuxPlayer\", Device=\"AndroidTV\", DeviceId=\"remux-android-tv\", Version=\"2.0.0\", Token=\"$tok\""
            }
            ClientSideFrameExtractor.setDataSource(streamUrl, headers)
        }

        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (isScrubbing) {
                    captureLiveSurfaceFrame()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        loadingSpinner.visibility = if (isScrubbing) View.GONE else View.VISIBLE
                    }
                    Player.STATE_READY -> {
                        loadingSpinner.visibility = View.GONE
                        val duration = exo.duration
                        if (duration > 0 && !isScrubbing) {
                            val remaining = (duration - exo.currentPosition).coerceAtLeast(0)
                            tvRemainingTime.text = "-${formatTimeMs(remaining)}"
                        }
                        if (!sessionReportedStarted) {
                            sessionReportedStarted = true
                            val client = jellyfinClient
                            val itemId = jellyfinItemId
                            val token = jellyfinToken
                            if (client != null && !itemId.isNullOrBlank() && !token.isNullOrBlank()) {
                                val srcId = jellyfinMediaSourceId ?: itemId
                                ioScope.launch {
                                    client.startPlaybackSession(itemId, srcId, token)
                                }
                            }
                        }

                        if (isSeeking) {
                            isSeeking = false
                            if (wasPlayingBeforeScrub && !isScrubbing) {
                                exo.play()
                            }
                        }
                    }
                    Player.STATE_ENDED -> {
                        loadingSpinner.visibility = View.GONE
                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        prefs.edit()
                            .remove("progress_${movieTitle}")
                            .remove("duration_${movieTitle}")
                            .apply()
                        showHud()
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("PlaybackActivity", "Playback error occurred: ${error.errorCodeName} (${error.errorCode})", error)
                loadingSpinner.visibility = View.GONE
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    scheduleHudHide()
                } else {
                    if (!isScrubbing) {
                        showHud()
                        handler.removeCallbacks(hideHudRunnable)
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateTrackBadges(tracks)
                if (!hasAppliedInitialTrackSelection && tracks.groups.isNotEmpty()) {
                    hasAppliedInitialTrackSelection = true
                    applySavedOrOriginalTracks(exo, tracks)
                }
            }
        })

        if (streamUrl.isNotEmpty()) {
            val mediaItem = androidx.media3.common.MediaItem.fromUri(streamUrl)
            exo.setMediaItem(mediaItem)
            exo.prepare()
            if (seekToPos != C.TIME_UNSET && seekToPos > 0) {
                exo.seekTo(seekToPos)
            }
            exo.playWhenReady = autoPlay
        }

        handler.post(updateProgressRunnable)
        timelineSeekBar.post { timelineSeekBar.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        showHud()
    }

    override fun onPause() {
        super.onPause()
        savePlaybackProgress()
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideHudRunnable)
        handler.removeCallbacks(scrubTickerRunnable)
        handler.removeCallbacks(scrubTransitionRunnable)
        hideQuickSkipBadgeRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun showCenterPlayPauseIndicator(isPlaying: Boolean) {
        ivCenterPlayPauseIcon.setImageResource(if (isPlaying) R.drawable.ic_play_arrow else R.drawable.ic_pause)
        centerPlayPauseIndicator.animate().cancel()
        centerPlayPauseIndicator.alpha = 0f
        centerPlayPauseIndicator.scaleX = 0.86f
        centerPlayPauseIndicator.scaleY = 0.86f
        centerPlayPauseIndicator.visibility = View.VISIBLE
        centerPlayPauseIndicator.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(130L)
            .withEndAction {
                centerPlayPauseIndicator.animate()
                    .alpha(0f)
                    .setStartDelay(450L)
                    .setDuration(240L)
                    .withEndAction { centerPlayPauseIndicator.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    private fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
                showCenterPlayPauseIndicator(isPlaying = false)
                showHud()
            } else {
                p.play()
                showCenterPlayPauseIndicator(isPlaying = true)
                scheduleHudHide()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                handler.removeCallbacks(scrubTransitionRunnable)
                pendingScrubDirection = 0
                // Do NOT remove scrubTickerRunnable on ACTION_UP: 1x and -1x must continue moving the timeline smoothly just like 2x..5x.
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val currentFocusView = currentFocus

            if (modalController.isModalOpen) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        closeModal()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val lastChild = (0 until llModalListContainer.childCount).map { llModalListContainer.getChildAt(it) }.lastOrNull()
                        if (currentFocusView == lastChild) {
                            closeModal()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val firstChild = llModalListContainer.getChildAt(0)
                        if (currentFocusView == firstChild) {
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        when (modalController.activeModalType) {
                            PlaybackModalController.ModalType.SUBTITLES -> { openAudioModal(); return true }
                            PlaybackModalController.ModalType.CHAPTERS -> {
                                if (btnDvMode.visibility == View.VISIBLE) {
                                    openDvModeModal()
                                    return true
                                }
                            }
                            else -> {}
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        when (modalController.activeModalType) {
                            PlaybackModalController.ModalType.AUDIO -> { openSubtitlesModal(); return true }
                            PlaybackModalController.ModalType.DV_MODE -> { openChaptersModal(); return true }
                            else -> {}
                        }
                    }
                }
                return super.dispatchKeyEvent(event)
            }

            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (isScrubbing) {
                    cancelScrubbing()
                    return true
                }
                if (hudOverlay.visibility == View.VISIBLE && hudOverlay.alpha > 0.5f) {
                    hudOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                        hudOverlay.visibility = View.GONE
                    }.start()
                    return true
                }
                finish()
                return true
            }

            val isHudVisible = (hudOverlay.visibility == View.VISIBLE && hudOverlay.alpha > 0.5f)

            if (isScrubbing) {
                handler.removeCallbacks(hideHudRunnable)
            } else if (event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
                       event.keyCode != KeyEvent.KEYCODE_ENTER &&
                       event.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER &&
                       event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT &&
                       event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                showHud()
            }

            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            ) {
                if (isScrubbing) {
                    commitScrubSeek()
                    return true
                }
                if (isHudVisible) {
                    if (currentFocusView != null && currentFocusView != timelineSeekBar && currentFocusView.isClickable) {
                        currentFocusView.performClick()
                        return true
                    }
                    togglePlayPause()
                    return true
                } else {
                    // Quick Peek Apple TV: Show HUD smoothly without interrupting playback
                    showHud()
                    timelineSeekBar.requestFocus()
                    return true
                }
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isScrubbing) {
                        jumpToPreviousChapter()
                        return true
                    }
                    if (currentFocusView == btnBack) {
                        return true
                    }
                    if (currentFocusView == timelineSeekBar) {
                        btnSubtitles.requestFocus()
                        return true
                    }
                    if (currentFocusView == btnSubtitles || currentFocusView == btnAudioTrack) {
                        btnBack.requestFocus()
                        return true
                    }
                    if (currentFocusView == btnChapters || currentFocusView == btnDvMode) {
                        timelineSeekBar.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isScrubbing) {
                        jumpToNextChapter()
                        return true
                    }
                    if (currentFocusView == btnBack) {
                        btnSubtitles.requestFocus()
                        return true
                    }
                    if (currentFocusView == btnSubtitles || currentFocusView == btnAudioTrack) {
                        timelineSeekBar.requestFocus()
                        return true
                    }
                    if (currentFocusView == timelineSeekBar) {
                        btnChapters.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    jumpToNextChapter()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    jumpToPreviousChapter()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isScrubbing) {
                        handleScrubKey(-1, event.repeatCount)
                        return true
                    }
                    if (hudOverlay.visibility == View.VISIBLE && currentFocusView != null && currentFocusView != timelineSeekBar) {
                        when (currentFocusView) {
                            btnAudioTrack -> { btnSubtitles.requestFocus(); return true }
                            btnSubtitles -> { return true }
                            btnDvMode -> { btnChapters.requestFocus(); return true }
                            btnChapters -> { return true }
                            btnBack -> { return true }
                        }
                    }
                    timelineSeekBar.requestFocus()
                    handleScrubKey(-1, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isScrubbing) {
                        handleScrubKey(1, event.repeatCount)
                        return true
                    }
                    if (hudOverlay.visibility == View.VISIBLE && currentFocusView != null && currentFocusView != timelineSeekBar) {
                        when (currentFocusView) {
                            btnChapters -> {
                                if (btnDvMode.visibility == View.VISIBLE) btnDvMode.requestFocus()
                                return true
                            }
                            btnDvMode -> { return true }
                            btnSubtitles -> { btnAudioTrack.requestFocus(); return true }
                            btnAudioTrack -> { return true }
                            btnBack -> { return true }
                        }
                    }
                    timelineSeekBar.requestFocus()
                    handleScrubKey(1, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (isScrubbing) {
                        commitScrubSeek()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                    timelineSeekBar.requestFocus()
                    handleScrubKey(1, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                    timelineSeekBar.requestFocus()
                    handleScrubKey(-1, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (isScrubbing) {
                        commitScrubSeek()
                        return true
                    }
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (isScrubbing) {
                        cancelScrubbing()
                        return true
                    }
                    if (hudOverlay.visibility == View.VISIBLE && hudOverlay.alpha > 0.5f) {
                        hudOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                            hudOverlay.visibility = View.GONE
                        }.start()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Exact Apple TV scrubbing physics:
     * - Holding Right/Left continuously (repeatCount > 0) stays locked at current speed (1x on first hold) while scrubbing continuously.
     * - Subsequent fresh taps (repeatCount == 0 while already scrubbing):
     *   - Same direction: steps up 1x -> 2x -> 3x -> 4x -> 5x (or -1x -> -5x).
     *   - Opposite direction: steps down yx -> (y - 1)x (e.g. 5x -> 4x -> 3x -> 2x -> 1x -> -1x).
     * - Every speed tier (including 1x / -1x) continuously advances/rewinds the timeline bar via scrubTickerRunnable.
     */
    private fun handleScrubKey(direction: Int, repeatCount: Int = 0) {
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0) return

        handler.removeCallbacks(hideHudRunnable)
        showHud()

        if (!isScrubbing) {
            wasPlayingBeforeScrub = p.isPlaying
            originalPositionBeforeScrub = p.currentPosition
            p.pause()
            isScrubbing = true
            scrubTargetMs = p.currentPosition
            scrubSpeed = if (direction > 0) 1 else -1
            setHudScrubbingMode(true)
            if (duration > 0) {
                chapterTicksView.setOriginalPositionRatio(originalPositionBeforeScrub.toFloat() / duration.toFloat())
            }
            captureLiveSurfaceFrame()
        } else if (repeatCount > 0) {
            // Holding the button continuously: keep current speed (e.g. 1x) and let scrubTickerRunnable glide smoothly.
            return
        } else {
            // Fresh tap while already scrubbing: step up in same direction, or step down (y -> y-1) in opposite direction.
            scrubSpeed = if (direction > 0) {
                when {
                    scrubSpeed < -1 -> scrubSpeed + 1
                    scrubSpeed == -1 -> 1
                    else -> (scrubSpeed + 1).coerceAtMost(5)
                }
            } else {
                when {
                    scrubSpeed > 1 -> scrubSpeed - 1
                    scrubSpeed == 1 -> -1
                    else -> (scrubSpeed - 1).coerceAtLeast(-5)
                }
            }
        }

        val speedMagnitude = Math.abs(scrubSpeed).coerceAtLeast(1)
        val chevrons = if (speedMagnitude >= 2) "▸▸" else "▸"
        val revChevrons = if (speedMagnitude >= 2) "◂◂" else "◂"
        val cleanSpeedLabel = if (scrubSpeed > 0) "${speedMagnitude}× $chevrons" else "$revChevrons ${speedMagnitude}×"

        tvScrubSpeedLeft.visibility = View.GONE
        tvScrubSpeedRight.visibility = View.GONE
        tvScrubSpeedAboveCard.text = cleanSpeedLabel
        tvScrubSpeedAboveCard.alpha = 1f
        tvScrubSpeedAboveCard.visibility = View.VISIBLE

        val sign = if (scrubSpeed > 0) 1 else -1
        val mult = AppSettings.getScrubSpeedPreset(this).multiplier
        val baseStep = when (speedMagnitude) {
            1 -> 6000L
            2 -> 15000L
            3 -> 35000L
            4 -> 75000L
            else -> 160000L
        }
        val instantStep = (baseStep * mult).toLong() * sign
        scrubTargetMs = (scrubTargetMs + instantStep).coerceIn(0L, duration)
        timelineSeekBar.progress = ((scrubTargetMs * 1000) / duration).toInt()

        tvScrubTime.text = formatTimeMs(scrubTargetMs)
        tvCurrentTime.text = formatTimeMs(scrubTargetMs)
        val remaining = (duration - scrubTargetMs).coerceAtLeast(0)
        tvRemainingTime.text = "-${formatTimeMs(remaining)}"

        val chap = getCurrentChapter(scrubTargetMs)
        if (chap != null) {
            if (tvScrubChapterTitle.text != chap.title) {
                tvScrubChapterTitle.text = chap.title
                tvScrubChapterTitle.alpha = 0.5f
                tvScrubChapterTitle.animate().alpha(1f).setDuration(120).start()
            }
            tvScrubChapterTitle.visibility = View.VISIBLE
        } else {
            tvScrubChapterTitle.visibility = View.GONE
        }

        updateScrubPreviewPosition(scrubTargetMs, duration)
        loadScrubThumbnail(scrubTargetMs)
        llScrubPreviewContainer.bringToFront()

        handler.removeCallbacks(scrubTickerRunnable)
        handler.removeCallbacks(scrubTransitionRunnable)
        hideQuickSkipBadgeRunnable?.let { handler.removeCallbacks(it) }
        val initialInterval = when (speedMagnitude) {
            1 -> 250L
            2 -> 220L
            3 -> 200L
            4 -> 180L
            else -> 150L
        }
        handler.postDelayed(scrubTickerRunnable, initialInterval)
    }

    private fun updateScrubPreviewPosition(targetMs: Long, duration: Long) {
        if (duration <= 0) return
        val ratio = (targetMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val density = resources.displayMetrics.density
        val loc = IntArray(2)
        timelineSeekBar.getLocationInWindow(loc)
        val seekbarScreenX = loc[0]
        val seekbarScreenY = loc[1]
        val usableWidth = (timelineSeekBar.width - timelineSeekBar.paddingStart - timelineSeekBar.paddingEnd).coerceAtLeast(1)
        val thumbCenterX = seekbarScreenX + timelineSeekBar.paddingStart + (ratio * usableWidth)

        val cardWidth = (220 * density).toInt()
        val cardHeight = (124 * density).toInt()
        val margin = 48 * density
        val screenWidth = resources.displayMetrics.widthPixels

        // Apple TV edge docking:
        // Clamps preview card between margin and (screenWidth - margin - cardWidth)
        val minCardLeft = margin
        val maxCardLeft = screenWidth - margin - cardWidth
        val idealCardLeft = thumbCenterX - cardWidth / 2f
        val clampedCardLeft = idealCardLeft.coerceIn(minCardLeft, maxCardLeft)
        val animDuration = 90L
        val interpolator = android.view.animation.DecelerateInterpolator()

        // Smooth damping and inertia Apple TV style
        cvScrubCard.animate().cancel()
        cvScrubCard.animate()
            .translationX(clampedCardLeft)
            .setDuration(animDuration)
            .setInterpolator(interpolator)
            .start()

        val seekbarCenterY = seekbarScreenY + (timelineSeekBar.height / 2f)
        val gap = 14f * density
        val cardBottom = seekbarCenterY - gap
        val cardTop = cardBottom - cardHeight
        cvScrubCard.translationY = cardTop

        // Box-free speed label centered directly above preview card (Diseño 11)
        var topAnchorY = cardTop
        if (tvScrubSpeedAboveCard.visibility == View.VISIBLE) {
            tvScrubSpeedAboveCard.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val spdW = tvScrubSpeedAboveCard.measuredWidth.takeIf { it > 0 } ?: (48 * density).toInt()
            val spdH = tvScrubSpeedAboveCard.measuredHeight.takeIf { it > 0 } ?: (20 * density).toInt()
            tvScrubSpeedAboveCard.animate().cancel()
            tvScrubSpeedAboveCard.animate()
                .translationX(clampedCardLeft + (cardWidth - spdW) / 2f)
                .setDuration(animDuration)
                .setInterpolator(interpolator)
                .start()
            topAnchorY = cardTop - spdH - (6 * density)
            tvScrubSpeedAboveCard.translationY = topAnchorY
        }

        // Chapter title softly above speed label
        if (tvScrubChapterTitle.visibility == View.VISIBLE) {
            tvScrubChapterTitle.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val chapWidth = tvScrubChapterTitle.measuredWidth.takeIf { it > 0 } ?: (80 * density).toInt()
            val chapHeight = tvScrubChapterTitle.measuredHeight.takeIf { it > 0 } ?: (20 * density).toInt()
            tvScrubChapterTitle.animate().cancel()
            tvScrubChapterTitle.animate()
                .translationX(clampedCardLeft + (cardWidth - chapWidth) / 2f)
                .setDuration(animDuration)
                .setInterpolator(interpolator)
                .start()
            tvScrubChapterTitle.translationY = topAnchorY - chapHeight - (2 * density)
        }

        // Single continuous vertical needle line from card bottom down through progress bar
        val lineWidth = 2.5f * density
        vScrubPinLine.animate().cancel()
        vScrubPinLine.animate()
            .translationX(thumbCenterX - (lineWidth / 2f))
            .setDuration(animDuration)
            .setInterpolator(interpolator)
            .start()
        vScrubPinLine.translationY = cardBottom
        val targetLineHeight = ((seekbarCenterY + 10 * density) - cardBottom).toInt()
        if (vScrubPinLine.layoutParams.height != targetLineHeight) {
            val lp = vScrubPinLine.layoutParams
            lp.height = targetLineHeight
            vScrubPinLine.layoutParams = lp
        }

        // Time and speed badge below the progress bar
        val relThumbX = timelineSeekBar.paddingStart + (ratio * usableWidth)
        val badgeWidth = llScrubTimeBadgeContainer.width.takeIf { it > 0 } ?: (70 * density).toInt()
        val maxOffset = (timelineSeekBar.width - badgeWidth - 75 * density).coerceAtLeast(0f)
        val targetBadgeX = (relThumbX - badgeWidth / 2f).coerceIn(0f, maxOffset)
        llScrubTimeBadgeContainer.animate().cancel()
        llScrubTimeBadgeContainer.animate()
            .translationX(targetBadgeX)
            .setDuration(animDuration)
            .setInterpolator(interpolator)
            .start()
    }

    private var lastLiveFrameBitmap: Bitmap? = null

    private fun captureLiveSurfaceFrame(capturedTargetMs: Long = scrubTargetMs) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val surfaceView = playerView.videoSurfaceView as? SurfaceView
            if (surfaceView != null && surfaceView.holder.surface.isValid) {
                try {
                    val density = resources.displayMetrics.density
                    val cardWidth = (220 * density).toInt().coerceAtLeast(100)
                    val cardHeight = (124 * density).toInt().coerceAtLeast(50)
                    val destBmp = Bitmap.createBitmap(cardWidth, cardHeight, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(surfaceView, destBmp, { result ->
                        val isBlack = ClientSideFrameExtractor.isBlackFrame(destBmp)
                        if (result == PixelCopy.SUCCESS && !isBlack) {
                            ClientSideFrameExtractor.registerFrame(streamUrl, capturedTargetMs, destBmp)
                            runOnUiThread {
                                lastLiveFrameBitmap = destBmp
                                // Guard against displaying stale frames: only update if user is within 25 seconds of this frame
                                if (isScrubbing && Math.abs(scrubTargetMs - capturedTargetMs) <= 25000L) {
                                    ivScrubThumbnail.setImageBitmap(destBmp)
                                }
                            }
                        }
                    }, handler)
                } catch (e: Exception) {
                    Log.w("PlaybackActivity", "PixelCopy frame capture failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Live keyframe extraction directly from MKV/video stream via ClientSideFrameExtractor.
     * Backed by RAM LRU cache + Disk cache + Server chapter thumbnails.
     */
    private fun loadScrubThumbnail(targetMs: Long) {
        if (streamUrl.isEmpty()) return

        // 1. High-Performance Jellyfin Trickplay Engine (True Apple TV Sub-Second Fluid Scrubbing)
        if (AppSettings.isTrickplayEnabled(this)) {
            val trickplayTile = JellyfinTrickplayManager.getTile(targetMs) { tileBmp ->
                runOnUiThread {
                    if (isScrubbing && Math.abs(scrubTargetMs - targetMs) <= 30000L) {
                        ivScrubThumbnail.setImageBitmap(tileBmp)
                    }
                }
            }
            if (trickplayTile != null) {
                ivScrubThumbnail.setImageBitmap(trickplayTile)
                return
            }
        }

        // 2. Instant nearest-neighbor cached keyframe (RAM or fast disk)
        val closest = ClientSideFrameExtractor.getClosestCachedFrame(streamUrl, targetMs)
        if (closest != null) {
            ivScrubThumbnail.setImageBitmap(closest)
            return
        }

        // 2. High-speed Chapter Thumbnail fallback for current chapter
        val currentChap = getCurrentChapter(targetMs)
        if (currentChap != null) {
            val chapIdx = currentChapters.indexOf(currentChap)
            if (chapIdx >= 0) {
                val chapBmp = chapterThumbnailMap[chapIdx]
                if (chapBmp != null) {
                    ivScrubThumbnail.setImageBitmap(chapBmp)
                    return
                } else {
                    fetchChapterThumbnailAsync(chapIdx)
                }
            }
        }

        // 3. Fallback to nearest available chapter thumbnail from map so preview is never black
        if (chapterThumbnailMap.isNotEmpty()) {
            val nearest = chapterThumbnailMap.entries
                .filter { it.key in currentChapters.indices }
                .minByOrNull { Math.abs(currentChapters[it.key].startTimeMs - targetMs) }
                ?.value
            if (nearest != null) {
                ivScrubThumbnail.setImageBitmap(nearest)
                return
            }
        }

        // 4. Fallback to frozen frame before scrub so preview box is never black
        if (lastLiveFrameBitmap != null) {
            ivScrubThumbnail.setImageBitmap(lastLiveFrameBitmap)
            return
        }

        // 5. Request live frame from video file / stream
        ClientSideFrameExtractor.requestFrame(streamUrl, targetMs, scrubSpeed) { bmp ->
            runOnUiThread {
                if (isScrubbing) {
                    ivScrubThumbnail.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun updateTrackBadges(tracks: Tracks) {
        var audioName = "AUDIO BITSTREAM"
        var channelText = ""
        var isDolbyVision = false
        var dvProfile = "P8.1"
        var isHdr10 = false
        var isHlg = false
        var is4k = false

        for (group in tracks.groups) {
            val type = group.type
            if (type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    if (group.isSelected && !group.isTrackSelected(i)) continue
                    val format = group.getTrackFormat(i)
                    if (format.frameRate > 0 && AppSettings.isAfrEnabled(this@PlaybackActivity)) {
                        val afrText = AutoFrameRateManager.applyAfr(
                            this@PlaybackActivity,
                            format.frameRate,
                            playerView.videoSurfaceView as? android.view.SurfaceView
                        )
                        if (afrText.isNotBlank() && AppSettings.isShowAfrIndicator(this@PlaybackActivity)) {
                            badgeAfr.text = afrText
                            badgeAfr.visibility = View.VISIBLE
                        } else {
                            badgeAfr.visibility = View.GONE
                        }
                    } else if (!AppSettings.isShowAfrIndicator(this@PlaybackActivity)) {
                        badgeAfr.visibility = View.GONE
                    }
                    if (format.width >= 3800 || format.height >= 2100) {
                        is4k = true
                    }
                    val mime = format.sampleMimeType ?: ""
                    val codecs = format.codecs ?: ""
                    if (mime.contains("dolby-vision", ignoreCase = true) ||
                        codecs.contains("dvhe", ignoreCase = true) ||
                        codecs.contains("dvh1", ignoreCase = true) ||
                        codecs.contains("hev1.08", ignoreCase = true)
                    ) {
                        isDolbyVision = true
                        dvProfile = when {
                            codecs.contains("07") || codecs.contains(".07.") -> "P7"
                            codecs.contains("08") || codecs.contains(".08.") -> "P8.1"
                            codecs.contains("05") || codecs.contains(".05.") -> "P5"
                            else -> "P8.1"
                        }
                    }

                    val colorTransfer = format.colorInfo?.colorTransfer
                    if (colorTransfer == C.COLOR_TRANSFER_ST2084) {
                        isHdr10 = true
                    } else if (colorTransfer == C.COLOR_TRANSFER_HLG) {
                        isHlg = true
                    }
                }
            } else if (type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val format = group.getTrackFormat(i)
                        val mime = format.sampleMimeType ?: ""
                        val trackLabel = format.label ?: ""
                        audioName = when {
                            mime.contains("truehd", ignoreCase = true) || mime.contains("true-hd", ignoreCase = true) -> {
                                if (trackLabel.contains("atmos", ignoreCase = true) || explicitAudioBadge?.contains("atmos", ignoreCase = true) == true) "TRUEHD ATMOS" else "DOLBY TRUEHD"
                            }
                            mime.contains("eac3-joc", ignoreCase = true) -> "DD+ ATMOS"
                            mime.contains("eac3", ignoreCase = true) -> {
                                if (trackLabel.contains("atmos", ignoreCase = true)) "DD+ ATMOS" else "DOLBY DIGITAL PLUS"
                            }
                            mime.contains("ac3", ignoreCase = true) -> "DOLBY DIGITAL PLUS"
                            mime.contains("dts", ignoreCase = true) -> when {
                                trackLabel.contains("dts:x", ignoreCase = true) || trackLabel.contains("dtsx", ignoreCase = true) -> "DTS:X"
                                mime.contains("dts.hd", ignoreCase = true) || mime.contains("dtshd", ignoreCase = true) || trackLabel.contains("hd", ignoreCase = true) -> "DTS-HD MA"
                                else -> "DTS"
                            }
                            mime.contains("flac", ignoreCase = true) -> "FLAC"
                            mime.contains("pcm", ignoreCase = true) || mime.contains("raw", ignoreCase = true) -> "PCM"
                            else -> "AUDIO BITSTREAM"
                        }
                        channelText = when (format.channelCount) {
                            8 -> "7.1"
                            6 -> "5.1"
                            2 -> "2.0"
                            1 -> "1.0"
                            else -> if (format.channelCount > 0) "${format.channelCount} CH" else ""
                        }
                    }
                }
            }
        }

        currentDynamicRange = when {
            isDolbyVision -> VideoDynamicRange.DOLBY_VISION
            isHdr10 -> VideoDynamicRange.HDR10
            isHlg -> VideoDynamicRange.HLG
            else -> VideoDynamicRange.SDR
        }
        currentDvProfile = dvProfile

        val resLabel = if (is4k) "4K UHD" else "HD"
        val hdrLabel: String? = when (currentDynamicRange) {
            VideoDynamicRange.DOLBY_VISION -> {
                btnDvMode.visibility = View.VISIBLE
                btnDvMode.text = when (currentDvMode) {
                    DolbyVisionProcessingMode.P8_TRANSMUTE -> "Dolby Vision P8.1"
                    DolbyVisionProcessingMode.P7_MEL_DIRECT -> "Dolby Vision P7"
                    DolbyVisionProcessingMode.FORCE_HDR10 -> "HDR10"
                }
                btnChapters.nextFocusRightId = R.id.btnDvMode
                if (currentDvMode == DolbyVisionProcessingMode.FORCE_HDR10) "HDR10" else "DOLBY VISION"
            }
            VideoDynamicRange.HDR10 -> {
                btnDvMode.visibility = View.VISIBLE
                btnDvMode.text = "HDR10"
                btnChapters.nextFocusRightId = R.id.btnDvMode
                if (movieVideoCodec.contains("HDR10+", ignoreCase = true)) "HDR10+" else "HDR10"
            }
            VideoDynamicRange.HLG -> {
                btnDvMode.visibility = View.VISIBLE
                btnDvMode.text = "HLG"
                btnChapters.nextFocusRightId = R.id.btnDvMode
                "HLG"
            }
            VideoDynamicRange.SDR -> {
                btnDvMode.visibility = View.GONE
                btnChapters.nextFocusRightId = View.NO_ID
                null
            }
        }

        applyPlayerLogoBadges(resLabel, hdrLabel, audioName)

        if (channelText.isNotEmpty()) {
            badgeChannels.text = channelText
            badgeChannels.visibility = View.VISIBLE
        } else {
            badgeChannels.visibility = View.GONE
        }
    }

    // Playback Modals (True live transparency over playing video — no frozen PixelCopy snapshot)

    private fun trackPrefKeys(): List<String> {
        val keys = mutableListOf<String>()
        jellyfinItemId?.takeIf { it.isNotBlank() }?.let { keys.add("item_$it") }
        jellyfinSeriesId?.takeIf { it.isNotBlank() }?.let { keys.add("series_$it") }
        jellyfinSeriesName?.takeIf { it.isNotBlank() }?.let { keys.add("series_name_${it.lowercase(Locale.ROOT).trim()}") }
        if (keys.isEmpty() && movieTitle.isNotBlank()) {
            keys.add("title_${movieTitle.lowercase(Locale.ROOT).trim()}")
        }
        return keys.distinct()
    }

    private fun saveAudioTrackPreference(format: Format) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val editor = prefs.edit()
        for (k in trackPrefKeys()) {
            editor.putString("pref_audio_lang_$k", format.language ?: "")
            editor.putString("pref_audio_mime_$k", format.sampleMimeType ?: "")
            editor.putInt("pref_audio_ch_$k", format.channelCount)
            editor.putString("pref_audio_label_$k", format.label ?: "")
        }
        editor.apply()
    }

    private fun saveSubtitleTrackPreference(format: Format?) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val editor = prefs.edit()
        for (k in trackPrefKeys()) {
            if (format == null) {
                editor.putBoolean("pref_sub_saved_$k", true)
                editor.putBoolean("pref_sub_off_$k", true)
            } else {
                val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                    format.label?.contains("forced", ignoreCase = true) == true
                editor.putBoolean("pref_sub_saved_$k", true)
                editor.putBoolean("pref_sub_off_$k", false)
                editor.putString("pref_sub_lang_$k", format.language ?: "")
                editor.putString("pref_sub_mime_$k", format.sampleMimeType ?: "")
                editor.putBoolean("pref_sub_forced_$k", isForced)
            }
        }
        editor.apply()
    }

    private fun applySavedOrOriginalTracks(exo: ExoPlayer, tracks: Tracks) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val rememberPerTitle = AppSettings.isRememberTracksPerTitle(this)
        val keys = if (rememberPerTitle) trackPrefKeys() else emptyList()
        var paramsBuilder = exo.trackSelectionParameters.buildUpon()
        var paramsModified = false

        // 1. AUDIO: Restore saved per-title/series preference OR apply DefaultAudioMode from Settings
        var savedAudioKey: String? = null
        for (k in keys) {
            if (prefs.contains("pref_audio_lang_$k") || prefs.contains("pref_audio_mime_$k")) {
                savedAudioKey = k
                break
            }
        }

        var chosenAudioGroup: Tracks.Group? = null
        var chosenAudioIndex = -1
        if (savedAudioKey != null) {
            val savedLang = prefs.getString("pref_audio_lang_$savedAudioKey", "") ?: ""
            val savedMime = prefs.getString("pref_audio_mime_$savedAudioKey", "") ?: ""
            val savedCh = prefs.getInt("pref_audio_ch_$savedAudioKey", -1)
            val savedLabel = prefs.getString("pref_audio_label_$savedAudioKey", "") ?: ""
            var bestScore = -1
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (i in 0 until group.length) {
                        val f = group.getTrackFormat(i)
                        var score = 0
                        if (savedLang.isNotBlank() && savedLang.equals(f.language ?: "", ignoreCase = true)) score += 50
                        if (savedMime.isNotBlank() && savedMime.equals(f.sampleMimeType ?: "", ignoreCase = true)) score += 30
                        if (savedCh > 0 && f.channelCount == savedCh) score += 15
                        if (savedLabel.isNotBlank() && savedLabel.equals(f.label ?: "", ignoreCase = true)) score += 20
                        if (score > bestScore && score >= 50) {
                            bestScore = score
                            chosenAudioGroup = group
                            chosenAudioIndex = i
                        }
                    }
                }
            }
        } else {
            val defaultAudioMode = AppSettings.getDefaultAudioMode(this)
            var bestOriginalScore = Int.MIN_VALUE
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (i in 0 until group.length) {
                        val f = group.getTrackFormat(i)
                        val mime = (f.sampleMimeType ?: "").lowercase(Locale.ROOT)
                        val label = (f.label ?: "").lowercase(Locale.ROOT)
                        val lang = (f.language ?: "").lowercase(Locale.ROOT)
                        val isCommentary = (f.roleFlags and C.ROLE_FLAG_COMMENTARY) != 0 ||
                            label.contains("commentary") || label.contains("comentario")
                        var score = 0
                        if (isCommentary) score -= 1000
                        when (defaultAudioMode) {
                            AppSettings.DefaultAudioMode.ENGLISH -> {
                                if (lang.startsWith("en") || label.contains("english")) score += 900
                            }
                            AppSettings.DefaultAudioMode.SPANISH -> {
                                if (lang.startsWith("es") || lang.startsWith("spa") || label.contains("spanish") || label.contains("español") || label.contains("latino")) score += 900
                            }
                            AppSettings.DefaultAudioMode.ORIGINAL_MASTER -> {
                                if (label.contains("original") || label.contains("orig")) score += 800
                            }
                        }
                        when {
                            mime.contains("true-hd") || mime.contains("truehd") -> score += 450
                            mime.contains("dts.hd") || mime.contains("dtshd") -> score += 400
                            mime.contains("flac") -> score += 360
                            mime.contains("raw") || mime.contains("pcm") -> score += 340
                            mime.contains("eac3") -> score += 180
                            mime.contains("dts") -> score += 160
                            mime.contains("ac3") -> score += 120
                        }
                        if (f.channelCount > 0) score += f.channelCount * 8
                        if (score > bestOriginalScore) {
                            bestOriginalScore = score
                            chosenAudioGroup = group
                            chosenAudioIndex = i
                        }
                    }
                }
            }
        }

        if (chosenAudioGroup != null && chosenAudioIndex != -1) {
            paramsBuilder = paramsBuilder
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .addOverride(TrackSelectionOverride(chosenAudioGroup.mediaTrackGroup, listOf(chosenAudioIndex)))
            paramsModified = true
        }

        // 2. SUBTITLES: Restore saved per-title/series preference OR apply DefaultSubtitleMode from Settings
        var savedSubKey: String? = null
        for (k in keys) {
            if (prefs.getBoolean("pref_sub_saved_$k", false)) {
                savedSubKey = k
                break
            }
        }

        if (savedSubKey != null) {
            userChangedSubtitles = true
            val isOff = prefs.getBoolean("pref_sub_off_$savedSubKey", false)
            if (isOff) {
                paramsBuilder = paramsBuilder
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                paramsModified = true
            } else {
                val savedLang = prefs.getString("pref_sub_lang_$savedSubKey", "") ?: ""
                val savedMime = prefs.getString("pref_sub_mime_$savedSubKey", "") ?: ""
                val savedForced = prefs.getBoolean("pref_sub_forced_$savedSubKey", false)
                var bestSubGroup: Tracks.Group? = null
                var bestSubIdx = -1
                var bestSubScore = -1
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_TEXT) {
                        for (i in 0 until group.length) {
                            val f = group.getTrackFormat(i)
                            val isForced = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                                f.label?.contains("forced", ignoreCase = true) == true
                            var score = 0
                            if (savedLang.isNotBlank() && savedLang.equals(f.language ?: "", ignoreCase = true)) score += 50
                            if (savedMime.isNotBlank() && savedMime.equals(f.sampleMimeType ?: "", ignoreCase = true)) score += 25
                            if (isForced == savedForced) score += 15
                            if (score > bestSubScore && score >= 50) {
                                bestSubScore = score
                                bestSubGroup = group
                                bestSubIdx = i
                            }
                        }
                    }
                }
                if (bestSubGroup != null && bestSubIdx != -1) {
                    paramsBuilder = paramsBuilder
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .addOverride(TrackSelectionOverride(bestSubGroup.mediaTrackGroup, listOf(bestSubIdx)))
                    paramsModified = true
                }
            }
        } else {
            val defaultSubMode = AppSettings.getDefaultSubtitleMode(this)
            when (defaultSubMode) {
                AppSettings.DefaultSubtitleMode.OFF -> {
                    paramsBuilder = paramsBuilder
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    paramsModified = true
                    userChangedSubtitles = true
                }
                AppSettings.DefaultSubtitleMode.FORCED,
                AppSettings.DefaultSubtitleMode.ENGLISH,
                AppSettings.DefaultSubtitleMode.SPANISH -> {
                    var bestSubGroup: Tracks.Group? = null
                    var bestSubIdx = -1
                    var bestSubScore = 0
                    for (group in tracks.groups) {
                        if (group.type == C.TRACK_TYPE_TEXT) {
                            for (i in 0 until group.length) {
                                val f = group.getTrackFormat(i)
                                val lang = (f.language ?: "").lowercase(Locale.ROOT)
                                val label = (f.label ?: "").lowercase(Locale.ROOT)
                                val isForced = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                                    label.contains("forced")
                                var score = 0
                                when (defaultSubMode) {
                                    AppSettings.DefaultSubtitleMode.FORCED -> if (isForced) score += 100
                                    AppSettings.DefaultSubtitleMode.ENGLISH -> if (lang.startsWith("en") || label.contains("english")) score += if (isForced) 60 else 100
                                    AppSettings.DefaultSubtitleMode.SPANISH -> if (lang.startsWith("es") || lang.startsWith("spa") || label.contains("spanish") || label.contains("español")) score += if (isForced) 60 else 100
                                    else -> {}
                                }
                                if (score > bestSubScore) {
                                    bestSubScore = score
                                    bestSubGroup = group
                                    bestSubIdx = i
                                }
                            }
                        }
                    }
                    if (bestSubGroup != null && bestSubIdx != -1) {
                        paramsBuilder = paramsBuilder
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .addOverride(TrackSelectionOverride(bestSubGroup.mediaTrackGroup, listOf(bestSubIdx)))
                        paramsModified = true
                        userChangedSubtitles = true
                    } else if (defaultSubMode == AppSettings.DefaultSubtitleMode.FORCED) {
                        paramsBuilder = paramsBuilder
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        paramsModified = true
                        userChangedSubtitles = true
                    }
                }
            }
        }

        if (paramsModified) {
            exo.trackSelectionParameters = paramsBuilder.build()
        } else if (!userChangedSubtitles) {
            autoSelectPgsSubtitles(exo, tracks)
        }
    }

    private fun openAudioModal() {
        val exo = player ?: return
        handler.removeCallbacks(hideHudRunnable)
        modalController.openAudioModal(
            exo = exo,
            anchorButton = btnAudioTrack,
            currentAudioBadgeText = badgeAudio.text.toString()
        ) { selectedFormat ->
            saveAudioTrackPreference(selectedFormat)
        }
    }

    private fun openSubtitlesModal() {
        val exo = player ?: return
        handler.removeCallbacks(hideHudRunnable)
        modalController.openSubtitlesModal(
            exo = exo,
            anchorButton = btnSubtitles
        ) { selectedFormat ->
            userChangedSubtitles = true
            saveSubtitleTrackPreference(selectedFormat)
        }
    }

    private fun openChaptersModal() {
        val exo = player ?: return
        handler.removeCallbacks(hideHudRunnable)
        val chaptersToShow = if (currentChapters.isNotEmpty()) currentChapters else explicitChapters
        modalController.openChaptersModal(
            chapters = chaptersToShow,
            currentPositionMs = exo.currentPosition,
            anchorButton = btnChapters
        ) { chap ->
            exo.seekTo(chap.startTimeMs)
            tvPlayerChapter.text = chap.title
            tvPlayerChapter.visibility = View.VISIBLE
        }
    }

    private fun openDvModeModal() {
        if (player == null) return
        handler.removeCallbacks(hideHudRunnable)
        modalController.openDvModeModal(
            currentDynamicRange = currentDynamicRange,
            currentDvProfile = currentDvProfile,
            currentDvMode = currentDvMode,
            movieTitle = movieTitle,
            anchorButton = btnDvMode
        ) { mode ->
            if (mode != currentDvMode) {
                currentDvMode = mode
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_KEY_DV_MODE, mode.name)
                    .apply()

                btnDvMode.text = when (mode) {
                    DolbyVisionProcessingMode.P8_TRANSMUTE -> "Dolby Vision P8.1"
                    DolbyVisionProcessingMode.P7_MEL_DIRECT -> "Dolby Vision P7"
                    DolbyVisionProcessingMode.FORCE_HDR10 -> "HDR10"
                }
                applyPlayerLogoBadges(
                    badgeResolution.text?.toString(),
                    if (mode == DolbyVisionProcessingMode.FORCE_HDR10) "HDR10" else "DOLBY VISION",
                    badgeAudio.text?.toString()
                )

                val currentPos = player?.currentPosition ?: 0L
                val wasPlaying = player?.isPlaying ?: true
                player?.release()
                initExoPlayer(seekToPos = currentPos, autoPlay = wasPlaying)
            }
        }
    }

    private fun closeModal(onClosed: (() -> Unit)? = null) {
        modalController.closeModal(onClosed)
    }

    private fun autoSelectPgsSubtitles(exo: ExoPlayer, tracks: Tracks) {
        modalController.autoSelectPgsSubtitles(exo, tracks)
    }

    private fun showHud() {
        handler.removeCallbacks(hideHudRunnable)
        if (isScrubbing) {
            setHudScrubbingMode(true)
            return
        }
        setHudScrubbingMode(false)

        if (hudOverlay.visibility != View.VISIBLE) {
            hudOverlay.visibility = View.VISIBLE
            hudOverlay.alpha = 0f
            hudOverlay.animate().alpha(1f).setDuration(200).start()
            if (modalOverlay.visibility != View.VISIBLE) {
                timelineSeekBar.post {
                    timelineSeekBar.requestFocus()
                    updateTimelineTimeFocusStyles(true)
                }
            }
        }
        scheduleHudHide()
    }

    private fun scheduleHudHide() {
        handler.removeCallbacks(hideHudRunnable)
        if (player?.isPlaying == true && modalOverlay.visibility != View.VISIBLE && !isScrubbing) {
            handler.postDelayed(hideHudRunnable, AppSettings.getHudAutoHideMs(this))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (modalController.isModalOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeModal()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                when (modalController.activeModalType) {
                    PlaybackModalController.ModalType.SUBTITLES -> { openAudioModal(); return true }
                    PlaybackModalController.ModalType.CHAPTERS -> {
                        if (btnDvMode.visibility == View.VISIBLE) {
                            openDvModeModal()
                            return true
                        }
                    }
                    else -> {}
                }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                when (modalController.activeModalType) {
                    PlaybackModalController.ModalType.AUDIO -> { openSubtitlesModal(); return true }
                    PlaybackModalController.ModalType.DV_MODE -> { openChaptersModal(); return true }
                    else -> {}
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        showHud()

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_ENTER -> {
                if (isScrubbing) {
                    commitScrubSeek()
                    return true
                }
                val currentFocus = currentFocus
                if (currentFocus != null && currentFocus != timelineSeekBar && currentFocus.isClickable) {
                    currentFocus.performClick()
                    return true
                }
                togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val currentFocus = currentFocus
                if (hudOverlay.visibility == View.VISIBLE) {
                    when (currentFocus) {
                        timelineSeekBar -> btnSubtitles.requestFocus()
                        btnSubtitles, btnAudioTrack -> btnBack.requestFocus()
                        btnChapters, btnDvMode -> timelineSeekBar.requestFocus()
                        else -> timelineSeekBar.requestFocus()
                    }
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val currentFocus = currentFocus
                if (hudOverlay.visibility == View.VISIBLE) {
                    when (currentFocus) {
                        btnBack -> btnSubtitles.requestFocus()
                        btnSubtitles, btnAudioTrack -> timelineSeekBar.requestFocus()
                        timelineSeekBar -> btnChapters.requestFocus()
                        else -> super.onKeyDown(keyCode, event)
                    }
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (hudOverlay.visibility == View.VISIBLE && hudOverlay.alpha > 0.5f) {
                    hudOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                        hudOverlay.visibility = View.GONE
                    }.start()
                } else {
                    finish()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun formatTimeMs(millis: Long): String {
        val totalSecs = millis / 1000
        val hours = totalSecs / 3600
        val minutes = (totalSecs % 3600) / 60
        val seconds = totalSecs % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val finalPosMs = player?.currentPosition ?: 0L
        savePlaybackProgress()
        val client = jellyfinClient
        val itemId = jellyfinItemId
        val token = jellyfinToken
        if (client != null) {
            jellyfinClient = null
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (!itemId.isNullOrBlank() && !token.isNullOrBlank()) {
                        client.reportStop(itemId, JellyfinMetadataMapper.msToTicks(finalPosMs), token)
                    }
                } finally {
                    client.close()
                }
            }
        }
        ioScope.cancel()
        handler.removeCallbacksAndMessages(null)
        AutoFrameRateManager.restoreDefaultMode(
            this,
            if (::binding.isInitialized) playerView.videoSurfaceView as? android.view.SurfaceView else null
        )
        ClientSideFrameExtractor.release()
        player?.release()
        player = null
    }
}
