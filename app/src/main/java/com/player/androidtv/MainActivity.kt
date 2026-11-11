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
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import com.player.coremedia.jellyfin.DynamicRangeType
import com.player.coremedia.jellyfin.JellyfinDirectPlayClient
import com.player.coremedia.jellyfin.JellyfinMetadataMapper
import com.player.coremedia.jellyfin.JellyfinMovie
import com.player.coremedia.jellyfin.JellyfinSession
import com.player.coremedia.jellyfin.JellyfinShow
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

    private enum class CatalogTab {
        MOVIES,
        TV_SHOWS
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
    private lateinit var btnTabMovies: TextView
    private lateinit var btnTabShows: TextView
    private lateinit var tvShelfTitle: TextView
    private lateinit var serverBadge: View
    private lateinit var serverStatusDot: View
    private lateinit var tvServerInfo: TextView
    private lateinit var shelfScrollView: android.widget.HorizontalScrollView
    private lateinit var llCardShelf: LinearLayout

    // Episode Picker Sheet views
    private lateinit var episodesModalOverlay: FrameLayout
    private lateinit var tvEpisodesSeriesTitle: TextView
    private lateinit var tvEpisodesSeriesMeta: TextView
    private lateinit var btnCloseEpisodes: TextView
    private lateinit var llSeasonsContainer: LinearLayout
    private lateinit var llEpisodesContainer: LinearLayout

    private var serverBaseUrl: String = "http://192.168.100.108:8096"
    private var activeSession: JellyfinSession? = null
    private var currentTab: CatalogTab = CatalogTab.MOVIES
    private var selectedMovieIndex = 0
    private var selectedShowIndex = 0
    private var activeEpisodesShow: JellyfinShow? = null
    private var selectedSeason = 1
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
    private var tvShows: List<JellyfinShow> = emptyList()

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
        btnTabMovies = findViewById(R.id.btnTabMovies)
        btnTabShows = findViewById(R.id.btnTabShows)
        tvShelfTitle = findViewById(R.id.tvShelfTitle)
        serverBadge = findViewById(R.id.serverBadge)
        serverStatusDot = findViewById(R.id.serverStatusDot)
        tvServerInfo = findViewById(R.id.tvServerInfo)
        shelfScrollView = findViewById(R.id.shelfScrollView)
        shelfScrollView.isVerticalScrollBarEnabled = false
        shelfScrollView.isHorizontalScrollBarEnabled = false
        shelfScrollView.overScrollMode = View.OVER_SCROLL_NEVER
        llCardShelf = findViewById(R.id.llCardShelf)

        episodesModalOverlay = findViewById(R.id.episodesModalOverlay)
        tvEpisodesSeriesTitle = findViewById(R.id.tvEpisodesSeriesTitle)
        tvEpisodesSeriesMeta = findViewById(R.id.tvEpisodesSeriesMeta)
        btnCloseEpisodes = findViewById(R.id.btnCloseEpisodes)
        llSeasonsContainer = findViewById(R.id.llSeasonsContainer)
        llEpisodesContainer = findViewById(R.id.llEpisodesContainer)

        btnPlayDirect.requestFocus()
        btnPlayDirect.setOnClickListener {
            when (currentTab) {
                CatalogTab.MOVIES -> movies.getOrNull(selectedMovieIndex)?.let { launchPlayback(it) }
                CatalogTab.TV_SHOWS -> tvShows.getOrNull(selectedShowIndex)?.let { playOrBrowseShow(it) }
            }
        }
        btnCheck.setOnClickListener {
            when (currentTab) {
                CatalogTab.MOVIES -> movies.getOrNull(selectedMovieIndex)?.let { toggleWatchedStatus(it) }
                CatalogTab.TV_SHOWS -> tvShows.getOrNull(selectedShowIndex)?.let { show ->
                    val targetEpisode = findNextEpisodeForShow(show)
                    if (targetEpisode != null) {
                        toggleEpisodeWatchedStatus(show, targetEpisode)
                    } else {
                        openEpisodesModal(show)
                    }
                }
            }
        }
        btnDetails.setOnClickListener {
            when (currentTab) {
                CatalogTab.MOVIES -> movies.getOrNull(selectedMovieIndex)?.let { showMovieDetailsDialog(it) }
                CatalogTab.TV_SHOWS -> tvShows.getOrNull(selectedShowIndex)?.let { openEpisodesModal(it) }
            }
        }
        btnTabMovies.setOnClickListener { switchCatalogTab(CatalogTab.MOVIES) }
        btnTabShows.setOnClickListener { switchCatalogTab(CatalogTab.TV_SHOWS) }
        btnTabMovies.setOnFocusChangeListener { _, _ -> updateTabPillsUi() }
        btnTabShows.setOnFocusChangeListener { _, _ -> updateTabPillsUi() }
        btnCloseEpisodes.setOnClickListener { closeEpisodesModal() }
        episodesModalOverlay.setOnClickListener { closeEpisodesModal() }
        serverBadge.setOnClickListener {
            showJellyfinConnectDialog()
        }
        updateTabPillsUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::episodesModalOverlay.isInitialized && episodesModalOverlay.visibility == View.VISIBLE) {
            closeEpisodesModal()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun updateTabPillsUi() {
        applyTabPillStyle(btnTabMovies, isSelected = currentTab == CatalogTab.MOVIES)
        applyTabPillStyle(btnTabShows, isSelected = currentTab == CatalogTab.TV_SHOWS)
    }

    private fun applyTabPillStyle(tabView: TextView, isSelected: Boolean) {
        val density = resources.displayMetrics.density
        val focused = tabView.isFocused
        val bgColor = when {
            focused -> Color.parseColor("#FFFFFF")
            isSelected -> Color.parseColor("#992C2C2E")
            else -> Color.parseColor("#661C1C1E")
        }
        val textColor = when {
            focused -> Color.parseColor("#000000")
            isSelected -> Color.parseColor("#FFFFFF")
            else -> Color.parseColor("#E5E5EA")
        }
        tabView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 17f * density
            setColor(bgColor)
            if (isSelected && !focused) {
                setStroke((1 * density).toInt(), Color.parseColor("#88FFFFFF"))
            } else if (!focused) {
                setStroke((1 * density).toInt(), Color.parseColor("#2EFFFFFF"))
            }
        }
        tabView.setTextColor(textColor)
    }

    private fun switchCatalogTab(tab: CatalogTab) {
        if (currentTab == tab) return
        currentTab = tab
        updateTabPillsUi()
        tvShelfTitle.text = if (tab == CatalogTab.MOVIES) "Movies" else "TV Shows"
        shelfScrollView.scrollTo(0, 0)
        rebuildCurrentShelf()
        updateCurrentHero(animate = true)
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
        tvShows = OfflineDemoCatalog.getTvShows(serverBaseUrl)
        selectedMovieIndex = 0
        selectedShowIndex = 0
        preloadMovieBitmapsAsync(movies)
        preloadShowBitmapsAsync(tvShows)
        rebuildCurrentShelf()
        updateCurrentHero(animate = false)

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
            val liveShows = client.fetchTvShows(session)
            client.close()

            withContext(Dispatchers.Main) {
                val resolvedName = serverInfo?.serverName?.takeIf { it.isNotBlank() }
                    ?: session.serverName.ifBlank { "Live Server" }
                if (liveMovies.isNotEmpty() || liveShows.isNotEmpty()) {
                    if (liveMovies.isNotEmpty()) {
                        movies = liveMovies
                        selectedMovieIndex = 0
                        preloadMovieBitmapsAsync(liveMovies)
                    }
                    if (liveShows.isNotEmpty()) {
                        tvShows = liveShows
                        selectedShowIndex = 0
                        preloadShowBitmapsAsync(liveShows)
                    }
                    backdropBitmaps.clear()
                    cardBitmaps.clear()
                    rebuildCurrentShelf()
                    updateCurrentHero(animate = true)
                    val totalCount = liveMovies.size + liveShows.size
                    updateServerBadgeUi(connected = true, label = "Jellyfin · $resolvedName ($totalCount)")
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
        tvShows = OfflineDemoCatalog.getTvShows(serverBaseUrl)
        selectedMovieIndex = 0
        selectedShowIndex = 0
        backdropBitmaps.clear()
        cardBitmaps.clear()
        rebuildCurrentShelf()
        updateCurrentHero(animate = true)
        preloadMovieBitmapsAsync(movies)
        preloadShowBitmapsAsync(tvShows)
        updateServerBadgeUi(connected = false, label = "Connect Jellyfin")
    }

    private fun preloadMovieBitmapsAsync(catalog: List<JellyfinMovie>) {
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
                        if (currentTab == CatalogTab.MOVIES && idx == selectedMovieIndex) {
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

    private fun preloadShowBitmapsAsync(shows: List<JellyfinShow>) {
        ioScope.launch {
            val backdropOptions = BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            for ((idx, show) in shows.withIndex()) {
                val backdropKey = show.backdropUrl ?: show.backdropAsset ?: ""
                if (backdropKey.isNotEmpty()) {
                    val bmp = loadBitmap(show.backdropUrl, show.backdropAsset, backdropOptions)
                    if (bmp != null) {
                        synchronized(backdropBitmaps) {
                            backdropBitmaps[backdropKey] = bmp
                        }
                        if (currentTab == CatalogTab.TV_SHOWS && idx == selectedShowIndex) {
                            withContext(Dispatchers.Main) {
                                ivHeroBackdrop.setImageBitmap(bmp)
                                ivHeroBackdrop.alpha = 1f
                            }
                        }
                    }
                }

                val cardKey = show.cardUrl ?: show.cardAsset ?: ""
                if (cardKey.isNotEmpty()) {
                    val cardBmp = loadBitmap(show.cardUrl, show.cardAsset, null)
                    if (cardBmp != null) {
                        synchronized(cardBitmaps) {
                            cardBitmaps[cardKey] = cardBmp
                        }
                        withContext(Dispatchers.Main) {
                            cardImageViews[show.id]?.setImageBitmap(cardBmp)
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

    private fun playbackDisplayTitle(movie: JellyfinMovie): String {
        val series = movie.seriesName
        val code = movie.episodeCode
        if (!series.isNullOrBlank() && code.isNotBlank()) {
            val upperTitle = movie.title.uppercase()
            if (upperTitle.contains(code.uppercase()) || upperTitle.startsWith(series.uppercase())) {
                return movie.title
            }
            return "${series.uppercase()} · $code · ${movie.title}"
        }
        return movie.title
    }

    private fun episodeRowDisplayTitle(episode: JellyfinMovie): String {
        val code = episode.episodeCode
        val rawTitle = episode.title.trim()
        val series = episode.seriesName?.trim().orEmpty()
        var cleaned = rawTitle
        if (series.isNotEmpty() && cleaned.startsWith(series, ignoreCase = true)) {
            cleaned = cleaned.substring(series.length).trimStart(' ', '·', '-', ':').trim()
        }
        if (code.isNotEmpty() && cleaned.startsWith(code, ignoreCase = true)) {
            cleaned = cleaned.substring(code.length).trimStart(' ', '·', '-', ':').trim()
        }
        return if (code.isNotEmpty() && cleaned.isNotEmpty()) {
            "$code  ·  $cleaned"
        } else if (code.isNotEmpty()) {
            code
        } else {
            rawTitle
        }
    }

    private fun getSavedProgress(prefs: SharedPreferences, movie: JellyfinMovie): Pair<Long, Long> {
        val displayTitle = playbackDisplayTitle(movie)
        val keys = listOf(displayTitle.lowercase().trim(), movie.title.lowercase().trim()).distinct()
        var pos = 0L
        var dur = 0L
        for (k in keys) {
            pos = prefs.getLong("progress_$k", 0L)
            dur = prefs.getLong("duration_$k", 0L)
            if (pos > 0L) break
        }
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
        updateCurrentHero(animate = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
    }

    private fun rebuildCurrentShelf() {
        when (currentTab) {
            CatalogTab.MOVIES -> buildMovieShelf()
            CatalogTab.TV_SHOWS -> buildShowShelf()
        }
    }

    private fun updateCurrentHero(animate: Boolean) {
        when (currentTab) {
            CatalogTab.MOVIES -> if (movies.isNotEmpty()) updateHeroMovie(selectedMovieIndex.coerceIn(movies.indices), animate)
            CatalogTab.TV_SHOWS -> if (tvShows.isNotEmpty()) updateHeroShow(selectedShowIndex.coerceIn(tvShows.indices), animate)
        }
    }

    private fun refreshShelfProgress() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (currentTab == CatalogTab.MOVIES) {
            for (movie in movies) {
                val pair = cardProgressBars[movie.id] ?: continue
                val (pos, dur) = getSavedProgress(prefs, movie)
                applyProgressToCardBar(pair, pos, dur)
            }
        } else {
            for (show in tvShows) {
                val pair = cardProgressBars[show.id] ?: continue
                val activeEp = findNextEpisodeForShow(show)
                if (activeEp != null) {
                    val (pos, dur) = getSavedProgress(prefs, activeEp)
                    applyProgressToCardBar(pair, pos, dur)
                } else {
                    pair.first.visibility = View.GONE
                }
            }
        }
    }

    private fun applyProgressToCardBar(pair: Pair<View, View>, pos: Long, dur: Long) {
        val (container, fill) = pair
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

    private fun buildMovieShelf() {
        llCardShelf.removeAllViews()
        cardProgressBars.clear()
        cardImageViews.clear()
        for ((index, movie) in movies.withIndex()) {
            val card = createShelfCard(
                index = index,
                itemId = movie.id,
                title = movie.title,
                subtitle = "${movie.year}  ·  ${if (movie.is4k) "4K" else "HD"}",
                cardUrl = movie.cardUrl,
                cardAsset = movie.cardAsset,
                onFocused = {
                    selectedMovieIndex = index
                    updateHeroMovie(index)
                },
                onActivated = {
                    selectedMovieIndex = index
                    updateHeroMovie(index)
                    launchPlayback(movie)
                }
            )
            llCardShelf.addView(card)
        }
        refreshShelfProgress()
    }

    private fun buildShowShelf() {
        llCardShelf.removeAllViews()
        cardProgressBars.clear()
        cardImageViews.clear()
        for ((index, show) in tvShows.withIndex()) {
            val seasonLabel = if (show.seasonsCount == 1) "1 Season" else "${show.seasonsCount} Seasons"
            val card = createShelfCard(
                index = index,
                itemId = show.id,
                title = show.title,
                subtitle = "${show.year}  ·  $seasonLabel",
                cardUrl = show.cardUrl,
                cardAsset = show.cardAsset,
                onFocused = {
                    selectedShowIndex = index
                    updateHeroShow(index)
                },
                onActivated = {
                    selectedShowIndex = index
                    updateHeroShow(index)
                    openEpisodesModal(show)
                }
            )
            llCardShelf.addView(card)
        }
        refreshShelfProgress()
    }

    private fun createShelfCard(
        index: Int,
        itemId: String,
        title: String,
        subtitle: String,
        cardUrl: String?,
        cardAsset: String?,
        onFocused: () -> Unit,
        onActivated: () -> Unit
    ): CardView {
        val density = resources.displayMetrics.density
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

        val fallbackBox = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#252836"), Color.parseColor("#121318"))
            )
        }
        val tvFallbackTitle = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
            letterSpacing = 0.05f
        }
        val tvFallbackSub = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 10.5f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        fallbackBox.addView(tvFallbackTitle)
        fallbackBox.addView(tvFallbackSub)

        val imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        cardImageViews[itemId] = imageView

        val cardKey = cardUrl ?: cardAsset ?: ""
        val cachedCard = synchronized(cardBitmaps) { cardBitmaps[cardKey] }
        if (cachedCard != null) {
            imageView.setImageBitmap(cachedCard)
        } else if (!cardAsset.isNullOrBlank()) {
            try {
                assets.open(cardAsset).use { stream ->
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
        cardProgressBars[itemId] = Pair(progressBarContainer, progressBarFill)

        frameLayout.addView(fallbackBox)
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
                onFocused()
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

        cardView.setOnClickListener { onActivated() }
        cardView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onActivated()
                true
            } else {
                false
            }
        }

        return cardView
    }

    private fun updateHeroMovie(index: Int, animate: Boolean = true) {
        if (index !in movies.indices) return
        val movie = movies[index]

        val metadataParts = listOfNotNull(
            movie.year.takeIf { it.isNotBlank() },
            movie.durationText.takeIf { it.isNotBlank() },
            movie.sizeText.takeIf { it.isNotBlank() }
        )

        bindHeroMetadataAndBackdrop(
            title = movie.title,
            genre = if (movie.year.isNotBlank()) "${movie.genreText}  ·  ${movie.year}" else movie.genreText,
            synopsis = movie.synopsis,
            metadataText = metadataParts.joinToString("  ·  "),
            is4k = movie.is4k,
            dynamicRange = movie.dynamicRange,
            audioBadge = movie.audioBadge,
            channelsBadge = movie.channelsBadge,
            actors = movie.actors,
            director = movie.director,
            backdropUrl = movie.backdropUrl,
            backdropAsset = movie.backdropAsset,
            animate = animate
        )

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
    }

    private fun updateHeroShow(index: Int, animate: Boolean = true) {
        if (index !in tvShows.indices) return
        val show = tvShows[index]
        val nextEp = findNextEpisodeForShow(show)
        val seasonText = if (show.seasonsCount == 1) "1 Season" else "${show.seasonsCount} Seasons"
        val metadataParts = listOfNotNull(
            show.year.takeIf { it.isNotBlank() },
            seasonText,
            nextEp?.episodeCode?.takeIf { it.isNotBlank() }
        )

        bindHeroMetadataAndBackdrop(
            title = show.title,
            genre = if (show.year.isNotBlank()) "${show.genreText}  ·  ${show.year}" else show.genreText,
            synopsis = show.synopsis,
            metadataText = metadataParts.joinToString("  ·  "),
            is4k = nextEp?.is4k ?: show.is4k,
            dynamicRange = nextEp?.dynamicRange ?: show.dynamicRange,
            audioBadge = nextEp?.audioBadge ?: show.audioBadge,
            channelsBadge = nextEp?.channelsBadge ?: show.channelsBadge,
            actors = show.actors,
            director = show.director,
            backdropUrl = show.backdropUrl,
            backdropAsset = show.backdropAsset,
            animate = animate
        )

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (nextEp != null) {
            val (savedPos, savedDur) = getSavedProgress(prefs, nextEp)
            if (savedDur > 0L && savedPos > 5000L && savedPos < savedDur - 30000L) {
                btnPlayDirect.text = "Resume ${nextEp.episodeCode}"
            } else {
                btnPlayDirect.text = "Play ${nextEp.episodeCode}"
            }
            btnCheck.isActivated = prefs.getBoolean("played_${nextEp.id}", nextEp.isPlayed)
        } else {
            btnPlayDirect.text = "Episodes"
            btnCheck.isActivated = false
        }
    }

    private fun bindHeroMetadataAndBackdrop(
        title: String,
        genre: String,
        synopsis: String,
        metadataText: String,
        is4k: Boolean,
        dynamicRange: DynamicRangeType,
        audioBadge: String,
        channelsBadge: String,
        actors: String,
        director: String,
        backdropUrl: String?,
        backdropAsset: String?,
        animate: Boolean
    ) {
        tvHeroTitle.text = title
        tvHeroGenre.text = genre
        tvHeroSynopsis.text = synopsis
        tvMetadataText.text = metadataText

        badgeResolution.text = if (is4k) "4K" else "HD"
        badgeResolution.visibility = View.VISIBLE

        when (dynamicRange) {
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

        badgeAudio.text = audioBadge
        badgeAudio.visibility = View.VISIBLE

        if (channelsBadge.isNotBlank()) {
            badgeChannels.text = channelsBadge
            badgeChannels.visibility = View.VISIBLE
        } else {
            badgeChannels.visibility = View.GONE
        }

        tvHeroCast.text = if (actors.isNotBlank()) "Cast  $actors" else ""
        tvHeroDirector.text = if (director.isNotBlank()) "Director  $director" else ""

        pendingBackdropRunnable?.let { mainHandler.removeCallbacks(it) }

        val crossfadeTask = Runnable {
            val key = backdropUrl ?: backdropAsset ?: ""
            val bmp = if (key.isNotEmpty()) synchronized(backdropBitmaps) { backdropBitmaps[key] } else null
            val resolved = bmp ?: if (!backdropAsset.isNullOrBlank()) {
                val opt = BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                try {
                    assets.open(backdropAsset).use { stream ->
                        BitmapFactory.decodeStream(stream, null, opt)
                    }
                } catch (_: Exception) { null }
            } else null

            ivHeroBackdrop.animate().cancel()
            ivHeroBackdropAlt.animate().cancel()
            if (isBackdropPrimary) {
                ivHeroBackdropAlt.setImageBitmap(resolved)
                ivHeroBackdropAlt.animate().alpha(if (resolved != null) 1f else 0f).setDuration(220).start()
                ivHeroBackdrop.animate().alpha(0f).setDuration(220).start()
                isBackdropPrimary = false
            } else {
                ivHeroBackdrop.setImageBitmap(resolved)
                ivHeroBackdrop.animate().alpha(if (resolved != null) 1f else 0f).setDuration(220).start()
                ivHeroBackdropAlt.animate().alpha(0f).setDuration(220).start()
                isBackdropPrimary = true
            }
        }

        pendingBackdropRunnable = crossfadeTask
        if (animate) {
            mainHandler.postDelayed(crossfadeTask, 120)
        } else {
            crossfadeTask.run()
        }
    }

    private fun findNextEpisodeForShow(show: JellyfinShow): JellyfinMovie? {
        if (show.episodes.isEmpty()) return null
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val sorted = show.episodes.sortedWith(compareBy({ it.seasonNumber ?: 1 }, { it.episodeNumber ?: 1 }))
        // Prefer an episode currently in progress, else first unwatched, else first episode
        for (ep in sorted) {
            val (pos, dur) = getSavedProgress(prefs, ep)
            if (dur > 0L && pos > 5000L && pos < dur - 30000L) return ep
        }
        return sorted.firstOrNull { !prefs.getBoolean("played_${it.id}", it.isPlayed) } ?: sorted.first()
    }

    private fun playOrBrowseShow(show: JellyfinShow) {
        val nextEp = findNextEpisodeForShow(show)
        if (nextEp != null) {
            launchPlayback(nextEp)
        } else {
            openEpisodesModal(show)
        }
    }

    private fun toggleEpisodeWatchedStatus(show: JellyfinShow, episode: JellyfinMovie) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val key = "played_${episode.id}"
        val nextPlayed = !prefs.getBoolean(key, episode.isPlayed)
        prefs.edit().putBoolean(key, nextPlayed).apply()
        val updatedEp = episode.copy(isPlayed = nextPlayed)
        val updatedEpisodes = show.episodes.map { if (it.id == episode.id) updatedEp else it }
        val updatedShow = show.copy(episodes = updatedEpisodes)
        tvShows = tvShows.map { if (it.id == show.id) updatedShow else it }
        if (currentTab == CatalogTab.TV_SHOWS) {
            updateHeroShow(selectedShowIndex, animate = false)
        }
        activeSession?.let { session ->
            ioScope.launch {
                val client = JellyfinDirectPlayClient(session.serverUrl)
                client.setPlayedStatus(session, episode.id, nextPlayed)
                client.close()
            }
        }
        Toast.makeText(
            this,
            if (nextPlayed) "${episode.episodeCode} marked as watched" else "${episode.episodeCode} marked as unwatched",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openEpisodesModal(show: JellyfinShow) {
        activeEpisodesShow = show
        tvEpisodesSeriesTitle.text = show.title
        val seasonLabel = if (show.seasonsCount == 1) "1 Season" else "${show.seasonsCount} Seasons"
        tvEpisodesSeriesMeta.text = "${show.year}  ·  $seasonLabel  ·  Direct Play"
        episodesModalOverlay.visibility = View.VISIBLE

        if (show.episodes.isNotEmpty()) {
            renderEpisodesSheetContent(show)
            return
        }

        val session = activeSession
        if (session == null) {
            renderEpisodesSheetContent(show)
            return
        }

        llSeasonsContainer.removeAllViews()
        llEpisodesContainer.removeAllViews()
        val loadingText = TextView(this).apply {
            text = "Loading episodes from Jellyfin..."
            setTextColor(Color.parseColor("#A1A1A6"))
            textSize = 13f
            setPadding(8, 24, 8, 24)
        }
        llEpisodesContainer.addView(loadingText)
        btnCloseEpisodes.requestFocus()

        ioScope.launch {
            val client = JellyfinDirectPlayClient(session.serverUrl)
            val fetched = client.fetchEpisodes(session, show.id)
            client.close()

            withContext(Dispatchers.Main) {
                val seasonsDetected = fetched.mapNotNull { it.seasonNumber }.distinct().size.coerceAtLeast(show.seasonsCount)
                val updatedShow = show.copy(episodes = fetched, seasonsCount = seasonsDetected)
                tvShows = tvShows.map { if (it.id == show.id) updatedShow else it }
                if (activeEpisodesShow?.id == show.id) {
                    activeEpisodesShow = updatedShow
                    renderEpisodesSheetContent(updatedShow)
                    if (currentTab == CatalogTab.TV_SHOWS) {
                        updateHeroShow(selectedShowIndex, animate = false)
                    }
                }
            }
        }
    }

    private fun closeEpisodesModal() {
        episodesModalOverlay.visibility = View.GONE
        activeEpisodesShow = null
        btnPlayDirect.requestFocus()
    }

    private fun renderEpisodesSheetContent(show: JellyfinShow) {
        llSeasonsContainer.removeAllViews()
        llEpisodesContainer.removeAllViews()

        val episodes = show.episodes
        if (episodes.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No playable episodes found for this series."
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 13f
                setPadding(8, 24, 8, 24)
            }
            llEpisodesContainer.addView(emptyView)
            btnCloseEpisodes.requestFocus()
            return
        }

        val seasons = episodes.map { it.seasonNumber ?: 1 }.distinct().sorted()
        if (selectedSeason !in seasons) {
            selectedSeason = seasons.first()
        }

        val density = resources.displayMetrics.density
        var firstSeasonPill: TextView? = null
        for (seasonNum in seasons) {
            val isActive = seasonNum == selectedSeason
            val pill = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (32 * density).toInt()
                ).apply {
                    marginEnd = (10 * density).toInt()
                }
                text = "Season $seasonNum"
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                isFocusable = true
                isClickable = true
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)

                fun refreshPillStyle(focused: Boolean) {
                    val bgColor = when {
                        focused -> Color.parseColor("#FFFFFF")
                        isActive -> Color.parseColor("#3DFFFFFF")
                        else -> Color.parseColor("#18FFFFFF")
                    }
                    val txtColor = if (focused) Color.BLACK else Color.WHITE
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 16f * density
                        setColor(bgColor)
                    }
                    setTextColor(txtColor)
                }

                refreshPillStyle(false)
                setOnFocusChangeListener { _, hasFocus -> refreshPillStyle(hasFocus) }
                setOnClickListener {
                    selectedSeason = seasonNum
                    renderEpisodesSheetContent(show)
                }
            }
            if (firstSeasonPill == null) firstSeasonPill = pill
            llSeasonsContainer.addView(pill)
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val seasonEpisodes = episodes
            .filter { (it.seasonNumber ?: 1) == selectedSeason }
            .sortedBy { it.episodeNumber ?: 1 }

        var firstEpisodeRow: View? = null
        for (episode in seasonEpisodes) {
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isClickable = true
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())

                fun applyRowBg(focused: Boolean) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 12f * density
                        setColor(if (focused) Color.parseColor("#33FFFFFF") else Color.parseColor("#14FFFFFF"))
                        if (focused) {
                            setStroke((1.5f * density).toInt(), Color.WHITE)
                        }
                    }
                }
                applyRowBg(false)
                setOnFocusChangeListener { _, hasFocus -> applyRowBg(hasFocus) }
                setOnClickListener {
                    closeEpisodesModal()
                    launchPlayback(episode)
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvTitle = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = episodeRowDisplayTitle(episode)
                setTextColor(Color.WHITE)
                textSize = 13.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            val tvSpecs = TextView(this).apply {
                val res = if (episode.is4k) "4K" else "HD"
                text = listOfNotNull(
                    episode.durationText.takeIf { it.isNotBlank() },
                    res,
                    episode.audioBadge.takeIf { it.isNotBlank() }
                ).joinToString("  ·  ")
                setTextColor(Color.parseColor("#A1A1A6"))
                textSize = 11f
            }
            topRow.addView(tvTitle)
            topRow.addView(tvSpecs)
            row.addView(topRow)

            if (episode.synopsis.isNotBlank()) {
                val tvEpOverview = TextView(this).apply {
                    text = episode.synopsis
                    setTextColor(Color.parseColor("#C7C7CC"))
                    textSize = 11.5f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, (4 * density).toInt(), 0, 0)
                }
                row.addView(tvEpOverview)
            }

            val (pos, dur) = getSavedProgress(prefs, episode)
            if (dur > 0L && pos > 5000L && pos < dur - 30000L) {
                val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (3 * density).toInt()
                    ).apply {
                        topMargin = (6 * density).toInt()
                    }
                    max = 100
                    progress = ((pos * 100L) / dur).toInt().coerceIn(2, 99)
                }
                row.addView(pb)
            }

            if (firstEpisodeRow == null) firstEpisodeRow = row
            llEpisodesContainer.addView(row)
        }

        (firstEpisodeRow ?: firstSeasonPill ?: btnCloseEpisodes).requestFocus()
    }

    private fun launchPlayback(movie: JellyfinMovie) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val (pos, dur) = getSavedProgress(prefs, movie)
        val seekPos = if (dur > 0L && pos > 5000L && pos < dur - 30000L) pos else androidx.media3.common.C.TIME_UNSET
        val displayTitle = playbackDisplayTitle(movie)

        val intent = Intent(this, PlaybackActivity::class.java).apply {
            data = Uri.parse(movie.streamUrl)
            if (seekPos != androidx.media3.common.C.TIME_UNSET) {
                putExtra("seek", seekPos)
            }
            putExtra("title", displayTitle)
            putExtra("MOVIE_TITLE", displayTitle)
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
