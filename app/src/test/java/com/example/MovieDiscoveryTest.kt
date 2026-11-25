package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.media.DiscoveryRoute
import com.example.media.FuzzyTitleMatcher
import com.example.media.MediaPersistence
import com.example.media.MediaPreferences
import com.example.media.MediaSearchResult
import com.example.media.MediaType
import com.example.media.MetadataRepository
import com.example.media.MovieDiscoveryViewModel
import com.example.media.QueryModifiers
import com.example.media.SearchEngineOption
import com.example.media.SearchLanguagePreference
import com.example.media.SourceDiscoveryEngine
import com.example.media.SourceSearchTarget
import com.example.media.SourceSiteConfig
import com.example.media.SourceSitesCatalog
import com.example.media.WebSourceResult
import com.example.media.ZeroKeyMetadataEnricher
import com.example.ui.SourceMetaCategory
import com.example.ui.parseHonestSourceBadges
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MovieDiscoveryTest {

    @Test
    fun `fuzzy matcher handles misspellings persian normalization and year extraction`() {
        val typoQuery = FuzzyTitleMatcher.parseQuery("Interstelar")
        assertEquals("Interstelar", typoQuery.cleanTitle)
        assertEquals(null, typoQuery.year)
        val interstellarScore = FuzzyTitleMatcher.scoreCandidate(typoQuery, "Interstellar", candidateYear = 2014)
        val unrelatedScore = FuzzyTitleMatcher.scoreCandidate(typoQuery, "The Matrix", candidateYear = 1999)
        assertTrue("Interstellar ($interstellarScore) should outscore Matrix ($unrelatedScore)", interstellarScore > unrelatedScore)
        assertTrue(interstellarScore > 80.0)

        val yearQuery = FuzzyTitleMatcher.parseQuery("Batman 2022")
        assertEquals("Batman", yearQuery.cleanTitle)
        assertEquals(2022, yearQuery.year)
        val score2022 = FuzzyTitleMatcher.scoreCandidate(yearQuery, "The Batman", candidateYear = 2022)
        val score1989 = FuzzyTitleMatcher.scoreCandidate(yearQuery, "Batman", candidateYear = 1989)
        assertTrue("2022 match should outrank 1989 when year 2022 is queried", score2022 > score1989)

        val faNorm1 = FuzzyTitleMatcher.normalizeText("میان ستاره ای")
        val faNorm2 = FuzzyTitleMatcher.normalizeText("ميان‌ستاره‌ای")
        assertTrue(FuzzyTitleMatcher.stringSimilarity(faNorm1, faNorm2) > 0.85)
        assertTrue(FuzzyTitleMatcher.containsPersian("برکینگ بد"))
    }

    @Test
    fun `web search query builder generates smart movie season and episode queries`() {
        val movieTarget = SourceSearchTarget(
            mediaId = "imdb:tt0816692",
            mediaType = MediaType.MOVIE,
            title = "Interstellar",
            persianTitle = "میان‌ستاره‌ای",
            releaseYear = 2014,
        )
        val movieQueries = FuzzyTitleMatcher.buildWebSearchQueries(
            target = movieTarget,
            searchLanguage = SearchLanguagePreference.BOTH,
            modifiers = QueryModifiers(includeSubtitle = true, includeDownload = true),
        )
        assertTrue(movieQueries.any { it.contains("Interstellar 2014 download") })
        assertTrue(movieQueries.any { it.contains("دانلود فیلم Interstellar") && it.contains("زیرنویس فارسی") })
        assertTrue(movieQueries.any { it.contains("میان‌ستاره‌ای") })

        val epTarget = SourceSearchTarget(
            mediaId = "imdb:tt0903747",
            mediaType = MediaType.TV,
            title = "Breaking Bad",
            persianTitle = "بریکینگ بد",
            releaseYear = 2008,
            seasonNumber = 2,
            episodeNumber = 5,
            episodeTitle = "Breakage",
        )
        val epQueries = FuzzyTitleMatcher.buildWebSearchQueries(epTarget, SearchLanguagePreference.BOTH)
        assertTrue(epQueries.any { it.contains("S02E05") })
        assertTrue(epQueries.any { it.contains("فصل 2 قسمت 5") })
    }

    @Test
    fun `safe url scheme validation blocks non http schemes`() {
        assertTrue(FuzzyTitleMatcher.isSafeHttpUrl("https://serialblog1.top/post"))
        assertTrue(FuzzyTitleMatcher.isSafeHttpUrl("http://example.com/movie.mkv"))
        assertFalse(FuzzyTitleMatcher.isSafeHttpUrl("javascript:alert(1)"))
        assertFalse(FuzzyTitleMatcher.isSafeHttpUrl("file:///etc/passwd"))
        assertFalse(FuzzyTitleMatcher.isSafeHttpUrl("intent://foo#Intent"))
    }

    @Test
    fun `streaming archive parser indexes html and matches queries with bounded memory`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cacheFile = File(context.cacheDir, "test_archive_index.tsv").apply { delete() }
        val sampleHtml = buildString {
            append("<html><body>\n")
            for (i in 1..200) {
                append("""<a href="/movies/sample-$i">Sample Movie $i (2020)</a><br/>""" + "\n")
            }
            append("""<a href="https://donyayeserial-new1.top/interstellar-2014/">دانلود فیلم Interstellar 2014 میان ستاره ای</a>""" + "\n")
            append("""<a href="https://donyayeserial-new1.top/breaking-bad/">دانلود سریال Breaking Bad برکینگ بد</a>""" + "\n")
            append("</body></html>")
        }

        val engine = SourceDiscoveryEngine()
        val count = engine.streamParseArchiveHtmlToTsv(
            inputStream = ByteArrayInputStream(sampleHtml.toByteArray(Charsets.UTF_8)),
            baseUrl = "https://dls2.aparatchi-dlcenter.top/DonyayeSerial/donyaye_serial_all_archive.html",
            destTsvFile = cacheFile,
        )
        assertEquals(202, count)

        val matches = engine.searchCachedArchiveTsv(
            cacheFile = cacheFile,
            target = SourceSearchTarget(
                mediaId = "1",
                mediaType = MediaType.MOVIE,
                title = "Interstellar",
                persianTitle = "میان ستاره ای",
                releaseYear = 2014,
            ),
            siteName = "DonyayeSerial Archive",
            siteId = "arch",
        )
        assertTrue(matches.any { it.url.contains("interstellar-2014") })
        cacheFile.delete()
    }

    @Test
    fun `parses tmdb imdb tvmaze wordpress and duckduckgo payloads accurately`() {
        val enricher = ZeroKeyMetadataEnricher(MetadataRepository.defaultOkHttpClient())
        val imdbJson = """{"d":[{"id":"tt0816692","l":"Interstellar","qid":"movie","y":2014,"s":"Matthew McConaughey, Anne Hathaway","i":{"imageUrl":"https://m.media-amazon.com/images/test._V1_.jpg"}}]}"""
        val imdbParsed = enricher.parseImdbSuggestionJson(imdbJson)
        assertEquals(1, imdbParsed.size)
        assertEquals("Interstellar", imdbParsed[0].title)
        assertEquals(2014, imdbParsed[0].releaseYear)
        assertTrue(imdbParsed[0].posterUrl!!.contains("_V1_UX380_"))

        val tvMazeShowJson = """
            {
              "id": 169,
              "name": "Breaking Bad",
              "premiered": "2008-01-20",
              "ended": "2013-09-29",
              "status": "Ended",
              "averageRuntime": 60,
              "genres": ["Drama", "Crime"],
              "rating": {"average": 9.2},
              "externals": {"imdb": "tt0903747"},
              "_embedded": {
                "cast": [{"person": {"name": "Bryan Cranston"}, "character": {"name": "Walter White"}}],
                "episodes": [
                  {"season": 1, "number": 1, "name": "Pilot", "airdate": "2008-01-20", "runtime": 58, "summary": "<p>Chemistry teacher.</p>"}
                ]
              }
            }
        """.trimIndent()
        val tvDetails = enricher.parseTvMazeFullShowJson(tvMazeShowJson)
        assertNotNull(tvDetails)
        assertEquals(1, tvDetails!!.seasons.size)
        assertEquals("Pilot", tvDetails.seasons[0].episodes[0].title)
        assertEquals("Bryan Cranston", tvDetails.cast[0].name)

        val engine = SourceDiscoveryEngine()
        val wpJson = """[{"id": 10, "link": "https://serialblog2.top/breaking-bad/", "title": {"rendered": "دانلود سریال Breaking Bad"}, "excerpt": {"rendered": "<p>فصل ۱ تا ۵</p>"}}]"""
        val wpHits = engine.parseWordPressPostsJson(wpJson, "SerialBlog", "serialblog")
        assertEquals(1, wpHits.size)
        assertEquals("serialblog2.top", wpHits[0].domain)

        val ddgHtml = """<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Farchive.org%2Fdetails%2Ftest">Test Result</a><a class="result__snippet">Sample snippet</a>"""
        val ddgHits = engine.parseDuckDuckGoHtml(ddgHtml)
        assertEquals(1, ddgHits.size)
        assertEquals("https://archive.org/details/test", ddgHits[0].url)
    }

    @Test
    fun `persistence and discovery viewmodel preserve favorites history sources and route backstack`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val persistence = MediaPersistence(context)
        persistence.clearMediaCache()
        persistence.clearSearchHistory()

        val vm = MovieDiscoveryViewModel()
        vm.initialize(context)

        val sample = MediaSearchResult(
            id = "imdb:tt0816692",
            mediaType = MediaType.MOVIE,
            title = "Interstellar",
            persianTitle = "میان‌ستاره‌ای",
            releaseYear = 2014,
        )
        vm.toggleFavorite(sample)
        assertTrue(vm.isFavorite(sample.id))

        assertTrue(vm.addCustomSourceSite("MyCinema", "mycinema.example.com"))
        assertFalse(vm.addCustomSourceSite("Bad", "javascript:alert(1)"))

        vm.updatePreferences {
            it.copy(
                searchEngine = SearchEngineOption.GOOGLE,
                searchLanguage = SearchLanguagePreference.FA,
            )
        }
        assertEquals(SearchEngineOption.GOOGLE, vm.state.value.preferences.searchEngine)

        vm.openMediaDetails(sample)
        assertTrue(vm.state.value.currentRoute is DiscoveryRoute.Details)

        vm.openSourceDiscovery(
            SourceSearchTarget(
                mediaId = sample.id,
                mediaType = MediaType.MOVIE,
                title = sample.title,
                releaseYear = 2014,
            )
        )
        assertTrue(vm.state.value.currentRoute is DiscoveryRoute.Sources)

        vm.openInternalBrowser("https://serialblog1.top", "SerialBlog", sample.title)
        assertTrue(vm.state.value.currentRoute is DiscoveryRoute.Browser)

        assertTrue(vm.popRoute())
        assertTrue(vm.state.value.currentRoute is DiscoveryRoute.Sources)
        assertTrue(vm.popRoute())
        assertTrue(vm.state.value.currentRoute is DiscoveryRoute.Details)
        assertTrue(vm.popRoute())
        assertTrue(vm.state.value.currentRoute is DiscoveryRoute.HomeSearch)
        assertFalse(vm.popRoute())
    }

    @Test
    fun `source sites catalog includes myf2ms and merges built in updates without losing custom sites`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val persistence = MediaPersistence(context)

        val builtIn = SourceSitesCatalog.BUILT_IN_SITES
        val myf2ms = builtIn.firstOrNull { it.id == "myf2ms" }
        assertNotNull("MyF2Ms should exist in SourceSitesCatalog", myf2ms)
        assertEquals("https://www.myf2ms.top", myf2ms!!.baseUrl)
        assertEquals("https://www.myf2ms.top/?s={query}", myf2ms.searchUrlTemplate)

        val previousSaved = listOf(
            builtIn.first { it.id == "serialblog" }.copy(baseUrl = "https://old-domain.example", enabled = false),
            SourceSiteConfig(
                id = "custom_1",
                name = "User Custom Site",
                baseUrl = "https://user-custom.example.com",
                searchUrlTemplate = "https://user-custom.example.com/?s={query}",
                isCustom = true,
                enabled = true,
            ),
        )
        val merged = persistence.mergeWithBuiltInCatalog(saved = previousSaved, defaults = builtIn)
        val mergedSerialBlog = merged.first { it.id == "serialblog" }
        assertEquals("https://serialblog1.top", mergedSerialBlog.baseUrl)
        assertFalse("User toggle off should be preserved across catalog updates", mergedSerialBlog.enabled)
        assertTrue("New built-in myf2ms should appear on upgrade", merged.any { it.id == "myf2ms" })
        assertTrue("User custom site should be preserved", merged.any { it.id == "custom_1" && it.isCustom })
    }

    @Test
    fun `honest source badge parser extracts only genuinely present markers`() {
        val richSource = WebSourceResult(
            id = "rich-1",
            title = "دانلود فیلم Interstellar 2014 دوبله فارسی 1080p",
            url = "https://dl.example.com/Interstellar.2014.1080p.Dubbed.mkv",
            domain = "dl.example.com",
            siteName = "ExampleSite",
            snippet = "نسخه MKV با کیفیت عالی و زیرنویس فارسی",
            detectedExtension = "mkv",
        )
        val badges = parseHonestSourceBadges(richSource)
        assertTrue(badges.any { it.label == "1080p" && it.category == SourceMetaCategory.QUALITY })
        assertTrue(badges.any { it.label == "MKV" && it.category == SourceMetaCategory.FORMAT })
        assertTrue(badges.any { it.category == SourceMetaCategory.AUDIO_SUB })

        val plainSource = WebSourceResult(
            id = "plain-1",
            title = "Inception Movie Page",
            url = "https://example.org/movies/inception",
            domain = "example.org",
            siteName = "ExampleSite",
            snippet = "Read synopsis and user reviews",
        )
        val plainBadges = parseHonestSourceBadges(plainSource)
        assertTrue("No quality/format tags should be fabricated", plainBadges.isEmpty())
    }

    @Test
    fun `live zero key search resolves typos persian queries and serialblog sources`() {
        runBlocking {
            val repo = MetadataRepository()
            val prefs = MediaPreferences()

            val typoHits = repo.searchMedia("Interstelar", prefs).getOrThrow()
            assertTrue("Expected Interstellar for typo Interstelar", typoHits.any { it.title.contains("Interstellar", ignoreCase = true) })

            val persianMovieHits = repo.searchMedia("میان ستاره ای", prefs).getOrThrow()
            assertTrue("Expected Interstellar for میان ستاره ای", persianMovieHits.any { it.title.contains("Interstellar", ignoreCase = true) })

            val persianTvHits = repo.searchMedia("برکینگ بد", prefs).getOrThrow()
            assertTrue("Expected Breaking Bad for برکینگ بد", persianTvHits.any { it.title.contains("Breaking Bad", ignoreCase = true) })

            val batmanHits = repo.searchMedia("Batman 2022", prefs).getOrThrow()
            assertTrue("Expected The Batman (2022) first or near top", batmanHits.take(3).any { it.title.contains("Batman", ignoreCase = true) && it.releaseYear == 2022 })

            val bbSummary = persianTvHits.first { it.title.contains("Breaking Bad", ignoreCase = true) && it.mediaType == MediaType.TV }
            val bbDetails = repo.getMediaDetails(bbSummary, prefs).getOrThrow()
            assertTrue("TV show details should load seasons via TVmaze zero-key fallback", bbDetails.seasons.isNotEmpty())
            assertTrue("TV show details should load episodes", bbDetails.seasons.first().episodes.isNotEmpty())

            val sourceEngine = SourceDiscoveryEngine()
            val sourcesBundle = sourceEngine.discoverSources(
                target = SourceSearchTarget(
                    mediaId = bbDetails.id,
                    mediaType = MediaType.TV,
                    title = "Breaking Bad",
                    persianTitle = bbDetails.persianTitle,
                    releaseYear = 2008,
                    seasonNumber = 1,
                    episodeNumber = 1,
                ),
                sites = MediaPersistence.defaultSourceSites().filter { !it.isArchive },
                preferences = prefs,
            )
            assertTrue(sourcesBundle.generatedQueries.isNotEmpty())
            assertTrue(sourcesBundle.siteResults.isNotEmpty() || sourcesBundle.webSearchResults.isNotEmpty())
        }
    }
}
