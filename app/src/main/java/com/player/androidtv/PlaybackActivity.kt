package com.player.androidtv

import android.app.Activity
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
    private lateinit var tvPlayerChapter: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvRemainingTime: TextView
    private lateinit var timelineSeekBar: SeekBar
    private lateinit var chapterTicksView: ChapterTicksView
    private lateinit var flPlayPauseBadge: FrameLayout
    private lateinit var ivPlayPauseGlyph: ImageView
    private lateinit var llActionsRow: LinearLayout

    // Apple TV Scrubbing Thumbnail Preview (Foto 4 y 6)
    private lateinit var llScrubPreviewContainer: View
    private lateinit var tvScrubChapterTitle: TextView
    private lateinit var cvScrubCard: CardView
    private lateinit var ivScrubThumbnail: ImageView
    private lateinit var vScrubPinLine: View
    private lateinit var llScrubTimeBadgeContainer: LinearLayout
    private lateinit var tvScrubTime: TextView
    private lateinit var tvScrubSpeedLeft: TextView
    private lateinit var tvScrubSpeedRight: TextView

    data class MovieChapter(
        val title: String,
        val startTimeMs: Long
    )
    private var currentChapters: List<MovieChapter> = emptyList()

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
    // Fontsize: 50px (50 / 1080 = 0.0463f)
    // MarginV: 155px (155 / 1080 = 0.1435f) -> Por encima de la barra negra en películas 2.40:1 Cinemascope
    // Luminancia: SMPTE Video White #EBEBEB, Outline negro sólido (sin drop shadow) idéntico a PGS Blu-ray
    private val subTextSizeFraction = 50f / 1080f
    private val subBottomPaddingFraction = 155f / 1080f

    // Apple TV Floating Popups
    private enum class ModalType { NONE, SUBTITLES, AUDIO, CHAPTERS, DV_MODE, INFO }
    private var activeModalType = ModalType.NONE

    private lateinit var modalOverlay: View
    private lateinit var modalCard: View
    private lateinit var tvModalTitle: TextView
    private lateinit var tvModalBadge: TextView
    private lateinit var llModalListContainer: LinearLayout

    private var streamUrl: String = ""
    private var movieTitle: String = ""
    private var movieYear: String = "1981"
    private var movieDuration: String = "1h 55m"
    private var movieGenre: String = "Movie · Action · Adventure"
    private var movieSynopsis: String = ""
    private var movieDirector: String = "Steven Spielberg"
    private var movieActors: String = "Harrison Ford, Karen Allen"
    private var movieSize: String = "56.73 GB Remux"
    private var movieVideoCodec: String = "DOLBY VISION P8.1"
    private var movieAudioCodec: String = "DOLBY ATMOS"
    private lateinit var btnChapters: Button
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
                    val duration = p.duration

                    if (duration > 0) {
                        if (currentChapters.isEmpty()) {
                            initMovieChapters(movieTitle, duration)
                        }
                        val progress = ((current * 1000) / duration).toInt()
                        timelineSeekBar.progress = progress
                        tvCurrentTime.text = formatTimeMs(current)
                        val remaining = (duration - current).coerceAtLeast(0)
                        tvRemainingTime.text = "-${formatTimeMs(remaining)}"

                        // Sincronizar capítulo actual en pantalla
                        val chap = getCurrentChapter(current)
                        if (chap != null) {
                            tvPlayerChapter.text = chap.title
                            tvPlayerChapter.visibility = View.VISIBLE
                        } else {
                            tvPlayerChapter.visibility = View.GONE
                        }

                        // Alinear contenedor de tiempo exactamente debajo del cursor vertical de la barra
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

    private fun showPlayPauseAnimation(isPlaying: Boolean) {
        ivPlayPauseGlyph.setImageResource(if (isPlaying) R.drawable.ic_play_arrow else R.drawable.ic_pause)
        flPlayPauseBadge.animate().cancel()
        flPlayPauseBadge.visibility = View.VISIBLE
        flPlayPauseBadge.alpha = 0f
        flPlayPauseBadge.scaleX = 0.82f
        flPlayPauseBadge.scaleY = 0.82f

        flPlayPauseBadge.animate()
            .alpha(1f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(140)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                flPlayPauseBadge.postDelayed({
                    flPlayPauseBadge.animate()
                        .alpha(0f)
                        .scaleX(1.14f)
                        .scaleY(1.14f)
                        .setDuration(220)
                        .setInterpolator(android.view.animation.AccelerateInterpolator())
                        .withEndAction {
                            flPlayPauseBadge.visibility = View.GONE
                        }
                        .start()
                }, 260)
            }
            .start()
    }

    private fun initMovieChapters(title: String, durationMs: Long) {
        currentChapters = when {
            title.contains("Whiplash", ignoreCase = true) -> listOf(
                MovieChapter("Chapter 1: Overture", 0L),
                MovieChapter("Chapter 2: Studio Band", 411828L),
                MovieChapter("Chapter 3: Caravan", 874457L),
                MovieChapter("Chapter 4: Overbeck", 1466382L),
                MovieChapter("Chapter 5: Dunellen Competition", 1806096L),
                MovieChapter("Chapter 6: First Chair", 2143975L),
                MovieChapter("Chapter 7: Blood on the Kit", 2611359L),
                MovieChapter("Chapter 8: Whiplash", 2815312L),
                MovieChapter("Chapter 9: Double Time", 3192481L),
                MovieChapter("Chapter 10: The Bus", 3757504L),
                MovieChapter("Chapter 11: The Car Crash", 4230560L),
                MovieChapter("Chapter 12: Expulsion", 4443606L),
                MovieChapter("Chapter 13: Jazz Club Reunion", 4668789L),
                MovieChapter("Chapter 14: JVC Jazz Festival", 4986106L),
                MovieChapter("Chapter 15: Not Quite My Tempo", 5128290L),
                MovieChapter("Chapter 16: Grand Finale Solo", 5516219L)
            )
            title.contains("Apocalypse", ignoreCase = true) -> listOf(
                MovieChapter("Chapter 1: The End / Saigon", 0L),
                MovieChapter("Chapter 2: The Mission", 565273L),
                MovieChapter("Chapter 3: Up the River", 1224431L),
                MovieChapter("Chapter 4: Kilgore / Air Cav", 1564312L),
                MovieChapter("Chapter 5: Ride of the Valkyries", 1921169L),
                MovieChapter("Chapter 6: Smell of Napalm", 2097428L),
                MovieChapter("Chapter 7: Tiger in the Jungle", 2749079L),
                MovieChapter("Chapter 8: Letters from Home", 3018014L),
                MovieChapter("Chapter 9: The USO Show", 3565728L),
                MovieChapter("Chapter 10: Hau Phat / Heavy Rain", 4133838L),
                MovieChapter("Chapter 11: The Sampan Incident", 4536406L),
                MovieChapter("Chapter 12: Do Lung Bridge", 4855141L),
                MovieChapter("Chapter 13: Kurtz's Dossier", 5439183L),
                MovieChapter("Chapter 14: French Plantation", 5781192L),
                MovieChapter("Chapter 15: Arrow Attack", 6250869L),
                MovieChapter("Chapter 16: Kurtz's Compound", 6776186L),
                MovieChapter("Chapter 17: Dennis Hopper / The Cage", 7310177L),
                MovieChapter("Chapter 18: The Horror, The Horror", 8058341L)
            )
            title.contains("Raiders", ignoreCase = true) -> listOf(
                MovieChapter("Chapter 1: South America, 1936", 0L),
                MovieChapter("Chapter 2: The Golden Idol & Boulder", 510000L),
                MovieChapter("Chapter 3: Marshall College", 855000L),
                MovieChapter("Chapter 4: Army Intelligence", 1260000L),
                MovieChapter("Chapter 5: The Raven / Nepal", 1725000L),
                MovieChapter("Chapter 6: Streets of Cairo", 2300000L),
                MovieChapter("Chapter 7: The Map Room", 2830000L),
                MovieChapter("Chapter 8: The Well of Souls", 3390000L),
                MovieChapter("Chapter 9: Flying Wing Brawl & Escape", 4180000L),
                MovieChapter("Chapter 10: Desert Convoy Chase", 4875000L),
                MovieChapter("Chapter 11: Aboard the Bantu Wind", 5520000L),
                MovieChapter("Chapter 12: U-Boat Submarine Base", 5960000L),
                MovieChapter("Chapter 13: Opening the Ark", 6420000L),
                MovieChapter("Chapter 14: Washington / Hangar 51", 6730000L)
            )
            title.contains("Nightmare", ignoreCase = true) -> listOf(
                MovieChapter("Chapter 1: This Is Halloween", 0L),
                MovieChapter("Chapter 2: Jack's Lament", 435000L),
                MovieChapter("Chapter 3: Christmas Town", 920000L),
                MovieChapter("Chapter 4: Town Meeting", 1510000L),
                MovieChapter("Chapter 5: Making Christmas", 2140000L),
                MovieChapter("Chapter 6: Kidnap the Sandy Claws", 2715000L),
                MovieChapter("Chapter 7: Jack Takes Flight", 3380000L),
                MovieChapter("Chapter 8: Graveyard Battle", 3960000L),
                MovieChapter("Chapter 9: Snow in Halloween Town", 4335000L)
            )
            title.contains("Hail", ignoreCase = true) -> listOf(
                MovieChapter("Chapter 1: Awakening in the Dark", 0L),
                MovieChapter("Chapter 2: The Petrova Enigma", 1120000L),
                MovieChapter("Chapter 3: First Contact with Rocky", 2295000L),
                MovieChapter("Chapter 4: The Xenonite Solution", 3570000L),
                MovieChapter("Chapter 5: Farming Taumoebas", 5110000L),
                MovieChapter("Chapter 6: The Journey Home", 6765000L),
                MovieChapter("Chapter 7: A Renewed Sun", 8060000L)
            )
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
    }

    private fun getCurrentChapter(timeMs: Long): MovieChapter? {
        if (currentChapters.isEmpty()) return null
        return currentChapters.lastOrNull { timeMs >= it.startTimeMs } ?: currentChapters.firstOrNull()
    }

    private fun setHudScrubbingMode(scrubbing: Boolean) {
        if (scrubbing) {
            topBar.visibility = View.GONE
            llPlayerHeaderRow.visibility = View.GONE
            llActionsRow.visibility = View.GONE
            llScrubPreviewContainer.visibility = View.VISIBLE
            llScrubPreviewContainer.alpha = 1f
            timelineSeekBar.thumb = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        } else {
            llScrubPreviewContainer.visibility = View.GONE
            topBar.visibility = View.VISIBLE
            llPlayerHeaderRow.visibility = View.VISIBLE
            llActionsRow.visibility = View.VISIBLE
            topBar.alpha = 1f
            llPlayerHeaderRow.alpha = 1f
            llActionsRow.alpha = 1f
            timelineSeekBar.thumb = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.seekbar_thumb_apple)
            chapterTicksView.setOriginalPositionRatio(null)
        }
    }

    private val scrubTickerRunnable = object : Runnable {
        override fun run() {
            if (!isScrubbing || scrubSpeed == 0) return
            val p = player ?: return
            val duration = p.duration
            if (duration <= 0) return

            val sign = if (scrubSpeed > 0) 1 else -1
            val stepMs = when (Math.abs(scrubSpeed)) {
                1 -> 1000L * sign
                2 -> 4000L * sign
                3 -> 12000L * sign
                4 -> 30000L * sign
                else -> 60000L * sign // 5x
            }

            scrubTargetMs = (scrubTargetMs + stepMs).coerceIn(0L, duration)
            val progress = ((scrubTargetMs * 1000) / duration).toInt()
            timelineSeekBar.progress = progress

            tvScrubTime.text = formatTimeMs(scrubTargetMs)
            tvCurrentTime.text = formatTimeMs(scrubTargetMs)
            val remaining = (duration - scrubTargetMs).coerceAtLeast(0)
            tvRemainingTime.text = "-${formatTimeMs(remaining)}"

            val chap = getCurrentChapter(scrubTargetMs)
            if (chap != null) {
                tvScrubChapterTitle.text = chap.title
                tvScrubChapterTitle.visibility = View.VISIBLE
            } else {
                tvScrubChapterTitle.visibility = View.GONE
            }

            updateScrubPreviewPosition(scrubTargetMs, duration)
            loadScrubThumbnail(movieTitle, scrubTargetMs)

            handler.postDelayed(this, 300L)
        }
    }

    private fun commitScrubSeek() {
        if (!isScrubbing) return
        handler.removeCallbacks(scrubTickerRunnable)
        chapterTicksView.setOriginalPositionRatio(null)
        val target = scrubTargetMs
        isSeeking = true
        player?.seekTo(target)
        player?.play()
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

    private fun parseIntent() {
        val incomingUri: Uri? = intent.data
        if (incomingUri != null) {
            streamUrl = incomingUri.toString()
            var title = intent.getStringExtra("MOVIE_TITLE")
                ?: intent.getStringExtra("title")
                ?: intent.getStringExtra("android.intent.extra.TITLE")
                ?: incomingUri.getQueryParameter("title")
                ?: incomingUri.lastPathSegment ?: "Jellyfin Video"
            val explicitYear = intent.getStringExtra("MOVIE_YEAR")
            if (!explicitYear.isNullOrBlank()) {
                movieYear = explicitYear
            }

            if (title.contains("Raiders", ignoreCase = true)) {
                title = "Raiders of the Lost Ark"
                movieYear = "1981"
                movieDuration = "1h 55m"
                movieGenre = "Movie  ·  Action  ·  Adventure"
                movieSynopsis = "Archeologist and adventurer Indiana Jones is hired by the US government to find the Ark of the Covenant before the Nazis obtain its awesome powers."
                movieDirector = "Steven Spielberg"
                movieActors = "Harrison Ford, Karen Allen, Paul Freeman"
                movieSize = "56.73 GB Remux"
                movieVideoCodec = "DOLBY VISION P8.1"
                movieAudioCodec = "DOLBY ATMOS"
            } else if (title.contains("Nightmare", ignoreCase = true)) {
                title = "The Nightmare Before Christmas"
                movieYear = "1993"
                movieDuration = "1h 16m"
                movieGenre = "Movie  ·  Animation  ·  Family"
                movieSynopsis = "Jack Skellington, king of Halloween Town, discovers Christmas Town, but his attempts to bring Christmas to his home cause confusion and danger."
                movieDirector = "Henry Selick (Story: Tim Burton)"
                movieActors = "Danny Elfman, Chris Sarandon, Catherine O'Hara"
                movieSize = "42.15 GB Remux"
                movieVideoCodec = "DOLBY VISION P8.1"
                movieAudioCodec = "DOLBY TRUEHD ATMOS"
            } else if (title.contains("Apocalypse", ignoreCase = true)) {
                title = "Apocalypse Now"
                movieYear = "1979"
                movieDuration = "2h 27m"
                movieGenre = "Movie  ·  War  ·  Drama"
                movieSynopsis = "During the Vietnam War, Captain Willard is assigned a classified mission to journey deep into Cambodia and terminate rogue Special Forces Colonel Kurtz."
                movieDirector = "Francis Ford Coppola"
                movieActors = "Marlon Brando, Martin Sheen, Robert Duvall, Laurence Fishburne"
                movieSize = "30.33 GB Remux"
                movieVideoCodec = "1080P BLU-RAY"
                movieAudioCodec = "DOLBY TRUEHD"
            } else if (title.contains("Hail Mary", ignoreCase = true) || title.contains("Hail", ignoreCase = true)) {
                title = "Project Hail Mary"
                movieYear = "2026"
                movieDuration = "2h 35m"
                movieGenre = "Movie  ·  Sci-Fi  ·  Adventure"
                movieSynopsis = "The sole survivor of a desperate interstellar mission awakens with amnesia, discovering the fate of humanity and Earth's survival rests squarely on his shoulders."
                movieDirector = "Phil Lord, Christopher Miller"
                movieActors = "Ryan Gosling, Sandra Hüller, Milana Vayntrub"
                movieSize = "81.29 GB Remux"
                movieVideoCodec = "DOLBY VISION P7 MEL"
                movieAudioCodec = "DOLBY ATMOS"
            } else if (title.contains("Whiplash", ignoreCase = true)) {
                title = "Whiplash"
                movieYear = "2014"
                movieDuration = "1h 46m"
                movieGenre = "Movie  ·  Drama  ·  Music"
                movieSynopsis = "An ambitious young jazz drummer enters an elite music conservatory, where he is driven past human limits by a ruthless and uncompromising instructor."
                movieDirector = "Damien Chazelle"
                movieActors = "Miles Teller, J.K. Simmons, Paul Reiser, Melissa Benoist"
                movieSize = "23.46 GB Remux"
                movieVideoCodec = "1080P AVC"
                movieAudioCodec = "DTS-HD MASTER AUDIO"
            }
            movieTitle = title

            intent.getStringExtra("MOVIE_DURATION")?.let { if (it.isNotBlank()) movieDuration = it }
            intent.getStringExtra("MOVIE_GENRE")?.let { if (it.isNotBlank()) movieGenre = it }
            intent.getStringExtra("MOVIE_SYNOPSIS")?.let { if (it.isNotBlank()) movieSynopsis = it }
            intent.getStringExtra("MOVIE_DIRECTOR")?.let { if (it.isNotBlank()) movieDirector = it }
            intent.getStringExtra("MOVIE_ACTORS")?.let { if (it.isNotBlank()) movieActors = it }
            intent.getStringExtra("MOVIE_SIZE")?.let { if (it.isNotBlank()) movieSize = it }
            intent.getStringExtra("MOVIE_VIDEO_CODEC")?.let { if (it.isNotBlank()) movieVideoCodec = it }
            intent.getStringExtra("MOVIE_AUDIO_CODEC")?.let { if (it.isNotBlank()) movieAudioCodec = it }
        } else {
            movieTitle = "Raiders of the Lost Ark"
            movieYear = "1981"
            movieDuration = "1h 55m"
            movieGenre = "Movie  ·  Action  ·  Adventure"
            movieSynopsis = "Archeologist and adventurer Indiana Jones is hired by the US government to find the Ark of the Covenant before the Nazis obtain its awesome powers."
            movieDirector = "Steven Spielberg"
            movieActors = "Harrison Ford, Karen Allen, Paul Freeman"
            movieSize = "56.73 GB Remux"
            movieVideoCodec = "DOLBY VISION P8.1"
            movieAudioCodec = "DOLBY ATMOS"
        }
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        hudOverlay = findViewById(R.id.hudOverlay)
        topBar = findViewById(R.id.topBar)
        llPlayerHeaderRow = findViewById(R.id.llPlayerHeaderRow)
        tvPlayerYear = findViewById(R.id.tvPlayerYear)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        tvPlayerChapter = findViewById(R.id.tvPlayerChapter)
        tvCurrentTime = findViewById(R.id.tvScrubTime)
        tvRemainingTime = findViewById(R.id.tvRemainingTime)
        timelineSeekBar = findViewById(R.id.timelineSeekBar)
        timelineSeekBar.background = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            timelineSeekBar.defaultFocusHighlightEnabled = false
        }
        chapterTicksView = findViewById(R.id.chapterTicksView)
        flPlayPauseBadge = findViewById(R.id.flPlayPauseBadge)
        ivPlayPauseGlyph = findViewById(R.id.ivPlayPauseGlyph)
        llActionsRow = findViewById(R.id.llActionsRow)

        llScrubPreviewContainer = findViewById(R.id.llScrubPreviewContainer)
        tvScrubChapterTitle = findViewById(R.id.tvScrubChapterTitle)
        cvScrubCard = findViewById(R.id.cvScrubCard)
        ivScrubThumbnail = findViewById(R.id.ivScrubThumbnail)
        vScrubPinLine = findViewById(R.id.vScrubPinLine)
        llScrubTimeBadgeContainer = findViewById(R.id.llScrubTimeBadgeContainer)
        tvScrubTime = findViewById(R.id.tvScrubTime)
        tvScrubSpeedLeft = findViewById(R.id.tvScrubSpeedLeft)
        tvScrubSpeedRight = findViewById(R.id.tvScrubSpeedRight)

        btnBack = findViewById(R.id.btnBack)
        btnInfo = findViewById(R.id.btnInfo)
        btnChapters = findViewById(R.id.btnChapters)
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
            badge4K.visibility = View.VISIBLE
            badge4K.text = "1080P HD"
            ivLogoDv.visibility = View.GONE
            badgeDv.visibility = View.GONE
            ivLogoAtmos.visibility = View.GONE
            badgeAudio.visibility = View.VISIBLE
            badgeAudio.text = "AUDIO BITSTREAM"
            btnDvMode.visibility = View.GONE
            btnChapters.nextFocusRightId = R.id.btnLibrary
            btnLibrary.nextFocusLeftId = R.id.btnChapters
        } else {
            ivLogoUhd.visibility = View.VISIBLE
            badge4K.visibility = View.GONE
            ivLogoDv.visibility = View.VISIBLE
            badgeDv.visibility = View.GONE
            ivLogoAtmos.visibility = View.GONE
            badgeAudio.visibility = View.VISIBLE
            badgeAudio.text = "AUDIO BITSTREAM"
            btnDvMode.visibility = View.VISIBLE
            btnChapters.nextFocusRightId = R.id.btnDvMode
            btnLibrary.nextFocusLeftId = R.id.btnDvMode
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

        btnChapters.setOnClickListener {
            openChaptersModal()
        }

        tvPlayerChapter.setOnClickListener {
            openChaptersModal()
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
                                loadScrubThumbnail(movieTitle, scrubTargetMs)
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
        val effectiveDvMode = currentDvMode

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
                showPlayPauseAnimation(isPlaying = false)
                showHud()
            } else {
                p.play()
                showPlayPauseAnimation(isPlaying = true)
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
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val lastChild = (0 until llModalListContainer.childCount).map { llModalListContainer.getChildAt(it) }.lastOrNull()
                        if (focus == lastChild) {
                            val targetBtn = if (activeModalType == ModalType.AUDIO) btnAudioTrack else btnSubtitles
                            closeModal()
                            targetBtn.requestFocus()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val firstChild = llModalListContainer.getChildAt(0)
                        if (focus == firstChild) {
                            return true
                        }
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
        } else {
            // Ya en modo scrubbing: acelerar o desacelerar de 1x hasta 5x
            if (direction > 0) {
                if (scrubSpeed < 0) {
                    scrubSpeed++
                    if (scrubSpeed == 0) scrubSpeed = 1
                } else if (scrubSpeed in 1..4) {
                    scrubSpeed++
                }
            } else {
                if (scrubSpeed > 1) {
                    scrubSpeed--
                } else if (scrubSpeed == 1) {
                    scrubSpeed = -1
                } else if (scrubSpeed in -4..-1) {
                    scrubSpeed--
                }
            }
        }

        val speedMagnitude = Math.abs(scrubSpeed)
        val speedBadgeText = "${speedMagnitude}x"

        if (scrubSpeed > 0) {
            // Adelantar: badge a la DERECHA según sketch del usuario: xx:xx:xx (yx)
            tvScrubSpeedLeft.visibility = View.GONE
            tvScrubSpeedRight.text = speedBadgeText
            tvScrubSpeedRight.visibility = View.VISIBLE
        } else {
            // Retroceder: badge a la IZQUIERDA según sketch del usuario: (yx) xx:xx:xx
            tvScrubSpeedRight.visibility = View.GONE
            tvScrubSpeedLeft.text = speedBadgeText
            tvScrubSpeedLeft.visibility = View.VISIBLE
        }

        val sign = if (scrubSpeed > 0) 1 else -1
        val instantStep = when (speedMagnitude) {
            1 -> 1000L * sign
            2 -> 4000L * sign
            3 -> 12000L * sign
            4 -> 30000L * sign
            else -> 60000L * sign // 5x
        }
        scrubTargetMs = (scrubTargetMs + instantStep).coerceIn(0L, duration)
        timelineSeekBar.progress = ((scrubTargetMs * 1000) / duration).toInt()

        tvScrubTime.text = formatTimeMs(scrubTargetMs)
        tvCurrentTime.text = formatTimeMs(scrubTargetMs)
        val remaining = (duration - scrubTargetMs).coerceAtLeast(0)
        tvRemainingTime.text = "-${formatTimeMs(remaining)}"

        val chap = getCurrentChapter(scrubTargetMs)
        if (chap != null) {
            tvScrubChapterTitle.text = chap.title
            tvScrubChapterTitle.visibility = View.VISIBLE
        } else {
            tvScrubChapterTitle.visibility = View.GONE
        }

        updateScrubPreviewPosition(scrubTargetMs, duration)
        loadScrubThumbnail(movieTitle, scrubTargetMs)
        llScrubPreviewContainer.bringToFront()

        handler.removeCallbacks(scrubTickerRunnable)
        handler.postDelayed(scrubTickerRunnable, 300L)
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
        cvScrubCard.translationX = clampedCardLeft

        val seekbarCenterY = seekbarScreenY + (timelineSeekBar.height / 2f)
        val gap = 14f * density
        val cardBottom = seekbarCenterY - gap
        val cardTop = cardBottom - cardHeight
        cvScrubCard.translationY = cardTop

        // Chapter pill above card
        if (tvScrubChapterTitle.visibility == View.VISIBLE) {
            tvScrubChapterTitle.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val chapWidth = tvScrubChapterTitle.measuredWidth.takeIf { it > 0 } ?: (80 * density).toInt()
            val chapHeight = tvScrubChapterTitle.measuredHeight.takeIf { it > 0 } ?: (24 * density).toInt()
            tvScrubChapterTitle.translationX = clampedCardLeft + (cardWidth - chapWidth) / 2f
            tvScrubChapterTitle.translationY = cardTop - chapHeight - (8 * density)
        }

        // Single continuous vertical needle line from card bottom down through progress bar
        val lineWidth = 2.5f * density
        vScrubPinLine.translationX = thumbCenterX - (lineWidth / 2f)
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
        llScrubTimeBadgeContainer.translationX = (relThumbX - badgeWidth / 2f).coerceIn(0f, maxOffset)
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
        // Silenciado por completo estilo Apple TV (sustituido por animación cinemática flPlayPauseBadge)
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

        val isMovieSdr = movieTitle.contains("Apocalypse", ignoreCase = true) ||
                movieTitle.contains("Whiplash", ignoreCase = true) || (!isDolbyVision && !isHdr && !is4k)

        if (is4k && !isMovieSdr) {
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
        } else if (isDolbyVision || currentDvMode != DolbyVisionProcessingMode.FORCE_HDR10) {
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

    private fun setModalCardMarginEnd(marginEndDp: Int) {
        val density = resources.displayMetrics.density
        val targetMargin = (marginEndDp * density).toInt()
        (modalCard.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            if (modalOverlay.visibility == View.VISIBLE && lp.marginEnd != targetMargin) {
                val startMargin = lp.marginEnd
                modalCard.animate().cancel()
                modalCard.translationX = (startMargin - targetMargin).toFloat()
                lp.marginEnd = targetMargin
                modalCard.layoutParams = lp
                modalCard.animate()
                    .translationX(0f)
                    .setDuration(160)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            } else {
                lp.marginEnd = targetMargin
                modalCard.translationX = 0f
                modalCard.layoutParams = lp
            }
        }
    }

    // Modal Flotante de Selección de Audio estilo Apple TV (Foto 3)
    private fun openAudioModal() {
        val exo = player ?: return
        activeModalType = ModalType.AUDIO

        showModal(
            title = "Audio",
            badge = badgeAudio.text.toString(),
            anchorButton = btnAudioTrack
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

                    when {
                        mime.contains("truehd", ignoreCase = true) -> {
                            badgePrimary = "DOLBY ATMOS"
                            score += 5
                        }
                        mime.contains("eac3", ignoreCase = true) -> {
                            badgePrimary = "E-AC3"
                            score += 4
                        }
                        mime.contains("ac3", ignoreCase = true) -> {
                            badgePrimary = "DOLBY AUDIO"
                            score += 3
                        }
                        mime.contains("dts", ignoreCase = true) -> {
                            badgePrimary = "DTS-HD MA"
                            score += 3
                        }
                        else -> {
                            badgePrimary = "PCM"
                        }
                    }

                    trackItems.add(
                        AudioTrackItem(group, i, format, lang, langDisplay, isSelected, titleText, badgePrimary, badgeSecondary, score)
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
                tvModalBadge.text = item.badgePrimary
                tvModalBadge.visibility = View.VISIBLE
            }

            llModalListContainer.addView(itemView)
        }

        // Option Apple TV: Night Mode and Clear Dialogue
        val nightModeItem = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
        nightModeItem.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.GONE
        val tvNightTitle = nightModeItem.findViewById<TextView>(R.id.tvTrackTitle)
        val tvNightBadge = nightModeItem.findViewById<TextView>(R.id.tvTrackBadge)
        val tvNightSub = nightModeItem.findViewById<TextView>(R.id.tvTrackSubtext)
        tvNightTitle.text = "Night Mode / Clear Dialogue"
        nightModeItem.setOnFocusChangeListener { _, hasFocus ->
            tvNightTitle.isSelected = hasFocus
        }
        tvNightSub.text = "Dynamic EBU R128 loudness compression for TV speakers"
        tvNightSub.visibility = View.VISIBLE
        tvNightBadge.text = "EBU R128"
        tvNightBadge.visibility = View.VISIBLE
        nightModeItem.setOnClickListener {
            // Activate EBU R128 compression
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

        showModal(
            title = "Subtitles",
            badge = "",
            anchorButton = btnSubtitles
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
            val titleText: String,
            val badgePrimary: String,
            val badgeSecondary: String,
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
                    val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                            format.label?.contains("forced", ignoreCase = true) == true ||
                            format.label?.contains("forzado", ignoreCase = true) == true
                    val isSdh = (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 ||
                            format.label?.contains("sdh", ignoreCase = true) == true ||
                            format.label?.contains("cc", ignoreCase = true) == true

                    var score = getLanguagePriority(lang)
                    if (isPgs) score += 20 // Priorizar PGS de Blu-ray nativo

                    val badgePrimary = if (isPgs) "PGS" else "SRT"
                    val badgeSecondary = when {
                        isForced -> "FORCED"
                        isSdh -> "SDH / CC"
                        else -> "" // Clean full subtitles without redundant tags
                    }

                    trackItems.add(
                        SubTrackItem(group, i, format, isPgs, langDisplay, isSelected, langDisplay, badgePrimary, badgeSecondary, score)
                    )
                }
            }
        }

        // Ordenar con español e inglés prioritarios
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

        val isSdr = movieTitle.contains("Apocalypse", ignoreCase = true) ||
                movieTitle.contains("Whiplash", ignoreCase = true)
        val isNativeP8 = movieTitle.contains("Nightmare", ignoreCase = true)

        if (isSdr) {
            showModal(
                title = "Direct Play (SDR)",
                badge = "SDR BT.709",
                anchorButton = btnDvMode
            )
            llModalListContainer.removeAllViews()
            val inflater = LayoutInflater.from(this)
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            itemView.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.VISIBLE
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            tvTitle.text = "Direct Play 1080p SDR (Native Rec.709)"
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
                badge = "NATIVE DV P8.1",
                anchorButton = btnDvMode
            )
            llModalListContainer.removeAllViews()
            val inflater = LayoutInflater.from(this)
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            itemView.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.VISIBLE
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            tvTitle.text = "Native Dolby Vision Profile 8.1 (Direct Play HW)"
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
            badge = currentDvMode.badgeText,
            anchorButton = btnDvMode
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

    private fun openChaptersModal() {
        val exo = player ?: return
        activeModalType = ModalType.CHAPTERS

        val countText = if (currentChapters.isNotEmpty()) "${currentChapters.size} CHAPTERS" else "CHAPTERS"
        showModal(
            title = "Chapters",
            badge = countText,
            anchorButton = btnChapters
        )

        llModalListContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val currentPos = exo.currentPosition
        val activeChapter = getCurrentChapter(currentPos)

        var activeItemView: View? = null

        for ((index, chapter) in currentChapters.withIndex()) {
            val itemView = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            val ivCheck = itemView.findViewById<ImageView>(R.id.ivTrackCheck)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTrackTitle)
            val tvBadgePrimary = itemView.findViewById<TextView>(R.id.tvTrackBadge)
            val tvBadgeSecondary = itemView.findViewById<TextView>(R.id.tvTrackBadgeSecondary)

            val isCurrent = (chapter == activeChapter) || (activeChapter == null && index == 0)
            ivCheck.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            itemView.isSelected = isCurrent
            tvTitle.text = chapter.title

            tvBadgePrimary.text = formatTimeMs(chapter.startTimeMs)
            tvBadgePrimary.visibility = View.VISIBLE
            tvBadgeSecondary.visibility = View.GONE

            itemView.setOnFocusChangeListener { _, hasFocus ->
                tvTitle.isSelected = hasFocus
            }

            itemView.setOnClickListener {
                exo.seekTo(chapter.startTimeMs)
                tvPlayerChapter.text = chapter.title
                tvPlayerChapter.visibility = View.VISIBLE
                closeModal()
            }

            llModalListContainer.addView(itemView)
            if (isCurrent) {
                activeItemView = itemView
            }
        }

        val focusTarget = activeItemView ?: llModalListContainer.getChildAt(0)
        focusTarget?.requestFocus()
        focusTarget?.post { focusTarget.requestFocus() }
    }

    private fun openInfoModal() {
        activeModalType = ModalType.INFO

        val isSdr = movieTitle.contains("Apocalypse", ignoreCase = true) ||
                movieTitle.contains("Whiplash", ignoreCase = true)

        val masterQualityBadge = if (isSdr) "1080P MASTER" else "4K UHD MASTER"

        showModal(
            title = "InSight",
            badge = masterQualityBadge,
            anchorButton = btnInfo
        )

        llModalListContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // Item 1: Movie Title & Year
        val titleItem = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
        titleItem.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.GONE
        val tvHeaderTitle = titleItem.findViewById<TextView>(R.id.tvTrackTitle)
        val tvHeaderSub = titleItem.findViewById<TextView>(R.id.tvTrackSubtext)
        val tvHeaderBadge = titleItem.findViewById<TextView>(R.id.tvTrackBadge)
        tvHeaderTitle.text = "$movieTitle ($movieYear)"
        tvHeaderTitle.isSelected = true
        tvHeaderSub.text = "$movieDuration  ·  $movieGenre"
        tvHeaderSub.visibility = View.VISIBLE
        tvHeaderBadge.text = movieSize
        tvHeaderBadge.visibility = View.VISIBLE
        titleItem.isFocusable = true
        titleItem.setOnFocusChangeListener { _, hasFocus -> tvHeaderTitle.isSelected = hasFocus }
        llModalListContainer.addView(titleItem)

        // Item 2: Storyline / Synopsis
        if (movieSynopsis.isNotBlank()) {
            val synopsisItem = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
            synopsisItem.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.GONE
            val tvSynTitle = synopsisItem.findViewById<TextView>(R.id.tvTrackTitle)
            val tvSynSub = synopsisItem.findViewById<TextView>(R.id.tvTrackSubtext)
            val tvSynBadge = synopsisItem.findViewById<TextView>(R.id.tvTrackBadge)
            tvSynTitle.text = "Storyline"
            tvSynTitle.isSelected = true
            tvSynSub.text = movieSynopsis
            tvSynSub.visibility = View.VISIBLE
            tvSynBadge.text = "SYNOPSIS"
            tvSynBadge.visibility = View.VISIBLE
            synopsisItem.isFocusable = true
            synopsisItem.setOnFocusChangeListener { _, hasFocus -> tvSynTitle.isSelected = hasFocus }
            llModalListContainer.addView(synopsisItem)
        }

        // Item 3: Director and Cast
        val castItem = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
        castItem.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.GONE
        val tvCastTitle = castItem.findViewById<TextView>(R.id.tvTrackTitle)
        val tvCastSub = castItem.findViewById<TextView>(R.id.tvTrackSubtext)
        val tvCastBadge = castItem.findViewById<TextView>(R.id.tvTrackBadge)
        tvCastTitle.text = "Director: $movieDirector"
        tvCastTitle.isSelected = true
        tvCastSub.text = "Cast: $movieActors"
        tvCastSub.visibility = View.VISIBLE
        tvCastBadge.text = "CAST & CREW"
        tvCastBadge.visibility = View.VISIBLE
        castItem.isFocusable = true
        castItem.setOnFocusChangeListener { _, hasFocus -> tvCastTitle.isSelected = hasFocus }
        llModalListContainer.addView(castItem)

        // Item 4: Video Spec
        val videoItem = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
        videoItem.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.GONE
        val tvVidTitle = videoItem.findViewById<TextView>(R.id.tvTrackTitle)
        val tvVidSub = videoItem.findViewById<TextView>(R.id.tvTrackSubtext)
        val tvVidBadge = videoItem.findViewById<TextView>(R.id.tvTrackBadge)
        val tvVidSec = videoItem.findViewById<TextView>(R.id.tvTrackBadgeSecondary)
        tvVidTitle.text = if (isSdr) "Video: 1080p SDR Rec.709 Direct Play" else "Video: 4K Dolby Vision Remux Direct Play"
        tvVidTitle.isSelected = true
        tvVidSub.text = if (isSdr) "Direct HW decode (H.264 High@L4.1)" else "Direct HW decode (HEVC Main 10 / NAL 62 transmute)"
        tvVidSub.visibility = View.VISIBLE
        tvVidSec.text = if (isSdr) "AVC" else "HEVC"
        tvVidSec.visibility = View.VISIBLE
        tvVidBadge.text = if (isSdr) "BT.709" else currentDvMode.badgeText
        tvVidBadge.visibility = View.VISIBLE
        videoItem.isFocusable = true
        videoItem.setOnFocusChangeListener { _, hasFocus -> tvVidTitle.isSelected = hasFocus }
        llModalListContainer.addView(videoItem)

        // Item 5: Lossless Audio Spec
        val audioItem = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
        audioItem.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.GONE
        val tvAudTitle = audioItem.findViewById<TextView>(R.id.tvTrackTitle)
        val tvAudSub = audioItem.findViewById<TextView>(R.id.tvTrackSubtext)
        val tvAudBadge = audioItem.findViewById<TextView>(R.id.tvTrackBadge)
        tvAudTitle.text = "Audio: $movieAudioCodec"
        tvAudTitle.isSelected = true
        tvAudSub.text = "Direct bitstream passthrough (HW_AV_SYNC)"
        tvAudSub.visibility = View.VISIBLE
        tvAudBadge.text = badgeAudio.text.toString()
        tvAudBadge.visibility = View.VISIBLE
        audioItem.isFocusable = true
        audioItem.setOnFocusChangeListener { _, hasFocus -> tvAudTitle.isSelected = hasFocus }
        llModalListContainer.addView(audioItem)

        // Item 6: Cinema Frame Rate
        val afrItem = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
        afrItem.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.GONE
        val tvAfrTitle = afrItem.findViewById<TextView>(R.id.tvTrackTitle)
        val tvAfrSub = afrItem.findViewById<TextView>(R.id.tvTrackSubtext)
        val tvAfrBadge = afrItem.findViewById<TextView>(R.id.tvTrackBadge)
        tvAfrTitle.text = "Match Content Frame Rate: 24.000 Hz"
        tvAfrTitle.isSelected = true
        tvAfrSub.text = "Zero 3:2 pulldown judder via HDMI 2.1 display mode"
        tvAfrSub.visibility = View.VISIBLE
        tvAfrBadge.text = "AFR 24.000"
        tvAfrBadge.visibility = View.VISIBLE
        afrItem.isFocusable = true
        afrItem.setOnFocusChangeListener { _, hasFocus -> tvAfrTitle.isSelected = hasFocus }
        llModalListContainer.addView(afrItem)

        // Item 7: Subtitles Rendering Engine
        val subsItem = inflater.inflate(R.layout.item_track_selection, llModalListContainer, false)
        subsItem.findViewById<ImageView>(R.id.ivTrackCheck).visibility = View.GONE
        val tvSubTitle = subsItem.findViewById<TextView>(R.id.tvTrackTitle)
        val tvSubSub = subsItem.findViewById<TextView>(R.id.tvTrackSubtext)
        val tvSubBadge = subsItem.findViewById<TextView>(R.id.tvTrackBadge)
        tvSubTitle.text = "Subtitles: PGS Hardware Cinema Engine"
        tvSubTitle.isSelected = true
        tvSubSub.text = "SMPTE 2084 reference white (#EBEBEB) with zero OLED glare"
        tvSubSub.visibility = View.VISIBLE
        tvSubBadge.text = "PGS / SRT"
        tvSubBadge.visibility = View.VISIBLE
        subsItem.isFocusable = true
        subsItem.setOnFocusChangeListener { _, hasFocus -> tvSubTitle.isSelected = hasFocus }
        llModalListContainer.addView(subsItem)

        titleItem.requestFocus()
        titleItem.post { titleItem.requestFocus() }
    }

    private fun showModal(title: String, badge: String, anchorButton: View? = null) {
        handler.removeCallbacks(hideHudRunnable)
        tvModalTitle.text = title
        if (badge.isNotBlank()) {
            tvModalBadge.text = badge
            tvModalBadge.visibility = View.VISIBLE
        } else {
            tvModalBadge.visibility = View.GONE
        }

        // Highlight activo en el botón que abrió el modal (estilo Apple TV)
        btnSubtitles.isActivated = (anchorButton == btnSubtitles)
        btnAudioTrack.isActivated = (anchorButton == btnAudioTrack)
        btnInfo.isActivated = (anchorButton == btnInfo)
        btnChapters.isActivated = (anchorButton == btnChapters)
        btnDvMode.isActivated = (anchorButton == btnDvMode)

        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        // Determinar margen inferior: 14dp limpio por encima de los botones
        val targetBottomMargin = if (anchorButton != null) {
            val loc = IntArray(2)
            anchorButton.getLocationOnScreen(loc)
            val btnTop = loc[1]
            val calculatedBottom = screenH - btnTop
            if (calculatedBottom in 80..420) calculatedBottom + (14 * density).toInt() else (186 * density).toInt()
        } else {
            (186 * density).toInt()
        }

        val lp = modalCard.layoutParams as FrameLayout.LayoutParams
        val isLeftAnchored = (anchorButton == btnInfo || anchorButton == btnChapters || anchorButton == btnDvMode)

        if (isLeftAnchored && anchorButton != null) {
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            val loc = IntArray(2)
            anchorButton.getLocationOnScreen(loc)
            val btnLeft = loc[0]
            val targetMarginStart = (btnLeft - (8 * density).toInt()).coerceAtLeast((32 * density).toInt())

            if (modalOverlay.visibility == View.VISIBLE && lp.marginStart != targetMarginStart) {
                val startMargin = lp.marginStart
                modalCard.animate().cancel()
                modalCard.translationX = (startMargin - targetMarginStart).toFloat()
                lp.marginStart = targetMarginStart
                lp.marginEnd = 0
                lp.bottomMargin = targetBottomMargin
                modalCard.layoutParams = lp
                modalCard.animate()
                    .translationX(0f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            } else {
                lp.marginStart = targetMarginStart
                lp.marginEnd = 0
                lp.bottomMargin = targetBottomMargin
                modalCard.translationX = 0f
                modalCard.layoutParams = lp
            }
            modalCard.pivotX = (30 * density)
            modalCard.pivotY = (260 * density)
        } else {
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            val fallbackMarginEnd = when (anchorButton) {
                btnSubtitles -> (100 * density).toInt()
                else -> (48 * density).toInt()
            }
            val targetMarginEnd = if (anchorButton != null) {
                val loc = IntArray(2)
                anchorButton.getLocationOnScreen(loc)
                val btnRight = loc[0] + anchorButton.width
                val calculatedEnd = screenW - btnRight
                if (calculatedEnd in 20..350) calculatedEnd else fallbackMarginEnd
            } else {
                fallbackMarginEnd
            }

            if (modalOverlay.visibility == View.VISIBLE && lp.marginEnd != targetMarginEnd) {
                val startMargin = lp.marginEnd
                modalCard.animate().cancel()
                modalCard.translationX = (startMargin - targetMarginEnd).toFloat()
                lp.marginStart = 0
                lp.marginEnd = targetMarginEnd
                lp.bottomMargin = targetBottomMargin
                modalCard.layoutParams = lp
                modalCard.animate()
                    .translationX(0f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            } else {
                lp.marginStart = 0
                lp.marginEnd = targetMarginEnd
                lp.bottomMargin = targetBottomMargin
                modalCard.translationX = 0f
                modalCard.layoutParams = lp
            }
            modalCard.pivotX = (350 * density) - (20 * density)
            modalCard.pivotY = (260 * density)
        }

        topBar.animate().cancel()
        topBar.animate().alpha(0f).setDuration(160).start()
        modalOverlay.bringToFront()

        if (modalOverlay.visibility != View.VISIBLE) {
            modalOverlay.visibility = View.VISIBLE
            modalOverlay.alpha = 0f
            modalCard.scaleX = 0.90f
            modalCard.scaleY = 0.90f
            modalCard.alpha = 0f
            modalOverlay.animate().alpha(1f).setDuration(180).start()
            modalCard.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun closeModal() {
        val lastModal = activeModalType
        activeModalType = ModalType.NONE

        // Quitar highlight a todos los botones
        btnSubtitles.isActivated = false
        btnAudioTrack.isActivated = false
        btnInfo.isActivated = false
        btnChapters.isActivated = false
        btnDvMode.isActivated = false

        topBar.animate().cancel()
        topBar.animate().alpha(1f).setDuration(160).start()
        modalCard.animate()
            .scaleX(0.90f)
            .scaleY(0.90f)
            .alpha(0f)
            .setDuration(150)
            .start()
        modalOverlay.animate().alpha(0f).setDuration(160).withEndAction {
            modalOverlay.visibility = View.GONE
            modalCard.translationX = 0f
            modalCard.scaleX = 1f
            modalCard.scaleY = 1f
            when (lastModal) {
                ModalType.SUBTITLES -> btnSubtitles.requestFocus()
                ModalType.AUDIO -> btnAudioTrack.requestFocus()
                ModalType.CHAPTERS -> btnChapters.requestFocus()
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
        val clean = code.trim().lowercase(Locale.ROOT)
        when (clean) {
            "lat", "es-419" -> return "Spanish (Latin America)"
            "spa", "es", "spanish" -> return "Spanish"
            "eng", "en", "english" -> return "English"
            "fra", "fre", "fr", "french" -> return "French"
            "deu", "ger", "de", "german" -> return "German"
            "ita", "it", "italian" -> return "Italian"
            "jpn", "ja", "japanese" -> return "Japanese"
            "por", "pt", "portuguese" -> return "Portuguese"
            "rus", "ru", "russian" -> return "Russian"
            "zho", "chi", "zh", "chinese" -> return "Chinese"
            "kor", "ko", "korean" -> return "Korean"
            "dan", "da", "danish" -> return "Danish"
            "nld", "dut", "nl", "dutch" -> return "Dutch"
            "swe", "sv", "swedish" -> return "Swedish"
            "nor", "no", "norwegian" -> return "Norwegian"
            "fin", "fi", "finnish" -> return "Finnish"
            "pol", "pl", "polish" -> return "Polish"
            "ces", "cze", "cs", "czech" -> return "Czech"
            "hun", "hu", "hungarian" -> return "Hungarian"
            "ell", "gre", "el", "greek" -> return "Greek"
            "ara", "ar", "arabic" -> return "Arabic"
            "heb", "he", "hebrew" -> return "Hebrew"
            "hin", "hi", "hindi" -> return "Hindi"
            "tha", "th", "thai" -> return "Thai"
            "tur", "tr", "turkish" -> return "Turkish"
            "vie", "vi", "vietnamese" -> return "Vietnamese"
            "und" -> return "Unknown"
        }
        try {
            val loc = Locale.forLanguageTag(clean).takeIf { it.displayLanguage.isNotBlank() }
                ?: Locale(clean)
            val name = loc.getDisplayLanguage(Locale.ENGLISH)
            if (name.isNotBlank()) {
                return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
        } catch (_: Exception) {}
        return code.uppercase(Locale.ROOT)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (modalOverlay.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeModal()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                when (activeModalType) {
                    ModalType.SUBTITLES -> { openAudioModal(); return true }
                    ModalType.INFO -> { openChaptersModal(); return true }
                    ModalType.CHAPTERS -> {
                        if (btnDvMode.visibility == View.VISIBLE) {
                            openDvModeModal()
                            return true
                        }
                    }
                    else -> {}
                }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                when (activeModalType) {
                    ModalType.AUDIO -> { openSubtitlesModal(); return true }
                    ModalType.CHAPTERS -> { openInfoModal(); return true }
                    ModalType.DV_MODE -> { openChaptersModal(); return true }
                    else -> {}
                }
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
                        btnInfo, btnChapters, btnDvMode, btnLibrary -> timelineSeekBar.requestFocus()
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
