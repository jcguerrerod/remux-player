package com.player.coremedia.player

/**
 * Modos de procesamiento y transmutación de perfiles Dolby Vision / HDR10 para Remux 4K.
 */
enum class DolbyVisionProcessingMode(
    val displayName: String,
    val badgeText: String,
    val description: String
) {
    /**
     * Descarta el NAL 63 (Full Enhancement Layer - FEL) por SIMD C++20 Zero-Copy y
     * transmuta la metadata dinámica RPU (NAL 62) a Perfil 8.1 (dvhe.08.06).
     * Máxima compatibilidad en todos los Smart TVs Dolby Vision (Sony, TCL, Philips, LG, Shield TV).
     */
    P8_TRANSMUTE(
        "Dolby Vision P8.1 (Recomendado)",
        "DOLBY VISION P8.1",
        "Filtra FEL (NAL 63) y mantiene 100% de la RPU dinámica en Perfil 8.1."
    ),

    /**
     * Mantiene el identificador de Perfil 7 nativo (dvhe.07.06).
     * Recomendado exclusivamente para películas con MEL (Minimal Enhancement Layer) sin sub-capa FEL
     * en televisores o dispositivos cuyo hardware admita el contenedor dvhe.07 directamente.
     */
    P7_MEL_DIRECT(
        "Dolby Vision P7 Nativo (Solo MEL)",
        "DOLBY VISION P7 (MEL)",
        "Transmite Perfil 7 nativo sin alterar el stream. Solo apto para películas MEL."
    ),

    /**
     * Ignora completamente las capas NAL de metadatos Dolby Vision y decodifica la capa base
     * HEVC Main 10 (BT.2020 / ST 2084 PQ) como HDR10 estándar.
     * Ideal para televisores sin soporte Dolby Vision o para descartar problemas de mapeo dinámico.
     */
    FORCE_HDR10(
        "Forzar Capa Base HDR10",
        "HDR10 / BT.2020",
        "Ignora metadatos dinámicos DV y reproduce en HDR10 PQ estándar (HEVC Main 10)."
    );

    companion object {
        fun fromString(name: String?): DolbyVisionProcessingMode {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: P8_TRANSMUTE
        }
    }
}
