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
    val backdropAsset: String? = null,
    val cardAsset: String? = null,
    val streamUrl: String,
    val mediaSourceId: String = id,
    val playbackPositionMs: Long = 0L,
    val isPlayed: Boolean = false,
    val chapters: List<JellyfinChapter> = emptyList()
)

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
        val cleanBase = serverUrl.trimEnd('/')
        val root = try {
            json.parseToJsonElement(jsonBody).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        val items = root["Items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            try {
                parseMovieItem(element.jsonObject, cleanBase, accessToken)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseMovieItem(item: JsonObject, baseUrl: String, token: String): JellyfinMovie? {
        val id = item["Id"]?.jsonPrimitive?.content ?: return null
        val name = item["Name"]?.jsonPrimitive?.content ?: return null
        val year = item["ProductionYear"]?.jsonPrimitive?.intOrNull?.toString() ?: ""
        val runTimeTicks = item["RunTimeTicks"]?.jsonPrimitive?.longOrNull ?: 0L
        val durationMs = ticksToMs(runTimeTicks)
        val durationText = formatRuntimeTicks(runTimeTicks)

        val genresList = item["Genres"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }
            ?.take(2)
            ?: emptyList()
        val genreParts = mutableListOf("Movie").apply {
            addAll(genresList)
        }
        val genreText = genreParts.joinToString("  ·  ")

        val synopsis = item["Overview"]?.jsonPrimitive?.content ?: ""

        val people = item["People"]?.jsonArray ?: JsonArray(emptyList())
        val directors = mutableListOf<String>()
        val actors = mutableListOf<String>()
        for (personEl in people) {
            val personObj = personEl.jsonObject
            val personName = personObj["Name"]?.jsonPrimitive?.content ?: continue
            val roleType = personObj["Type"]?.jsonPrimitive?.content ?: ""
            if (roleType.equals("Director", ignoreCase = true) && directors.size < 2) {
                directors.add(personName)
            } else if (roleType.equals("Actor", ignoreCase = true) && actors.size < 4) {
                actors.add(personName)
            }
        }

        val mediaSources = item["MediaSources"]?.jsonArray
        val primarySource = mediaSources?.firstOrNull()?.jsonObject
        val mediaSourceId = primarySource?.get("Id")?.jsonPrimitive?.content ?: id
        val container = primarySource?.get("Container")?.jsonPrimitive?.content ?: "mkv"
        val sizeBytes = primarySource?.get("Size")?.jsonPrimitive?.longOrNull ?: 0L
        val sizeText = formatFileSize(sizeBytes)

        val streams = primarySource?.get("MediaStreams")?.jsonArray
            ?: item["MediaStreams"]?.jsonArray
            ?: JsonArray(emptyList())

        val videoStream = streams.firstOrNull {
            it.jsonObject["Type"]?.jsonPrimitive?.content.equals("Video", ignoreCase = true)
        }?.jsonObject

        val audioStream = streams.firstOrNull {
            val obj = it.jsonObject
            obj["Type"]?.jsonPrimitive?.content.equals("Audio", ignoreCase = true) &&
                obj["IsDefault"]?.jsonPrimitive?.booleanOrNull == true
        }?.jsonObject ?: streams.firstOrNull {
            it.jsonObject["Type"]?.jsonPrimitive?.content.equals("Audio", ignoreCase = true)
        }?.jsonObject

        val width = videoStream?.get("Width")?.jsonPrimitive?.intOrNull ?: 0
        val height = videoStream?.get("Height")?.jsonPrimitive?.intOrNull ?: 0
        val is4k = width >= 3800 || height >= 2100

        val rangeType = videoStream?.get("VideoRangeType")?.jsonPrimitive?.content ?: ""
        val range = videoStream?.get("VideoRange")?.jsonPrimitive?.content ?: ""
        val videoTitle = videoStream?.get("DisplayTitle")?.jsonPrimitive?.content ?: ""
        val videoProfile = videoStream?.get("Profile")?.jsonPrimitive?.content ?: ""
        val videoCodec = videoStream?.get("Codec")?.jsonPrimitive?.content ?: ""

        val combinedVideoSignals = "$rangeType $range $videoTitle $videoProfile".uppercase(Locale.US)
        val dynamicRange = when {
            combinedVideoSignals.contains("DOVI") || combinedVideoSignals.contains("DOLBY VISION") -> DynamicRangeType.DOLBY_VISION
            combinedVideoSignals.contains("HLG") -> DynamicRangeType.HLG
            combinedVideoSignals.contains("HDR") -> DynamicRangeType.HDR10
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
            DynamicRangeType.HDR10 -> if (is4k) "4K HDR10" else "HDR10"
            DynamicRangeType.HLG -> if (is4k) "4K HLG" else "HLG"
            DynamicRangeType.SDR -> if (is4k) "4K ${videoCodec.uppercase(Locale.US)}" else "1080P ${videoCodec.uppercase(Locale.US)}"
        }

        val audioCodec = audioStream?.get("Codec")?.jsonPrimitive?.content ?: ""
        val audioProfile = audioStream?.get("Profile")?.jsonPrimitive?.content ?: ""
        val audioTitle = audioStream?.get("DisplayTitle")?.jsonPrimitive?.content ?: ""
        val audioChannels = audioStream?.get("Channels")?.jsonPrimitive?.intOrNull ?: 0
        val combinedAudioSignals = "$audioCodec $audioProfile $audioTitle".uppercase(Locale.US)

        val audioBadge = when {
            combinedAudioSignals.contains("ATMOS") -> "DOLBY ATMOS"
            combinedAudioSignals.contains("TRUEHD") -> "DOLBY TRUEHD"
            combinedAudioSignals.contains("DTS-HD") || combinedAudioSignals.contains("DTS:X") || combinedAudioSignals.contains("MASTER") -> "DTS-HD MA"
            combinedAudioSignals.contains("DTS") || combinedAudioSignals.contains("DCA") -> "DTS"
            combinedAudioSignals.contains("EAC3") || combinedAudioSignals.contains("AC3") -> "DOLBY AUDIO"
            combinedAudioSignals.contains("PCM") -> "PCM"
            combinedAudioSignals.contains("FLAC") -> "FLAC"
            else -> "AUDIO BITSTREAM"
        }
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

        val backdropTags = item["BackdropImageTags"]?.jsonArray
        val hasBackdrop = backdropTags != null && backdropTags.isNotEmpty()
        val imageTags = item["ImageTags"]?.jsonObject
        val hasThumb = imageTags?.containsKey("Thumb") == true
        val hasPrimary = imageTags?.containsKey("Primary") == true

        val backdropUrl = if (hasBackdrop) {
            "$baseUrl/Items/$id/Images/Backdrop/0?maxWidth=1920&quality=90&api_key=$token"
        } else if (hasPrimary) {
            "$baseUrl/Items/$id/Images/Primary?maxWidth=1920&quality=90&api_key=$token"
        } else {
            null
        }

        val cardUrl = when {
            hasThumb -> "$baseUrl/Items/$id/Images/Thumb?maxWidth=640&quality=90&api_key=$token"
            hasBackdrop -> "$baseUrl/Items/$id/Images/Backdrop/0?maxWidth=640&quality=88&api_key=$token"
            hasPrimary -> "$baseUrl/Items/$id/Images/Primary?maxWidth=640&quality=88&api_key=$token"
            else -> null
        }

        val ext = container.split(",").firstOrNull()?.trim()?.ifBlank { "mkv" } ?: "mkv"
        val streamUrl = "$baseUrl/Videos/$id/stream.$ext?Static=true&MediaSourceId=$mediaSourceId&api_key=$token"

        return JellyfinMovie(
            id = id,
            title = name.uppercase(Locale.US),
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
            backdropUrl = backdropUrl,
            cardUrl = cardUrl,
            streamUrl = streamUrl,
            mediaSourceId = mediaSourceId,
            playbackPositionMs = playbackPosMs,
            isPlayed = isPlayed,
            chapters = chapters
        )
    }
}
