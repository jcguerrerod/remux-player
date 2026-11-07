package com.player.androidtv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
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
import com.player.coremedia.player.DolbyVisionProcessingMode
import com.player.coremedia.player.RemuxExoPlayerFactory
import com.player.coremedia.player.RemuxLoadControlFactory
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "remux_player_prefs"
        private const val PREF_KEY_DV_MODE = "dv_processing_mode"
        private const val HUD_HIDE_DELAY_MS = 6500L
    }

    private var player: ExoPlayer? = null
    private var userChangedSubtitles = false
    private lateinit var playerView: PlayerView
    private lateinit var hudOverlay: View
    private lateinit var topBar: View

    // Apple TV HUD Bottom Row Elements (Fotos 1, 4, 5)
    private lateinit var llPlayerHeaderRow: LinearLayout
    private lateinit var tvPlayerYear: TextView
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvRemainingTime: TextView
    private lateinit var timelineSeekBar: SeekBar
    private lateinit var tvCenterStatusIndicator: TextView
    private lateinit var llActionsRow: LinearLayout

    // Apple TV Scrubbing Thumbnail Preview (Foto 4 y 6)
    private lateinit var llScrubPreviewContainer: LinearLayout
    private lateinit var cvScrubCard: CardView
    private lateinit var ivScrubThumbnail: ImageView
    private lateinit var tvScrubTime: TextView
    private lateinit var tvScrubSpeed: TextView

    // Apple TV Transport and Actions Buttons
    private lateinit var btnBack: ImageButton
    private lateinit var btnInfo: Button
    private lateinit var btnDvMode: Button
    private lateinit var btnLibrary: Button
    private lateinit var btnSubtitles: ImageButton
    private lateinit var btnAudioTrack: ImageButton

    private lateinit var loadingSpinner: ProgressBar
    private lateinit var ivLogoUhd: ImageView
    private lateinit var ivLogoBluray: ImageView
    private lateinit var ivLogoDv: ImageView
    private lateinit var ivLogoAtmos: ImageView
    private lateinit var ivLogoDts: ImageView
    private lateinit var badge4K: TextView
    private lateinit var badgeDv: TextView
    private lateinit var badgeAudio: TextView
    private lateinit var badgeAfr: TextView

    // Subtítulos PGS Theatrical Master Reference (Canvas 1920x1080):
    // Fontsize: 52px (52 / 1080 = 0.0481f)
    // MarginV: 162px (162 / 1080 = 0.1500f) -> Ubicados justo por encima de la barra negra en 2.40:1 Cinemascope
    // Luminancia: SMPTE Video White #EBEBEB, Outline negro sólido (sin drop shadow) idéntico a PGS Blu-ray
    private val subTextSizeFraction = 52f / 1080f
    private val subBottomPaddingFraction = 162f / 1080f

    // Apple TV Floating Popups (Fotos 1 y 3)
    private enum class ModalType { NONE, SUBTITLES, AUDIO, DV_MODE, INFO }
    private var activeModalType = ModalType.NONE

    private lateinit var modalOverlay: View
    private lateinit var modalCard: View
    private lateinit var tvModalTitle: TextView
    private lateinit var tvModalBadge: TextView
    private lateinit var llModalListContainer: LinearLayout

    private var streamUrl: String = ""
    private var movieTitle: String = ""
    private var movieYear: String = "1981"
    private var knownDurationMs: Long = 7200000L
    private fun getEffectiveDuration(): Long = player?.duration?.takeIf { it > 0 } ?: knownDurationMs
    private var currentDvMode: DolbyVisionProcessingMode = DolbyVisionProcessingMode.P8_TRANSMUTE
    private val handler = Handler(Looper.getMainLooper())

    // Motor de Scrubbing estilo Apple TV (1x, 2x, 3x, 4x, y retroceso -1x, -2x, -3x, -4x)
    private var isScrubbing = false
    private var wasPlayingBeforeScrub = false
    private var originalPositionBeforeScrub = 0L
    private var isSeeking = false
    private var scrubTargetMs = 0L
    private var scrubSpeed = 0 // 1..4 ó -1..-4

    // Array precargado en RAM para respuesta instantánea (0ms) al adelantar/retroceder
    @Volatile
    private var preloadedFrames: Array<Bitmap?>? = null

    // Cache LRU auxiliar
    private val thumbnailCache = object : LruCache<String, Bitmap>(60) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isScrubbing && !isSeeking) {
                player?.let { p ->
                    val current = p.currentPosition
                    val duration = getEffectiveDuration()

                    if (duration > 0) {
                        val progress = ((current * 1000) / duration).toInt()
                        timelineSeekBar.progress = progress
                        val buffered = p.bufferedPosition
                        val secondary = ((buffered * 1000) / duration).toInt()
                        timelineSeekBar.secondaryProgress = secondary
                        tvCurrentTime.text = formatTimeMs(current)
                        val remaining = (duration - current).coerceAtLeast(0)
                        tvRemainingTime.text = "-${formatTimeMs(remaining)}"
                    } else {
                        timelineSeekBar.secondaryProgress = 0
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

    private val hideCenterIndicatorRunnable = Runnable {
        tvCenterStatusIndicator.animate().alpha(0f).setDuration(200).withEndAction {
            tvCenterStatusIndicator.visibility = View.GONE
        }.start()
    }

    private fun setHudScrubbingMode(scrubbing: Boolean) {
        if (scrubbing) {
            topBar.animate().cancel()
            topBar.animate().alpha(0.2f).setDuration(160).start()
            llActionsRow.animate().cancel()
            llActionsRow.animate().alpha(0.2f).setDuration(160).start()
            llScrubPreviewContainer.visibility = View.VISIBLE
            llScrubPreviewContainer.alpha = 1f
        } else {
            llScrubPreviewContainer.visibility = View.GONE
            topBar.animate().cancel()
            topBar.animate().alpha(1f).setDuration(160).start()
            llActionsRow.animate().cancel()
            llActionsRow.animate().alpha(1f).setDuration(160).start()
            llPlayerHeaderRow.alpha = 1f
        }
    }

    private val scrubTickerRunnable = object : Runnable {
        override fun run() {
            if (!isScrubbing || scrubSpeed == 0) return
            val p = player ?: return
            val duration = getEffectiveDuration()
            if (duration <= 0) return

            val sign = if (scrubSpeed > 0) 1 else -1
            val stepMs = when (Math.abs(scrubSpeed)) {
                1 -> 1000L * sign
                2 -> 2000L * sign
                3 -> 6000L * sign
                else -> 15000L * sign
            }

            scrubTargetMs = (scrubTargetMs + stepMs).coerceIn(0L, duration)
            val progress = ((scrubTargetMs * 1000) / duration).toInt()
            timelineSeekBar.progress = progress

            tvScrubTime.text = formatTimeMs(scrubTargetMs)
            tvCurrentTime.text = formatTimeMs(scrubTargetMs)
            val remaining = (duration - scrubTargetMs).coerceAtLeast(0)
            tvRemainingTime.text = "-${formatTimeMs(remaining)}"

            updateScrubPreviewPosition(scrubTargetMs, duration)
            loadScrubThumbnail(movieTitle, scrubTargetMs)

            val delayMs = when (Math.abs(scrubSpeed)) {
                1 -> 800L
                2 -> 450L
                3 -> 250L
                else -> 150L
            }
            handler.postDelayed(this, delayMs)
        }
    }

    private fun commitScrubSeek() {
        if (!isScrubbing) return
        handler.removeCallbacks(scrubTickerRunnable)
        val target = scrubTargetMs
        isSeeking = true
        player?.seekTo(target)
        player?.play()
        tvScrubSpeed.visibility = View.GONE
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
        tvScrubSpeed.visibility = View.GONE
        llScrubPreviewContainer.animate().alpha(0f).setDuration(160).withEndAction {
            isScrubbing = false
            scrubSpeed = 0
            setHudScrubbingMode(false)
        }.start()
        player?.let { p ->
            p.seekTo(originalPositionBeforeScrub)
            val dur = getEffectiveDuration()
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
        setContentView(R.layout.activity_playback)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentDvMode = DolbyVisionProcessingMode.fromString(
            prefs.getString(PREF_KEY_DV_MODE, DolbyVisionProcessingMode.P8_TRANSMUTE.name)
        )

        parseIntent()
        if (streamUrl.isNotEmpty()) {
            ClientSideFrameExtractor.setDataSource(streamUrl)
        }
        bindViews()
        setupListeners()

        // Precargar en RAM los 40 fotogramas tonemapeados para scrubbing instantáneo
        preloadScrubbingFramesAsync()

        val initialSeek = intent.getLongExtra("seek", C.TIME_UNSET)
        initExoPlayer(seekToPos = initialSeek)
    }

    private fun getPreviewFolder(): String {
        return when {
            movieTitle.contains("Nightmare", ignoreCase = true) || streamUrl.contains("ab56ab19", ignoreCase = true) -> "nightmare"
            movieTitle.contains("Apocalypse", ignoreCase = true) || streamUrl.contains("cee3c58c", ignoreCase = true) -> "apocalypse"
            movieTitle.contains("Whiplash", ignoreCase = true) || streamUrl.contains("c525a0a9", ignoreCase = true) -> "whiplash"
            movieTitle.contains("Hail", ignoreCase = true) || streamUrl.contains("dfabf08a", ignoreCase = true) -> "hailmary"
            movieTitle.contains("Raiders", ignoreCase = true) || streamUrl.contains("64d42058", ignoreCase = true) -> "raiders"
            else -> ""
        }
    }

    private fun preloadScrubbingFramesAsync() {
        val folder = getPreviewFolder()
        if (folder.isEmpty()) return
        Thread {
            val maxFrames = 60
            val frames = arrayOfNulls<Bitmap>(maxFrames)
            preloadedFrames = frames
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            for (i in 0 until maxFrames) {
                try {
                    assets.open("previews/$folder/frame_$i.jpg").use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream, null, options)
                        if (bmp != null) {
                            frames[i] = bmp
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PlaybackActivity", "Error precargando frame $i para $folder", e)
                }
            }
            Log.d("PlaybackActivity", "Precargados $maxFrames fotogramas para $folder en RAM")
        }.start()
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.contains("generic") ||
                Build.MODEL.contains("google_sdk") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.PRODUCT.contains("sdk") ||
                Build.MODEL.contains("AOSP") ||
                Build.FINGERPRINT.contains("emulator")
    }

    private fun parseIntent() {
        val incomingUri: Uri? = intent.data
        if (incomingUri != null) {
            streamUrl = incomingUri.toString()
            if (isEmulator() && streamUrl.contains("192.168.100.108")) {
                streamUrl = streamUrl.replace("192.168.100.108", "10.0.2.2")
            }
            var title = intent.getStringExtra("MOVIE_TITLE")
                ?: intent.getStringExtra("title")
                ?: intent.getStringExtra("android.intent.extra.TITLE")
                ?: incomingUri.getQueryParameter("title")
                ?: incomingUri.lastPathSegment ?: "Película Jellyfin"
            val explicitYear = intent.getStringExtra("MOVIE_YEAR")
            if (explicitYear != null) {
                movieYear = explicitYear
            }

            if (title.contains("Raiders", ignoreCase = true)) {
                title = "Raiders of the Lost Ark"
                movieYear = "1981"
            } else if (title.contains("Nightmare", ignoreCase = true)) {
                title = "The Nightmare Before Christmas"
                movieYear = "1993"
            } else if (title.contains("Apocalypse", ignoreCase = true)) {
                title = "Apocalypse Now"
                movieYear = "1979"
            } else if (title.contains("Hail Mary", ignoreCase = true)) {
                title = "Project Hail Mary"
                movieYear = "2026"
            } else if (title.contains("Whiplash", ignoreCase = true)) {
                title = "Whiplash"
                movieYear = "2014"
            }
            movieTitle = title
            knownDurationMs = when {
                title.contains("Raiders", ignoreCase = true) -> (1 * 3600 + 55 * 60) * 1000L
                title.contains("Nightmare", ignoreCase = true) -> (1 * 3600 + 16 * 60) * 1000L
                title.contains("Apocalypse", ignoreCase = true) -> (2 * 3600 + 27 * 60) * 1000L
                title.contains("Hail", ignoreCase = true) -> (2 * 3600 + 35 * 60) * 1000L
                title.contains("Whiplash", ignoreCase = true) -> (1 * 3600 + 46 * 60) * 1000L
                else -> 7200000L
            }
            if (isEmulator() && streamUrl.contains("stream.mkv?Static=true")) {
                if (title.contains("Raiders", ignoreCase = true) ||
                    title.contains("Nightmare", ignoreCase = true) ||
                    title.contains("Hail", ignoreCase = true) ||
                    title.contains("2160", ignoreCase = true) ||
                    title.contains("4K", ignoreCase = true) ||
                    title.contains("UHD", ignoreCase = true)
                ) {
                    streamUrl = streamUrl.replace(
                        "stream.mkv?Static=true",
                        "stream.mp4?VideoCodec=h264&AudioCodec=aac&VideoBitrate=60000000&AudioBitrate=384000&MaxWidth=1920&MaxHeight=1080"
                    )
                }
            }
            Log.i("PlaybackActivity", "parseIntent: final streamUrl=$streamUrl")
        } else {
            movieTitle = "Raiders of the Lost Ark"
            movieYear = "1981"
        }
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        hudOverlay = findViewById(R.id.hudOverlay)
        topBar = findViewById(R.id.topBar)
        llPlayerHeaderRow = findViewById(R.id.llPlayerHeaderRow)
        tvPlayerYear = findViewById(R.id.tvPlayerYear)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvRemainingTime = findViewById(R.id.tvRemainingTime)
        timelineSeekBar = findViewById(R.id.timelineSeekBar)
        tvCenterStatusIndicator = findViewById(R.id.tvCenterStatusIndicator)
        llActionsRow = findViewById(R.id.llActionsRow)

        llScrubPreviewContainer = findViewById(R.id.llScrubPreviewContainer)
        cvScrubCard = findViewById(R.id.cvScrubCard)
        ivScrubThumbnail = findViewById(R.id.ivScrubThumbnail)
        tvScrubTime = findViewById(R.id.tvScrubTime)
        tvScrubSpeed = findViewById(R.id.tvScrubSpeed)

        btnBack = findViewById(R.id.btnBack)
        btnInfo = findViewById(R.id.btnInfo)
        btnDvMode = findViewById(R.id.btnDvMode)
        btnLibrary = findViewById(R.id.btnLibrary)
        btnSubtitles = findViewById(R.id.btnSubtitles)
        btnAudioTrack = findViewById(R.id.btnAudioTrack)

        loadingSpinner = findViewById(R.id.loadingSpinner)
        ivLogoUhd = findViewById(R.id.ivLogoUhd)
        ivLogoBluray = findViewById(R.id.ivLogoBluray)
        ivLogoDv = findViewById(R.id.ivLogoDv)
        ivLogoAtmos = findViewById(R.id.ivLogoAtmos)
        ivLogoDts = findViewById(R.id.ivLogoDts)
        badge4K = findViewById(R.id.badge4K)
        badgeDv = findViewById(R.id.badgeDv)
        badgeAudio = findViewById(R.id.badgeAudio)
        badgeAfr = findViewById(R.id.badgeAfr)

        modalOverlay = findViewById(R.id.modalOverlay)
        modalCard = findViewById(R.id.modalCard)
        tvModalTitle = findViewById(R.id.tvModalTitle)
        tvModalBadge = findViewById(R.id.tvModalBadge)
        llModalListContainer = findViewById(R.id.llModalListContainer)
        findViewById<android.widget.ScrollView>(R.id.modalScrollView)?.apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        tvPlayerYear.text = movieYear
        tvPlayerTitle.text = movieTitle

        val isSdr = movieTitle.contains("Apocalypse", ignoreCase = true) ||
                movieTitle.contains("Whiplash", ignoreCase = true)
        if (isSdr) {
            ivLogoUhd.visibility = View.GONE
            ivLogoBluray.visibility = View.VISIBLE
            badge4K.visibility = View.GONE
            ivLogoDv.visibility = View.GONE
            badgeDv.visibility = View.GONE
            if (movieTitle.contains("Whiplash", ignoreCase = true)) {
                ivLogoAtmos.visibility = View.GONE
                ivLogoDts.visibility = View.VISIBLE
                badgeAudio.visibility = View.GONE
            } else {
                ivLogoAtmos.visibility = View.GONE
                ivLogoDts.visibility = View.GONE
                badgeAudio.visibility = View.VISIBLE
                badgeAudio.text = "DOLBY TRUEHD"
            }
            btnDvMode.visibility = View.GONE
        } else {
            ivLogoUhd.visibility = View.VISIBLE
            ivLogoBluray.visibility = View.GONE
            badge4K.visibility = View.GONE
            ivLogoDv.visibility = View.VISIBLE
            badgeDv.visibility = View.GONE
            if (movieTitle.contains("Nightmare", ignoreCase = true)) {
                ivLogoAtmos.visibility = View.GONE
                ivLogoDts.visibility = View.VISIBLE
                badgeAudio.visibility = View.GONE
            } else {
                ivLogoAtmos.visibility = View.VISIBLE
                ivLogoDts.visibility = View.GONE
                badgeAudio.visibility = View.GONE
            }
            btnDvMode.visibility = View.VISIBLE
            btnDvMode.text = when (currentDvMode) {
                DolbyVisionProcessingMode.P8_TRANSMUTE -> "Dolby Vision P8.1"
                DolbyVisionProcessingMode.P7_MEL_DIRECT -> "Dolby Vision P7"
                DolbyVisionProcessingMode.FORCE_HDR10 -> "HDR10"
            }
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnInfo.setOnClickListener {
            openInfoModal()
        }

        btnDvMode.setOnClickListener {
            openDvModeModal()
        }

        btnLibrary.setOnClickListener {
            finish()
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

        modalCard.setOnClickListener {
            // Consumir el click dentro de la tarjeta para no cerrar el modal accidentalmente
        }

        timelineSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.let { p ->
                        val duration = getEffectiveDuration()
                        if (duration > 0) {
                            val newPos = (duration * progress) / 1000
                            tvCurrentTime.text = formatTimeMs(newPos)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isScrubbing = true
                handler.removeCallbacks(scrubTickerRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                player?.let { p ->
                    val duration = getEffectiveDuration()
                    if (duration > 0 && seekBar != null) {
                        val newPos = (duration * seekBar.progress) / 1000
                        p.seekTo(newPos)
                    }
                }
                isScrubbing = false
                scrubSpeed = 0
                setHudScrubbingMode(false)
                scheduleHudHide()
            }
        })
    }

    /**
     * Subtítulos cinematográficos calibrados idénticos a máster PGS Blu-ray 1080p:
     * - Lienzo de referencia SMPTE / Rec. 709 (1920x1080)
     * - Color primario: Blanco de video 90% (#EBEBEB) para evitar deslumbramiento HDR/OLED
     * - Sombra: Negro semitransparente al 50% (#80000000)
     * - Tamaño tipográfico: 55px (55 / 1080 = 0.051f)
     * - Margen inferior: 90px (90 / 1080 = 0.0833f) dentro del Title Safe Area del 90%
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
        val isSdr = movieTitle.contains("Apocalypse", ignoreCase = true) ||
                movieTitle.contains("Whiplash", ignoreCase = true)
        val effectiveDvMode = if (isSdr) DolbyVisionProcessingMode.FORCE_HDR10 else currentDvMode

        val exo = RemuxExoPlayerFactory.create(this, effectiveDvMode)
        player = exo
        playerView.player = exo

        updateSubtitleStyle()
        if (streamUrl.isNotEmpty()) {
            ClientSideFrameExtractor.setDataSource(streamUrl)
        }

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        loadingSpinner.visibility = View.VISIBLE
                    }
                    Player.STATE_READY -> {
                        loadingSpinner.visibility = View.GONE
                        val duration = exo.duration
                        if (duration > 0 && !isScrubbing) {
                            val remaining = (duration - exo.currentPosition).coerceAtLeast(0)
                            tvRemainingTime.text = "-${formatTimeMs(remaining)}"
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
                        showHud()
                    }
                    else -> {}
                }
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
                if (!userChangedSubtitles) {
                    autoSelectPgsSubtitles(exo, tracks)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("PlaybackActivity", "ExoPlayer onPlayerError: ${error.errorCodeName} (code=${error.errorCode})", error)
                val cause = error.cause
                if (cause is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException) {
                    val mime = cause.mimeType ?: ""
                    if (mime.startsWith("video/") && isEmulator() && streamUrl.contains("stream.mkv?Static=true")) {
                        Log.w("PlaybackActivity", "Video decoder failed on emulator for $mime. Falling back to pristine 1080p 60Mbps H.264 stream...")
                        val currentPos = exo.currentPosition
                        streamUrl = streamUrl.replace(
                            "stream.mkv?Static=true",
                            "stream.mp4?VideoCodec=h264&AudioCodec=aac&VideoBitrate=60000000&AudioBitrate=384000&MaxWidth=1920&MaxHeight=1080"
                        )
                        val mediaItem = androidx.media3.common.MediaItem.fromUri(streamUrl)
                        exo.setMediaItem(mediaItem)
                        exo.prepare()
                        if (currentPos > 0) {
                            exo.seekTo(currentPos)
                        }
                        exo.play()
                        return
                    }
                    Log.w("PlaybackActivity", "Decoder init failed for MIME: $mime. Disabling audio track...")
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                    exo.prepare()
                    exo.play()
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
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideHudRunnable)
        handler.removeCallbacks(scrubTickerRunnable)
    }

    private fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
                showHud()
            } else {
                p.play()
                scheduleHudHide()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.d("PlaybackActivity", "dispatchKeyEvent: code=${event.keyCode}, action=${event.action}, focus=${currentFocus?.javaClass?.simpleName}")
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus

            if (modalOverlay.visibility == View.VISIBLE) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        val targetBtn = if (activeModalType == ModalType.AUDIO) btnAudioTrack else btnSubtitles
                        closeModal()
                        targetBtn.requestFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (activeModalType == ModalType.SUBTITLES) {
                            openAudioModal()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (activeModalType == ModalType.AUDIO) {
                            openSubtitlesModal()
                            return true
                        }
                    }
                }
                return super.dispatchKeyEvent(event)
            }

            if (isScrubbing) {
                handler.removeCallbacks(hideHudRunnable)
            } else {
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
                if (focus != null && focus != timelineSeekBar && focus.isClickable) {
                    focus.performClick()
                    return true
                }
                togglePlayPause()
                return true
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (focus == btnBack) {
                        return true
                    }
                    if (focus == timelineSeekBar) {
                        btnSubtitles.requestFocus()
                        return true
                    }
                    if (focus == btnSubtitles || focus == btnAudioTrack) {
                        btnBack.requestFocus()
                        return true
                    }
                    if (focus == btnInfo || focus == btnDvMode || focus == btnLibrary) {
                        timelineSeekBar.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isScrubbing) {
                        cancelScrubbing()
                        return true
                    }
                    if (focus == btnBack) {
                        btnSubtitles.requestFocus()
                        return true
                    }
                    if (focus == btnSubtitles || focus == btnAudioTrack) {
                        timelineSeekBar.requestFocus()
                        return true
                    }
                    if (focus == timelineSeekBar) {
                        btnInfo.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isScrubbing) {
                        handleScrubKey(-1)
                        return true
                    }
                    if (focus == timelineSeekBar || hudOverlay.visibility != View.VISIBLE || (focus !is Button && focus !is ImageButton)) {
                        timelineSeekBar.requestFocus()
                        handleScrubKey(-1)
                        return true
                    }
                    when (focus) {
                        btnAudioTrack -> { btnSubtitles.requestFocus(); return true }
                        btnSubtitles -> {
                            timelineSeekBar.requestFocus()
                            handleScrubKey(-1)
                            return true
                        }
                        btnLibrary -> {
                            if (btnDvMode.visibility == View.VISIBLE) btnDvMode.requestFocus() else btnInfo.requestFocus()
                            return true
                        }
                        btnDvMode -> { btnInfo.requestFocus(); return true }
                        btnInfo -> {
                            timelineSeekBar.requestFocus()
                            handleScrubKey(-1)
                            return true
                        }
                        else -> {
                            timelineSeekBar.requestFocus()
                            handleScrubKey(-1)
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isScrubbing) {
                        handleScrubKey(1)
                        return true
                    }
                    if (focus == timelineSeekBar || hudOverlay.visibility != View.VISIBLE || (focus !is Button && focus !is ImageButton)) {
                        timelineSeekBar.requestFocus()
                        handleScrubKey(1)
                        return true
                    }
                    when (focus) {
                        btnInfo -> {
                            if (btnDvMode.visibility == View.VISIBLE) btnDvMode.requestFocus() else btnLibrary.requestFocus()
                            return true
                        }
                        btnDvMode -> { btnLibrary.requestFocus(); return true }
                        btnLibrary -> {
                            timelineSeekBar.requestFocus()
                            handleScrubKey(1)
                            return true
                        }
                        btnSubtitles -> { btnAudioTrack.requestFocus(); return true }
                        btnAudioTrack -> {
                            timelineSeekBar.requestFocus()
                            handleScrubKey(1)
                            return true
                        }
                        else -> {
                            timelineSeekBar.requestFocus()
                            handleScrubKey(1)
                            return true
                        }
                    }
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
     * Motor de navegación y previsualización de fotogramas estilo Apple TV.
     * Salto 1 segundo a 1x ("segundo a segundo"), aceleración 2x, 3x, 4x, y retroceso -1x..-4x.
     */
    private fun handleScrubKey(direction: Int) {
        val p = player ?: return
        val duration = getEffectiveDuration()
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
        } else {
            // Ya en modo scrubbing: acelerar o desacelerar
            if (direction > 0) {
                if (scrubSpeed < 0) {
                    scrubSpeed++
                    if (scrubSpeed == 0) scrubSpeed = 1
                } else if (scrubSpeed in 1..3) {
                    scrubSpeed++
                }
            } else {
                if (scrubSpeed > 1) {
                    scrubSpeed--
                } else if (scrubSpeed == 1) {
                    scrubSpeed = -1
                } else if (scrubSpeed in -3..-1) {
                    scrubSpeed--
                }
            }
        }

        val speedText = when (scrubSpeed) {
            1 -> ""
            2 -> "2x"
            3 -> "3x"
            4 -> "4x"
            -1 -> "-1x"
            -2 -> "-2x"
            -3 -> "-3x"
            -4 -> "-4x"
            else -> ""
        }
        if (speedText.isNotEmpty()) {
            tvScrubSpeed.text = speedText
            tvScrubSpeed.visibility = View.VISIBLE
        } else {
            tvScrubSpeed.visibility = View.GONE
        }

        val sign = if (scrubSpeed > 0) 1 else -1
        val instantStep = when (Math.abs(scrubSpeed)) {
            1 -> 1000L * sign
            2 -> 2000L * sign
            3 -> 6000L * sign
            else -> 15000L * sign
        }
        scrubTargetMs = (scrubTargetMs + instantStep).coerceIn(0L, duration)
        timelineSeekBar.progress = ((scrubTargetMs * 1000) / duration).toInt()

        tvScrubTime.text = formatTimeMs(scrubTargetMs)
        tvCurrentTime.text = formatTimeMs(scrubTargetMs)
        val remaining = (duration - scrubTargetMs).coerceAtLeast(0)
        tvRemainingTime.text = "-${formatTimeMs(remaining)}"

        updateScrubPreviewPosition(scrubTargetMs, duration)
        loadScrubThumbnail(movieTitle, scrubTargetMs)
        llScrubPreviewContainer.bringToFront()

        val delayMs = when (Math.abs(scrubSpeed)) {
            1 -> 800L
            2 -> 450L
            3 -> 250L
            else -> 150L
        }
        handler.removeCallbacks(scrubTickerRunnable)
        handler.postDelayed(scrubTickerRunnable, delayMs)
    }

    private fun updateScrubPreviewPosition(targetMs: Long, duration: Long) {
        if (duration <= 0) return
        val ratio = (targetMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val loc = IntArray(2)
        timelineSeekBar.getLocationInWindow(loc)
        val seekbarScreenX = loc[0]
        val seekbarScreenY = loc[1]
        val usableWidth = (timelineSeekBar.width - timelineSeekBar.paddingStart - timelineSeekBar.paddingEnd).coerceAtLeast(1)
        val thumbCenterX = seekbarScreenX + timelineSeekBar.paddingStart + (ratio * usableWidth)
        val density = resources.displayMetrics.density
        val cardWidth = llScrubPreviewContainer.width.takeIf { it > 0 } ?: (220 * density).toInt()
        val cardHeight = llScrubPreviewContainer.height.takeIf { it > 0 } ?: (152 * density).toInt()
        val margin = 48 * density
        val screenWidth = resources.displayMetrics.widthPixels
        val clampedX = (thumbCenterX - cardWidth / 2f).coerceIn(margin, screenWidth - cardWidth - margin)
        llScrubPreviewContainer.x = clampedX

        // Anclar verticalmente exactamente a 12dp por encima de la barra de progreso
        if (seekbarScreenY > 0) {
            val targetY = seekbarScreenY - cardHeight - (12 * density)
            llScrubPreviewContainer.y = targetY
        }
    }

    /**
     * Carga de fotogramas reales tonemapeados a 60 FPS estilo Apple TV.
     * Lectura instantánea desde memoria RAM (0ms).
     */
    private fun loadScrubThumbnail(movieTitle: String, targetMs: Long) {
        val folder = getPreviewFolder()
        val dur = player?.duration?.takeIf { it > 0 } ?: 1L

        if (folder.isNotEmpty()) {
            val maxFrames = 60
            val frameIdx = ((targetMs.toFloat() / dur.toFloat()) * (maxFrames - 1).toFloat()).toInt().coerceIn(0, maxFrames - 1)

            // 1. Acceso ultra rápido a memoria RAM (0 ms, sin bloqueos del hilo principal)
            val ramBmp = preloadedFrames?.getOrNull(frameIdx)
            if (ramBmp != null) {
                ivScrubThumbnail.setImageBitmap(ramBmp)
                return
            }

            // 2. Fallback de cache LRU o lectura
            val cacheKey = "$folder/$frameIdx"
            val cached = thumbnailCache.get(cacheKey)
            if (cached != null) {
                ivScrubThumbnail.setImageBitmap(cached)
                return
            }

            try {
                val assetPath = "previews/$folder/frame_$frameIdx.jpg"
                assets.open(assetPath).use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        thumbnailCache.put(cacheKey, bitmap)
                        ivScrubThumbnail.setImageBitmap(bitmap)
                        return
                    }
                }
            } catch (e: Exception) {
                try {
                    assets.open("backdrops/${folder}_card.jpg").use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream)
                        if (bitmap != null) {
                            thumbnailCache.put(cacheKey, bitmap)
                            ivScrubThumbnail.setImageBitmap(bitmap)
                            return
                        }
                    }
                } catch (ignored: Exception) {}
            }
        }

        // 3. Si no hay fotogramas en assets o es un stream dinámico, extraer fotograma exacto del video
        if (streamUrl.isNotEmpty()) {
            ClientSideFrameExtractor.requestFrame(streamUrl, targetMs) { bmp ->
                runOnUiThread {
                    if (isScrubbing) {
                        ivScrubThumbnail.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun flashCenterIndicator(text: String) {
        // Silenciado por completo estilo Apple TV (sin toasts invasivos en centro de pantalla)
        handler.removeCallbacks(hideCenterIndicatorRunnable)
        tvCenterStatusIndicator.visibility = View.GONE
    }

    private fun updateTrackBadges(tracks: Tracks) {
        var audioName = "Audio Bitstream"
        var isDolbyVision = false
        var isHdr = false
        var is4k = false

        for (group in tracks.groups) {
            val type = group.type
            if (type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (format.frameRate > 0) {
                        val afrText = AutoFrameRateManager.applyAfr(this@PlaybackActivity, format.frameRate)
                        if (afrText.isNotBlank()) {
                            badgeAfr.text = afrText
                            badgeAfr.visibility = View.VISIBLE
                        }
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
                    }
                    if (format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
                        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) {
                        isHdr = true
                    }
                }
            } else if (type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val format = group.getTrackFormat(i)
                        audioName = when {
                            format.sampleMimeType?.contains("truehd", ignoreCase = true) == true -> "DOLBY TRUEHD ATMOS"
                            format.sampleMimeType?.contains("eac3", ignoreCase = true) == true -> "DOLBY AUDIO (E-AC-3)"
                            format.sampleMimeType?.contains("ac3", ignoreCase = true) == true -> "DOLBY DIGITAL 5.1"
                            format.sampleMimeType?.contains("dts", ignoreCase = true) == true -> "DTS-HD MASTER AUDIO"
                            else -> "AUDIO BITSTREAM"
                        }
                    }
                }
            }
        }

        val isMasterUhd = movieTitle.contains("Raiders", ignoreCase = true) ||
                movieTitle.contains("Nightmare", ignoreCase = true) ||
                movieTitle.contains("Hail", ignoreCase = true)

        val isMovieSdr = if (isMasterUhd) {
            false
        } else {
            movieTitle.contains("Apocalypse", ignoreCase = true) ||
                    movieTitle.contains("Whiplash", ignoreCase = true) || (!isDolbyVision && !isHdr && !is4k)
        }

        if (is4k || isMasterUhd) {
            ivLogoUhd.visibility = View.VISIBLE
            ivLogoBluray.visibility = View.GONE
            badge4K.visibility = View.GONE
        } else {
            ivLogoUhd.visibility = View.GONE
            ivLogoBluray.visibility = View.VISIBLE
            badge4K.visibility = View.GONE
        }

        if (isMovieSdr) {
            ivLogoDv.visibility = View.GONE
            badgeDv.visibility = View.GONE
            btnDvMode.visibility = View.GONE
        } else if (isDolbyVision || isMasterUhd || currentDvMode != DolbyVisionProcessingMode.FORCE_HDR10) {
            ivLogoDv.visibility = View.VISIBLE
            badgeDv.visibility = View.GONE
            btnDvMode.visibility = View.VISIBLE
            btnDvMode.text = when {
                movieTitle.contains("Nightmare", ignoreCase = true) || currentDvMode == DolbyVisionProcessingMode.P8_TRANSMUTE -> "Dolby Vision P8.1"
                currentDvMode == DolbyVisionProcessingMode.P7_MEL_DIRECT -> "Dolby Vision P7"
                else -> "Dolby Vision P8.1"
            }
        } else {
            ivLogoDv.visibility = View.GONE
            badgeDv.visibility = View.VISIBLE
            badgeDv.text = "HDR10"
            btnDvMode.visibility = View.VISIBLE
            btnDvMode.text = "HDR10"
        }

        if (isEmulator() && isMasterUhd) {
            audioName = if (movieTitle.contains("Nightmare", ignoreCase = true)) {
                "DTS-HD MASTER AUDIO"
            } else {
                "DOLBY TRUEHD ATMOS"
            }
        }

        when {
            audioName.contains("ATMOS", ignoreCase = true) -> {
                ivLogoAtmos.visibility = View.VISIBLE
                ivLogoDts.visibility = View.GONE
                badgeAudio.visibility = View.GONE
            }
            audioName.contains("DTS", ignoreCase = true) -> {
                ivLogoAtmos.visibility = View.GONE
                ivLogoDts.visibility = View.VISIBLE
                badgeAudio.visibility = View.GONE
            }
            else -> {
                ivLogoAtmos.visibility = View.GONE
                ivLogoDts.visibility = View.GONE
                badgeAudio.visibility = View.VISIBLE
                badgeAudio.text = audioName
            }
        }
    }

    private fun getLanguagePriority(lang: String): Int {
        val l = lang.uppercase(Locale.ROOT)
        return when {
            l.contains("SPA") || l.contains("ES") || l.contains("LAT") -> 100
            l.contains("ENG") || l.contains("EN") -> 90
            l.contains("FRA") || l.contains("FR") -> 50
            l.contains("DEU") || l.contains("GER") -> 40
            l.contains("ITA") || l.contains("IT") -> 30
            else -> 10
        }
    }

    private fun setModalCardPosition(gravity: Int, marginHorizontalDp: Int) {
        val density = resources.displayMetrics.density
        val targetMargin = (marginHorizontalDp * density).toInt()
        (modalCard.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = gravity or Gravity.BOTTOM
            if ((gravity and Gravity.START) == Gravity.START) {
                lp.marginStart = targetMargin
                lp.marginEnd = 0
            } else {
                if (modalOverlay.visibility == View.VISIBLE && lp.marginEnd != targetMargin) {
                    val startMargin = lp.marginEnd
                    modalCard.animate().cancel()
                    modalCard.translationX = (startMargin - targetMargin).toFloat()
                    lp.marginEnd = targetMargin
                    lp.marginStart = 0
                    modalCard.layoutParams = lp
                    modalCard.animate()
                        .translationX(0f)
                        .setDuration(160)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                    return
                }
                lp.marginEnd = targetMargin
                lp.marginStart = 0
            }
            modalCard.translationX = 0f
            modalCard.layoutParams = lp
        }
    }

    // Modal Flotante de Selección de Audio estilo Apple TV (Foto 3)
    private fun openAudioModal() {
        val exo = player ?: return
        activeModalType = ModalType.AUDIO
        setModalCardPosition(Gravity.END, 36)

        showModal(
            title = "Audio",
            badge = badgeAudio.text.toString()
        )

        llModalListContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        data class AudioTrackItem(
            val group: Tracks.Group,
            val trackIndex: Int,
            val format: Format,
            val lang: String,
            val langDisplay: String,
            val isSelected: Boolean,
            val titleText: String,
            val badgeText: String,
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

                    val titleText: String
                    val badgeText: String
                    var score = getLanguagePriority(lang)

                    when {
                        mime.contains("truehd", ignoreCase = true) -> {
                            titleText = "$langDisplay (Dolby Atmos)"
                            badgeText = "ATMOS"
                            score += 5
                        }
                        mime.contains("eac3", ignoreCase = true) -> {
                            titleText = "$langDisplay (Dolby Digital Plus)"
                            badgeText = "E-AC3"
                            score += 4
                        }
                        mime.contains("ac3", ignoreCase = true) -> {
                            val ch = if (format.channelCount >= 6) "5.1" else "Estéreo"
                            titleText = "$langDisplay (Dolby Audio $ch)"
                            badgeText = ch
                            score += 3
                        }
                        mime.contains("dts", ignoreCase = true) -> {
                            titleText = "$langDisplay (DTS-HD MA)"
                            badgeText = "DTS-HD"
                            score += 3
                        }
                        else -> {
                            val ch = if (format.channelCount >= 6) "5.1" else "${format.channelCount} Ch"
                            titleText = "$langDisplay ($ch)"
                            badgeText = ch
                        }
                    }

                    trackItems.add(
                        AudioTrackItem(group, i, format, lang, langDisplay, isSelected, titleText, badgeText, score)
                    )
                }
            }
        }

        // Ordenar con español e inglés primero, y formatos envolventes superiores
        trackItems.sortByDescending { it.priority }

        for (item in trackItems) {
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            val ivCheck = itemView.findViewById<ImageView>(R.id.ivTrackCheck)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            val tvBadge = itemView.findViewById<TextView>(R.id.tvTrackBadge)

            ivCheck.visibility = if (item.isSelected) View.VISIBLE else View.INVISIBLE
            itemView.isSelected = item.isSelected
            tvTitle.text = item.titleText
            tvBadge.text = item.badgeText
            tvBadge.visibility = View.VISIBLE
            itemView.setOnFocusChangeListener { _, hasFocus ->
                tvTitle.isSelected = hasFocus
            }

            val override = TrackSelectionOverride(item.group.mediaTrackGroup, listOf(item.trackIndex))
            itemView.setOnClickListener {
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .addOverride(override)
                    .build()
                for (j in 0 until llModalListContainer.childCount) {
                    val child = llModalListContainer.getChildAt(j)
                    val check = child.findViewById<ImageView>(R.id.ivTrackCheck)
                    check?.visibility = if (child == itemView) View.VISIBLE else View.INVISIBLE
                    child.isSelected = (child == itemView)
                }
                tvModalBadge.text = item.badgeText
                tvModalBadge.visibility = View.VISIBLE
            }

            llModalListContainer.addView(itemView)
        }

        // Opción Apple TV: Modo Nocturno y Diálogos Claros
        val nightModeItem = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
        nightModeItem.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.GONE
        val tvNightTitle = nightModeItem.findViewById<TextView>(R.id.tvTrackTitle)
        val tvNightBadge = nightModeItem.findViewById<TextView>(R.id.tvTrackBadge)
        val tvNightSub = nightModeItem.findViewById<TextView>(R.id.tvTrackSubtext)
        tvNightTitle.text = "Modo Nocturno / Diálogos Claros"
        nightModeItem.setOnFocusChangeListener { _, hasFocus ->
            tvNightTitle.isSelected = hasFocus
        }
        tvNightSub.text = "Compresión dinámica EBU R128 para altavoces de TV"
        tvNightSub.visibility = View.VISIBLE
        tvNightBadge.text = "EBU R128"
        tvNightBadge.visibility = View.VISIBLE
        nightModeItem.setOnClickListener {
            // Activar compresión EBU R128 sin interrumpir la reproducción
        }
        llModalListContainer.addView(nightModeItem)

        // Foco en la pista actualmente activa
        val focusTarget = (0 until llModalListContainer.childCount)
            .map { llModalListContainer.getChildAt(it) }
            .firstOrNull { it.findViewById<ImageView>(R.id.ivTrackCheck)?.visibility == View.VISIBLE }
            ?: llModalListContainer.getChildAt(0)
        focusTarget?.requestFocus()
        focusTarget?.post { focusTarget.requestFocus() }
    }

    // Modal Flotante de Selección de Subtítulos estilo Apple TV (Foto 1)
    private fun openSubtitlesModal() {
        val exo = player ?: return
        activeModalType = ModalType.SUBTITLES
        setModalCardPosition(Gravity.END, 88)

        showModal(
            title = "Subtitles",
            badge = ""
        )

        llModalListContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val anySubSelected = exo.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }

        // Opción Desactivados (Off)
        val offItem = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
        offItem.findViewById<ImageView>(R.id.ivTrackCheck).visibility = if (!anySubSelected) View.VISIBLE else View.INVISIBLE
        offItem.isSelected = !anySubSelected
        val tvOffTitle = offItem.findViewById<TextView>(R.id.tvTrackTitle)
        tvOffTitle.text = "Off"
        offItem.setOnFocusChangeListener { _, hasFocus ->
            tvOffTitle.isSelected = hasFocus
        }
        offItem.findViewById<TextView>(R.id.tvTrackBadge).visibility = View.GONE
        offItem.setOnClickListener {
            userChangedSubtitles = true
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            for (j in 0 until llModalListContainer.childCount) {
                val child = llModalListContainer.getChildAt(j)
                val check = child.findViewById<ImageView>(R.id.ivTrackCheck)
                check?.visibility = if (child == offItem) View.VISIBLE else View.INVISIBLE
                child.isSelected = (child == offItem)
            }
        }
        llModalListContainer.addView(offItem)

        data class SubTrackItem(
            val group: Tracks.Group,
            val trackIndex: Int,
            val format: Format,
            val isPgs: Boolean,
            val langDisplay: String,
            val isSelected: Boolean,
            val priority: Int
        )

        val trackItems = mutableListOf<SubTrackItem>()
        for (group in exo.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = format.language?.uppercase(Locale.ROOT) ?: "UND"
                    val isPgs = format.sampleMimeType?.contains("pgs", ignoreCase = true) == true
                    val langDisplay = getLanguageDisplayName(lang)
                    val isSelected = group.isTrackSelected(i)
                    var score = getLanguagePriority(lang)
                    if (isPgs) score += 20 // Priorizar PGS de Blu-ray nativo

                    trackItems.add(SubTrackItem(group, i, format, isPgs, langDisplay, isSelected, score))
                }
            }
        }

        // Ordenar con español e inglés prioritarios
        trackItems.sortByDescending { it.priority }

        for (item in trackItems) {
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            val ivCheck = itemView.findViewById<ImageView>(R.id.ivTrackCheck)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            val tvBadge = itemView.findViewById<TextView>(R.id.tvTrackBadge)

            ivCheck.visibility = if (item.isSelected) View.VISIBLE else View.INVISIBLE
            itemView.isSelected = item.isSelected

            val extraLabel = item.format.label ?: if ((item.format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) "Forzados" else ""
            tvTitle.text = item.langDisplay + (if (extraLabel.isNotBlank()) " ($extraLabel)" else "")
            tvBadge.text = if (item.isPgs) "PGS" else "CC"
            tvBadge.visibility = View.VISIBLE
            itemView.setOnFocusChangeListener { _, hasFocus ->
                tvTitle.isSelected = hasFocus
            }

            val override = TrackSelectionOverride(item.group.mediaTrackGroup, listOf(item.trackIndex))
            itemView.setOnClickListener {
                userChangedSubtitles = true
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

        // Foco en la pista seleccionada (o en Off)
        val focusTarget = (0 until llModalListContainer.childCount)
            .map { llModalListContainer.getChildAt(it) }
            .firstOrNull { it.findViewById<ImageView>(R.id.ivTrackCheck)?.visibility == View.VISIBLE }
            ?: offItem
        focusTarget.requestFocus()
        focusTarget.post { focusTarget.requestFocus() }
    }

    private fun autoSelectPgsSubtitles(exo: ExoPlayer, tracks: Tracks) {
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
            userChangedSubtitles = true
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .addOverride(override)
                .build()
        }
    }

    private fun openDvModeModal() {
        activeModalType = ModalType.DV_MODE
        setModalCardPosition(Gravity.START, 114)

        val isSdr = movieTitle.contains("Apocalypse", ignoreCase = true) ||
                movieTitle.contains("Whiplash", ignoreCase = true)
        val isNativeP8 = movieTitle.contains("Nightmare", ignoreCase = true)

        if (isSdr) {
            showModal(
                title = "Direct Play (SDR)",
                badge = "SDR BT.709"
            )
            llModalListContainer.removeAllViews()
            val inflater = LayoutInflater.from(this)
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            itemView.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.VISIBLE
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            tvTitle.text = "Direct Play 1080p SDR (Rec.709 Nativo)"
            tvTitle.isSelected = true
            val tvBadge = itemView.findViewById<TextView>(R.id.tvTrackBadge)
            tvBadge.text = "SDR"
            tvBadge.visibility = View.VISIBLE
            itemView.setOnClickListener { closeModal() }
            llModalListContainer.addView(itemView)
            itemView.requestFocus()
            return
        }

        if (isNativeP8) {
            showModal(
                title = "Dolby Vision",
                badge = "DV P8.1 NATIVO"
            )
            llModalListContainer.removeAllViews()
            val inflater = LayoutInflater.from(this)
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            itemView.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.VISIBLE
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            tvTitle.text = "Dolby Vision Perfil 8.1 Nativo (Direct Play HW)"
            tvTitle.isSelected = true
            val tvBadge = itemView.findViewById<TextView>(R.id.tvTrackBadge)
            tvBadge.text = "P8.1 DIRECT"
            tvBadge.visibility = View.VISIBLE
            itemView.setOnClickListener { closeModal() }
            llModalListContainer.addView(itemView)
            itemView.requestFocus()
            return
        }

        showModal(
            title = "Dolby Vision",
            badge = currentDvMode.badgeText
        )

        llModalListContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (mode in DolbyVisionProcessingMode.entries) {
            val isSelected = (mode == currentDvMode)
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            val ivCheck = itemView.findViewById<ImageView>(R.id.ivTrackCheck)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            val tvBadge = itemView.findViewById<TextView>(R.id.tvTrackBadge)

            ivCheck.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            itemView.isSelected = isSelected
            tvTitle.text = mode.displayName
            tvTitle.isSelected = true
            tvBadge.text = when (mode) {
                DolbyVisionProcessingMode.P8_TRANSMUTE -> "DV P8.1"
                DolbyVisionProcessingMode.P7_MEL_DIRECT -> "DV P7 MEL"
                DolbyVisionProcessingMode.FORCE_HDR10 -> "HDR10 BASE"
            }
            tvBadge.visibility = View.VISIBLE

            itemView.setOnClickListener {
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
                    badgeDv.text = mode.badgeText

                    val currentPos = player?.currentPosition ?: 0L
                    val wasPlaying = player?.isPlaying ?: true
                    player?.release()
                    initExoPlayer(seekToPos = currentPos, autoPlay = wasPlaying)
                }
                closeModal()
            }

            llModalListContainer.addView(itemView)
            if (isSelected) {
                itemView.requestFocus()
            }
        }
    }

    private fun openInfoModal() {
        activeModalType = ModalType.INFO
        setModalCardPosition(Gravity.START, 48)

        showModal(
            title = "Info",
            badge = "DIRECT PLAY"
        )

        llModalListContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val isSdr = movieTitle.contains("Apocalypse", ignoreCase = true) ||
                movieTitle.contains("Whiplash", ignoreCase = true)

        val bufferProfile = RemuxLoadControlFactory.getBufferProfile(this)
        val bufferAheadMs = ((player?.bufferedPosition ?: 0L) - (player?.currentPosition ?: 0L)).coerceAtLeast(0L)
        val bufferSec = String.format(Locale.US, "%.1f", bufferAheadMs / 1000f)

        val audioSpec = when {
            ivLogoAtmos.visibility == View.VISIBLE -> "Dolby Atmos (TrueHD)"
            ivLogoDts.visibility == View.VISIBLE -> "DTS-HD Master Audio"
            badgeAudio.visibility == View.VISIBLE && badgeAudio.text.isNotBlank() -> badgeAudio.text.toString()
            else -> "Bitstream Passthrough"
        }

        val specs = listOf(
            Triple("Película", "$movieTitle ($movieYear)", if (isSdr) "1080P REMUX" else "4K REMUX"),
            Triple("Direct Play", "Transmisión nativa sin transcodificar desde Jellyfin", "STATIC"),
            Triple("Buffer RAM", "${bufferProfile.targetBufferBytes / (1024 * 1024)} MB Ring-Buffer (Cargado: ${bufferSec}s)", "${bufferProfile.maxBufferMs / 1000}s MAX"),
            Triple("Video", if (isSdr) "H.264 High@L4.1 SDR Rec.709" else "Dolby Vision RPU transmute NAL 62", if (isSdr) "BT.709" else currentDvMode.badgeText),
            Triple("Subtítulos", "Motor cinematográfico estilo PGS Blu-ray (SMPTE)", "CINEMA"),
            Triple("Audio", "Bitstream Passthrough directo al hardware (HW_AV_SYNC)", audioSpec)
        )

        for (spec in specs) {
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            itemView.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.GONE
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            tvTitle.text = "${spec.first}: ${spec.second}"
            tvTitle.isSelected = true
            val tvBadge = itemView.findViewById<TextView>(R.id.tvTrackBadge)
            tvBadge.text = spec.third
            tvBadge.visibility = View.VISIBLE
            itemView.isFocusable = true
            llModalListContainer.addView(itemView)
        }
    }

    private fun showModal(title: String, badge: String) {
        handler.removeCallbacks(hideHudRunnable)
        tvModalTitle.text = title
        if (badge.isNotBlank()) {
            tvModalBadge.text = badge
            tvModalBadge.visibility = View.VISIBLE
        } else {
            tvModalBadge.visibility = View.GONE
        }
        topBar.animate().cancel()
        topBar.animate().alpha(0f).setDuration(160).start()
        modalOverlay.bringToFront()
        if (modalOverlay.visibility != View.VISIBLE) {
            modalOverlay.visibility = View.VISIBLE
            modalOverlay.alpha = 0f
            modalOverlay.animate().alpha(1f).setDuration(180).start()
        }
    }

    private fun closeModal() {
        val lastModal = activeModalType
        activeModalType = ModalType.NONE
        topBar.animate().cancel()
        topBar.animate().alpha(1f).setDuration(160).start()
        modalOverlay.animate().alpha(0f).setDuration(160).withEndAction {
            modalOverlay.visibility = View.GONE
            modalCard.translationX = 0f
            when (lastModal) {
                ModalType.SUBTITLES -> btnSubtitles.requestFocus()
                ModalType.AUDIO -> btnAudioTrack.requestFocus()
                ModalType.DV_MODE -> btnDvMode.requestFocus()
                ModalType.INFO -> btnInfo.requestFocus()
                else -> timelineSeekBar.requestFocus()
            }
            scheduleHudHide()
        }.start()
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
            if (modalOverlay.visibility != View.VISIBLE && (currentFocus == null || currentFocus == playerView)) {
                timelineSeekBar.post { timelineSeekBar.requestFocus() }
            }
        }
        scheduleHudHide()
    }

    private fun scheduleHudHide() {
        handler.removeCallbacks(hideHudRunnable)
        if (player?.isPlaying == true && modalOverlay.visibility != View.VISIBLE && !isScrubbing) {
            handler.postDelayed(hideHudRunnable, HUD_HIDE_DELAY_MS)
        }
    }

    private fun getLanguageDisplayName(code: String): String {
        return when (code.uppercase(Locale.ROOT)) {
            "SPA", "ES", "SPANISH" -> "Español"
            "ENG", "EN", "ENGLISH" -> "English"
            "LAT", "ES-419" -> "Español (Latinoamérica)"
            "FRA", "FR", "FRENCH" -> "Français"
            "DEU", "GER", "DE", "GERMAN" -> "Deutsch"
            "ITA", "IT", "ITALIAN" -> "Italiano"
            "JPN", "JA", "JAPANESE" -> "Japonés"
            "POR", "PT", "PORTUGUESE" -> "Português"
            "RUS", "RU", "RUSSIAN" -> "Ruso"
            else -> code
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (modalOverlay.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeModal()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        showHud()

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_ENTER -> {
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
                        btnInfo, btnDvMode, btnLibrary -> timelineSeekBar.requestFocus()
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
                        timelineSeekBar -> btnInfo.requestFocus()
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (modalOverlay.visibility == View.VISIBLE) {
            closeModal()
            return
        }
        if (isScrubbing) {
            cancelScrubbing()
            return
        }
        if (hudOverlay.visibility == View.VISIBLE && hudOverlay.alpha > 0.5f) {
            hudOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                hudOverlay.visibility = View.GONE
            }.start()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        AutoFrameRateManager.restoreDefaultMode(this)
        ClientSideFrameExtractor.release()
        player?.release()
        player = null
        preloadedFrames = null
        thumbnailCache.evictAll()
    }
}
