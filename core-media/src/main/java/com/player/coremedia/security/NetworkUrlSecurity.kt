package com.player.coremedia.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Validates server URLs, external media URIs, and redacts sensitive tokens from logs.
 */
object NetworkUrlSecurity {

    sealed class ServerUrlResult {
        data class Valid(val normalizedUrl: String, val isEncrypted: Boolean) : ServerUrlResult()
        data class Invalid(val reason: String) : ServerUrlResult()
    }

    private val CONTROL_CHAR_REGEX = Regex("[\\u0000-\\u001F\\u007F]")
    private val SENSITIVE_QUERY_REGEX = Regex("(?i)([?&](?:api_key|token|x-emby-token|password|pw)=)[^&]+")
    private const val MAX_URI_LENGTH = 4096

    /**
     * Validates and normalizes a Jellyfin server base URL.
     * Cleartext HTTP is allowed only for local/LAN/private addresses; public hosts require HTTPS.
     */
    fun validateServerUrl(rawUrl: String): ServerUrlResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URI_LENGTH) {
            return ServerUrlResult.Invalid("Enter a valid server URL")
        }
        if (CONTROL_CHAR_REGEX.containsMatchIn(trimmed) || trimmed.any { it.isWhitespace() }) {
            return ServerUrlResult.Invalid("Server URL contains invalid characters")
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return ServerUrlResult.Invalid("Malformed server URL")
        }

        val scheme = uri.scheme?.lowercase(Locale.US) ?: ""
        if (scheme != "http" && scheme != "https") {
            return ServerUrlResult.Invalid("Only http:// or https:// URLs are allowed")
        }
        if (!uri.rawUserInfo.isNullOrEmpty()) {
            return ServerUrlResult.Invalid("Embedded credentials in URL are not allowed")
        }
        if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) {
            return ServerUrlResult.Invalid("Server URL must not include query parameters or fragments")
        }

        val host = uri.host?.trim()?.trim('[', ']') ?: ""
        if (host.isEmpty()) {
            return ServerUrlResult.Invalid("Server URL is missing a valid host")
        }
        if (uri.port != -1 && uri.port !in 1..65535) {
            return ServerUrlResult.Invalid("Invalid server port")
        }

        if (scheme == "http" && !isPrivateOrLocalHost(host)) {
            return ServerUrlResult.Invalid("Public servers must use https://")
        }

        val normalized = trimmed.trimEnd('/')
        return ServerUrlResult.Valid(normalized, isEncrypted = scheme == "https")
    }

    /**
     * Validates inbound playback URIs (including external ACTION_VIEW intents) to block
     * dangerous schemes and LFI paths targeting private application storage.
     */
    fun validateMediaPlaybackUri(rawUri: String?): Boolean {
        val trimmed = rawUri?.trim() ?: return false
        if (trimmed.isEmpty() || trimmed.length > MAX_URI_LENGTH) return false
        if (CONTROL_CHAR_REGEX.containsMatchIn(trimmed)) return false

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return false
        }

        return when (uri.scheme?.lowercase(Locale.US)) {
            "http", "https" -> !uri.host.isNullOrBlank() && uri.rawUserInfo.isNullOrEmpty()
            "content" -> true
            "file" -> {
                val path = (uri.path ?: "").lowercase(Locale.US)
                if (path.isBlank()) return false
                val blockedPrefixes = listOf(
                    "/data/data/",
                    "/data/user/",
                    "/data/user_de/",
                    "/proc/",
                    "/sys/"
                )
                blockedPrefixes.none { path.startsWith(it) } && !path.contains("remux_player_prefs")
            }
            else -> false
        }
    }

    /**
     * Redacts Jellyfin API keys and session tokens before writing URLs to logcat.
     */
    fun redactSensitiveUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return SENSITIVE_QUERY_REGEX.replace(url) { match ->
            "${match.groupValues[1]}REDACTED"
        }
    }

    internal fun isPrivateOrLocalHost(host: String): Boolean {
        val lower = host.lowercase(Locale.US).trimEnd('.')
        if (lower == "localhost" ||
            lower.endsWith(".local") ||
            lower.endsWith(".lan") ||
            lower.endsWith(".home") ||
            lower.endsWith(".internal") ||
            !lower.contains('.')
        ) {
            return true
        }

        // Literal IPv4 / IPv6 check without performing DNS resolution on caller thread
        val ipv4 = parseLiteralIpv4(lower)
        if (ipv4 != null) {
            val b0 = ipv4[0]
            val b1 = ipv4[1]
            return b0 == 10 || // 10.0.0.0/8
                b0 == 127 || // 127.0.0.0/8
                (b0 == 172 && b1 in 16..31) || // 172.16.0.0/12
                (b0 == 192 && b1 == 168) || // 192.168.0.0/16
                (b0 == 169 && b1 == 254) || // 169.254.0.0/16 link-local
                (b0 == 100 && b1 in 64..127) // 100.64.0.0/10 CGNAT / Tailscale
        }

        if (lower.contains(':')) {
            return try {
                val addr = InetAddress.getByName(lower)
                addr is Inet6Address && (
                    addr.isLoopbackAddress ||
                        addr.isLinkLocalAddress ||
                        addr.isSiteLocalAddress ||
                        ((addr.address[0].toInt() and 0xFE) == 0xFC) // ULA fc00::/7
                    )
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    private fun parseLiteralIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return octets
    }
}
