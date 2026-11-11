package com.player.coremedia.jellyfin

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

/**
 * Cliente REST Jellyfin de alto rendimiento.
 * Configura perfiles de cliente para forzar DirectPlay de MKV 4K Blu-ray Remux
 * sin transcodificación en el servidor Jellyfin y gestiona autenticación,
 * catálogo, capítulos, imágenes y reporte de sesión en tiempo real.
 */
class JellyfinDirectPlayClient(
    private val serverUrl: String
) {
    private val baseUrl: String = serverUrl.trimEnd('/')

    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 8000
            connectTimeoutMillis = 4000
        }
    }

    private fun buildAuthHeader(token: String? = null): String {
        val base = "MediaBrowser Client=\"REMUX Player\", Device=\"Android TV\", DeviceId=\"remux-player-atv\", Version=\"1.0.0\""
        return if (!token.isNullOrBlank()) "$base, Token=\"$token\"" else base
    }

    /**
     * Consulta la información pública del servidor Jellyfin para validar conectividad.
     */
    suspend fun testConnection(): JellyfinServerInfo? {
        return try {
            val response = client.get("$baseUrl/System/Info/Public") {
                header("Authorization", buildAuthHeader())
            }
            if (!response.status.isSuccess()) return null
            JellyfinMetadataMapper.parseServerInfo(response.bodyAsText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Autentica un usuario mediante nombre y contraseña en /Users/AuthenticateByName.
     */
    suspend fun authenticateByName(username: String, password: String, serverName: String = ""): JellyfinSession? {
        return try {
            val payload = buildJsonObject {
                put("Username", username)
                put("Pw", password)
            }
            val response = client.post("$baseUrl/Users/AuthenticateByName") {
                contentType(ContentType.Application.Json)
                header("Authorization", buildAuthHeader())
                setBody(payload)
            }
            if (!response.status.isSuccess()) return null
            JellyfinMetadataMapper.parseAuthSession(response.bodyAsText(), baseUrl, serverName)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Descarga el catálogo real de películas con Streams de video/audio, capítulos y progreso.
     */
    suspend fun fetchMovies(session: JellyfinSession): List<JellyfinMovie> {
        return try {
            val response = client.get("${session.serverUrl}/Users/${session.userId}/Items") {
                header("X-Emby-Token", session.accessToken)
                header("Authorization", buildAuthHeader(session.accessToken))
                parameter("IncludeItemTypes", "Movie")
                parameter("Recursive", "true")
                parameter(
                    "Fields",
                    "Overview,Genres,People,MediaSources,MediaStreams,Chapters,RunTimeTicks,ProductionYear"
                )
                parameter("SortBy", "DateCreated,SortName")
                parameter("SortOrder", "Descending")
            }
            if (!response.status.isSuccess()) return emptyList()
            JellyfinMetadataMapper.parseMoviesResponse(
                response.bodyAsText(),
                session.serverUrl,
                session.accessToken
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Marca o desmarca una película como vista en Jellyfin.
     */
    suspend fun setPlayedStatus(session: JellyfinSession, itemId: String, played: Boolean): Boolean {
        return try {
            val url = "${session.serverUrl}/Users/${session.userId}/PlayedItems/$itemId"
            val response = if (played) {
                client.post(url) {
                    header("X-Emby-Token", session.accessToken)
                    header("Authorization", buildAuthHeader(session.accessToken))
                }
            } else {
                client.delete(url) {
                    header("X-Emby-Token", session.accessToken)
                    header("Authorization", buildAuthHeader(session.accessToken))
                }
            }
            response.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Construye un DeviceProfile que informa compatibilidad nativa absoluta
     * con contenedores MKV, Dolby Vision Perfil 8 (dvhe.08) y Audio HD passthrough.
     */
    fun createForcedDirectProfile(): JsonObject {
        return buildJsonObject {
            put("Name", "AndroidTV-Reference-DV-Player")
            putJsonArray("DirectPlayProfiles") {
                addJsonObject {
                    put("Container", "mkv,m2ts,mp4")
                    put("Type", "Video")
                    put("VideoCodec", "hevc,h265,h264")
                    put("AudioCodec", "truehd,dts,dts-hd,dtshd,eac3,ac3,pcm,flac")
                }
            }
            putJsonArray("CodecProfiles") {
                addJsonObject {
                    put("Type", "Video")
                    put("Codec", "hevc")
                    putJsonArray("Conditions") {
                        addJsonObject {
                            put("Condition", "EqualsAny")
                            put("Property", "VideoProfile")
                            put("Value", "dvhe.08|main10|main")
                            put("IsRequired", false)
                        }
                    }
                }
            }
        }
    }

    /**
     * Inicia una sesión de reproducción en Jellyfin informando DirectPlay.
     */
    suspend fun startPlaybackSession(itemId: String, mediaSourceId: String, token: String) {
        try {
            val payload = buildJsonObject {
                put("ItemId", itemId)
                put("MediaSourceId", mediaSourceId)
                put("PlayMethod", "DirectPlay")
                put("DeviceProfile", createForcedDirectProfile())
            }

            client.post("$baseUrl/Sessions/Playing") {
                contentType(ContentType.Application.Json)
                header("X-Emby-Token", token)
                header("Authorization", buildAuthHeader(token))
                setBody(payload)
            }
        } catch (_: Exception) {}
    }

    /**
     * Envía sincronización periódica de progreso de reproducción.
     */
    suspend fun reportProgress(itemId: String, positionTicks: Long, token: String, isPaused: Boolean = false) {
        try {
            client.post("$baseUrl/Sessions/Playing/Progress") {
                contentType(ContentType.Application.Json)
                header("X-Emby-Token", token)
                header("Authorization", buildAuthHeader(token))
                setBody(buildJsonObject {
                    put("ItemId", itemId)
                    put("PositionTicks", positionTicks)
                    put("PlayMethod", "DirectPlay")
                    put("IsPaused", isPaused)
                })
            }
        } catch (_: Exception) {}
    }

    /**
     * Informa la finalización o detención de la reproducción.
     */
    suspend fun reportStop(itemId: String, positionTicks: Long, token: String) {
        try {
            client.post("$baseUrl/Sessions/Playing/Stopped") {
                contentType(ContentType.Application.Json)
                header("X-Emby-Token", token)
                header("Authorization", buildAuthHeader(token))
                setBody(buildJsonObject {
                    put("ItemId", itemId)
                    put("PositionTicks", positionTicks)
                })
            }
        } catch (_: Exception) {}
    }

    fun close() {
        client.close()
    }
}
