package com.player.coremedia.jellyfin

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

enum class DynamicRangeType {
    SDR,
    HDR10,
    HLG,
    DOLBY_VISION
}

@Serializable
data class JellyfinChapter(
    val title: String,
    val startTimeMs: Long
)

data class JellyfinServerInfo(
    val serverName: String,
    val version: String,
    val id: String
)

data class JellyfinSession(
    val serverUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
    val serverName: String = ""
)

data class JellyfinMovie(
    val id: String,
    val title: String,
    val year: String,
    val durationText: String,
    val durationMs: Long,
    val genreText: String,
    val sizeText: String,
    val videoCodecLabel: String,
    val audioCodecLabel: String,
    val is4k: Boolean,
    val dynamicRange: DynamicRangeType,
    val dvProfile: String,
    val audioBadge: String,
    val channelsBadge: String,
    val synopsis: String,
    val actors: String,
    val director: String,
    val backdropUrl: String? = null,
    val cardUrl: String? = null,
    val logoUrl: String? = null,
    val tmdbId: String? = null,
    val backdropAsset: String? = null,
    val cardAsset: String? = null,
    val streamUrl: String,
    val mediaSourceId: String = id,
    val playbackPositionMs: Long = 0L,
    val isPlayed: Boolean = false,
    val chapters: List<JellyfinChapter> = emptyList(),
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val seriesId: String? = null,
    val resolutionBadge: String = if (is4k) "4K" else "HD"
) {
    val episodeCode: String
        get() = if (seasonNumber != null && episodeNumber != null) {
            String.format(Locale.US, "S%02dE%02d", seasonNumber, episodeNumber)
        } else {
            ""
        }
}

data class JellyfinShow(
    val id: String,
    val title: String,
    val year: String,
    val genreText: String,
    val synopsis: String,
    val actors: String,
    val director: String,
    val seasonsCount: Int = 1,
    val backdropUrl: String? = null,
    val cardUrl: String? = null,
    val logoUrl: String? = null,
    val tmdbId: String? = null,
    val backdropAsset: String? = null,
    val cardAsset: String? = null,
    val is4k: Boolean = false,
    val dynamicRange: DynamicRangeType = DynamicRangeType.SDR,
    val audioBadge: String = "",
    val channelsBadge: String = "",
    val resolutionBadge: String = "",
    val episodes: List<JellyfinMovie> = emptyList()
) {
    /**
     * Devuelve una copia de la serie con sus episodios asociados y la metadata técnica agregada
     * (resolución máxima, rango dinámico más avanzado y mejor perfil de audio).
     */
    fun withEpisodes(newEpisodes: List<JellyfinMovie>): JellyfinShow {
        if (newEpisodes.isEmpty()) {
            return this.copy(episodes = newEpisodes)
        }
        val bestVideo = newEpisodes.maxByOrNull { ep ->
            val rangeRank = when (ep.dynamicRange) {
                DynamicRangeType.DOLBY_VISION -> 5
                DynamicRangeType.HDR10 -> if (ep.videoCodecLabel.contains("HDR10+", ignoreCase = true)) 4 else 3
                DynamicRangeType.HLG -> 2
                DynamicRangeType.SDR -> 1
            }
            val resRank = when {
                ep.is4k -> 20
                ep.resolutionBadge.equals("HD", ignoreCase = true) -> 10
                else -> 0
            }
            resRank + rangeRank
        } ?: newEpisodes.first()

        val bestAudio = newEpisodes.maxByOrNull { ep ->
            val codecRank = when (ep.audioBadge) {
                "TRUEHD ATMOS" -> 100
                "DOLBY ATMOS" -> 98
                "DTS:X" -> 95
                "DOLBY TRUEHD" -> 90
                "DTS-HD MA" -> 85
                "FLAC" -> 75
                "PCM" -> 72
                "DD+ ATMOS" -> 68
                "DOLBY DIGITAL PLUS" -> 60
                "DTS" -> 55
                "DOLBY AUDIO" -> 45
                "AAC", "OPUS" -> 30
                "MP3", "VORBIS" -> 20
                else -> 10
            }
            val chRank = when (ep.channelsBadge) {
                "7.1" -> 8
                "5.1" -> 6
                "2.0" -> 2
                "1.0" -> 1
                else -> 0
            }
            codecRank * 10 + chRank
        } ?: newEpisodes.first()

        val seasonsDetected = newEpisodes.mapNotNull { it.seasonNumber }.distinct().size.coerceAtLeast(seasonsCount)
        val has4k = newEpisodes.any { it.is4k }
        val aggregatedRes = when {
            has4k -> "4K"
            newEpisodes.any { it.resolutionBadge == "HD" } -> "HD"
            newEpisodes.any { it.resolutionBadge == "SD" } -> "SD"
            else -> bestVideo.resolutionBadge.ifBlank { if (has4k) "4K" else "HD" }
        }

        return this.copy(
            episodes = newEpisodes,
            seasonsCount = seasonsDetected,
            is4k = has4k,
            dynamicRange = bestVideo.dynamicRange,
            audioBadge = bestAudio.audioBadge,
            channelsBadge = bestAudio.channelsBadge,
            resolutionBadge = aggregatedRes
        )
    }
}

object JellyfinMetadataMapper {

    private val json = Json { ignoreUnknownKeys = true }

    fun ticksToMs(ticks: Long): Long = (ticks / 10_000L).coerceAtLeast(0L)

    fun msToTicks(ms: Long): Long = (ms.coerceAtLeast(0L)) * 10_000L

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.2f GB", gb)
        } else {
            val mb = bytes / (1024.0 * 1024.0)
            String.format(Locale.US, "%.0f MB", mb)
        }
    }

    fun formatRuntimeTicks(ticks: Long): String {
        val totalMinutes = (ticksToMs(ticks) / 60_000L).toInt()
        if (totalMinutes <= 0) return "Direct Play"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    fun serializeChapters(chapters: List<JellyfinChapter>): String {
        if (chapters.isEmpty()) return ""
        return json.encodeToString(chapters)
    }

    fun deserializeChapters(serialized: String?): List<JellyfinChapter> {
        if (serialized.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<JellyfinChapter>>(serialized)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseServerInfo(jsonBody: String): JellyfinServerInfo? {
        return try {
            val obj = json.parseToJsonElement(jsonBody).jsonObject
            JellyfinServerInfo(
                serverName = obj["ServerName"]?.jsonPrimitive?.content ?: "Jellyfin Server",
                version = obj["Version"]?.jsonPrimitive?.content ?: "",
                id = obj["Id"]?.jsonPrimitive?.content ?: ""
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseAuthSession(jsonBody: String, serverUrl: String, serverName: String = ""): JellyfinSession? {
        return try {
            val root = json.parseToJsonElement(jsonBody).jsonObject
            val token = root["AccessToken"]?.jsonPrimitive?.content ?: return null
            val userObj = root["User"]?.jsonObject
            val userId = userObj?.get("Id")?.jsonPrimitive?.content ?: return null
            val userName = userObj["Name"]?.jsonPrimitive?.content ?: "User"
            JellyfinSession(
                serverUrl = serverUrl.trimEnd('/'),
                accessToken = token,
                userId = userId,
                userName = userName,
                serverName = serverName
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseMoviesResponse(jsonBody: String, serverUrl: String, accessToken: String): List<JellyfinMovie> {
        return parsePlayableListResponse(jsonBody, serverUrl, accessToken)
    }

    fun parseEpisodesResponse(jsonBody: String, serverUrl: String, accessToken: String): List<JellyfinMovie> {
        return parsePlayableListResponse(jsonBody, serverUrl, accessToken)
    }

    private fun parsePlayableListResponse(jsonBody: String, serverUrl: String, accessToken: String): List<JellyfinMovie> {
        val cleanBase = serverUrl.trimEnd('/')
        val root = try {
            json.parseToJsonElement(jsonBody).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        val items = root["Items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            try {
                parsePlayableItem(element.jsonObject, cleanBase, accessToken)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun parseSeriesResponse(jsonBody: String, serverUrl: String, accessToken: String): List<JellyfinShow> {
        val cleanBase = serverUrl.trimEnd('/')
        val root = try {
            json.parseToJsonElement(jsonBody).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        val items = root["Items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            try {
                parseSeriesItem(element.jsonObject, cleanBase, accessToken)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseSeriesItem(item: JsonObject, baseUrl: String, token: String): JellyfinShow? {
        val id = item["Id"]?.jsonPrimitive?.content ?: return null
        val name = item["Name"]?.jsonPrimitive?.content ?: return null
        val year = item["ProductionYear"]?.jsonPrimitive?.intOrNull?.toString() ?: ""
        val genresList = item["Genres"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }
            ?.take(2)
            ?: emptyList()
        val genreText = (listOf("TV Series") + genresList).joinToString("  ·  ")
        val synopsis = item["Overview"]?.jsonPrimitive?.content ?: ""
        val (directors, actors) = extractCrew(item)
        val extractedImages = extractImages(item, id, baseUrl, token)
        val tmdbId = item["ProviderIds"]?.jsonObject?.get("Tmdb")?.jsonPrimitive?.content
        val seasonsCount = item["ChildCount"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1

        return JellyfinShow(
            id = id,
            title = name.uppercase(Locale.US),
            year = year,
            genreText = genreText,
            synopsis = synopsis,
            actors = actors.joinToString(", "),
            director = directors.joinToString(", "),
            seasonsCount = seasonsCount,
            backdropUrl = extractedImages.backdropUrl,
            cardUrl = extractedImages.cardUrl,
            logoUrl = extractedImages.logoUrl,
            tmdbId = tmdbId,
            is4k = false,
            dynamicRange = DynamicRangeType.SDR,
            audioBadge = "",
            channelsBadge = "",
            resolutionBadge = ""
        )
    }

    private fun extractCrew(item: JsonObject): Pair<List<String>, List<String>> {
        val people = item["People"]?.jsonArray ?: JsonArray(emptyList())
        val directors = mutableListOf<String>()
        val actors = mutableListOf<String>()
        for (personEl in people) {
            val personObj = personEl.jsonObject
            val personName = personObj["Name"]?.jsonPrimitive?.content ?: continue
            val roleType = personObj["Type"]?.jsonPrimitive?.content ?: ""
            if ((roleType.equals("Director", ignoreCase = true) || roleType.equals("Creator", ignoreCase = true)) && directors.size < 2) {
                directors.add(personName)
            } else if (roleType.equals("Actor", ignoreCase = true) && actors.size < 4) {
                actors.add(personName)
            }
        }
        return directors to actors
    }

    data class ExtractedMediaImages(
        val backdropUrl: String?,
        val cardUrl: String?,
        val logoUrl: String?
    )

    private fun extractImages(item: JsonObject, id: String, baseUrl: String, token: String): ExtractedMediaImages {
        val backdropTags = item["BackdropImageTags"]?.jsonArray
        val hasBackdrop = backdropTags != null && backdropTags.isNotEmpty()
        val imageTags = item["ImageTags"]?.jsonObject
        val hasThumb = imageTags?.containsKey("Thumb") == true
        val hasPrimary = imageTags?.containsKey("Primary") == true
        val hasLogo = imageTags?.containsKey("Logo") == true
        val parentLogoItemId = item["ParentLogoItemId"]?.jsonPrimitive?.content
            ?: item["SeriesId"]?.jsonPrimitive?.content
        val hasParentLogo = !item["ParentLogoImageTag"]?.jsonPrimitive?.content.isNullOrBlank()
        val tokenQuery = if (token.isNotBlank()) "&api_key=$token" else ""

        val backdropUrl = if (hasBackdrop) {
            "$baseUrl/Items/$id/Images/Backdrop/0?maxWidth=1920&quality=90$tokenQuery"
        } else if (hasPrimary) {
            "$baseUrl/Items/$id/Images/Primary?maxWidth=1920&quality=90$tokenQuery"
        } else {
            null
        }

        val cardUrl = when {
            hasThumb -> "$baseUrl/Items/$id/Images/Thumb?maxWidth=640&quality=90$tokenQuery"
            hasBackdrop -> "$baseUrl/Items/$id/Images/Backdrop/0?maxWidth=640&quality=88$tokenQuery"
            hasPrimary -> "$baseUrl/Items/$id/Images/Primary?maxWidth=640&quality=88$tokenQuery"
            else -> null
        }

        val logoUrl = when {
            hasLogo -> "$baseUrl/Items/$id/Images/Logo?maxWidth=800&format=png$tokenQuery"
            hasParentLogo && !parentLogoItemId.isNullOrBlank() -> "$baseUrl/Items/$parentLogoItemId/Images/Logo?maxWidth=800&format=png$tokenQuery"
            else -> "$baseUrl/Items/$id/Images/Logo?maxWidth=800&format=png$tokenQuery"
        }

        return ExtractedMediaImages(backdropUrl, cardUrl, logoUrl)
    }

    private fun parsePlayableItem(item: JsonObject, baseUrl: String, token: String): JellyfinMovie? {
        val id = item["Id"]?.jsonPrimitive?.content ?: return null
        val name = item["Name"]?.jsonPrimitive?.content ?: return null
        val itemType = item["Type"]?.jsonPrimitive?.content ?: "Movie"
        val isEpisode = itemType.equals("Episode", ignoreCase = true)
        val seriesName = item["SeriesName"]?.jsonPrimitive?.content
        val seriesId = item["SeriesId"]?.jsonPrimitive?.content
        val seasonNum = item["ParentIndexNumber"]?.jsonPrimitive?.intOrNull
        val episodeNum = item["IndexNumber"]?.jsonPrimitive?.intOrNull
        val year = item["ProductionYear"]?.jsonPrimitive?.intOrNull?.toString() ?: ""
        val runTimeTicks = item["RunTimeTicks"]?.jsonPrimitive?.longOrNull ?: 0L
        val durationMs = ticksToMs(runTimeTicks)
        val durationText = formatRuntimeTicks(runTimeTicks)

        val genresList = item["Genres"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }
            ?.take(2)
            ?: emptyList()
        val genreParts = mutableListOf(if (isEpisode) "Episode" else "Movie").apply {
            addAll(genresList)
        }
        val genreText = genreParts.joinToString("  ·  ")

        val synopsis = item["Overview"]?.jsonPrimitive?.content ?: ""
        val (directors, actors) = extractCrew(item)

        val mediaSources = item["MediaSources"]?.jsonArray
        val primarySource = mediaSources?.firstOrNull()?.jsonObject
        val mediaSourceId = primarySource?.get("Id")?.jsonPrimitive?.content ?: id
        val container = primarySource?.get("Container")?.jsonPrimitive?.content ?: "mkv"
        val sizeBytes = primarySource?.get("Size")?.jsonPrimitive?.longOrNull ?: 0L
        val sizeText = formatFileSize(sizeBytes)

        // Aggregate all streams across every MediaSource so the landing page ALWAYS shows the highest video & audio quality available
        val allStreams: List<JsonObject> = buildList {
            if (mediaSources != null && mediaSources.isNotEmpty()) {
                for (srcEl in mediaSources) {
                    val srcStreams = srcEl.jsonObject["MediaStreams"]?.jsonArray ?: continue
                    for (st in srcStreams) add(st.jsonObject)
                }
            }
            if (isEmpty()) {
                val fallbackStreams = item["MediaStreams"]?.jsonArray
                if (fallbackStreams != null) {
                    for (st in fallbackStreams) add(st.jsonObject)
                }
            }
        }

        val videoStreams = allStreams.filter {
            it["Type"]?.jsonPrimitive?.content.equals("Video", ignoreCase = true)
        }
        val videoStream = videoStreams.maxByOrNull { vs ->
            val w = vs["Width"]?.jsonPrimitive?.intOrNull ?: 0
            val h = vs["Height"]?.jsonPrimitive?.intOrNull ?: 0
            val resScore = when {
                w >= 3600 || h >= 2000 -> 10_000L
                w >= 1200 || h >= 700 -> 5_000L
                w > 0 || h > 0 -> 1_000L
                else -> 0L
            }
            val signals = buildString {
                append(vs["VideoRangeType"]?.jsonPrimitive?.content ?: "").append(' ')
                append(vs["VideoRange"]?.jsonPrimitive?.content ?: "").append(' ')
                append(vs["DisplayTitle"]?.jsonPrimitive?.content ?: "").append(' ')
                append(vs["Profile"]?.jsonPrimitive?.content ?: "")
            }.uppercase(Locale.US)
            val rangeScore = when {
                signals.contains("DOVI") || signals.contains("DOLBY VISION") -> 4_000L
                signals.contains("HDR10+") || signals.contains("HDR10PLUS") -> 3_500L
                signals.contains("HDR") -> 3_000L
                signals.contains("HLG") -> 2_000L
                else -> 1_000L
            }
            val bitrateBonus = ((vs["BitRate"]?.jsonPrimitive?.longOrNull ?: 0L) / 1_000_000L).coerceAtMost(500L)
            resScore + rangeScore + bitrateBonus
        }

        val audioStreams = allStreams.filter {
            it["Type"]?.jsonPrimitive?.content.equals("Audio", ignoreCase = true)
        }
        val nonCommentaryAudio = audioStreams.filterNot { au ->
            val titleSig = "${au["DisplayTitle"]?.jsonPrimitive?.content ?: ""} ${au["Title"]?.jsonPrimitive?.content ?: ""}".lowercase(Locale.US)
            titleSig.contains("commentary") || titleSig.contains("comentario")
        }.ifEmpty { audioStreams }

        fun resolveAudioBadge(au: JsonObject): String {
            val codec = au["Codec"]?.jsonPrimitive?.content ?: ""
            val profile = au["Profile"]?.jsonPrimitive?.content ?: ""
            val title = au["DisplayTitle"]?.jsonPrimitive?.content ?: ""
            val rawTitle = au["Title"]?.jsonPrimitive?.content ?: ""
            val sig = "$codec $profile $title $rawTitle".uppercase(Locale.US)
            val hasAtmos = sig.contains("ATMOS")
            val isTrueHd = sig.contains("TRUEHD") || sig.contains("TRUE-HD") || sig.contains("MLP")
            val isEac3 = sig.contains("EAC3") || sig.contains("E-AC-3") || sig.contains("EC-3") || sig.contains("DIGITAL PLUS") || sig.contains("DD+")
            return when {
                hasAtmos && isTrueHd -> "TRUEHD ATMOS"
                hasAtmos && isEac3 -> "DD+ ATMOS"
                hasAtmos -> "DOLBY ATMOS"
                sig.contains("DTS:X") || sig.contains("DTSX") || sig.contains("DTS-X") -> "DTS:X"
                isTrueHd -> "DOLBY TRUEHD"
                sig.contains("DTS-HD") || sig.contains("DTSHD") || sig.contains("MASTER") -> "DTS-HD MA"
                sig.contains("FLAC") -> "FLAC"
                sig.contains("PCM") -> "PCM"
                isEac3 -> "DOLBY DIGITAL PLUS"
                sig.contains("DTS") || sig.contains("DCA") -> "DTS"
                sig.contains("AC3") || sig.contains("DOLBY DIGITAL") -> "DOLBY AUDIO"
                sig.contains("AAC") -> "AAC"
                sig.contains("OPUS") -> "OPUS"
                sig.contains("MP3") -> "MP3"
                sig.contains("VORBIS") -> "VORBIS"
                codec.isNotBlank() -> codec.uppercase(Locale.US)
                else -> ""
            }
        }

        fun scoreAudioStream(au: JsonObject): Int {
            val badge = resolveAudioBadge(au)
            val codecScore = when (badge) {
                "TRUEHD ATMOS" -> 1000
                "DOLBY ATMOS" -> 980
                "DTS:X" -> 950
                "DOLBY TRUEHD" -> 900
                "DTS-HD MA" -> 850
                "FLAC" -> 750
                "PCM" -> 720
                "DD+ ATMOS" -> 680
                "DOLBY DIGITAL PLUS" -> 600
                "DTS" -> 550
                "DOLBY AUDIO" -> 450
                "AAC", "OPUS" -> 300
                "MP3", "VORBIS" -> 200
                else -> 100
            }
            val ch = au["Channels"]?.jsonPrimitive?.intOrNull ?: 0
            return codecScore + ch * 10
        }

        val audioStream = nonCommentaryAudio.maxByOrNull { scoreAudioStream(it) }

        val width = videoStream?.get("Width")?.jsonPrimitive?.intOrNull ?: 0
        val height = videoStream?.get("Height")?.jsonPrimitive?.intOrNull ?: 0
        val is4k = width >= 3600 || height >= 2000
        val resolutionBadge = when {
            is4k -> "4K"
            width >= 1200 || height >= 700 -> "HD"
            width > 0 || height > 0 -> "SD"
            else -> ""
        }

        val rangeType = videoStream?.get("VideoRangeType")?.jsonPrimitive?.content ?: ""
        val range = videoStream?.get("VideoRange")?.jsonPrimitive?.content ?: ""
        val videoTitle = videoStream?.get("DisplayTitle")?.jsonPrimitive?.content ?: ""
        val videoProfile = videoStream?.get("Profile")?.jsonPrimitive?.content ?: ""
        val videoCodec = videoStream?.get("Codec")?.jsonPrimitive?.content ?: ""

        val combinedVideoSignals = "$rangeType $range $videoTitle $videoProfile".uppercase(Locale.US)
        val isHdr10Plus = combinedVideoSignals.contains("HDR10+") || combinedVideoSignals.contains("HDR10PLUS")
        val dynamicRange = when {
            combinedVideoSignals.contains("DOVI") || combinedVideoSignals.contains("DOLBY VISION") -> DynamicRangeType.DOLBY_VISION
            combinedVideoSignals.contains("HLG") -> DynamicRangeType.HLG
            isHdr10Plus || combinedVideoSignals.contains("HDR") -> DynamicRangeType.HDR10
            else -> DynamicRangeType.SDR
        }
        val dvProfile = when {
            dynamicRange != DynamicRangeType.DOLBY_VISION -> ""
            combinedVideoSignals.contains("07") || combinedVideoSignals.contains("P7") -> "P7"
            combinedVideoSignals.contains("05") || combinedVideoSignals.contains("P5") -> "P5"
            else -> "P8.1"
        }
        val videoCodecLabel = when (dynamicRange) {
            DynamicRangeType.DOLBY_VISION -> "DOLBY VISION $dvProfile"
            DynamicRangeType.HDR10 -> if (isHdr10Plus) {
                if (is4k) "4K HDR10+" else "HDR10+"
            } else {
                if (is4k) "4K HDR10" else "HDR10"
            }
            DynamicRangeType.HLG -> if (is4k) "4K HLG" else "HLG"
            DynamicRangeType.SDR -> when {
                is4k -> "4K ${videoCodec.uppercase(Locale.US)}"
                resolutionBadge == "SD" -> "SD ${videoCodec.uppercase(Locale.US)}"
                else -> "1080P ${videoCodec.uppercase(Locale.US)}"
            }
        }

        val audioTitle = audioStream?.get("DisplayTitle")?.jsonPrimitive?.content ?: ""
        val audioChannels = audioStream?.get("Channels")?.jsonPrimitive?.intOrNull ?: 0
        val audioBadge = if (audioStream != null) resolveAudioBadge(audioStream) else ""
        val channelsBadge = when (audioChannels) {
            8 -> "7.1"
            6 -> "5.1"
            2 -> "2.0"
            1 -> "1.0"
            else -> ""
        }

        val chaptersJson = item["Chapters"]?.jsonArray ?: JsonArray(emptyList())
        val chapters = chaptersJson.mapIndexedNotNull { idx, chEl ->
            val chObj = chEl.jsonObject
            val startTicks = chObj["StartPositionTicks"]?.jsonPrimitive?.longOrNull ?: return@mapIndexedNotNull null
            val chTitle = chObj["Name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"
            JellyfinChapter(chTitle, ticksToMs(startTicks))
        }

        val userData = item["UserData"]?.jsonObject
        val playbackPosMs = ticksToMs(userData?.get("PlaybackPositionTicks")?.jsonPrimitive?.longOrNull ?: 0L)
        val isPlayed = userData?.get("Played")?.jsonPrimitive?.booleanOrNull ?: false
        val extractedImages = extractImages(item, id, baseUrl, token)
        val tmdbId = item["ProviderIds"]?.jsonObject?.get("Tmdb")?.jsonPrimitive?.content

        val ext = container.split(",").firstOrNull()?.trim()?.ifBlank { "mkv" } ?: "mkv"
        val streamUrl = "$baseUrl/Videos/$id/stream.$ext?Static=true&MediaSourceId=$mediaSourceId&api_key=$token"

        val displayTitle = if (isEpisode && !seriesName.isNullOrBlank() && seasonNum != null && episodeNum != null) {
            "${seriesName.uppercase(Locale.US)} · S%02dE%02d · %s".format(Locale.US, seasonNum, episodeNum, name)
        } else {
            name.uppercase(Locale.US)
        }

        return JellyfinMovie(
            id = id,
            title = displayTitle,
            year = year,
            durationText = durationText,
            durationMs = durationMs,
            genreText = genreText,
            sizeText = sizeText,
            videoCodecLabel = videoCodecLabel,
            audioCodecLabel = audioTitle.ifBlank { audioBadge },
            is4k = is4k,
            dynamicRange = dynamicRange,
            dvProfile = dvProfile,
            audioBadge = audioBadge,
            channelsBadge = channelsBadge,
            synopsis = synopsis,
            actors = actors.joinToString(", "),
            director = directors.joinToString(", "),
            backdropUrl = extractedImages.backdropUrl,
            cardUrl = extractedImages.cardUrl,
            logoUrl = extractedImages.logoUrl,
            tmdbId = tmdbId,
            streamUrl = streamUrl,
            mediaSourceId = mediaSourceId,
            playbackPositionMs = playbackPosMs,
            isPlayed = isPlayed,
            chapters = chapters,
            seriesName = seriesName,
            seasonNumber = seasonNum,
            episodeNumber = episodeNum,
            seriesId = seriesId,
            resolutionBadge = resolutionBadge
        )
    }
}
