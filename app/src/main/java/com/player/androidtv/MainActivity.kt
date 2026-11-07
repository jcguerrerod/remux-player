package com.player.androidtv

import android.app.Activity
import android.content.Intent
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
            genre = "Movie  ·  Acción  ·  Aventura",
            size = "56.73 GB Remux",
            videoCodec = "DOLBY VISION P8.1",
            audioCodec = "DOLBY ATMOS",
            synopsis = "El intrépido arqueólogo Indiana Jones emprende una misión global para recuperar la legendaria Arca de la Alianza antes de que caiga en manos de las fuerzas nazis.",
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
            genre = "Movie  ·  Animación  ·  Fantasía",
            size = "49.02 GB Remux",
            videoCodec = "DOLBY VISION P8.1",
            audioCodec = "DTS-HD MASTER AUDIO 7.1",
            synopsis = "Jack Skellington, el rey de las calabazas de Ciudad de Halloween, descubre la luminosa Ciudad de la Navidad y decide apoderarse de la festividad con inesperadas consecuencias.",
            actors = "Danny Elfman, Chris Sarandon, Catherine O'Hara, William Hickey",
            director = "Henry Selick (Historia: Tim Burton)",
            backdropAsset = "backdrops/nightmare.jpg",
            cardAsset = "backdrops/nightmare_card.jpg",
            itemId = "ab56ab19-3dea-37a4-d273-c4db2be3678e"
        ),
        RemuxMovie(
            title = "APOCALYPSE NOW",
            year = "1979",
            duration = "2h 27m",
            genre = "Movie  ·  Bélico  ·  Drama",
            size = "30.33 GB Remux",
            videoCodec = "1080P BLU-RAY",
            audioCodec = "DOLBY TRUEHD",
            synopsis = "Durante la Guerra de Vietnam, el capitán Willard recibe la misión clasificada de remontar el río y liquidar al coronel Kurtz, un oficial renegado que ha enloquecido.",
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
            genre = "Movie  ·  Ciencia Ficción  ·  Aventura",
            size = "81.29 GB Remux",
            videoCodec = "DOLBY VISION P7 MEL",
            audioCodec = "DOLBY ATMOS",
            synopsis = "El único superviviente de una desesperada misión espacial interestelar despierta sin recuerdos para descubrir que de él depende salvar la supervivencia del planeta Tierra.",
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
            genre = "Movie  ·  Drama  ·  Música",
            size = "23.46 GB Remux",
            videoCodec = "1080P AVC",
            audioCodec = "DTS-HD MASTER AUDIO",
            synopsis = "Un joven y ambicioso baterista de jazz ingresa al prestigioso conservatorio Shaffer, donde es empujado más allá de sus límites físicos y mentales por un despiadado instructor.",
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

        if (isEmulator()) {
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

            // Borde luminoso blanco puro de Apple TV (2.5dp)
            val borderOverlay = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundResource(R.drawable.bg_apple_tv_card_border)
                isDuplicateParentStateEnabled = true
            }

            frameLayout.addView(imageView)
            frameLayout.addView(borderOverlay)
            cardView.addView(frameLayout)

            // Zoom suave estilo Apple TV (1.06x), borde blanco y auto-scroll centrado suave
            cardView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1.06f)
                        .scaleY(1.06f)
                        .translationZ(12 * density)
                        .setDuration(150)
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
                        .setDuration(150)
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
        btnPlayDirect.text = movie.duration
        tvHeroCast.text = "Reparto  ${movie.actors}"
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

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.contains("generic") ||
                Build.MODEL.contains("google_sdk") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.PRODUCT.contains("sdk") ||
                Build.MODEL.contains("AOSP") ||
                Build.FINGERPRINT.contains("emulator")
    }

    private fun launchPlayback(movie: RemuxMovie) {
        val streamUrl = if (isEmulator() && (movie.videoCodec.contains("HEVC") || movie.videoCodec.contains("DOLBY VISION"))) {
            "$serverBaseUrl/Videos/${movie.itemId}/stream.mp4?VideoCodec=h264&AudioCodec=aac&VideoBitrate=60000000&AudioBitrate=384000&MaxWidth=1920&MaxHeight=1080"
        } else {
            "$serverBaseUrl/Videos/${movie.itemId}/stream.mkv?Static=true"
        }
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            data = Uri.parse(streamUrl)
            putExtra("title", movie.title)
            putExtra("MOVIE_TITLE", movie.title)
            putExtra("MOVIE_YEAR", movie.year)
        }
        startActivity(intent)
    }
}
