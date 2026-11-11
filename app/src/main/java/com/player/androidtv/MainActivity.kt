package com.player.androidtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import com.player.coremedia.jellyfin.DynamicRangeType
import com.player.coremedia.jellyfin.JellyfinDirectPlayClient
import com.player.coremedia.jellyfin.JellyfinMetadataMapper
import com.player.coremedia.jellyfin.JellyfinMovie
import com.player.coremedia.jellyfin.JellyfinSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "remux_player_prefs"
        private const val KEY_JF_URL = "jellyfin_server_url"
        private const val KEY_JF_TOKEN = "jellyfin_access_token"
        private const val KEY_JF_USER_ID = "jellyfin_user_id"
        private const val KEY_JF_USER_NAME = "jellyfin_user_name"
        private const val KEY_JF_SERVER_NAME = "jellyfin_server_name"
    }

    private lateinit var ivHeroBackdrop: ImageView
    private lateinit var ivHeroBackdropAlt: ImageView
    private lateinit var heroContentContainer: View
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroGenre: TextView
    private lateinit var tvHeroSynopsis: TextView
    private lateinit var tvMetadataText: TextView
    private lateinit var badgeResolution: TextView
    private lateinit var badgeHdr: TextView
    private lateinit var badgeAudio: TextView
    private lateinit var badgeChannels: TextView
    private lateinit var btnPlayDirect: Button
    private lateinit var btnCheck: ImageButton
    private lateinit var btnDetails: ImageButton
    private lateinit var tvHeroCast: TextView
    private lateinit var tvHeroDirector: TextView
    private lateinit var serverBadge: View
    private lateinit var serverStatusDot: View
    private lateinit var tvServerInfo: TextView
    private lateinit var shelfScrollView: android.widget.HorizontalScrollView
    private lateinit var llCardShelf: LinearLayout

    private var serverBaseUrl: String = "http://192.168.100.108:8096"
    private var activeSession: JellyfinSession? = null
    private var selectedMovieIndex = 0
    private var isBackdropPrimary = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingBackdropRunnable: Runnable? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // RAM caches for fluid 60 FPS navigation without dropped frames
    private val backdropBitmaps = HashMap<String, Bitmap>()
    private val cardBitmaps = HashMap<String, Bitmap>()
    private val cardProgressBars = HashMap<String, Pair<View, View>>()
    private val cardImageViews = HashMap<String, ImageView>()

    private var movies: List<JellyfinMovie> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("google_sdk") || Build.MODEL.contains("Emulator")) {
            serverBaseUrl = "http://10.0.2.2:8096"
        }

        bindViews()
        loadInitialCatalogAndSyncJellyfin()
    }

    private fun bindViews() {
        ivHeroBackdrop = findViewById(R.id.ivHeroBackdrop)
        ivHeroBackdropAlt = findViewById(R.id.ivHeroBackdropAlt)
        heroContentContainer = findViewById(R.id.heroContentContainer)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroGenre = findViewById(R.id.tvHeroGenre)
        tvHeroSynopsis = findViewById(R.id.tvHeroSynopsis)
        tvMetadataText = findViewById(R.id.tvMetadataText)
        badgeResolution = findViewById(R.id.badgeResolution)
        badgeHdr = findViewById(R.id.badgeHdr)
        badgeAudio = findViewById(R.id.badgeAudio)
        badgeChannels = findViewById(R.id.badgeChannels)
        btnPlayDirect = findViewById(R.id.btnPlayDirect)
        btnCheck = findViewById(R.id.btnCheck)
        btnDetails = findViewById(R.id.btnDetails)
        tvHeroCast = findViewById(R.id.tvHeroCast)
        tvHeroDirector = findViewById(R.id.tvHeroDirector)
        serverBadge = findViewById(R.id.serverBadge)
        serverStatusDot = findViewById(R.id.serverStatusDot)
        tvServerInfo = findViewById(R.id.tvServerInfo)
        shelfScrollView = findViewById(R.id.shelfScrollView)
        shelfScrollView.isVerticalScrollBarEnabled = false
        shelfScrollView.isHorizontalScrollBarEnabled = false
        shelfScrollView.overScrollMode = View.OVER_SCROLL_NEVER
        llCardShelf = findViewById(R.id.llCardShelf)

        btnPlayDirect.requestFocus()
        btnPlayDirect.setOnClickListener {
            movies.getOrNull(selectedMovieIndex)?.let { launchPlayback(it) }
        }
        btnCheck.setOnClickListener {
            movies.getOrNull(selectedMovieIndex)?.let { toggleWatchedStatus(it) }
        }
        btnDetails.setOnClickListener {
            movies.getOrNull(selectedMovieIndex)?.let { showMovieDetailsDialog(it) }
        }
        serverBadge.setOnClickListener {
            showJellyfinConnectDialog()
        }
    }

    private fun loadInitialCatalogAndSyncJellyfin() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_JF_URL, null)
        val savedToken = prefs.getString(KEY_JF_TOKEN, null)
        val savedUserId = prefs.getString(KEY_JF_USER_ID, null)
        val savedUserName = prefs.getString(KEY_JF_USER_NAME, "") ?: ""
        val savedServerName = prefs.getString(KEY_JF_SERVER_NAME, "") ?: ""

        if (!savedUrl.isNullOrBlank()) {
            serverBaseUrl = savedUrl
        }

        // Immediately render offline showcase catalog so UI appears at 0ms without blocking
        movies = OfflineDemoCatalog.getMovies(serverBaseUrl)
        selectedMovieIndex = 0
        preloadBitmapsAsync(movies)
        buildMovieShelf()
        updateHeroMovie(0, animate = false)

        if (!savedUrl.isNullOrBlank() && !savedToken.isNullOrBlank() && !savedUserId.isNullOrBlank()) {
            val session = JellyfinSession(savedUrl, savedToken, savedUserId, savedUserName, savedServerName)
            activeSession = session
            updateServerBadgeUi(connected = true, label = if (savedServerName.isNotBlank()) "Jellyfin · $savedServerName" else "Jellyfin Connected")
            syncLibraryFromJellyfin(session)
        } else {
            updateServerBadgeUi(connected = false, label = "Connect Jellyfin")
            probeLocalJellyfinServer()
        }
    }

    private fun probeLocalJellyfinServer() {
        ioScope.launch {
            val client = JellyfinDirectPlayClient(serverBaseUrl)
            val info = client.testConnection()
            client.close()
            if (info != null) {
                withContext(Dispatchers.Main) {
                    if (activeSession == null) {
                        tvServerInfo.text = "Jellyfin · ${info.serverName} (Sign In)"
                    }
                }
            }
        }
    }

    private fun syncLibraryFromJellyfin(session: JellyfinSession) {
        ioScope.launch {
            val client = JellyfinDirectPlayClient(session.serverUrl)
            val serverInfo = client.testConnection()
            val liveMovies = client.fetchMovies(session)
            client.close()

            withContext(Dispatchers.Main) {
                val resolvedName = serverInfo?.serverName?.takeIf { it.isNotBlank() }
                    ?: session.serverName.ifBlank { "Live Server" }
                if (liveMovies.isNotEmpty()) {
                    movies = liveMovies
                    selectedMovieIndex = 0
                    backdropBitmaps.clear()
                    cardBitmaps.clear()
                    buildMovieShelf()
                    updateHeroMovie(0, animate = true)
                    preloadBitmapsAsync(liveMovies)
                    updateServerBadgeUi(connected = true, label = "Jellyfin · $resolvedName (${liveMovies.size})")
                } else {
                    updateServerBadgeUi(
                        connected = serverInfo != null,
                        label = if (serverInfo != null) "Jellyfin · $resolvedName (Demo)" else "Connect Jellyfin"
                    )
                }
            }
        }
    }

    private fun updateServerBadgeUi(connected: Boolean, label: String) {
        tvServerInfo.text = label
        val dotColor = if (connected) Color.parseColor("#30D158") else Color.parseColor("#8E8E93")
        serverStatusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
        }
    }

    private fun toggleWatchedStatus(movie: JellyfinMovie) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val key = "played_${movie.id}"
        val currentlyPlayed = prefs.getBoolean(key, movie.isPlayed)
        val nextPlayed = !currentlyPlayed

        prefs.edit().putBoolean(key, nextPlayed).apply()
        val updated = movie.copy(isPlayed = nextPlayed)
        val mutable = movies.toMutableList()
        if (selectedMovieIndex in mutable.indices) {
            mutable[selectedMovieIndex] = updated
            movies = mutable
        }
        btnCheck.isActivated = nextPlayed

        activeSession?.let { session ->
            ioScope.launch {
                val client = JellyfinDirectPlayClient(session.serverUrl)
                client.setPlayedStatus(session, movie.id, nextPlayed)
                client.close()
            }
        }

        Toast.makeText(
            this,
            if (nextPlayed) "Marked as watched" else "Marked as unwatched",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showMovieDetailsDialog(movie: JellyfinMovie) {
        val lines = buildString {
            appendLine("${movie.title} (${movie.year})")
            appendLine("${movie.durationText}  ·  ${movie.genreText}")
            if (movie.sizeText.isNotBlank()) appendLine("File Size: ${movie.sizeText}")
            appendLine()
            appendLine("Video: ${if (movie.is4k) "4K UHD" else "1080p HD"} · ${movie.dynamicRange.name.replace('_', ' ')} ${movie.dvProfile}".trim())
            appendLine("Audio: ${movie.audioBadge} ${movie.channelsBadge}".trim())
            appendLine("Chapters: ${movie.chapters.size}")
            if (movie.director.isNotBlank()) appendLine("Director: ${movie.director}")
            if (movie.actors.isNotBlank()) appendLine("Cast: ${movie.actors}")
            if (movie.synopsis.isNotBlank()) {
                appendLine()
                append(movie.synopsis)
            }
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Title Details")
            .setMessage(lines.trim())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showJellyfinConnectDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val urlInput = EditText(this).apply {
            hint = "Server URL (e.g. http://192.168.100.108:8096)"
            setText(activeSession?.serverUrl ?: serverBaseUrl)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8E8E93"))
            setSingleLine(true)
        }
        val userInput = EditText(this).apply {
            hint = "Username"
            setText(activeSession?.userName ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8E8E93"))
            setSingleLine(true)
        }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8E8E93"))
            setSingleLine(true)
        }

        container.addView(urlInput)
        container.addView(userInput)
        container.addView(passInput)

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Jellyfin Server Connection")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val url = urlInput.text.toString().trim().trimEnd('/')
                val username = userInput.text.toString().trim()
                val password = passInput.text.toString()
                if (url.isBlank() || username.isBlank()) {
                    Toast.makeText(this, "Enter server URL and username", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                authenticateAndConnect(url, username, password)
            }
            .setNegativeButton("Cancel", null)

        if (activeSession != null) {
            builder.setNeutralButton("Use Offline Demo") { _, _ ->
                disconnectJellyfinSession()
            }
        }
        builder.show()
    }

    private fun authenticateAndConnect(url: String, username: String, password: String) {
        updateServerBadgeUi(connected = false, label = "Connecting...")
        ioScope.launch {
            val client = JellyfinDirectPlayClient(url)
            val info = client.testConnection()
            if (info == null) {
                client.close()
                withContext(Dispatchers.Main) {
                    updateServerBadgeUi(connected = activeSession != null, label = activeSession?.serverName?.let { "Jellyfin · $it" } ?: "Connect Jellyfin")
                    Toast.makeText(this@MainActivity, "Could not reach Jellyfin at $url", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val session = client.authenticateByName(username, password, info.serverName)
            client.close()
            withContext(Dispatchers.Main) {
                if (session == null) {
                    updateServerBadgeUi(connected = false, label = "Connect Jellyfin")
                    Toast.makeText(this@MainActivity, "Invalid username or password", Toast.LENGTH_LONG).show()
                } else {
                    serverBaseUrl = session.serverUrl
                    activeSession = session
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_JF_URL, session.serverUrl)
                        .putString(KEY_JF_TOKEN, session.accessToken)
                        .putString(KEY_JF_USER_ID, session.userId)
                        .putString(KEY_JF_USER_NAME, session.userName)
                        .putString(KEY_JF_SERVER_NAME, session.serverName)
                        .apply()
                    Toast.makeText(this@MainActivity, "Connected to ${info.serverName}", Toast.LENGTH_SHORT).show()
                    syncLibraryFromJellyfin(session)
                }
            }
        }
    }

    private fun disconnectJellyfinSession() {
        activeSession = null
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(KEY_JF_TOKEN)
            .remove(KEY_JF_USER_ID)
            .remove(KEY_JF_SERVER_NAME)
            .apply()
        movies = OfflineDemoCatalog.getMovies(serverBaseUrl)
        selectedMovieIndex = 0
        backdropBitmaps.clear()
        cardBitmaps.clear()
        buildMovieShelf()
        updateHeroMovie(0, animate = true)
        preloadBitmapsAsync(movies)
        updateServerBadgeUi(connected = false, label = "Connect Jellyfin")
    }

    private fun preloadBitmapsAsync(catalog: List<JellyfinMovie>) {
        ioScope.launch {
            val backdropOptions = BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            for ((idx, movie) in catalog.withIndex()) {
                val backdropKey = movie.backdropUrl ?: movie.backdropAsset ?: ""
                if (backdropKey.isNotEmpty()) {
                    val bmp = loadBitmap(movie.backdropUrl, movie.backdropAsset, backdropOptions)
                    if (bmp != null) {
                        synchronized(backdropBitmaps) {
                            backdropBitmaps[backdropKey] = bmp
                        }
                        if (idx == selectedMovieIndex) {
                            withContext(Dispatchers.Main) {
                                ivHeroBackdrop.setImageBitmap(bmp)
                                ivHeroBackdrop.alpha = 1f
                            }
                        }
                    }
                }

                val cardKey = movie.cardUrl ?: movie.cardAsset ?: ""
                if (cardKey.isNotEmpty()) {
                    val cardBmp = loadBitmap(movie.cardUrl, movie.cardAsset, null)
                    if (cardBmp != null) {
                        synchronized(cardBitmaps) {
                            cardBitmaps[cardKey] = cardBmp
                        }
                        withContext(Dispatchers.Main) {
                            cardImageViews[movie.id]?.setImageBitmap(cardBmp)
                        }
                    }
                }
            }
        }
    }

    private fun loadBitmap(remoteUrl: String?, assetPath: String?, options: BitmapFactory.Options?): Bitmap? {
        if (!remoteUrl.isNullOrBlank()) {
            try {
                val conn = URL(remoteUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 6000
                conn.useCaches = true
                conn.inputStream.use { stream ->
                    return BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Remote image load failed: $remoteUrl", e)
            }
        }
        if (!assetPath.isNullOrBlank()) {
            try {
                assets.open(assetPath).use { stream ->
                    return BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Asset image load failed: $assetPath", e)
            }
        }
        return null
    }

    private fun getSavedProgress(prefs: SharedPreferences, movie: JellyfinMovie): Pair<Long, Long> {
        val normPKey = "progress_" + movie.title.lowercase().trim()
        val normDKey = "duration_" + movie.title.lowercase().trim()
        var pos = prefs.getLong(normPKey, 0L)
        var dur = prefs.getLong(normDKey, 0L)
        if (pos == 0L) pos = prefs.getLong("progress_${movie.title}", 0L)
        if (dur == 0L) dur = prefs.getLong("duration_${movie.title}", 0L)
        if (pos == 0L && movie.playbackPositionMs > 0L) {
            pos = movie.playbackPositionMs
            dur = movie.durationMs
        }
        return Pair(pos, dur)
    }

    override fun onResume() {
        super.onResume()
        refreshShelfProgress()
        if (movies.isNotEmpty()) {
            updateHeroMovie(selectedMovieIndex.coerceIn(movies.indices), animate = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
    }

    private fun refreshShelfProgress() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        for (movie in movies) {
            val pair = cardProgressBars[movie.id] ?: continue
            val (container, fill) = pair
            val (pos, dur) = getSavedProgress(prefs, movie)
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
        cardProgressBars.clear()
        cardImageViews.clear()
        val density = resources.displayMetrics.density

        for ((index, movie) in movies.withIndex()) {
            val cardView = CardView(this).apply {
                val marginStartDp = if (index == 0) 16 else 8
                layoutParams = LinearLayout.LayoutParams(
                    (240 * density).toInt(),
                    (135 * density).toInt()
                ).apply {
                    marginStart = (marginStartDp * density).toInt()
                    marginEnd = (18 * density).toInt()
                    topMargin = (12 * density).toInt()
                    bottomMargin = (12 * density).toInt()
                }
                radius = 16 * density
                cardElevation = 6 * density
                preventCornerOverlap = false
                setCardBackgroundColor(Color.parseColor("#1C1C1E"))
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
            cardImageViews[movie.id] = imageView

            val cardKey = movie.cardUrl ?: movie.cardAsset ?: ""
            val cachedCard = synchronized(cardBitmaps) { cardBitmaps[cardKey] }
            if (cachedCard != null) {
                imageView.setImageBitmap(cachedCard)
            } else if (!movie.cardAsset.isNullOrBlank()) {
                try {
                    assets.open(movie.cardAsset!!).use { stream ->
                        imageView.setImageBitmap(BitmapFactory.decodeStream(stream))
                    }
                } catch (_: Exception) {}
            }

            val borderOverlay = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundResource(R.drawable.bg_apple_tv_card_border)
                isDuplicateParentStateEnabled = true
            }

            val progressBarContainer = FrameLayout(this).apply {
                val barHeight = (3.5f * density).toInt()
                val marginH = (14 * density).toInt()
                val marginB = (10 * density).toInt()
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    barHeight
                ).apply {
                    gravity = Gravity.BOTTOM
                    marginStart = marginH
                    marginEnd = marginH
                    bottomMargin = marginB
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 2f * density
                    setColor(Color.parseColor("#80000000"))
                }
                visibility = View.GONE
            }

            val progressBarFill = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 2f * density
                    setColor(Color.parseColor("#FFFFFF"))
                }
            }
            progressBarContainer.addView(progressBarFill)
            cardProgressBars[movie.id] = Pair(progressBarContainer, progressBarFill)

            frameLayout.addView(imageView)
            frameLayout.addView(progressBarContainer)
            frameLayout.addView(borderOverlay)
            cardView.addView(frameLayout)

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

                    v.post {
                        val targetScrollX = if (index == 0) 0 else (v.left - (shelfScrollView.width / 2) + (v.width / 2)).coerceAtLeast(0)
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
        refreshShelfProgress()
    }

    private fun updateHeroMovie(index: Int, animate: Boolean = true) {
        if (index !in movies.indices) return
        val movie = movies[index]

        tvHeroTitle.text = movie.title
        tvHeroGenre.text = if (movie.year.isNotBlank()) "${movie.genreText}  ·  ${movie.year}" else movie.genreText
        tvHeroSynopsis.text = movie.synopsis

        val metadataParts = listOfNotNull(
            movie.year.takeIf { it.isNotBlank() },
            movie.durationText.takeIf { it.isNotBlank() },
            movie.sizeText.takeIf { it.isNotBlank() }
        )
        tvMetadataText.text = metadataParts.joinToString("  ·  ")

        badgeResolution.text = if (movie.is4k) "4K" else "HD"
        badgeResolution.visibility = View.VISIBLE

        when (movie.dynamicRange) {
            DynamicRangeType.SDR -> badgeHdr.visibility = View.GONE
            DynamicRangeType.HDR10 -> {
                badgeHdr.visibility = View.VISIBLE
                badgeHdr.text = "HDR10"
            }
            DynamicRangeType.HLG -> {
                badgeHdr.visibility = View.VISIBLE
                badgeHdr.text = "HLG"
            }
            DynamicRangeType.DOLBY_VISION -> {
                badgeHdr.visibility = View.VISIBLE
                badgeHdr.text = "DOLBY VISION"
            }
        }

        badgeAudio.text = movie.audioBadge
        badgeAudio.visibility = View.VISIBLE

        if (movie.channelsBadge.isNotBlank()) {
            badgeChannels.text = movie.channelsBadge
            badgeChannels.visibility = View.VISIBLE
        } else {
            badgeChannels.visibility = View.GONE
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val (savedPos, savedDur) = getSavedProgress(prefs, movie)
        if (savedDur > 0L && savedPos > 5000L && savedPos < savedDur - 30000L) {
            val remainingMs = (savedDur - savedPos).coerceAtLeast(60000L)
            val remainingHours = remainingMs / (1000 * 60 * 60)
            val remainingMinutes = (remainingMs / (1000 * 60)) % 60
            val remainingText = if (remainingHours > 0) "${remainingHours}h ${remainingMinutes}m" else "${remainingMinutes}m"
            btnPlayDirect.text = "Resume · $remainingText left"
        } else {
            btnPlayDirect.text = movie.durationText
        }

        btnCheck.isActivated = prefs.getBoolean("played_${movie.id}", movie.isPlayed)
        tvHeroCast.text = if (movie.actors.isNotBlank()) "Cast  ${movie.actors}" else ""
        tvHeroDirector.text = if (movie.director.isNotBlank()) "Director  ${movie.director}" else ""

        pendingBackdropRunnable?.let { mainHandler.removeCallbacks(it) }

        val crossfadeTask = Runnable {
            val key = movie.backdropUrl ?: movie.backdropAsset ?: ""
            val bmp = synchronized(backdropBitmaps) { backdropBitmaps[key] } ?: run {
                val opt = BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                if (!movie.backdropAsset.isNullOrBlank()) {
                    try {
                        assets.open(movie.backdropAsset!!).use { stream ->
                            BitmapFactory.decodeStream(stream, null, opt)
                        }
                    } catch (_: Exception) { null }
                } else null
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

    private fun launchPlayback(movie: JellyfinMovie) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val (pos, dur) = getSavedProgress(prefs, movie)
        val seekPos = if (dur > 0L && pos > 5000L && pos < dur - 30000L) pos else androidx.media3.common.C.TIME_UNSET

        val intent = Intent(this, PlaybackActivity::class.java).apply {
            data = Uri.parse(movie.streamUrl)
            if (seekPos != androidx.media3.common.C.TIME_UNSET) {
                putExtra("seek", seekPos)
            }
            putExtra("title", movie.title)
            putExtra("MOVIE_TITLE", movie.title)
            putExtra("MOVIE_YEAR", movie.year)
            putExtra("MOVIE_DURATION", movie.durationText)
            putExtra("MOVIE_GENRE", movie.genreText)
            putExtra("MOVIE_SYNOPSIS", movie.synopsis)
            putExtra("MOVIE_DIRECTOR", movie.director)
            putExtra("MOVIE_ACTORS", movie.actors)
            putExtra("MOVIE_SIZE", movie.sizeText)
            putExtra("MOVIE_VIDEO_CODEC", movie.videoCodecLabel)
            putExtra("MOVIE_AUDIO_CODEC", movie.audioCodecLabel)
            putExtra("MOVIE_IS_4K", movie.is4k)
            putExtra("MOVIE_HDR_TYPE", movie.dynamicRange.name)
            putExtra("MOVIE_DV_PROFILE", movie.dvProfile)
            putExtra("MOVIE_AUDIO_BADGE", movie.audioBadge)
            putExtra("MOVIE_CHANNELS_BADGE", movie.channelsBadge)
            putExtra("MOVIE_CHAPTERS_JSON", JellyfinMetadataMapper.serializeChapters(movie.chapters))
            activeSession?.let { s ->
                putExtra("JELLYFIN_SERVER_URL", s.serverUrl)
                putExtra("JELLYFIN_TOKEN", s.accessToken)
                putExtra("JELLYFIN_ITEM_ID", movie.id)
                putExtra("JELLYFIN_MEDIA_SOURCE_ID", movie.mediaSourceId)
            }
        }
        val options = android.app.ActivityOptions.makeCustomAnimation(
            this,
            R.anim.cinematic_fade_in,
            R.anim.cinematic_zoom_out
        )
        startActivity(intent, options.toBundle())
    }
}
