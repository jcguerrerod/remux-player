package com.player.androidtv.ui.settings

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.player.androidtv.R
import com.player.androidtv.settings.AppSettings
import com.player.coremedia.jellyfin.JellyfinSession
import com.player.coremedia.player.DolbyVisionProcessingMode
import java.io.File
import java.util.Locale

/**
 * tvOS 18 Split View Settings Controller (Diseño S1).
 * Left column: 5 categories + live contextual description card.
 * Right column: grouped 10dp pearl-focus rows with instant persistence.
 */
class SettingsOverlayController(
    private val context: Context,
    private val overlay: View,
    private val categoriesContainer: LinearLayout,
    private val detailContainer: LinearLayout,
    private val detailScrollView: ScrollView,
    private val tvInfoTitle: TextView,
    private val tvInfoBody: TextView,
    private val getSession: () -> JellyfinSession?,
    private val getLibraryCountLabel: () -> String,
    private val onOpenServerConnect: () -> Unit,
    private val onSyncLibraryNow: () -> Unit,
    private val onDisconnectServer: () -> Unit,
    private val onClearImageCache: () -> Unit,
    private val onClosed: () -> Unit
) {
    enum class Category(val title: String, val defaultInfoTitle: String, val defaultInfoBody: String) {
        VIDEO(
            "Video & Display",
            "Video Pipeline",
            "Configure default Dolby Vision processing, automatic TV refresh rate matching and scene previews."
        ),
        AUDIO(
            "Audio & Subtitles",
            "Audio & Subtitles",
            "Control default audio language selection, per-title track memory and subtitle defaults."
        ),
        PLAYBACK(
            "Playback & Controls",
            "Playback & Controls",
            "Tune scrubbing speed, HUD auto-hide delay and automatic episode playback."
        ),
        SERVER(
            "Server & Library",
            "Jellyfin Server",
            "Manage your Jellyfin Direct Play connection, trigger manual library syncs or clear local caches."
        ),
        ABOUT(
            "About & Diagnostics",
            "Device Diagnostics",
            "Inspect detected Android TV display capabilities and active lossless audio decoders."
        )
    }

    private val density = context.resources.displayMetrics.density
    private var activeCategory: Category = Category.VIDEO
    private val categoryViews = mutableMapOf<Category, TextView>()
    private var firstDetailRow: View? = null
    private var isRenderingDetail = false

    val isOpen: Boolean
        get() = overlay.visibility == View.VISIBLE

    fun open(initialCategory: Category = Category.VIDEO) {
        activeCategory = initialCategory
        buildCategorySidebar()
        renderCategory(activeCategory)

        overlay.animate().cancel()
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(180L).start()

        overlay.post {
            firstDetailRow?.requestFocus() ?: categoryViews[activeCategory]?.requestFocus()
        }
    }

    fun close() {
        if (!isOpen) return
        overlay.animate().cancel()
        overlay.animate()
            .alpha(0f)
            .setDuration(150L)
            .withEndAction {
                overlay.visibility = View.GONE
                onClosed()
            }
            .start()
    }

    fun refreshIfOpen() {
        if (isOpen) {
            renderCategory(activeCategory)
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun buildCategorySidebar() {
        categoriesContainer.removeAllViews()
        categoryViews.clear()

        Category.values().forEachIndexed { index, cat ->
            val tv = TextView(context).apply {
                text = cat.title
                textSize = 13.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                background = ContextCompat.getDrawable(context, R.drawable.bg_settings_category)
                isFocusable = true
                isClickable = true
                isSelected = (cat == activeCategory)
                setTextColor(colorForCategory(focused = false, selected = (cat == activeCategory)))

                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44)
                ).apply {
                    if (index > 0) topMargin = dp(6)
                }

                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && !isRenderingDetail) {
                        if (activeCategory != cat) {
                            activeCategory = cat
                            updateCategorySelectionStyles()
                            renderCategory(cat)
                        }
                        updateInfoCard(cat.defaultInfoTitle, cat.defaultInfoBody)
                    }
                    setTextColor(colorForCategory(focused = hasFocus, selected = (cat == activeCategory)))
                }

                setOnClickListener {
                    activeCategory = cat
                    updateCategorySelectionStyles()
                    renderCategory(cat)
                    firstDetailRow?.requestFocus()
                }

                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        firstDetailRow?.requestFocus()
                        true
                    } else {
                        false
                    }
                }
            }
            categoryViews[cat] = tv
            categoriesContainer.addView(tv)
        }
    }

    private fun updateCategorySelectionStyles() {
        categoryViews.forEach { (cat, view) ->
            val selected = (cat == activeCategory)
            view.isSelected = selected
            view.setTextColor(colorForCategory(focused = view.hasFocus(), selected = selected))
        }
    }

    private fun colorForCategory(focused: Boolean, selected: Boolean): Int = when {
        focused -> Color.parseColor("#0E1016")
        selected -> Color.WHITE
        else -> Color.parseColor("#AAB2C2")
    }

    private fun updateInfoCard(title: String, body: String) {
        tvInfoTitle.text = title
        tvInfoBody.text = body
    }

    private fun renderCategory(category: Category, restoreFocusedTag: Any? = null) {
        if (isRenderingDetail) return
        isRenderingDetail = true
        try {
            val focusedTag = restoreFocusedTag ?: detailContainer.findFocus()?.tag
            val focusWasInDetail = detailContainer.hasFocus()
            // Temporarily block focus jumps onto the left sidebar while tearing down & rebuilding right-column rows
            categoriesContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            detailContainer.removeAllViews()
            firstDetailRow = null
            if (!focusWasInDetail) {
                detailScrollView.scrollTo(0, 0)
            }
            updateInfoCard(category.defaultInfoTitle, category.defaultInfoBody)

            when (category) {
                Category.VIDEO -> renderVideoSettings()
                Category.AUDIO -> renderAudioSettings()
                Category.PLAYBACK -> renderPlaybackSettings()
                Category.SERVER -> renderServerSettings()
                Category.ABOUT -> renderAboutDiagnostics()
            }

            if (focusWasInDetail && focusedTag != null) {
                for (i in 0 until detailContainer.childCount) {
                    val child = detailContainer.getChildAt(i)
                    if (child.tag == focusedTag && child.isFocusable) {
                        child.requestFocus()
                        break
                    }
                }
            }
            categoriesContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            updateCategorySelectionStyles()
        } finally {
            isRenderingDetail = false
        }
    }

    private fun renderVideoSettings() {
        addSectionHeader("VIDEO PIPELINE", isFirst = true)

        val dvMode = AppSettings.getDvMode(context)
        val dvDesc = when (dvMode) {
            DolbyVisionProcessingMode.P8_TRANSMUTE -> "Converts Profile 7 MEL/FEL streams to Profile 8.1 on-the-fly for native Android TV hardware playback."
            DolbyVisionProcessingMode.P7_MEL_DIRECT -> "Passes Profile 7 MEL directly to the hardware decoder without transmutation."
            DolbyVisionProcessingMode.FORCE_HDR10 -> "Strips Dolby Vision RPU metadata and plays the base HDR10 layer."
        }
        addSettingRow(
            tag = "dv_mode",
            label = "Default Dolby Vision Mode",
            value = "${AppSettings.getDvModeLabel(dvMode)}  ›",
            infoTitle = AppSettings.getDvModeLabel(dvMode),
            infoBody = dvDesc
        ) {
            AppSettings.setDvMode(context, AppSettings.nextDvMode(dvMode))
            renderCategory(Category.VIDEO)
        }

        val trickplay = AppSettings.isTrickplayEnabled(context)
        addSettingRow(
            tag = "trickplay",
            label = "Trickplay Scene Previews",
            value = if (trickplay) "On" else "Off",
            infoTitle = "Trickplay Scene Previews",
            infoBody = "Loads Jellyfin Trickplay sprite sheets for instant scene preview thumbnails while scrubbing."
        ) {
            AppSettings.setTrickplayEnabled(context, !trickplay)
            renderCategory(Category.VIDEO)
        }

        addSectionHeader("TV DISPLAY MATCHING", isFirst = false)

        val afr = AppSettings.isAfrEnabled(context)
        addSettingRow(
            tag = "afr",
            label = "Match Frame Rate (AFR)",
            value = if (afr) "On" else "Off",
            infoTitle = "Auto Frame Rate Matching",
            infoBody = "Switches your TV display refresh rate (23.976 / 24 / 25 / 50 / 60 Hz) to eliminate cinema judder."
        ) {
            AppSettings.setAfrEnabled(context, !afr)
            renderCategory(Category.VIDEO)
        }

        val showAfr = AppSettings.isShowAfrIndicator(context)
        addSettingRow(
            tag = "show_afr",
            label = "Show Refresh Rate Indicator",
            value = if (showAfr) "On" else "Off",
            infoTitle = "Refresh Rate Indicator",
            infoBody = "Displays the active refresh rate (e.g. 23.976 Hz) in the top-right corner of the player HUD."
        ) {
            AppSettings.setShowAfrIndicator(context, !showAfr)
            renderCategory(Category.VIDEO)
        }
    }

    private fun renderAudioSettings() {
        addSectionHeader("AUDIO PREFERENCES", isFirst = true)

        val audioMode = AppSettings.getDefaultAudioMode(context)
        addSettingRow(
            tag = "default_audio",
            label = "Default Audio Track",
            value = "${audioMode.label}  ›",
            infoTitle = "Default Audio: ${audioMode.label}",
            infoBody = audioMode.description
        ) {
            AppSettings.setDefaultAudioMode(context, audioMode.next())
            renderCategory(Category.AUDIO)
        }

        val rememberTracks = AppSettings.isRememberTracksPerTitle(context)
        addSettingRow(
            tag = "remember_tracks",
            label = "Remember Tracks Per Title",
            value = if (rememberTracks) "On" else "Off",
            infoTitle = "Per-Title Track Memory",
            infoBody = "Remembers the last audio and subtitle track you chose for each movie or TV series."
        ) {
            AppSettings.setRememberTracksPerTitle(context, !rememberTracks)
            renderCategory(Category.AUDIO)
        }

        addSectionHeader("SUBTITLES", isFirst = false)

        val subMode = AppSettings.getDefaultSubtitleMode(context)
        addSettingRow(
            tag = "default_sub",
            label = "Default Subtitles",
            value = "${subMode.label}  ›",
            infoTitle = "Default Subtitles: ${subMode.label}",
            infoBody = subMode.description
        ) {
            AppSettings.setDefaultSubtitleMode(context, subMode.next())
            renderCategory(Category.AUDIO)
        }

        val subSize = AppSettings.getSubtitleSize(context)
        addSettingRow(
            tag = "sub_size",
            label = "Subtitle Text Size",
            value = "${subSize.label}  ›",
            infoTitle = "Subtitle Text Size",
            infoBody = "Adjusts font size for SRT / ASS / WebVTT text subtitles. PGS bitmap tracks keep native Blu-ray styling."
        ) {
            AppSettings.setSubtitleSize(context, subSize.next())
            renderCategory(Category.AUDIO)
        }
    }

    private fun renderPlaybackSettings() {
        addSectionHeader("CONTROLS & SCRUBBING", isFirst = true)

        val scrubPreset = AppSettings.getScrubSpeedPreset(context)
        addSettingRow(
            tag = "scrub_preset",
            label = "Scrubbing Speed Preset",
            value = "${scrubPreset.label}  ›",
            infoTitle = "Scrubbing Speed",
            infoBody = scrubPreset.description
        ) {
            AppSettings.setScrubSpeedPreset(context, scrubPreset.next())
            renderCategory(Category.PLAYBACK)
        }

        val hudMs = AppSettings.getHudAutoHideMs(context)
        addSettingRow(
            tag = "hud_hide",
            label = "HUD Auto-Hide Delay",
            value = "${hudMs / 1000}s  ›",
            infoTitle = "HUD Auto-Hide Delay",
            infoBody = "How long the playback overlay stays visible after remote activity before fading out."
        ) {
            AppSettings.setHudAutoHideMs(context, AppSettings.nextHudAutoHideMs(hudMs))
            renderCategory(Category.PLAYBACK)
        }

        addSectionHeader("TV SHOWS", isFirst = false)

        val autoNext = AppSettings.isAutoPlayNextEpisode(context)
        addSettingRow(
            tag = "auto_next",
            label = "Auto-Play Next Episode",
            value = if (autoNext) "On" else "Off",
            infoTitle = "Auto-Play Next Episode",
            infoBody = "Automatically advances to the next episode in a series when playback reaches the end."
        ) {
            AppSettings.setAutoPlayNextEpisode(context, !autoNext)
            renderCategory(Category.PLAYBACK)
        }
    }

    private fun renderServerSettings() {
        val session = getSession()
        addSectionHeader("JELLYFIN SERVER", isFirst = true)

        addSettingRow(
            tag = "server_connect",
            label = if (session != null) "Connected Server" else "Connect Jellyfin Server",
            value = if (session != null) "${session.serverName.ifBlank { "Jellyfin" }}  ›" else "Not Connected  ›",
            infoTitle = if (session != null) session.serverName.ifBlank { "Jellyfin" } else "Direct Play Server",
            infoBody = if (session != null) {
                "URL: ${session.serverUrl}\nUser: ${session.userName}\nLibrary: ${getLibraryCountLabel()}"
            } else {
                "Connect to your local Jellyfin server for full 4K Remux Direct Play streaming."
            }
        ) {
            close()
            onOpenServerConnect()
        }

        if (session != null) {
            addSettingRow(
                tag = "sync_now",
                label = "Sync Library Now",
                value = "${getLibraryCountLabel()}  ›",
                infoTitle = "Manual Library Sync",
                infoBody = "Refreshes movies, TV shows, watch progress and TMDb ClearLogos from Jellyfin immediately."
            ) {
                onSyncLibraryNow()
                close()
            }

            addSettingRow(
                tag = "disconnect_server",
                label = "Disconnect Server",
                value = "Sign Out  ›",
                infoTitle = "Disconnect Jellyfin",
                infoBody = "Signs out of ${session.serverName.ifBlank { "Jellyfin" }} and switches back to offline demo mode."
            ) {
                onDisconnectServer()
                renderCategory(Category.SERVER)
            }
        }

        addSectionHeader("STORAGE & CACHE", isFirst = false)

        val cacheLabel = formatCacheSizeMb(context.cacheDir)
        addSettingRow(
            tag = "clear_cache",
            label = "Clear Image & Logo Cache",
            value = "$cacheLabel  ›",
            infoTitle = "Local Artwork Cache",
            infoBody = "Removes cached backdrops, posters, ClearLogos and Trickplay tiles. They will re-download automatically."
        ) {
            clearDirectoryContents(context.cacheDir)
            onClearImageCache()
            renderCategory(Category.SERVER)
        }
    }

    private fun renderAboutDiagnostics() {
        addSectionHeader("APPLICATION", isFirst = true)

        val versionName = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
        addSettingRow(
            tag = "app_version",
            label = "Version",
            value = versionName,
            infoTitle = "4K Remux Player for Android TV",
            infoBody = "Direct Play MKV / Dolby Vision / Lossless Audio client inspired by Apple TV+ (tvOS 18)."
        ) {}

        addSettingRow(
            tag = "audio_engine",
            label = "Lossless Audio Engine",
            value = "FFmpeg TrueHD / DTS-HD Active",
            infoTitle = "FFmpeg Audio Decoder",
            infoBody = "Decodes 7.1 Dolby TrueHD and DTS-HD MA natively in software to uncompressed multi-channel PCM when HDMI passthrough is unavailable."
        ) {}

        addSectionHeader("DEVICE CAPABILITIES", isFirst = false)

        val deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase(Locale.ROOT) }} ${Build.MODEL}"
        addSettingRow(
            tag = "device_model",
            label = "Device",
            value = deviceModel,
            infoTitle = deviceModel,
            infoBody = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        ) {}

        val hdrSummary = detectDisplayHdrSummary()
        addSettingRow(
            tag = "display_hdr",
            label = "Display HDR Support",
            value = hdrSummary,
            infoTitle = "Hardware HDR Capabilities",
            infoBody = "Reported directly by your TV's Android Display HAL: $hdrSummary."
        ) {}
    }

    private fun addSectionHeader(title: String, isFirst: Boolean) {
        val tv = TextView(context).apply {
            text = title
            textSize = 10.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#8CA0B4CC"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (isFirst) dp(4) else dp(22)
                bottomMargin = dp(8)
                marginStart = dp(4)
            }
        }
        detailContainer.addView(tv)
    }

    private fun addSettingRow(
        tag: String,
        label: String,
        value: String,
        infoTitle: String,
        infoBody: String,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(context).apply {
            this.tag = tag
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            background = ContextCompat.getDrawable(context, R.drawable.bg_settings_row)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val tvLabel = TextView(context).apply {
            text = label
            textSize = 13.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#F0FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvValue = TextView(context).apply {
            text = value
            textSize = 12.5f
            setTextColor(Color.parseColor("#A8DCE2F0"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
            }
        }

        row.addView(tvLabel)
        row.addView(tvValue)

        row.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                tvLabel.setTextColor(Color.parseColor("#0E1016"))
                tvValue.setTextColor(Color.parseColor("#C81E222E"))
                updateInfoCard(infoTitle, infoBody)
            } else {
                tvLabel.setTextColor(Color.parseColor("#F0FFFFFF"))
                tvValue.setTextColor(Color.parseColor("#A8DCE2F0"))
            }
        }

        row.setOnClickListener { onClick() }

        row.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                categoryViews[activeCategory]?.requestFocus()
                true
            } else {
                false
            }
        }

        if (firstDetailRow == null) {
            firstDetailRow = row
        }
        detailContainer.addView(row)
    }

    private fun detectDisplayHdrSummary(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return "SDR"
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val caps = wm.defaultDisplay?.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            val labels = mutableListOf<String>()
            if (caps.contains(android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)) labels.add("Dolby Vision")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps.contains(android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)) labels.add("HDR10+")
            if (caps.contains(android.view.Display.HdrCapabilities.HDR_TYPE_HDR10)) labels.add("HDR10")
            if (caps.contains(android.view.Display.HdrCapabilities.HDR_TYPE_HLG)) labels.add("HLG")
            if (labels.isEmpty()) "SDR" else labels.joinToString(" · ")
        } catch (_: Exception) {
            "SDR"
        }
    }

    private fun formatCacheSizeMb(dir: File?): String {
        val bytes = computeDirSize(dir)
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1.0) "< 1 MB" else String.format(Locale.US, "%.1f MB", mb)
    }

    private fun computeDirSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { computeDirSize(it) } ?: 0L
    }

    private fun clearDirectoryContents(dir: File?) {
        dir?.listFiles()?.forEach { child ->
            try {
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            } catch (_: Exception) {}
        }
    }
}
