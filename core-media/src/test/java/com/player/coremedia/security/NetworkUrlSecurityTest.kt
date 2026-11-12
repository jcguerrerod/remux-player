package com.player.coremedia.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUrlSecurityTest {

    @Test
    fun `validateServerUrl allows local and LAN http servers and strips trailing slash`() {
        val lan = NetworkUrlSecurity.validateServerUrl("http://192.168.100.108:8096/")
        assertTrue(lan is NetworkUrlSecurity.ServerUrlResult.Valid)
        assertEquals("http://192.168.100.108:8096", (lan as NetworkUrlSecurity.ServerUrlResult.Valid).normalizedUrl)

        val emulator = NetworkUrlSecurity.validateServerUrl("http://10.0.2.2:8096")
        assertTrue(emulator is NetworkUrlSecurity.ServerUrlResult.Valid)

        val tailscale = NetworkUrlSecurity.validateServerUrl("http://100.72.10.5:8096")
        assertTrue(tailscale is NetworkUrlSecurity.ServerUrlResult.Valid)

        val mdns = NetworkUrlSecurity.validateServerUrl("http://nas.local:8096")
        assertTrue(mdns is NetworkUrlSecurity.ServerUrlResult.Valid)
    }

    @Test
    fun `validateServerUrl requires https for public hosts and rejects invalid schemes`() {
        val publicHttp = NetworkUrlSecurity.validateServerUrl("http://media.example.com:8096")
        assertTrue(publicHttp is NetworkUrlSecurity.ServerUrlResult.Invalid)

        val publicHttps = NetworkUrlSecurity.validateServerUrl("https://media.example.com:8920")
        assertTrue(publicHttps is NetworkUrlSecurity.ServerUrlResult.Valid)
        assertTrue((publicHttps as NetworkUrlSecurity.ServerUrlResult.Valid).isEncrypted)

        val embeddedCreds = NetworkUrlSecurity.validateServerUrl("https://user:pass@media.example.com")
        assertTrue(embeddedCreds is NetworkUrlSecurity.ServerUrlResult.Invalid)

        val fileScheme = NetworkUrlSecurity.validateServerUrl("file:///etc/passwd")
        assertTrue(fileScheme is NetworkUrlSecurity.ServerUrlResult.Invalid)
    }

    @Test
    fun `validateMediaPlaybackUri allows safe media URIs and blocks private storage LFI`() {
        assertTrue(NetworkUrlSecurity.validateMediaPlaybackUri("http://192.168.100.108:8096/Videos/1/stream.mkv"))
        assertTrue(NetworkUrlSecurity.validateMediaPlaybackUri("https://cdn.example.com/movie.mp4"))
        assertTrue(NetworkUrlSecurity.validateMediaPlaybackUri("content://com.android.providers.media/123"))
        assertTrue(NetworkUrlSecurity.validateMediaPlaybackUri("file:///storage/emulated/0/Movies/film.mkv"))

        assertFalse(NetworkUrlSecurity.validateMediaPlaybackUri("file:///data/data/com.player.androidtv/shared_prefs/remux_player_prefs.xml"))
        assertFalse(NetworkUrlSecurity.validateMediaPlaybackUri("javascript:alert(1)"))
        assertFalse(NetworkUrlSecurity.validateMediaPlaybackUri(""))
    }

    @Test
    fun `redactSensitiveUrl hides api keys and tokens`() {
        val raw = "http://192.168.100.108:8096/Items/abc/Images/Backdrop/0?maxWidth=1920&api_key=secret12345&quality=90"
        val redacted = NetworkUrlSecurity.redactSensitiveUrl(raw)
        assertFalse(redacted.contains("secret12345"))
        assertTrue(redacted.contains("api_key=REDACTED"))
        assertTrue(redacted.contains("quality=90"))
    }
}
