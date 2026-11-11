package com.player.coremedia.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinMetadataMapperTest {

    @Test
    fun `formatFileSize formats gigabytes and megabytes accurately`() {
        assertEquals("56.73 GB", JellyfinMetadataMapper.formatFileSize(60_913_372_365L))
        assertEquals("850 MB", JellyfinMetadataMapper.formatFileSize(891_289_600L))
        assertEquals("", JellyfinMetadataMapper.formatFileSize(0L))
    }

    @Test
    fun `formatRuntimeTicks formats hours and minutes cleanly`() {
        val ticks1h55m = (1 * 3600L + 55 * 60L) * 10_000_000L
        assertEquals("1h 55m", JellyfinMetadataMapper.formatRuntimeTicks(ticks1h55m))

        val ticks45m = (45 * 60L) * 10_000_000L
        assertEquals("45m", JellyfinMetadataMapper.formatRuntimeTicks(ticks45m))
    }

    @Test
    fun `serialize and deserialize chapters preserves order and timestamps`() {
        val chapters = listOf(
            JellyfinChapter("Chapter 1: Intro", 0L),
            JellyfinChapter("Chapter 2: Climax", 3_600_000L)
        )
        val encoded = JellyfinMetadataMapper.serializeChapters(chapters)
        val decoded = JellyfinMetadataMapper.deserializeChapters(encoded)

        assertEquals(2, decoded.size)
        assertEquals("Chapter 1: Intro", decoded[0].title)
        assertEquals(0L, decoded[0].startTimeMs)
        assertEquals("Chapter 2: Climax", decoded[1].title)
        assertEquals(3_600_000L, decoded[1].startTimeMs)
    }

    @Test
    fun `parseMoviesResponse detects 4K HDR10, Dolby Vision P7, SDR, audio badges and chapters`() {
        val sampleJson = """
        {
          "Items": [
            {
              "Id": "item-hdr10",
              "Name": "The Odyssey",
              "ProductionYear": 2026,
              "RunTimeTicks": 103200000000,
              "Genres": ["Sci-Fi", "Adventure"],
              "Overview": "Epic voyage home.",
              "People": [
                {"Name": "Christopher Nolan", "Type": "Director"},
                {"Name": "Anne Hathaway", "Type": "Actor"}
              ],
              "BackdropImageTags": ["tag-b1"],
              "ImageTags": {"Thumb": "tag-t1"},
              "UserData": {"PlaybackPositionTicks": 1200000000, "Played": false},
              "Chapters": [
                {"Name": "Prologue", "StartPositionTicks": 0},
                {"Name": "Voyage", "StartPositionTicks": 5000000000}
              ],
              "MediaSources": [
                {
                  "Id": "source-1",
                  "Container": "mkv",
                  "Size": 94757715968,
                  "MediaStreams": [
                    {
                      "Type": "Video",
                      "Width": 3840,
                      "Height": 2160,
                      "Codec": "hevc",
                      "VideoRange": "HDR",
                      "VideoRangeType": "HDR10"
                    },
                    {
                      "Type": "Audio",
                      "IsDefault": true,
                      "Codec": "dts",
                      "Profile": "DTS-HD MA",
                      "Channels": 6,
                      "DisplayTitle": "English DTS-HD MA 5.1"
                    }
                  ]
                }
              ]
            },
            {
              "Id": "item-dv7",
              "Name": "Project Hail Mary",
              "ProductionYear": 2026,
              "RunTimeTicks": 93000000000,
              "MediaSources": [
                {
                  "Id": "source-2",
                  "Container": "mkv",
                  "Size": 87284480409,
                  "MediaStreams": [
                    {
                      "Type": "Video",
                      "Width": 3840,
                      "Height": 2160,
                      "Codec": "hevc",
                      "VideoRangeType": "DOVIWithHDR10",
                      "Profile": "dvhe.07"
                    },
                    {
                      "Type": "Audio",
                      "IsDefault": true,
                      "Codec": "truehd",
                      "Profile": "Dolby TrueHD + Dolby Atmos",
                      "Channels": 8
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val movies = JellyfinMetadataMapper.parseMoviesResponse(
            sampleJson,
            "http://10.0.2.2:8096/",
            "tok123"
        )

        assertEquals(2, movies.size)

        val odyssey = movies[0]
        assertEquals("THE ODYSSEY", odyssey.title)
        assertTrue(odyssey.is4k)
        assertEquals(DynamicRangeType.HDR10, odyssey.dynamicRange)
        assertEquals("DTS-HD MA", odyssey.audioBadge)
        assertEquals("5.1", odyssey.channelsBadge)
        assertEquals(2, odyssey.chapters.size)
        assertEquals("Prologue", odyssey.chapters[0].title)
        assertEquals(500_000L, odyssey.chapters[1].startTimeMs)
        assertEquals(120_000L, odyssey.playbackPositionMs)
        assertFalse(odyssey.isPlayed)
        assertEquals("Christopher Nolan", odyssey.director)
        assertNotNull(odyssey.backdropUrl)
        assertTrue(odyssey.streamUrl.contains("/Videos/item-hdr10/stream.mkv?Static=true"))

        val hailMary = movies[1]
        assertTrue(hailMary.is4k)
        assertEquals(DynamicRangeType.DOLBY_VISION, hailMary.dynamicRange)
        assertEquals("P7", hailMary.dvProfile)
        assertEquals("DOLBY ATMOS", hailMary.audioBadge)
        assertEquals("7.1", hailMary.channelsBadge)
    }

    @Test
    fun `parseAuthSession returns null on malformed payload and parses valid login`() {
        assertNull(JellyfinMetadataMapper.parseAuthSession("{}", "http://localhost:8096"))
        val valid = """{"AccessToken":"abc-token","User":{"Id":"u-1","Name":"Juan"}}"""
        val session = JellyfinMetadataMapper.parseAuthSession(valid, "http://localhost:8096/", "Guerrero")
        assertNotNull(session)
        assertEquals("abc-token", session?.accessToken)
        assertEquals("u-1", session?.userId)
        assertEquals("Juan", session?.userName)
        assertEquals("http://localhost:8096", session?.serverUrl)
    }
}
