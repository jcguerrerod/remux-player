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
        assertEquals("TRUEHD ATMOS", hailMary.audioBadge)
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

    @Test
    fun `parseSeriesResponse extracts series metadata seasons count and artwork urls`() {
        val sampleSeriesJson = """
        {
          "Items": [
            {
              "Id": "series-101",
              "Name": "Severance",
              "ProductionYear": 2022,
              "ChildCount": 2,
              "Overview": "Mark leads a team of office workers whose memories have been surgically divided.",
              "Genres": ["Sci-Fi", "Thriller"],
              "People": [
                {"Name": "Adam Scott", "Type": "Actor"},
                {"Name": "Britt Lower", "Type": "Actor"},
                {"Name": "Ben Stiller", "Type": "Director"}
              ],
              "BackdropImageTags": ["img-backdrop-1"],
              "ImageTags": { "Primary": "img-primary-1" }
            }
          ]
        }
        """.trimIndent()

        val shows = JellyfinMetadataMapper.parseSeriesResponse(
            sampleSeriesJson,
            "http://10.0.2.2:8096/",
            "tok123"
        )

        assertEquals(1, shows.size)
        val severance = shows[0]
        assertEquals("series-101", severance.id)
        assertEquals("SEVERANCE", severance.title)
        assertEquals("2022", severance.year)
        assertEquals(2, severance.seasonsCount)
        assertEquals("TV Series  ·  Sci-Fi  ·  Thriller", severance.genreText)
        assertEquals("Adam Scott, Britt Lower", severance.actors)
        assertEquals("Ben Stiller", severance.director)
        assertTrue(severance.backdropUrl?.contains("/Items/series-101/Images/Backdrop") == true)
        assertTrue(severance.cardUrl?.contains("/Items/series-101/Images/Backdrop") == true)
        assertFalse(severance.is4k)
        assertEquals(DynamicRangeType.SDR, severance.dynamicRange)
        assertEquals("", severance.audioBadge)
        assertEquals("", severance.channelsBadge)
        assertEquals("", severance.resolutionBadge)
        assertTrue(severance.episodes.isEmpty())
    }

    @Test
    fun `withEpisodes aggregates highest video quality and best audio codec across episodes`() {
        val baseShow = JellyfinShow(
            id = "show-sv",
            title = "SILICON VALLEY",
            year = "2014",
            genreText = "TV Series · Comedy",
            synopsis = "Tech startup journey",
            actors = "Thomas Middleditch, Martin Starr",
            director = "Mike Judge"
        )
        assertFalse(baseShow.is4k)
        assertEquals(DynamicRangeType.SDR, baseShow.dynamicRange)

        val ep1 = JellyfinMovie(
            id = "sv-1",
            title = "Minimum Viable Product",
            year = "2014",
            durationText = "28m",
            durationMs = 1_680_000L,
            genreText = "Episode · Comedy",
            sizeText = "3.2 GB",
            videoCodecLabel = "1080P AVC",
            audioCodecLabel = "DTS 5.1",
            is4k = false,
            dynamicRange = DynamicRangeType.SDR,
            dvProfile = "",
            audioBadge = "DTS",
            channelsBadge = "5.1",
            synopsis = "Richard develops algorithm",
            actors = "Thomas Middleditch",
            director = "Mike Judge",
            streamUrl = "http://localhost/sv1",
            seriesName = "Silicon Valley",
            seasonNumber = 1,
            episodeNumber = 1,
            resolutionBadge = "HD"
        )

        val ep2 = JellyfinMovie(
            id = "sv-2",
            title = "The Cap Table",
            year = "2014",
            durationText = "29m",
            durationMs = 1_740_000L,
            genreText = "Episode · Comedy",
            sizeText = "3.4 GB",
            videoCodecLabel = "1080P AVC",
            audioCodecLabel = "DTS-HD MA 5.1",
            is4k = false,
            dynamicRange = DynamicRangeType.SDR,
            dvProfile = "",
            audioBadge = "DTS-HD MA",
            channelsBadge = "5.1",
            synopsis = "Peter Gregory stalls",
            actors = "Thomas Middleditch",
            director = "Mike Judge",
            streamUrl = "http://localhost/sv2",
            seriesName = "Silicon Valley",
            seasonNumber = 2,
            episodeNumber = 1,
            resolutionBadge = "HD"
        )

        val aggregated = baseShow.withEpisodes(listOf(ep1, ep2))
        assertEquals(2, aggregated.episodes.size)
        assertEquals(2, aggregated.seasonsCount)
        assertFalse(aggregated.is4k)
        assertEquals("HD", aggregated.resolutionBadge)
        assertEquals(DynamicRangeType.SDR, aggregated.dynamicRange)
        assertEquals("DTS-HD MA", aggregated.audioBadge)
        assertEquals("5.1", aggregated.channelsBadge)
    }

    @Test
    fun `withEpisodes recognizes 4K Dolby Vision and Dolby Atmos when present`() {
        val baseShow = JellyfinShow(
            id = "show-mota",
            title = "MASTERS OF THE AIR",
            year = "2024",
            genreText = "TV Series · War · Drama",
            synopsis = "Bloody Hundredth in WWII",
            actors = "Austin Butler, Callum Turner",
            director = "Cary Joji Fukunaga"
        )

        val ep1 = JellyfinMovie(
            id = "mota-1",
            title = "Part One",
            year = "2024",
            durationText = "55m",
            durationMs = 3_300_000L,
            genreText = "Episode · War",
            sizeText = "14.2 GB",
            videoCodecLabel = "DOLBY VISION P8.1",
            audioCodecLabel = "Dolby Atmos 5.1",
            is4k = true,
            dynamicRange = DynamicRangeType.DOLBY_VISION,
            dvProfile = "P8.1",
            audioBadge = "DOLBY ATMOS",
            channelsBadge = "5.1",
            synopsis = "First mission over Bremen",
            actors = "Austin Butler",
            director = "Cary Joji Fukunaga",
            streamUrl = "http://localhost/mota1",
            seriesName = "Masters of the Air",
            seasonNumber = 1,
            episodeNumber = 1,
            resolutionBadge = "4K"
        )

        val aggregated = baseShow.withEpisodes(listOf(ep1))
        assertTrue(aggregated.is4k)
        assertEquals("4K", aggregated.resolutionBadge)
        assertEquals(DynamicRangeType.DOLBY_VISION, aggregated.dynamicRange)
        assertEquals("DOLBY ATMOS", aggregated.audioBadge)
        assertEquals("5.1", aggregated.channelsBadge)
    }

    @Test
    fun `parseEpisodesResponse extracts season episode code streams and chapters`() {
        val sampleEpisodesJson = """
        {
          "Items": [
            {
              "Id": "ep-101",
              "Name": "Good News About Hell",
              "SeriesName": "Severance",
              "ParentIndexNumber": 1,
              "IndexNumber": 1,
              "ProductionYear": 2022,
              "RunTimeTicks": 34200000000,
              "Overview": "Mark is promoted after Petey vanishes.",
              "UserData": { "PlaybackPositionTicks": 600000000, "Played": false },
              "Chapters": [
                {"Name": "Orientation", "StartPositionTicks": 0},
                {"Name": "MDR Floor", "StartPositionTicks": 9000000000}
              ],
              "MediaSources": [
                {
                  "Size": 18253611008,
                  "MediaStreams": [
                    {
                      "Type": "Video",
                      "Codec": "hevc",
                      "Width": 3840,
                      "Height": 2160,
                      "VideoRange": "HDR",
                      "VideoRangeType": "DOVI"
                    },
                    {
                      "Type": "Audio",
                      "Codec": "eac3",
                      "Profile": "Dolby Digital Plus + Dolby Atmos",
                      "Channels": 6
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val episodes = JellyfinMetadataMapper.parseEpisodesResponse(
            sampleEpisodesJson,
            "http://10.0.2.2:8096",
            "tok123"
        )

        assertEquals(1, episodes.size)
        val ep1 = episodes[0]
        assertEquals("ep-101", ep1.id)
        assertEquals("GOOD NEWS ABOUT HELL", ep1.title)
        assertEquals("Severance", ep1.seriesName)
        assertEquals(1, ep1.seasonNumber)
        assertEquals(1, ep1.episodeNumber)
        assertEquals("S01E01", ep1.episodeCode)
        assertEquals("57m", ep1.durationText)
        assertTrue(ep1.is4k)
        assertEquals(DynamicRangeType.DOLBY_VISION, ep1.dynamicRange)
        assertEquals("DD+ ATMOS", ep1.audioBadge)
        assertEquals(60_000L, ep1.playbackPositionMs)
        assertEquals(2, ep1.chapters.size)
        assertTrue(ep1.streamUrl.contains("/Videos/ep-101/stream.mkv?Static=true"))
    }
}
