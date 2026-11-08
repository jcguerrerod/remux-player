package com.player.coremedia.player

/**
 * Processing and transmute modes for Dolby Vision / HDR10 profiles on 4K Remux playback.
 */
enum class DolbyVisionProcessingMode(
    val displayName: String,
    val badgeText: String,
    val description: String
) {
    /**
     * Discards NAL 63 (Full Enhancement Layer - FEL) via Zero-Copy C++20 SIMD and
     * transmutes dynamic RPU metadata (NAL 62) to Profile 8.1 (dvhe.08.06).
     * Maximum compatibility across all Dolby Vision Smart TVs (Sony, TCL, Philips, LG, Shield TV).
     */
    P8_TRANSMUTE(
        "Dolby Vision P8.1 (Recommended)",
        "DOLBY VISION P8.1",
        "Filters FEL (NAL 63) and preserves 100% dynamic RPU metadata transmuted to Profile 8.1."
    ),

    /**
     * Retains native Profile 7 identifier (dvhe.07.06).
     * Recommended exclusively for titles with MEL (Minimal Enhancement Layer) without FEL sub-layers
     * on TVs or hardware players supporting dvhe.07 directly.
     */
    P7_MEL_DIRECT(
        "Dolby Vision P7 Native (MEL Only)",
        "DOLBY VISION P7 (MEL)",
        "Transmits native Profile 7 without modifying stream. Suitable for MEL titles on supported displays."
    ),

    /**
     * Bypasses Dolby Vision metadata NALs entirely and decodes the HEVC Main 10 base layer
     * (BT.2020 / ST 2084 PQ) as standard HDR10.
     * Ideal for HDR displays without Dolby Vision support or troubleshooting tone mapping.
     */
    FORCE_HDR10(
        "Force HDR10 Base Layer",
        "HDR10 / BT.2020",
        "Bypasses Dolby Vision dynamic metadata and decodes standard HDR10 PQ (HEVC Main 10)."
    );

    companion object {
        fun fromString(name: String?): DolbyVisionProcessingMode {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: P8_TRANSMUTE
        }
    }
}
