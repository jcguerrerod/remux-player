package com.player.androidtv

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView

data class RemuxMovie(
    val title: String,
    val year: String,
    val duration: String,
    val genre: String,
    val size: String,
    val videoCodec: String,
    val audioCodec: String,
    val synopsis: String,
    val actors: String,
    val director: String,
    val backdropAsset: String,
    val cardAsset: String,
    val itemId: String
)

class MainActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "remux_player_prefs"
    }

    private lateinit var ivHeroBackdrop: ImageView
    private lateinit var ivHeroBackdropAlt: ImageView
    private lateinit var heroContentContainer: View
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroGenre: TextView
    private lateinit var tvHeroSynopsis: TextView
    private lateinit var tvMetadataText: TextView
    private lateinit var ivLogoUhd: ImageView
    private lateinit var ivLogoBluray: ImageView
    private lateinit var ivLogoDv: ImageView
    private lateinit var ivLogoAtmos: ImageView
    private lateinit var ivLogoDts: ImageView
    private lateinit var badge4K: TextView
    private lateinit var badgeDv: TextView
    private lateinit var badgeAudio: TextView
    private lateinit var btnPlayDirect: Button
    private lateinit var btnCheck: ImageButton
    private lateinit var btnDetails: ImageButton
    private lateinit var tvHeroCast: TextView
    private lateinit var tvHeroDirector: TextView
    private lateinit var tvServerInfo: TextView
    private lateinit var llCardShelf: LinearLayout

    private var serverBaseUrl: String = "http://192.168.100.108:8096"
    private var selectedMovieIndex = 0
    private var isBackdropPrimary = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingBackdropRunnable: Runnable? = null

    // Cache en RAM de alto rendimiento para navegación 60 FPS sin lag
    private val backdropBitmaps = HashMap<String, Bitmap>()
    private val cardBitmaps = HashMap<String, Bitmap>()

    private val movies = listOf(
        RemuxMovie(
            title = "RAIDERS OF THE LOST ARK",
            year = "1981",
            duration = "1h 55m",
            genre = "Movie  ·  Action  ·  Adventure",
            size = "56.73 GB Remux",
            videoCodec = "DOLBY VISION P8.1",
            audioCodec = "DOLBY ATMOS",
            synopsis = "Archaeology professor Indiana Jones ventures across the globe to uncover the legendary Ark of the Covenant before insidious Nazi forces can seize its ancient power.",
            actors = "Harrison Ford, Karen Allen, Paul Freeman, John Rhys-Davies",
            director = "Steven Spielberg",
            backdropAsset = "backdrops/raiders.jpg",
            cardAsset = "backdrops/raiders_card.jpg",
            itemId = "64d42058-033b-8bbe-daa0-c9a6b3f42c6b"
        ),
        RemuxMovie(
            title = "THE NIGHTMARE BEFORE CHRISTMAS",
            year = "1993",
            duration = "1h 16m",
            genre = "Movie  ·  Animation  ·  Fantasy",
            size = "49.02 GB Remux",
            videoCodec = "DOLBY VISION P8.1",
            audioCodec = "DTS-HD MASTER AUDIO 7.1",
            synopsis = "Jack Skellington, the Pumpkin King of Halloween Town, accidentally stumbles into Christmas Town and plots to take over the festive holiday with delightfully chaotic results.",
            actors = "Danny Elfman, Chris Sarandon, Catherine O'Hara, William Hickey",
            director = "Henry Selick (Story: Tim Burton)",
            backdropAsset = "backdrops/nightmare.jpg",
            cardAsset = "backdrops/nightmare_card.jpg",
            itemId = "ab56ab19-3dea-37a4-d273-c4db2be3678e"
        ),
        RemuxMovie(
            title = "APOCALYPSE NOW",
            year = "1979",
            duration = "2h 27m",
            genre = "Movie  ·  War  ·  Drama",
            size = "30.33 GB Remux",
            videoCodec = "1080P BLU-RAY",
            audioCodec = "DOLBY TRUEHD",
            synopsis = "During the Vietnam War, Captain Willard is assigned a classified mission to journey deep into Cambodia and terminate rogue Special Forces Colonel Kurtz.",
            actors = "Marlon Brando, Martin Sheen, Robert Duvall, Laurence Fishburne",
            director = "Francis Ford Coppola",
            backdropAsset = "backdrops/apocalypse.jpg",
            cardAsset = "backdrops/apocalypse_card.jpg",
            itemId = "cee3c58c-3ab9-0224-626b-c991aafc2cc5"
        ),
        RemuxMovie(
            title = "PROJECT HAIL MARY",
            year = "2026",
            duration = "2h 35m",
            genre = "Movie  ·  Sci-Fi  ·  Adventure",
            size = "81.29 GB Remux",
            videoCodec = "DOLBY VISION P7 MEL",
            audioCodec = "DOLBY ATMOS",
            synopsis = "The sole survivor of a desperate interstellar mission awakens with amnesia, discovering the fate of humanity and Earth's survival rests squarely on his shoulders.",
            actors = "Ryan Gosling, Sandra Hüller, Milana Vayntrub",
            director = "Phil Lord, Christopher Miller",
            backdropAsset = "backdrops/hailmary.jpg",
            cardAsset = "backdrops/hailmary_card.jpg",
            itemId = "dfabf08a-80ba-1cc3-5d20-0aecccaf0952"
        ),
        RemuxMovie(
            title = "WHIPLASH",
            year = "2014",
            duration = "1h 46m",
            genre = "Movie  ·  Drama  ·  Music",
            size = "23.46 GB Remux",
            videoCodec = "1080P AVC",
            audioCodec = "DTS-HD MASTER AUDIO",
            synopsis = "An ambitious young jazz drummer enters an elite music conservatory, where he is driven past human limits by a ruthless and uncompromising instructor.",
            actors = "Miles Teller, J.K. Simmons, Paul Reiser, Melissa Benoist",
            director = "Damien Chazelle",
            backdropAsset = "backdrops/whiplash.jpg",
            cardAsset = "backdrops/whiplash_card.jpg",
            itemId = "c525a0a9-9404-ceea-8e69-c3ca1565b03a"
        )
    )

    private lateinit var shelfScrollView: android.widget.HorizontalScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("google_sdk")) {
            serverBaseUrl = "http://10.0.2.2:8096"
        }

        ivHeroBackdrop = findViewById(R.id.ivHeroBackdrop)
        ivHeroBackdropAlt = findViewById(R.id.ivHeroBackdropAlt)
        heroContentContainer = findViewById(R.id.heroContentContainer)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroGenre = findViewById(R.id.tvHeroGenre)
        tvHeroSynopsis = findViewById(R.id.tvHeroSynopsis)
        tvMetadataText = findViewById(R.id.tvMetadataText)
        ivLogoUhd = findViewById(R.id.ivLogoUhd)
        ivLogoBluray = findViewById(R.id.ivLogoBluray)
        ivLogoDv = findViewById(R.id.ivLogoDv)
        ivLogoAtmos = findViewById(R.id.ivLogoAtmos)
        ivLogoDts = findViewById(R.id.ivLogoDts)
        badge4K = findViewById(R.id.badge4K)
        badgeDv = findViewById(R.id.badgeDv)
        badgeAudio = findViewById(R.id.badgeAudio)
        btnPlayDirect = findViewById(R.id.btnPlayDirect)
        btnCheck = findViewById(R.id.btnCheck)
        btnDetails = findViewById(R.id.btnDetails)
        tvHeroCast = findViewById(R.id.tvHeroCast)
        tvHeroDirector = findViewById(R.id.tvHeroDirector)
        tvServerInfo = findViewById(R.id.tvServerInfo)
        shelfScrollView = findViewById(R.id.shelfScrollView)
        shelfScrollView.isVerticalScrollBarEnabled = false
        shelfScrollView.isHorizontalScrollBarEnabled = false
        shelfScrollView.overScrollMode = View.OVER_SCROLL_NEVER
        llCardShelf = findViewById(R.id.llCardShelf)

        // Carga en segundo plano de backdrops optimizados a 1080p para fluidez 60 FPS absoluta
        preloadBitmapsAsync()

        buildMovieShelf()
        updateHeroMovie(0, animate = false)

        btnPlayDirect.requestFocus()
        btnPlayDirect.setOnClickListener {
            launchPlayback(movies[selectedMovieIndex])
        }
    }

    private fun preloadBitmapsAsync() {
        Thread {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 2 // Escala 4K a 1080p nativo: 4x menos RAM, decodificación instantánea
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            for (movie in movies) {
                try {
                    assets.open(movie.backdropAsset).use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream, null, options)
                        if (bmp != null) {
                            synchronized(backdropBitmaps) {
                                backdropBitmaps[movie.backdropAsset] = bmp
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error precargando backdrop ${movie.backdropAsset}", e)
                }

                try {
                    assets.open(movie.cardAsset).use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) {
                            synchronized(cardBitmaps) {
                                cardBitmaps[movie.cardAsset] = bmp
                            }
                        }
                    }
                } catch (ignored: Exception) {}
            }

            // Refrescar el primer backdrop si ya está en RAM
            mainHandler.post {
                val firstBmp = synchronized(backdropBitmaps) { backdropBitmaps[movies[selectedMovieIndex].backdropAsset] }
                if (firstBmp != null) {
                    ivHeroBackdrop.setImageBitmap(firstBmp)
                    ivHeroBackdrop.alpha = 1f
                }
            }
        }.start()
    }

    private val cardProgressBars = HashMap<String, Pair<View, View>>()

    private fun getSavedProgress(prefs: SharedPreferences, title: String): Pair<Long, Long> {
        val normPKey = "progress_" + title.lowercase().trim()
        val normDKey = "duration_" + title.lowercase().trim()
        var pos = prefs.getLong(normPKey, 0L)
        var dur = prefs.getLong(normDKey, 0L)
        if (pos == 0L) pos = prefs.getLong("progress_$title", 0L)
        if (dur == 0L) dur = prefs.getLong("duration_$title", 0L)
        if (pos == 0L) {
            val all = prefs.all
            for ((k, v) in all) {
                if (k.startsWith("progress_") && k.substring(9).equals(title, ignoreCase = true) && v is Long) {
                    pos = v
                    val matchedDur = all["duration_" + k.substring(9)] as? Long
                    if (matchedDur != null && matchedDur > 0L) dur = matchedDur
                    break
                }
            }
        }
        return Pair(pos, dur)
    }

    override fun onResume() {
        super.onResume()
        refreshShelfProgress()
        updateHeroMovie(selectedMovieIndex, animate = false)
    }

    private fun refreshShelfProgress() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        for (movie in movies) {
            val pair = cardProgressBars[movie.title] ?: continue
            val (container, fill) = pair
            val (pos, dur) = getSavedProgress(prefs, movie.title)
            if (dur > 0L && pos > 5000L && pos < dur - 30000L) {
                container.visibility = View.VISIBLE
                val ratio = (pos.toFloat() / dur.toFloat()).coerceIn(0.02f, 1f)
                container.post {
                    val totalW = container.width
                    if (totalW > 0) {
                        fill.layoutParams.width = (totalW * ratio).toInt()
                        fill.requestLayout()
                    }
                }
            } else {
                container.visibility = View.GONE
            }
        }
    }

    private fun buildMovieShelf() {
        llCardShelf.removeAllViews()
        val density = resources.displayMetrics.density

        for ((index, movie) in movies.withIndex()) {
            val cardView = CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (240 * density).toInt(),
                    (135 * density).toInt()
                ).apply {
                    marginStart = (6 * density).toInt()
                    marginEnd = (18 * density).toInt()
                    topMargin = (6 * density).toInt()
                    bottomMargin = (6 * density).toInt()
                }
                radius = 16 * density
                cardElevation = 6 * density
                setCardBackgroundColor(android.graphics.Color.parseColor("#1C1C1E"))
                isFocusable = true
                isClickable = true
            }

            val frameLayout = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                isDuplicateParentStateEnabled = true
            }

            val imageView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            // Cargar imagen de tarjeta desde asset o cache
            try {
                assets.open(movie.cardAsset).use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    imageView.setImageBitmap(bmp)
                }
            } catch (e: Exception) {
                try {
                    assets.open(movie.backdropAsset).use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        imageView.setImageBitmap(bmp)
                    }
                } catch (ignored: Exception) {}
            }

            // Borde luminoso blanco puro de Apple TV (2.5dp) con efecto Liquid Glass
            val borderOverlay = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundResource(R.drawable.bg_apple_tv_card_border)
                isDuplicateParentStateEnabled = true
            }

            // Barra de progreso "Continuar viendo" Apple TV (3.5dp, fondo negro translúcido y relleno blanco puro)
            val progressBarContainer = FrameLayout(this).apply {
                val barHeight = (3.5f * density).toInt()
                val marginH = (14 * density).toInt()
                val marginB = (10 * density).toInt()
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    barHeight
                ).apply {
                    gravity = android.view.Gravity.BOTTOM
                    marginStart = marginH
                    marginEnd = marginH
                    bottomMargin = marginB
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 2f * density
                    setColor(android.graphics.Color.parseColor("#80000000"))
                }
                visibility = View.GONE
            }

            val progressBarFill = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 2f * density
                    setColor(android.graphics.Color.parseColor("#FFFFFF"))
                }
            }
            progressBarContainer.addView(progressBarFill)
            cardProgressBars[movie.title] = Pair(progressBarContainer, progressBarFill)

            frameLayout.addView(imageView)
            frameLayout.addView(progressBarContainer)
            frameLayout.addView(borderOverlay)
            cardView.addView(frameLayout)

            // Zoom suave estilo Apple TV (1.08x), borde blanco y auto-scroll centrado suave
            cardView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .translationZ(16 * density)
                        .setDuration(180)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                    selectedMovieIndex = index
                    updateHeroMovie(index)

                    // Centrar suavemente la tarjeta seleccionada en el scrollview
                    v.post {
                        val targetScrollX = (v.left - (shelfScrollView.width / 2) + (v.width / 2)).coerceAtLeast(0)
                        shelfScrollView.smoothScrollTo(targetScrollX, 0)
                    }
                } else {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationZ(0f)
                        .setDuration(180)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            }

            cardView.setOnClickListener {
                selectedMovieIndex = index
                updateHeroMovie(index)
                launchPlayback(movie)
            }

            cardView.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    launchPlayback(movie)
                    true
                } else {
                    false
                }
            }

            llCardShelf.addView(cardView)
        }
    }

    private fun updateHeroMovie(index: Int, animate: Boolean = true) {
        val movie = movies[index]

        val isSdr = movie.title.contains("Apocalypse", ignoreCase = true) ||
                movie.title.contains("Whiplash", ignoreCase = true)

        // 1. Actualización instantánea (0ms) de la ficha técnica y sinopsis
        tvHeroTitle.text = movie.title
        tvHeroGenre.text = "${movie.genre}  ·  ${movie.year}"
        tvHeroSynopsis.text = movie.synopsis
        tvMetadataText.text = "${movie.year}  ·  ${movie.duration}  ·  ${movie.size}"

        if (isSdr) {
            ivLogoUhd.visibility = View.GONE
            ivLogoBluray.visibility = View.VISIBLE
            badge4K.visibility = View.GONE
            ivLogoDv.visibility = View.GONE
            badgeDv.visibility = View.GONE
        } else {
            ivLogoUhd.visibility = View.VISIBLE
            ivLogoBluray.visibility = View.GONE
            badge4K.visibility = View.GONE
            ivLogoDv.visibility = View.VISIBLE
            badgeDv.visibility = View.GONE
        }

        when {
            movie.audioCodec.contains("Atmos", ignoreCase = true) -> {
                ivLogoAtmos.visibility = View.VISIBLE
                ivLogoDts.visibility = View.GONE
                badgeAudio.visibility = View.GONE
            }
            movie.audioCodec.contains("DTS", ignoreCase = true) -> {
                ivLogoAtmos.visibility = View.GONE
                ivLogoDts.visibility = View.VISIBLE
                badgeAudio.visibility = View.GONE
            }
            else -> {
                ivLogoAtmos.visibility = View.GONE
                ivLogoDts.visibility = View.GONE
                badgeAudio.visibility = View.VISIBLE
                badgeAudio.text = movie.audioCodec
            }
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val (pos, dur) = getSavedProgress(prefs, movie.title)
        if (dur > 0L && pos > 5000L && pos < dur - 30000L) {
            val remainingMs = dur - pos
            val remainingHours = remainingMs / (1000 * 60 * 60)
            val remainingMinutes = (remainingMs / (1000 * 60)) % 60
            val remainingText = if (remainingHours > 0) "${remainingHours}h ${remainingMinutes}m" else "${remainingMinutes}m"
            btnPlayDirect.text = "Resume · $remainingText left"
        } else {
            btnPlayDirect.text = movie.duration
        }
        tvHeroCast.text = "Cast  ${movie.actors}"
        tvHeroDirector.text = "Director  ${movie.director}"

        // 2. Debounce del crossfade de fondo para navegación 60 FPS fluida en Android TV
        pendingBackdropRunnable?.let { mainHandler.removeCallbacks(it) }

        val crossfadeTask = Runnable {
            val cachedBmp = synchronized(backdropBitmaps) { backdropBitmaps[movie.backdropAsset] }
            val bmp = cachedBmp ?: try {
                val opt = BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                assets.open(movie.backdropAsset).use { stream ->
                    BitmapFactory.decodeStream(stream, null, opt)
                }
            } catch (e: Exception) {
                null
            }

            if (bmp != null) {
                ivHeroBackdrop.animate().cancel()
                ivHeroBackdropAlt.animate().cancel()
                if (isBackdropPrimary) {
                    ivHeroBackdropAlt.setImageBitmap(bmp)
                    ivHeroBackdropAlt.animate().alpha(1f).setDuration(220).start()
                    ivHeroBackdrop.animate().alpha(0f).setDuration(220).start()
                    isBackdropPrimary = false
                } else {
                    ivHeroBackdrop.setImageBitmap(bmp)
                    ivHeroBackdrop.animate().alpha(1f).setDuration(220).start()
                    ivHeroBackdropAlt.animate().alpha(0f).setDuration(220).start()
                    isBackdropPrimary = true
                }
            }
        }

        pendingBackdropRunnable = crossfadeTask
        if (animate) {
            mainHandler.postDelayed(crossfadeTask, 120)
        } else {
            crossfadeTask.run()
        }
    }

    private fun launchPlayback(movie: RemuxMovie) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val (pos, dur) = getSavedProgress(prefs, movie.title)
        val seekPos = if (dur > 0L && pos > 5000L && pos < dur - 30000L) pos else androidx.media3.common.C.TIME_UNSET

        val streamUrl = "$serverBaseUrl/Videos/${movie.itemId}/stream.mkv?Static=true"
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            data = Uri.parse(streamUrl)
            if (seekPos != androidx.media3.common.C.TIME_UNSET) {
                putExtra("seek", seekPos)
            }
            putExtra("title", movie.title)
            putExtra("MOVIE_TITLE", movie.title)
            putExtra("MOVIE_YEAR", movie.year)
            putExtra("MOVIE_DURATION", movie.duration)
            putExtra("MOVIE_GENRE", movie.genre)
            putExtra("MOVIE_SYNOPSIS", movie.synopsis)
            putExtra("MOVIE_DIRECTOR", movie.director)
            putExtra("MOVIE_ACTORS", movie.actors)
            putExtra("MOVIE_SIZE", movie.size)
            putExtra("MOVIE_VIDEO_CODEC", movie.videoCodec)
            putExtra("MOVIE_AUDIO_CODEC", movie.audioCodec)
        }
        val options = android.app.ActivityOptions.makeCustomAnimation(
            this,
            R.anim.cinematic_fade_in,
            R.anim.cinematic_zoom_out
        )
        startActivity(intent, options.toBundle())
    }
}
