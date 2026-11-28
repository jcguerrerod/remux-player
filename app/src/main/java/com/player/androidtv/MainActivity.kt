package com.player.androidtv

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.player.androidtv.databinding.ActivityMainBinding
import com.player.androidtv.media.ImageCacheHelper
import com.player.androidtv.security.TokenVault
import com.player.androidtv.ui.LiquidGlassHelper
import com.player.androidtv.ui.card.ShelfCardViewHolder
import com.player.androidtv.ui.modal.EpisodeRowViewHolder
import com.player.androidtv.ui.settings.SettingsOverlayController
import com.player.coremedia.jellyfin.DynamicRangeType
import com.player.coremedia.jellyfin.JellyfinDirectPlayClient
import com.player.coremedia.jellyfin.JellyfinMetadataMapper
import com.player.coremedia.jellyfin.JellyfinMovie
import com.player.coremedia.jellyfin.JellyfinSession
import com.player.coremedia.jellyfin.JellyfinShow
import com.player.coremedia.security.NetworkUrlSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

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

    private lateinit var binding: ActivityMainBinding

    private var serverBaseUrl: String = "http://192.168.100.108:8096"
    private var activeSession: JellyfinSession? = null
    private var currentTab: CatalogTab = CatalogTab.MOVIES
    private var selectedMovieIndex = 0
    private var selectedShowIndex = 0
    private var activeEpisodesShow: JellyfinShow? = null
    private var activeDetailsMovie: JellyfinMovie? = null
    private var selectedSeason = 1
    private var isBackdropPrimary = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingBackdropRunnable: Runnable? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val imageFetchDispatcher = Dispatchers.IO.limitedParallelism(4)
    private var preloadMoviesJob: Job? = null
    private var preloadShowsJob: Job? = null

    private val clientLock = Any()
    private val cachedClientRef = AtomicReference<Pair<String, JellyfinDirectPlayClient>?>(null)

    // Bounded LRU image cache for 60 FPS navigation without memory bloat
    private val imageCache = ImageCacheHelper()
    private val shelfCardHolders = mutableMapOf<String, ShelfCardViewHolder>()

    private var movies: List<JellyfinMovie> = emptyList()
    private var tvShows: List<JellyfinShow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("google_sdk") || Build.MODEL.contains("Emulator")) {
            serverBaseUrl = "http://10.0.2.2:8096"
        }

        setupViews()
        loadInitialCatalogAndSyncJellyfin()
    }

    private lateinit var settingsController: SettingsOverlayController

    private fun setupViews() {
        with(binding) {
            settingsController = SettingsOverlayController(
                context = this@MainActivity,
                overlay = settingsInclude.settingsOverlay,
                categoriesContainer = settingsInclude.llSettingsCategories,
                detailContainer = settingsInclude.llSettingsDetailContainer,
                detailScrollView = settingsInclude.svSettingsDetail,
                tvInfoTitle = settingsInclude.tvSettingsInfoTitle,
                tvInfoBody = settingsInclude.tvSettingsInfoBody,
                getSession = { activeSession },
                getLibraryCountLabel = { "${movies.size} Movies · ${tvShows.size} Shows" },
                onOpenServerConnect = { showJellyfinConnectDialog() },
                onSyncLibraryNow = { activeSession?.let { syncLibraryFromJellyfin(it) } },
                onDisconnectServer = { disconnectJellyfinSession() },
                onClearImageCache = { imageCache.clearAll() },
                onClosed = {
                    binding.mainContentBlurTarget.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                    binding.serverBadge.requestFocus()
                    updateTabPillsUi()
                }
            )
            shelfScrollView.isVerticalScrollBarEnabled = false
            shelfScrollView.isHorizontalScrollBarEnabled = false
            shelfScrollView.overScrollMode = View.OVER_SCROLL_NEVER

            // Liquid Glass sheets refract the real content that sits behind them.
            detailsModalCard.backdropSource = mainContentBlurTarget
            connectModalCard.backdropSource = mainContentBlurTarget
            episodesModalCard.backdropSource = mainContentBlurTarget

            btnPlayDirect.requestFocus()
            btnPlayDirect.setOnClickListener {
                when (currentTab) {
                    CatalogTab.MOVIES -> movies.getOrNull(selectedMovieIndex)?.let { launchPlayback(it) }
                    CatalogTab.TV_SHOWS -> tvShows.getOrNull(selectedShowIndex)?.let { playOrBrowseShow(it) }
                }
            }
            btnRestart.setOnClickListener {
                when (currentTab) {
                    CatalogTab.MOVIES -> movies.getOrNull(selectedMovieIndex)?.let { resetMovieProgress(it) }
                    CatalogTab.TV_SHOWS -> tvShows.getOrNull(selectedShowIndex)?.let { show ->
                        findNextEpisodeForShow(show)?.let { ep -> resetEpisodeProgress(show, ep) }
                    }
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
            serverBadge.setOnFocusChangeListener { _, _ -> updateTabPillsUi() }

            episodesModalOverlay.setOnClickListener { closeEpisodesModal() }
            detailsModalOverlay.setOnClickListener { closeDetailsModal() }
            btnDetailsPlayNow.setOnClickListener {
                val movie = activeDetailsMovie ?: return@setOnClickListener
                closeDetailsModal()
                launchPlayback(movie)
            }
            btnDetailsPlayNow.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) detailsModalCard.energize(v.x + v.width * 0.5f, v.y + v.height * 0.5f)
            }

            connectModalOverlay.setOnClickListener { closeConnectModal() }
            btnConnectDisconnect.setOnClickListener {
                closeConnectModal()
                disconnectJellyfinSession()
            }
            btnConnectDisconnect.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) connectModalCard.energize(v.x + v.width * 0.5f, v.y + v.height * 0.5f)
            }
            btnConnectSubmit.setOnClickListener { submitJellyfinConnect() }
            btnConnectSubmit.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) connectModalCard.energize(v.x + v.width * 0.5f, v.y + v.height * 0.5f)
            }
            etPassword.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submitJellyfinConnect()
                    true
                } else {
                    false
                }
            }
            serverBadge.setOnClickListener {
                binding.mainContentBlurTarget.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                settingsController.open()
            }
        }
        updateTabPillsUi()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (::settingsController.isInitialized && settingsController.isOpen) {
                settingsController.close()
                return true
            }
            if (binding.connectModalOverlay.visibility == View.VISIBLE) {
                closeConnectModal()
                return true
            }
            if (binding.detailsModalOverlay.visibility == View.VISIBLE) {
                closeDetailsModal()
                return true
            }
            if (binding.episodesModalOverlay.visibility == View.VISIBLE) {
                closeEpisodesModal()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun updateTabPillsUi() {
        with(binding) {
            btnTabMovies.isActivated = (currentTab == CatalogTab.MOVIES)
            btnTabShows.isActivated = (currentTab == CatalogTab.TV_SHOWS)
            val activeNavId = if (currentTab == CatalogTab.MOVIES) R.id.btnTabMovies else R.id.btnTabShows
            btnPlayDirect.nextFocusUpId = activeNavId
        }
    }

    private fun switchCatalogTab(tab: CatalogTab) {
        if (currentTab == tab) return
        currentTab = tab
        updateTabPillsUi()
        binding.shelfScrollView.scrollTo(0, 0)
        rebuildCurrentShelf()
        updateCurrentHero(animate = true)
    }

    private fun getOrCreateClient(serverUrl: String): JellyfinDirectPlayClient {
        val normalized = serverUrl.trim().trimEnd('/')
        synchronized(clientLock) {
            val current = cachedClientRef.get()
            if (current != null && current.first == normalized) {
                return current.second
            }
            current?.second?.close()
            val fresh = JellyfinDirectPlayClient(normalized)
            cachedClientRef.set(normalized to fresh)
            return fresh
        }
    }

    private fun loadInitialCatalogAndSyncJellyfin() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_JF_URL, null)
        val savedToken = TokenVault.readAndMigrateToken(prefs, KEY_JF_TOKEN)
        val savedUserId = prefs.getString(KEY_JF_USER_ID, null)
        val savedUserName = prefs.getString(KEY_JF_USER_NAME, "") ?: ""
        val savedServerName = prefs.getString(KEY_JF_SERVER_NAME, "") ?: ""

        if (!savedUrl.isNullOrBlank()) {
            when (val validation = NetworkUrlSecurity.validateServerUrl(savedUrl)) {
                is NetworkUrlSecurity.ServerUrlResult.Valid -> serverBaseUrl = validation.normalizedUrl
                is NetworkUrlSecurity.ServerUrlResult.Invalid -> Log.w(TAG, "Ignored invalid stored URL: ${validation.reason}")
            }
        }

        // Render showcase catalog immediately so UI appears at 0ms without blocking
        val demoMovies = OfflineDemoCatalog.getMovies(serverBaseUrl)
        val demoShows = OfflineDemoCatalog.getTvShows(serverBaseUrl)
        movies = if (!savedToken.isNullOrBlank()) {
            demoMovies.map { m ->
                val tokParam = if (m.logoUrl?.contains("api_key=") == false) "&api_key=$savedToken" else ""
                m.copy(logoUrl = m.logoUrl?.let { "$it$tokParam" })
            }
        } else {
            demoMovies
        }
        tvShows = demoShows
        selectedMovieIndex = 0
        selectedShowIndex = 0
        preloadMovieBitmapsAsync(movies)
        preloadShowBitmapsAsync(tvShows)
        rebuildCurrentShelf()
        updateCurrentHero(animate = false)

        if (!savedUrl.isNullOrBlank() && !savedToken.isNullOrBlank() && !savedUserId.isNullOrBlank()) {
            val session = JellyfinSession(serverBaseUrl, savedToken, savedUserId, savedUserName, savedServerName)
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
            val client = getOrCreateClient(serverBaseUrl)
            val info = client.testConnection()
            if (info != null) {
                withContext(Dispatchers.Main) {
                    if (::settingsController.isInitialized) {
                        settingsController.refreshIfOpen()
                    }
                }
            }
        }
    }

    private fun syncLibraryFromJellyfin(session: JellyfinSession) {
        ioScope.launch {
            val client = getOrCreateClient(session.serverUrl)
            val serverInfo = client.testConnection()
            val moviesDeferred = async { client.fetchMovies(session) }
            val showsDeferred = async { client.fetchTvShows(session) }
            val episodesDeferred = async { client.fetchAllEpisodes(session) }

            val liveMovies = moviesDeferred.await()
            val rawShows = showsDeferred.await()
            val allEpisodes = episodesDeferred.await()

            val episodesBySeriesId = allEpisodes.filter { !it.seriesId.isNullOrBlank() }.groupBy { it.seriesId }
            val episodesBySeriesName = allEpisodes.filter { !it.seriesName.isNullOrBlank() }.groupBy { it.seriesName?.trim()?.lowercase(Locale.US) }

            val liveShows = rawShows.map { show ->
                val matching = episodesBySeriesId[show.id]
                    ?: episodesBySeriesName[show.title.trim().lowercase(Locale.US)]
                    ?: emptyList()
                if (matching.isNotEmpty()) {
                    show.withEpisodes(matching)
                } else {
                    show
                }
            }

            withContext(Dispatchers.Main) {
                val resolvedName = serverInfo?.serverName?.takeIf { it.isNotBlank() }
                    ?: session.serverName.ifBlank { "Live Server" }
                if (liveMovies.isNotEmpty() || liveShows.isNotEmpty()) {
                    imageCache.clearAll()
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
                    rebuildCurrentShelf()
                    updateCurrentHero(animate = true)
                    if (!binding.btnTabMovies.hasFocus() && !binding.btnTabShows.hasFocus() && !binding.serverBadge.hasFocus()) {
                        binding.btnPlayDirect.post { binding.btnPlayDirect.requestFocus() }
                    }
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
        binding.tvServerInfo.text = "Settings"
        binding.serverStatusDot.visibility = View.GONE
        if (::settingsController.isInitialized) {
            settingsController.refreshIfOpen()
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
        binding.btnCheck.isActivated = nextPlayed
        binding.btnCheck.text = if (nextPlayed) "Watched" else "Mark Watched"
        refreshShelfProgress()

        activeSession?.let { session ->
            ioScope.launch {
                val client = getOrCreateClient(session.serverUrl)
                client.setPlayedStatus(session, movie.id, nextPlayed)
            }
        }
    }

    private fun resetMovieProgress(movie: JellyfinMovie) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val displayTitle = playbackDisplayTitle(movie)
        val keys = listOf(displayTitle.lowercase().trim(), movie.title.lowercase().trim()).distinct()
        val editor = prefs.edit()
            .putBoolean("reset_${movie.id}", true)
            .putBoolean("reset_${movie.title.lowercase().trim()}", true)
            .remove("progress_${movie.title}")
            .remove("duration_${movie.title}")
        for (k in keys) {
            editor.remove("progress_$k").remove("duration_$k")
        }
        editor.apply()

        val updated = movie.copy(playbackPositionMs = 0L)
        val mutable = movies.toMutableList()
        if (selectedMovieIndex in mutable.indices) {
            mutable[selectedMovieIndex] = updated
            movies = mutable
        }

        refreshShelfProgress()
        updateCurrentHero(animate = false)

        activeSession?.let { session ->
            ioScope.launch {
                val client = getOrCreateClient(session.serverUrl)
                client.reportProgress(movie.id, 0L, session.accessToken, isPaused = true)
            }
        }
    }

    private fun resetEpisodeProgress(show: JellyfinShow, episode: JellyfinMovie) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val displayTitle = playbackDisplayTitle(episode)
        val keys = listOf(displayTitle.lowercase().trim(), episode.title.lowercase().trim()).distinct()
        val editor = prefs.edit()
            .putBoolean("reset_${episode.id}", true)
            .putBoolean("reset_${episode.title.lowercase().trim()}", true)
            .remove("progress_${episode.title}")
            .remove("duration_${episode.title}")
        for (k in keys) {
            editor.remove("progress_$k").remove("duration_$k")
        }
        editor.apply()

        val updatedEp = episode.copy(playbackPositionMs = 0L)
        val updatedEpisodes = show.episodes.map { if (it.id == episode.id) updatedEp else it }
        val updatedShow = show.copy(episodes = updatedEpisodes)
        tvShows = tvShows.map { if (it.id == show.id) updatedShow else it }

        refreshShelfProgress()
        updateCurrentHero(animate = false)

        activeSession?.let { session ->
            ioScope.launch {
                val client = getOrCreateClient(session.serverUrl)
                client.reportProgress(episode.id, 0L, session.accessToken, isPaused = true)
            }
        }
    }

    private fun bindLogoOrTitle(
        logoView: android.widget.ImageView,
        titleView: TextView,
        title: String,
        logoUrl: String?
    ) {
        titleView.text = title
        if (!logoUrl.isNullOrBlank()) {
            val cachedLogo = imageCache.getCachedLogo(logoUrl)
            if (cachedLogo != null) {
                logoView.tag = logoUrl
                logoView.setImageBitmap(cachedLogo)
                logoView.alpha = 1f
                logoView.visibility = View.VISIBLE
                titleView.visibility = View.GONE
            } else {
                titleView.visibility = View.GONE
                logoView.visibility = View.INVISIBLE
                logoView.tag = logoUrl
                ioScope.launch(imageFetchDispatcher) {
                    val fetchedLogo = imageCache.loadLogo(logoUrl)
                    withContext(Dispatchers.Main) {
                        if (logoView.tag == logoUrl) {
                            if (fetchedLogo != null) {
                                logoView.setImageBitmap(fetchedLogo)
                                logoView.alpha = 1f
                                logoView.visibility = View.VISIBLE
                                titleView.visibility = View.GONE
                            } else {
                                titleView.visibility = View.VISIBLE
                                logoView.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        } else {
            logoView.tag = null
            logoView.visibility = View.GONE
            titleView.visibility = View.VISIBLE
        }
    }

    private fun showMovieDetailsDialog(movie: JellyfinMovie) {
        activeDetailsMovie = movie
        with(binding) {
            bindLogoOrTitle(ivDetailsModalLogo, tvDetailsModalTitle, movie.title, movie.logoUrl)
            tvDetailsModalSub.text = listOfNotNull(
                movie.year.takeIf { it.isNotBlank() },
                movie.genreText.takeIf { it.isNotBlank() },
                movie.durationText.takeIf { it.isNotBlank() }
            ).joinToString("  ·  ")

            val resLabel = if (movie.is4k) "4K UHD" else if (movie.resolutionBadge == "SD") "SD" else "1080p HD"
            val rangeLabel = when (movie.dynamicRange) {
                DynamicRangeType.DOLBY_VISION -> "Dolby Vision ${movie.dvProfile}".trim()
                DynamicRangeType.HDR10 -> "HDR10"
                DynamicRangeType.HLG -> "HLG"
                DynamicRangeType.SDR -> "SDR"
            }
            tvDetailsVideoSpec.text = "$resLabel · $rangeLabel"
            tvDetailsAudioSpec.text = listOfNotNull(
                movie.audioBadge.takeIf { it.isNotBlank() },
                movie.channelsBadge.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            val sizePart = movie.sizeText.takeIf { it.isNotBlank() } ?: "Direct Stream"
            tvDetailsFileSpec.text = "$sizePart · ${movie.chapters.size} Ch."
            tvDetailsSynopsis.text = movie.synopsis.ifBlank { "Direct Play Blu-ray / Master Stream ready for local lossless playback." }

            val crewLines = listOfNotNull(
                movie.director.takeIf { it.isNotBlank() }?.let { "Director: $it" },
                movie.actors.takeIf { it.isNotBlank() }?.let { "Cast: $it" }
            ).joinToString("\n")
            tvDetailsCrew.text = crewLines
            tvDetailsCrew.visibility = if (crewLines.isNotBlank()) View.VISIBLE else View.GONE

            heroContentContainer.animate().cancel()
            heroContentContainer.alpha = 0f
            bottomShelfSection.visibility = View.INVISIBLE
            topBar.visibility = View.INVISIBLE
            mainContentBlurTarget.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            LiquidGlassHelper.showModalOverlay(
                overlay = detailsModalOverlay,
                sheetCard = detailsModalCard,
                anchorView = currentFocus ?: btnDetails
            ) {
                btnDetailsPlayNow.requestFocus()
            }
        }
    }

    private fun closeDetailsModal() {
        binding.mainContentBlurTarget.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.heroContentContainer.animate().cancel()
        binding.heroContentContainer.alpha = 1f
        binding.bottomShelfSection.visibility = View.VISIBLE
        binding.topBar.visibility = View.VISIBLE
        LiquidGlassHelper.hideModalOverlay(
            overlay = binding.detailsModalOverlay,
            sheetCard = binding.detailsModalCard
        ) {
            activeDetailsMovie = null
            binding.btnDetails.requestFocus()
            updateTabPillsUi()
        }
    }

    private fun showJellyfinConnectDialog() {
        with(binding) {
            etServerUrl.setText(activeSession?.serverUrl ?: serverBaseUrl)
            etUsername.setText(activeSession?.userName ?: "")
            etPassword.setText("")
            tvConnectError.visibility = View.GONE
            btnConnectSubmit.isEnabled = true
            btnConnectSubmit.text = "Connect"
            btnConnectDisconnect.visibility = if (activeSession != null) View.VISIBLE else View.GONE

            heroContentContainer.animate().cancel()
            heroContentContainer.alpha = 0f
            bottomShelfSection.visibility = View.INVISIBLE
            topBar.visibility = View.INVISIBLE
            mainContentBlurTarget.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            LiquidGlassHelper.showModalOverlay(
                overlay = connectModalOverlay,
                sheetCard = connectModalCard,
                anchorView = serverBadge
            ) {
                etServerUrl.requestFocus()
            }
        }
    }

    private fun closeConnectModal() {
        binding.mainContentBlurTarget.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.heroContentContainer.animate().cancel()
        binding.heroContentContainer.alpha = 1f
        binding.bottomShelfSection.visibility = View.VISIBLE
        binding.topBar.visibility = View.VISIBLE
        LiquidGlassHelper.hideModalOverlay(
            overlay = binding.connectModalOverlay,
            sheetCard = binding.connectModalCard
        ) {
            binding.etPassword.setText("")
            binding.tvConnectError.visibility = View.GONE
            binding.serverBadge.requestFocus()
            updateTabPillsUi()
        }
    }

    private fun submitJellyfinConnect() {
        val rawUrl = binding.etServerUrl.text.toString()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()

        val urlCheck = NetworkUrlSecurity.validateServerUrl(rawUrl)
        if (urlCheck is NetworkUrlSecurity.ServerUrlResult.Invalid) {
            binding.tvConnectError.text = urlCheck.reason
            binding.tvConnectError.visibility = View.VISIBLE
            return
        }
        if (username.isBlank()) {
            binding.tvConnectError.text = "Enter a valid Jellyfin username"
            binding.tvConnectError.visibility = View.VISIBLE
            return
        }

        val safeUrl = (urlCheck as NetworkUrlSecurity.ServerUrlResult.Valid).normalizedUrl
        binding.tvConnectError.visibility = View.GONE
        binding.btnConnectSubmit.isEnabled = false
        binding.btnConnectSubmit.text = "Connecting..."
        authenticateAndConnect(safeUrl, username, password)
    }

    private fun authenticateAndConnect(url: String, username: String, password: String) {
        updateServerBadgeUi(connected = false, label = "Connecting...")
        ioScope.launch {
            val client = getOrCreateClient(url)
            val info = client.testConnection()
            if (info == null) {
                withContext(Dispatchers.Main) {
                    updateServerBadgeUi(connected = activeSession != null, label = activeSession?.serverName?.let { "Jellyfin · $it" } ?: "Connect Jellyfin")
                    binding.btnConnectSubmit.isEnabled = true
                    binding.btnConnectSubmit.text = "Connect"
                    if (binding.connectModalOverlay.visibility == View.VISIBLE) {
                        binding.tvConnectError.text = "Could not reach Jellyfin at $url"
                        binding.tvConnectError.visibility = View.VISIBLE
                    } else {
                        Toast.makeText(this@MainActivity, "Could not reach Jellyfin server", Toast.LENGTH_LONG).show()
                    }
                }
                return@launch
            }

            val session = client.authenticateByName(username, password, info.serverName)
            withContext(Dispatchers.Main) {
                binding.btnConnectSubmit.isEnabled = true
                binding.btnConnectSubmit.text = "Connect"
                if (session == null) {
                    updateServerBadgeUi(connected = activeSession != null, label = activeSession?.serverName?.let { "Jellyfin · $it" } ?: "Connect Jellyfin")
                    binding.tvConnectError.text = "Invalid username or password"
                    binding.tvConnectError.visibility = View.VISIBLE
                } else {
                    serverBaseUrl = session.serverUrl
                    activeSession = session
                    val editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_JF_URL, session.serverUrl)
                        .putString(KEY_JF_USER_ID, session.userId)
                        .putString(KEY_JF_USER_NAME, session.userName)
                        .putString(KEY_JF_SERVER_NAME, session.serverName)
                    TokenVault.writeToken(editor, KEY_JF_TOKEN, session.accessToken)
                    editor.apply()

                    closeConnectModal()
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
        imageCache.clearAll()
        rebuildCurrentShelf()
        updateCurrentHero(animate = true)
        preloadMovieBitmapsAsync(movies)
        preloadShowBitmapsAsync(tvShows)
        updateServerBadgeUi(connected = false, label = "Connect Jellyfin")
    }

    private fun preloadMovieBitmapsAsync(catalog: List<JellyfinMovie>) {
        preloadMoviesJob?.cancel()
        preloadMoviesJob = ioScope.launch(imageFetchDispatcher) {
            catalog.mapIndexed { idx, movie ->
                async {
                    val bmp = imageCache.loadBackdrop(movie.backdropUrl, movie.backdropAsset, assets)
                    if (bmp != null && currentTab == CatalogTab.MOVIES && idx == selectedMovieIndex) {
                        withContext(Dispatchers.Main) {
                            binding.ivHeroBackdrop.setImageBitmap(bmp)
                            binding.ivHeroBackdrop.alpha = 1f
                        }
                    }
                    val cardBmp = imageCache.loadCardThumbnail(movie.cardUrl, movie.cardAsset, assets)
                    if (cardBmp != null) {
                        withContext(Dispatchers.Main) {
                            shelfCardHolders[movie.id]?.setThumbnail(cardBmp)
                        }
                    }
                    if (!movie.logoUrl.isNullOrBlank()) {
                        val logoBmp = imageCache.loadLogo(movie.logoUrl)
                        withContext(Dispatchers.Main) {
                            if (currentTab == CatalogTab.MOVIES && idx == selectedMovieIndex) {
                                if (logoBmp != null) {
                                    binding.ivHeroLogo.tag = movie.logoUrl
                                    binding.ivHeroLogo.setImageBitmap(logoBmp)
                                    binding.ivHeroLogo.alpha = 1f
                                    binding.ivHeroLogo.visibility = View.VISIBLE
                                    binding.tvHeroTitle.visibility = View.GONE
                                } else {
                                    binding.ivHeroLogo.visibility = View.GONE
                                    binding.tvHeroTitle.visibility = View.VISIBLE
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun preloadShowBitmapsAsync(shows: List<JellyfinShow>) {
        preloadShowsJob?.cancel()
        preloadShowsJob = ioScope.launch(imageFetchDispatcher) {
            shows.mapIndexed { idx, show ->
                async {
                    val bmp = imageCache.loadBackdrop(show.backdropUrl, show.backdropAsset, assets)
                    if (bmp != null && currentTab == CatalogTab.TV_SHOWS && idx == selectedShowIndex) {
                        withContext(Dispatchers.Main) {
                            binding.ivHeroBackdrop.setImageBitmap(bmp)
                            binding.ivHeroBackdrop.alpha = 1f
                        }
                    }
                    val cardBmp = imageCache.loadCardThumbnail(show.cardUrl, show.cardAsset, assets)
                    if (cardBmp != null) {
                        withContext(Dispatchers.Main) {
                            shelfCardHolders[show.id]?.setThumbnail(cardBmp)
                        }
                    }
                    if (!show.logoUrl.isNullOrBlank()) {
                        val logoBmp = imageCache.loadLogo(show.logoUrl)
                        withContext(Dispatchers.Main) {
                            if (currentTab == CatalogTab.TV_SHOWS && idx == selectedShowIndex) {
                                if (logoBmp != null) {
                                    binding.ivHeroLogo.tag = show.logoUrl
                                    binding.ivHeroLogo.setImageBitmap(logoBmp)
                                    binding.ivHeroLogo.alpha = 1f
                                    binding.ivHeroLogo.visibility = View.VISIBLE
                                    binding.tvHeroTitle.visibility = View.GONE
                                } else {
                                    binding.ivHeroLogo.visibility = View.GONE
                                    binding.tvHeroTitle.visibility = View.VISIBLE
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
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
        val isReset = prefs.getBoolean("reset_${movie.id}", false) ||
            prefs.getBoolean("reset_${movie.title.lowercase().trim()}", false)
        if (pos == 0L && !isReset && movie.playbackPositionMs > 0L) {
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
        pendingBackdropRunnable?.let { mainHandler.removeCallbacks(it) }
        preloadMoviesJob?.cancel()
        preloadShowsJob?.cancel()
        synchronized(clientLock) {
            cachedClientRef.getAndSet(null)?.second?.close()
        }
        imageCache.clearAll()
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
                val holder = shelfCardHolders[movie.id] ?: continue
                val (pos, dur) = getSavedProgress(prefs, movie)
                val isWatched = prefs.getBoolean("played_${movie.id}", movie.isPlayed)
                holder.applyPlaybackProgress(pos, dur, isWatched)
            }
        } else {
            for (show in tvShows) {
                val holder = shelfCardHolders[show.id] ?: continue
                val activeEp = findNextEpisodeForShow(show)
                if (activeEp != null) {
                    val (pos, dur) = getSavedProgress(prefs, activeEp)
                    val allWatched = show.episodes.isNotEmpty() && show.episodes.all { ep ->
                        prefs.getBoolean("played_${ep.id}", ep.isPlayed)
                    }
                    holder.applyPlaybackProgress(pos, dur, allWatched)
                } else {
                    holder.applyPlaybackProgress(0L, 0L, false)
                }
            }
        }
    }

    private fun buildMovieShelf() {
        binding.llCardShelf.removeAllViews()
        shelfCardHolders.clear()
        for ((index, movie) in movies.withIndex()) {
            val holder = ShelfCardViewHolder.inflate(binding.llCardShelf)
            val cachedCard = imageCache.getCachedCard(movie.cardUrl ?: movie.cardAsset ?: "")
                ?: if (!movie.cardAsset.isNullOrBlank()) imageCache.loadCardThumbnail(null, movie.cardAsset, assets) else null

            holder.bind(
                index = index,
                title = movie.title,
                subtitle = "${movie.year}  ·  ${movie.resolutionBadge.ifBlank { if (movie.is4k) "4K" else "HD" }}",
                cachedThumbnail = cachedCard,
                scrollView = binding.shelfScrollView,
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
            shelfCardHolders[movie.id] = holder
            binding.llCardShelf.addView(holder.root)
        }
        refreshShelfProgress()
    }

    private fun buildShowShelf() {
        binding.llCardShelf.removeAllViews()
        shelfCardHolders.clear()
        for ((index, show) in tvShows.withIndex()) {
            val seasonLabel = if (show.seasonsCount == 1) "1 Season" else "${show.seasonsCount} Seasons"
            val holder = ShelfCardViewHolder.inflate(binding.llCardShelf)
            val cachedCard = imageCache.getCachedCard(show.cardUrl ?: show.cardAsset ?: "")
                ?: if (!show.cardAsset.isNullOrBlank()) imageCache.loadCardThumbnail(null, show.cardAsset, assets) else null

            holder.bind(
                index = index,
                title = show.title,
                subtitle = "${show.year}  ·  $seasonLabel",
                cachedThumbnail = cachedCard,
                scrollView = binding.shelfScrollView,
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
            shelfCardHolders[show.id] = holder
            binding.llCardShelf.addView(holder.root)
        }
        refreshShelfProgress()
    }

    private fun updateHeroMovie(index: Int, animate: Boolean = true) {
        if (index !in movies.indices) return
        val movie = movies[index]

        val cleanGenre = movie.genreText.removePrefix("Movie  ·  ").removePrefix("TV Series  ·  ")
        val metadataParts = listOfNotNull(
            cleanGenre.takeIf { it.isNotBlank() },
            movie.year.takeIf { it.isNotBlank() },
            movie.durationText.takeIf { it.isNotBlank() }
        )

        bindHeroMetadataAndBackdrop(
            title = movie.title,
            genre = metadataParts.joinToString("  ·  "),
            synopsis = movie.synopsis,
            metadataText = "",
            is4k = movie.is4k,
            dynamicRange = movie.dynamicRange,
            audioBadge = movie.audioBadge,
            channelsBadge = movie.channelsBadge,
            resolutionBadge = movie.resolutionBadge,
            actors = movie.actors,
            director = movie.director,
            backdropUrl = movie.backdropUrl,
            backdropAsset = movie.backdropAsset,
            logoUrl = movie.logoUrl,
            animate = animate
        )

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val (savedPos, savedDur) = getSavedProgress(prefs, movie)
        val hasProgress = savedDur > 0L && savedPos > 5000L && savedPos < savedDur - 30000L
        if (hasProgress) {
            val remainingMs = (savedDur - savedPos).coerceAtLeast(60000L)
            val remainingHours = remainingMs / (1000 * 60 * 60)
            val remainingMinutes = (remainingMs / (1000 * 60)) % 60
            val remainingText = if (remainingHours > 0) "${remainingHours}h ${remainingMinutes}m" else "${remainingMinutes}m"
            binding.btnPlayDirect.text = "Resume · $remainingText"
        } else {
            binding.btnPlayDirect.text = "Play"
        }

        val isWatched = prefs.getBoolean("played_${movie.id}", movie.isPlayed)
        binding.btnCheck.isActivated = isWatched
        binding.btnCheck.text = if (isWatched) "Watched" else "Mark Watched"
        binding.btnDetails.text = "Details"
        binding.btnRestart.visibility = if (hasProgress) View.VISIBLE else View.GONE
    }

    private fun updateHeroShow(index: Int, animate: Boolean = true) {
        if (index !in tvShows.indices) return
        val show = tvShows[index]
        val nextEp = findNextEpisodeForShow(show)
        val seasonText = if (show.seasonsCount == 1) "1 Season" else "${show.seasonsCount} Seasons"
        val metadataParts = listOfNotNull(
            show.year.takeIf { it.isNotBlank() },
            seasonText
        )

        bindHeroMetadataAndBackdrop(
            title = show.title,
            genre = show.genreText.removePrefix("TV Series  ·  ").removePrefix("Movie  ·  "),
            synopsis = show.synopsis,
            metadataText = metadataParts.joinToString("  ·  "),
            is4k = nextEp?.is4k ?: show.is4k,
            dynamicRange = nextEp?.dynamicRange ?: show.dynamicRange,
            audioBadge = nextEp?.audioBadge ?: show.audioBadge,
            channelsBadge = nextEp?.channelsBadge ?: show.channelsBadge,
            resolutionBadge = nextEp?.resolutionBadge ?: show.resolutionBadge,
            actors = show.actors,
            director = show.director,
            backdropUrl = show.backdropUrl,
            backdropAsset = show.backdropAsset,
            logoUrl = show.logoUrl,
            animate = animate
        )

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (nextEp != null) {
            val (savedPos, savedDur) = getSavedProgress(prefs, nextEp)
            val hasProgress = savedDur > 0L && savedPos > 5000L && savedPos < savedDur - 30000L
            if (hasProgress) {
                binding.btnPlayDirect.text = "Resume ${nextEp.episodeCode}"
            } else {
                binding.btnPlayDirect.text = "Play ${nextEp.episodeCode}"
            }
            val isWatched = prefs.getBoolean("played_${nextEp.id}", nextEp.isPlayed)
            binding.btnCheck.isActivated = isWatched
            binding.btnCheck.text = if (isWatched) "Watched" else "Mark Watched"
            binding.btnRestart.visibility = if (hasProgress) View.VISIBLE else View.GONE
        } else {
            binding.btnPlayDirect.text = "Episodes"
            binding.btnCheck.isActivated = false
            binding.btnCheck.text = "Mark Watched"
            binding.btnRestart.visibility = View.GONE
        }
        binding.btnDetails.text = "Episodes"
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
        resolutionBadge: String,
        actors: String,
        director: String,
        backdropUrl: String?,
        backdropAsset: String?,
        logoUrl: String?,
        animate: Boolean
    ) {
        with(binding) {
            tvHeroTitle.text = title
            if (!logoUrl.isNullOrBlank()) {
                val cachedLogo = imageCache.getCachedLogo(logoUrl)
                if (cachedLogo != null) {
                    ivHeroLogo.tag = logoUrl
                    ivHeroLogo.animate().cancel()
                    ivHeroLogo.setImageBitmap(cachedLogo)
                    ivHeroLogo.alpha = 1f
                    ivHeroLogo.visibility = View.VISIBLE
                    tvHeroTitle.visibility = View.GONE
                } else {
                    // El elemento tiene logo oficial: NUNCA mostramos el texto plano de reserva
                    // para evitar el parpadeo. Ocultamos el texto y mantenemos el logo invisible
                    // hasta que se complete la carga desde disco o red.
                    tvHeroTitle.visibility = View.GONE
                    ivHeroLogo.visibility = View.INVISIBLE
                    val requestedLogoUrl = logoUrl
                    ivHeroLogo.tag = requestedLogoUrl

                    ioScope.launch(imageFetchDispatcher) {
                        val fetchedLogo = imageCache.loadLogo(requestedLogoUrl)
                        withContext(Dispatchers.Main) {
                            if (ivHeroLogo.tag == requestedLogoUrl) {
                                if (fetchedLogo != null) {
                                    ivHeroLogo.setImageBitmap(fetchedLogo)
                                    ivHeroLogo.alpha = 0f
                                    ivHeroLogo.visibility = View.VISIBLE
                                    ivHeroLogo.animate().alpha(1f).setDuration(120).start()
                                    tvHeroTitle.visibility = View.GONE
                                } else {
                                    // Solo si la descarga del logo falla por completo, mostramos el título como fallback
                                    tvHeroTitle.visibility = View.VISIBLE
                                    ivHeroLogo.visibility = View.GONE
                                }
                            }
                        }
                    }
                }
            } else {
                ivHeroLogo.tag = null
                ivHeroLogo.visibility = View.GONE
                tvHeroTitle.visibility = View.VISIBLE
            }

            tvHeroGenre.text = listOfNotNull(
                genre.takeIf { it.isNotBlank() },
                metadataText.takeIf { it.isNotBlank() }
            ).joinToString("  ·  ")

            val rawRes = resolutionBadge.ifBlank { if (is4k) "4K" else "HD" }
            val resPillText = when {
                rawRes.contains("4K", ignoreCase = true) || rawRes.contains("2160", ignoreCase = true) -> "4K"
                rawRes.contains("HD", ignoreCase = true) || rawRes.contains("1080", ignoreCase = true) || rawRes.contains("720", ignoreCase = true) -> "HD"
                rawRes.isNotBlank() -> rawRes.uppercase(Locale.ROOT)
                else -> ""
            }
            ivHeroResolution.visibility = View.GONE
            if (resPillText.isNotBlank()) {
                badgeResolution.text = resPillText
                badgeResolution.visibility = View.VISIBLE
            } else {
                badgeResolution.visibility = View.GONE
            }

            when (dynamicRange) {
                DynamicRangeType.DOLBY_VISION -> {
                    ivHeroDolbyVision.setImageResource(R.drawable.ic_logo_dolby_vision)
                    ivHeroDolbyVision.visibility = View.VISIBLE
                    badgeHdr.visibility = View.GONE
                }
                DynamicRangeType.HDR10 -> {
                    ivHeroDolbyVision.setImageResource(R.drawable.ic_logo_hdr10)
                    ivHeroDolbyVision.visibility = View.VISIBLE
                    badgeHdr.visibility = View.GONE
                }
                DynamicRangeType.HLG -> {
                    ivHeroDolbyVision.visibility = View.GONE
                    badgeHdr.text = "HLG"
                    badgeHdr.visibility = View.VISIBLE
                }
                DynamicRangeType.SDR -> {
                    ivHeroDolbyVision.visibility = View.GONE
                    badgeHdr.visibility = View.GONE
                }
            }

            val audioLogo = when {
                audioBadge.contains("ATMOS", ignoreCase = true) -> R.drawable.ic_logo_dolby_atmos
                audioBadge.contains("TRUEHD", ignoreCase = true) || audioBadge.contains("TRUE-HD", ignoreCase = true) -> R.drawable.ic_logo_dolby_truehd
                audioBadge.contains("DTS:X", ignoreCase = true) || audioBadge.contains("DTSX", ignoreCase = true) || audioBadge.contains("DTS-X", ignoreCase = true) -> R.drawable.ic_logo_dts_x
                audioBadge.contains("DTS-HD", ignoreCase = true) || audioBadge.contains("DTSHD", ignoreCase = true) || audioBadge.contains("DTS HD", ignoreCase = true) -> R.drawable.ic_logo_dts_hd_ma
                audioBadge.contains("DTS", ignoreCase = true) -> R.drawable.ic_logo_dts
                audioBadge.contains("DIGITAL PLUS", ignoreCase = true) || audioBadge.contains("EAC3", ignoreCase = true) || audioBadge.contains("DD+", ignoreCase = true) || audioBadge.contains("DOLBY", ignoreCase = true) -> R.drawable.ic_logo_dolby_digital_plus
                else -> null
            }
            if (audioLogo != null) {
                ivHeroDolbyAtmos.setImageResource(audioLogo)
                ivHeroDolbyAtmos.visibility = View.VISIBLE
                badgeAudio.visibility = View.GONE
            } else if (audioBadge.isNotBlank()) {
                ivHeroDolbyAtmos.visibility = View.GONE
                badgeAudio.text = audioBadge
                badgeAudio.visibility = View.VISIBLE
            } else {
                ivHeroDolbyAtmos.visibility = View.GONE
                badgeAudio.visibility = View.GONE
            }

            ivHeroAudioCarrier.visibility = View.GONE

            if (channelsBadge.isNotBlank()) {
                badgeChannels.text = channelsBadge
                badgeChannels.visibility = View.VISIBLE
            } else {
                badgeChannels.visibility = View.GONE
            }
        }

        pendingBackdropRunnable?.let { mainHandler.removeCallbacks(it) }

        val crossfadeTask = Runnable {
            val key = backdropUrl ?: backdropAsset ?: ""
            val cached = imageCache.getCachedBackdrop(key)
            val resolved = cached ?: if (!backdropAsset.isNullOrBlank()) {
                imageCache.loadBackdrop(null, backdropAsset, assets)
            } else null

            applyBackdropCrossfade(resolved)

            if (resolved == null && !backdropUrl.isNullOrBlank()) {
                ioScope.launch(imageFetchDispatcher) {
                    val fetched = imageCache.loadBackdrop(backdropUrl, backdropAsset, assets)
                    if (fetched != null) {
                        withContext(Dispatchers.Main) {
                            applyBackdropCrossfade(fetched)
                        }
                    }
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

    private fun applyBackdropCrossfade(resolved: Bitmap?) {
        binding.ivHeroBackdrop.animate().cancel()
        binding.ivHeroBackdropAlt.animate().cancel()
        if (isBackdropPrimary) {
            binding.ivHeroBackdropAlt.setImageBitmap(resolved)
            binding.ivHeroBackdropAlt.animate().alpha(if (resolved != null) 1f else 0f).setDuration(220).start()
            binding.ivHeroBackdrop.animate().alpha(0f).setDuration(220).start()
            isBackdropPrimary = false
        } else {
            binding.ivHeroBackdrop.setImageBitmap(resolved)
            binding.ivHeroBackdrop.animate().alpha(if (resolved != null) 1f else 0f).setDuration(220).start()
            binding.ivHeroBackdropAlt.animate().alpha(0f).setDuration(220).start()
            isBackdropPrimary = true
        }
    }

    private fun findNextEpisodeForShow(show: JellyfinShow): JellyfinMovie? {
        if (show.episodes.isEmpty()) return null
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val sorted = show.episodes.sortedWith(compareBy({ it.seasonNumber ?: 1 }, { it.episodeNumber ?: 1 }))
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
        refreshShelfProgress()
        if (currentTab == CatalogTab.TV_SHOWS) {
            updateHeroShow(selectedShowIndex, animate = false)
        }
        activeSession?.let { session ->
            ioScope.launch {
                val client = getOrCreateClient(session.serverUrl)
                client.setPlayedStatus(session, episode.id, nextPlayed)
            }
        }
    }

    private fun openEpisodesModal(show: JellyfinShow) {
        activeEpisodesShow = show
        with(binding) {
            bindLogoOrTitle(ivEpisodesModalLogo, tvEpisodesSeriesTitle, show.title, show.logoUrl)
            val seasonLabel = if (show.seasonsCount == 1) "1 Season" else "${show.seasonsCount} Seasons"
            tvEpisodesSeriesMeta.text = "${show.year}  ·  $seasonLabel  ·  Direct Play"
            heroContentContainer.animate().cancel()
            heroContentContainer.alpha = 0f
            bottomShelfSection.visibility = View.INVISIBLE
            topBar.visibility = View.INVISIBLE
            mainContentBlurTarget.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            LiquidGlassHelper.showModalOverlay(
                overlay = episodesModalOverlay,
                sheetCard = episodesModalCard,
                anchorView = currentFocus ?: btnDetails
            )

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
            val loadingText = TextView(this@MainActivity).apply {
                text = "Loading episodes from Jellyfin..."
                setTextColor(Color.parseColor("#A1A1A6"))
                textSize = 13f
                setPadding(8, 24, 8, 24)
            }
            llEpisodesContainer.addView(loadingText)

            ioScope.launch {
                val client = getOrCreateClient(session.serverUrl)
                val fetched = client.fetchEpisodes(session, show.id)

                withContext(Dispatchers.Main) {
                    val updatedShow = show.withEpisodes(fetched)
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
    }

    private fun closeEpisodesModal() {
        binding.mainContentBlurTarget.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.heroContentContainer.animate().cancel()
        binding.heroContentContainer.alpha = 1f
        binding.bottomShelfSection.visibility = View.VISIBLE
        binding.topBar.visibility = View.VISIBLE
        LiquidGlassHelper.hideModalOverlay(
            overlay = binding.episodesModalOverlay,
            sheetCard = binding.episodesModalCard
        ) {
            activeEpisodesShow = null
            binding.btnPlayDirect.requestFocus()
            updateTabPillsUi()
        }
    }

    private fun renderEpisodesSheetContent(show: JellyfinShow) {
        with(binding) {
            llSeasonsContainer.removeAllViews()
            llEpisodesContainer.removeAllViews()

            val episodes = show.episodes
            if (episodes.isEmpty()) {
                val emptyView = TextView(this@MainActivity).apply {
                    text = "No playable episodes found for this series."
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 13f
                    setPadding(8, 24, 8, 24)
                }
                llEpisodesContainer.addView(emptyView)
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
                val pill = TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (34 * density).toInt()
                    ).apply {
                        marginStart = (8 * density).toInt()
                    }
                    text = "Season $seasonNum"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                    isFocusable = true
                    isClickable = true
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)

                    fun refreshPillStyle(focused: Boolean) {
                        LiquidGlassHelper.styleGlassTabPill(
                            view = this,
                            focused = focused,
                            selected = isActive,
                            cornerRadiusDp = 17f
                        )
                    }

                    refreshPillStyle(false)
                    setOnFocusChangeListener { v, hasFocus ->
                        refreshPillStyle(hasFocus)
                        if (hasFocus) {
                            episodesModalCard.energize(v.x + v.width * 0.5f, v.y + v.height * 0.5f)
                        }
                    }
                    setOnClickListener {
                        if (selectedSeason != seasonNum) {
                            selectedSeason = seasonNum
                            renderEpisodesSheetContent(show)
                        }
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
                val (savedPos, savedDur) = getSavedProgress(prefs, episode)
                val isWatched = prefs.getBoolean("played_${episode.id}", episode.isPlayed)
                val thumbUrl = episode.cardUrl ?: show.cardUrl
                val thumbAsset = episode.cardAsset ?: show.cardAsset
                val cachedThumb = imageCache.getCachedCard(thumbUrl ?: thumbAsset ?: "")

                val holder = EpisodeRowViewHolder.inflate(llEpisodesContainer)
                holder.bind(
                    episode = episode,
                    displayTitle = episodeRowDisplayTitle(episode),
                    savedPos = savedPos,
                    savedDur = savedDur,
                    isWatched = isWatched,
                    cachedThumbnail = cachedThumb,
                    onActivated = {
                        closeEpisodesModal()
                        launchPlayback(episode)
                    }
                )
                if (cachedThumb == null && (!thumbUrl.isNullOrBlank() || !thumbAsset.isNullOrBlank())) {
                    ioScope.launch(imageFetchDispatcher) {
                        val loaded = imageCache.loadCardThumbnail(thumbUrl, thumbAsset, assets)
                        if (loaded != null) {
                            withContext(Dispatchers.Main) {
                                holder.setThumbnail(loaded)
                            }
                        }
                    }
                }
                if (firstEpisodeRow == null) firstEpisodeRow = holder.root
                llEpisodesContainer.addView(holder.root)
            }

            (firstEpisodeRow ?: firstSeasonPill)?.requestFocus()
        }
    }

    private fun launchPlayback(movie: JellyfinMovie) {
        if (!NetworkUrlSecurity.validateMediaPlaybackUri(movie.streamUrl)) {
            Toast.makeText(this, "Blocked invalid or unsafe media stream URL", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val (pos, dur) = getSavedProgress(prefs, movie)
        val seekPos = if (dur > 0L && pos > 5000L && pos < dur - 30000L) pos else androidx.media3.common.C.TIME_UNSET
        val displayTitle = playbackDisplayTitle(movie)
        val isEpisode = !movie.seriesName.isNullOrBlank() || movie.episodeCode.isNotBlank()
        val resolvedLogoUrl = movie.logoUrl?.takeIf { it.isNotBlank() }
            ?: activeEpisodesShow?.logoUrl?.takeIf { it.isNotBlank() }
            ?: tvShows.firstOrNull {
                (!movie.seriesId.isNullOrBlank() && it.id == movie.seriesId) ||
                    (!movie.seriesName.isNullOrBlank() && it.title.equals(movie.seriesName, ignoreCase = true))
            }?.logoUrl

        val intent = Intent(this, PlaybackActivity::class.java).apply {
            data = Uri.parse(movie.streamUrl)
            if (seekPos != androidx.media3.common.C.TIME_UNSET) {
                putExtra("seek", seekPos)
            }
            putExtra("title", displayTitle)
            putExtra("MOVIE_TITLE", displayTitle)
            if (isEpisode) {
                putExtra("EPISODE_SUBTITLE", episodeRowDisplayTitle(movie).replace("  ·  ", " · "))
            }
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
            putExtra("MOVIE_BACKDROP_ASSET", movie.backdropAsset)
            putExtra("MOVIE_BACKDROP_URL", movie.backdropUrl)
            putExtra("MOVIE_LOGO_URL", resolvedLogoUrl)
            putExtra("MOVIE_TMDB_ID", movie.tmdbId)
            putExtra("MOVIE_CHAPTERS_JSON", JellyfinMetadataMapper.serializeChapters(movie.chapters))
            val serverUrlToPass = activeSession?.serverUrl ?: "http://192.168.100.108:8096"
            putExtra("JELLYFIN_SERVER_URL", serverUrlToPass)
            activeSession?.accessToken?.let { putExtra("JELLYFIN_TOKEN", it) }
            putExtra("JELLYFIN_ITEM_ID", movie.id)
            putExtra("JELLYFIN_MEDIA_SOURCE_ID", movie.mediaSourceId)
            movie.seriesId?.let { putExtra("JELLYFIN_SERIES_ID", it) }
            movie.seriesName?.let { putExtra("JELLYFIN_SERIES_NAME", it) }
        }
        val options = android.app.ActivityOptions.makeCustomAnimation(
            this,
            R.anim.cinematic_fade_in,
            R.anim.cinematic_zoom_out
        )
        startActivity(intent, options.toBundle())
    }
}
