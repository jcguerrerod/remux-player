package com.player.coremedia.jellyfin

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

/**
 * Cliente REST Jellyfin de alto rendimiento.
 * Configura perfiles de cliente para forzar DirectPlay de MKV 4K Blu-ray Remux
 * sin transcodificación en el servidor Jellyfin.
 */
class JellyfinDirectPlayClient(
    private val serverUrl: String
) {
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
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
                    put("VideoCodec", "hevc,h265")
                    put("AudioCodec", "truehd,dts,dts-hd,dtshd,eac3,ac3,pcm")
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
                            put("Value", "dvhe.08|main10")
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
        val payload = buildJsonObject {
            put("ItemId", itemId)
            put("MediaSourceId", mediaSourceId)
            put("PlayMethod", "DirectPlay")
            put("DeviceProfile", createForcedDirectProfile())
        }

        client.post("$serverUrl/Sessions/Playing") {
            contentType(ContentType.Application.Json)
            header("X-Emby-Token", token)
            setBody(payload)
        }
    }

    /**
     * Envía sincronización periódica de progreso de reproducción.
     */
    suspend fun reportProgress(itemId: String, positionTicks: Long, token: String) {
        client.post("$serverUrl/Sessions/Playing/Progress") {
            contentType(ContentType.Application.Json)
            header("X-Emby-Token", token)
            setBody(buildJsonObject {
                put("ItemId", itemId)
                put("PositionTicks", positionTicks)
                put("PlayMethod", "DirectPlay")
                put("IsPaused", false)
            })
        }
    }

    /**
     * Informa la finalización o detención de la reproducción.
     */
    suspend fun reportStop(itemId: String, positionTicks: Long, token: String) {
        client.post("$serverUrl/Sessions/Playing/Stopped") {
            contentType(ContentType.Application.Json)
            header("X-Emby-Token", token)
            setBody(buildJsonObject {
                put("ItemId", itemId)
                put("PositionTicks", positionTicks)
            })
        }
    }
}
