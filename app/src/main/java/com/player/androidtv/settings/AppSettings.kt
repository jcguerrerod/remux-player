package com.player.androidtv.settings

import android.content.Context
import android.content.SharedPreferences
import com.player.coremedia.player.DolbyVisionProcessingMode

/**
 * Centralized settings store for the Android TV player.
 * Persists all user preferences in SharedPreferences ("remux_player_prefs")
 * and is shared directly between SettingsOverlayController and PlaybackActivity.
 */
object AppSettings {
    const val PREFS_NAME = "remux_player_prefs"

    private const val KEY_DV_MODE = "dv_processing_mode"
    private const val KEY_AFR_ENABLED = "settings_afr_enabled"
    private const val KEY_SHOW_AFR_INDICATOR = "settings_show_afr_indicator"
    private const val KEY_TRICKPLAY_ENABLED = "settings_trickplay_enabled"

    private const val KEY_DEFAULT_AUDIO_MODE = "settings_default_audio_mode"
    private const val KEY_REMEMBER_TRACKS = "settings_remember_tracks_per_title"
    private const val KEY_DEFAULT_SUBTITLE_MODE = "settings_default_subtitle_mode"
    private const val KEY_SUBTITLE_SIZE = "settings_subtitle_size"

    private const val KEY_SCRUB_PRESET = "settings_scrub_speed_preset"
    private const val KEY_HUD_HIDE_MS = "settings_hud_auto_hide_ms"
    private const val KEY_AUTOPLAY_NEXT = "settings_autoplay_next_episode"

    enum class DefaultAudioMode(val label: String, val description: String) {
        ORIGINAL_MASTER(
            "Original Master",
            "Automatically selects the theatrical lossless master audio track (TrueHD / DTS-HD MA / FLAC / PCM) when no saved preference exists."
        ),
        ENGLISH(
            "English",
            "Prefers English audio tracks by default, choosing the highest available bitrate and channel layout."
        ),
        SPANISH(
            "Spanish",
            "Prefers Spanish audio tracks by default, choosing the highest available bitrate and channel layout."
        );

        fun next(): DefaultAudioMode = values()[(ordinal + 1) % values().size]
    }

    enum class DefaultSubtitleMode(val label: String, val description: String) {
        OFF(
            "Off",
            "Keeps subtitles disabled by default unless you previously enabled them for a specific movie or series."
        ),
        FORCED(
            "Forced Only",
            "Automatically enables forced subtitles for foreign dialogue while keeping full subtitles off."
        ),
        ENGLISH(
            "English",
            "Automatically enables English subtitles (preferring native PGS/SRT) on new titles."
        ),
        SPANISH(
            "Spanish",
            "Automatically enables Spanish subtitles (preferring native PGS/SRT) on new titles."
        );

        fun next(): DefaultSubtitleMode = values()[(ordinal + 1) % values().size]
    }

    enum class SubtitleSize(val label: String, val textSizeSp: Float) {
        SMALL("Small", 18f),
        MEDIUM("Medium", 22f),
        LARGE("Large", 27f);

        fun next(): SubtitleSize = values()[(ordinal + 1) % values().size]
    }

    enum class ScrubSpeedPreset(val label: String, val multiplier: Float, val description: String) {
        STANDARD(
            "Standard (1×–5×)",
            1.0f,
            "Balanced Apple TV scrubbing speed across 1× to 5× for movies and episodes."
        ),
        FINE(
            "Fine",
            0.6f,
            "Slower, more precise scrubbing steps for locating exact scenes frame by frame."
        ),
        FAST(
            "Fast",
            1.5f,
            "Faster timeline scrubbing for quickly jumping across long 4K remux movies."
        );

        fun next(): ScrubSpeedPreset = values()[(ordinal + 1) % values().size]
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Video & Display ---
    fun getDvMode(context: Context): DolbyVisionProcessingMode =
        DolbyVisionProcessingMode.fromString(
            prefs(context).getString(KEY_DV_MODE, DolbyVisionProcessingMode.P8_TRANSMUTE.name)
        )

    fun setDvMode(context: Context, mode: DolbyVisionProcessingMode) {
        prefs(context).edit().putString(KEY_DV_MODE, mode.name).apply()
    }

    fun getDvModeLabel(mode: DolbyVisionProcessingMode): String = when (mode) {
        DolbyVisionProcessingMode.P8_TRANSMUTE -> "Profile 8.1 Transmute"
        DolbyVisionProcessingMode.P7_MEL_DIRECT -> "Profile 7 Direct"
        DolbyVisionProcessingMode.FORCE_HDR10 -> "HDR10"
    }

    fun nextDvMode(current: DolbyVisionProcessingMode): DolbyVisionProcessingMode = when (current) {
        DolbyVisionProcessingMode.P8_TRANSMUTE -> DolbyVisionProcessingMode.P7_MEL_DIRECT
        DolbyVisionProcessingMode.P7_MEL_DIRECT -> DolbyVisionProcessingMode.FORCE_HDR10
        DolbyVisionProcessingMode.FORCE_HDR10 -> DolbyVisionProcessingMode.P8_TRANSMUTE
    }

    fun isAfrEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AFR_ENABLED, true)

    fun setAfrEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AFR_ENABLED, enabled).apply()
    }

    fun isShowAfrIndicator(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_AFR_INDICATOR, true)

    fun setShowAfrIndicator(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_AFR_INDICATOR, show).apply()
    }

    fun isTrickplayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRICKPLAY_ENABLED, true)

    fun setTrickplayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRICKPLAY_ENABLED, enabled).apply()
    }

    // --- Audio & Subtitles ---
    fun getDefaultAudioMode(context: Context): DefaultAudioMode {
        val raw = prefs(context).getString(KEY_DEFAULT_AUDIO_MODE, DefaultAudioMode.ORIGINAL_MASTER.name)
        return DefaultAudioMode.values().firstOrNull { it.name == raw } ?: DefaultAudioMode.ORIGINAL_MASTER
    }

    fun setDefaultAudioMode(context: Context, mode: DefaultAudioMode) {
        prefs(context).edit().putString(KEY_DEFAULT_AUDIO_MODE, mode.name).apply()
    }

    fun isRememberTracksPerTitle(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMEMBER_TRACKS, true)

    fun setRememberTracksPerTitle(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMEMBER_TRACKS, enabled).apply()
    }

    fun getDefaultSubtitleMode(context: Context): DefaultSubtitleMode {
        val raw = prefs(context).getString(KEY_DEFAULT_SUBTITLE_MODE, DefaultSubtitleMode.OFF.name)
        return DefaultSubtitleMode.values().firstOrNull { it.name == raw } ?: DefaultSubtitleMode.OFF
    }

    fun setDefaultSubtitleMode(context: Context, mode: DefaultSubtitleMode) {
        prefs(context).edit().putString(KEY_DEFAULT_SUBTITLE_MODE, mode.name).apply()
    }

    fun getSubtitleSize(context: Context): SubtitleSize {
        val raw = prefs(context).getString(KEY_SUBTITLE_SIZE, SubtitleSize.MEDIUM.name)
        return SubtitleSize.values().firstOrNull { it.name == raw } ?: SubtitleSize.MEDIUM
    }

    fun setSubtitleSize(context: Context, size: SubtitleSize) {
        prefs(context).edit().putString(KEY_SUBTITLE_SIZE, size.name).apply()
    }

    // --- Playback & Controls ---
    fun getScrubSpeedPreset(context: Context): ScrubSpeedPreset {
        val raw = prefs(context).getString(KEY_SCRUB_PRESET, ScrubSpeedPreset.STANDARD.name)
        return ScrubSpeedPreset.values().firstOrNull { it.name == raw } ?: ScrubSpeedPreset.STANDARD
    }

    fun setScrubSpeedPreset(context: Context, preset: ScrubSpeedPreset) {
        prefs(context).edit().putString(KEY_SCRUB_PRESET, preset.name).apply()
    }

    fun getHudAutoHideMs(context: Context): Long =
        prefs(context).getLong(KEY_HUD_HIDE_MS, 5000L)

    fun setHudAutoHideMs(context: Context, delayMs: Long) {
        prefs(context).edit().putLong(KEY_HUD_HIDE_MS, delayMs).apply()
    }

    fun nextHudAutoHideMs(current: Long): Long = when (current) {
        3000L -> 5000L
        5000L -> 8000L
        else -> 3000L
    }

    fun isAutoPlayNextEpisode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOPLAY_NEXT, true)

    fun setAutoPlayNextEpisode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOPLAY_NEXT, enabled).apply()
    }
}
