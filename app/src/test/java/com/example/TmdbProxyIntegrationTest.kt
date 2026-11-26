package com.example

import com.example.media.MediaPersistence
import com.example.media.MediaPreferences
import com.example.media.MediaSearchResult
import com.example.media.MediaType
import com.example.media.MetadataRepository
import com.example.media.MovieDiscoveryViewModel
import com.example.media.ProviderCategory
import com.example.media.SearchLanguagePreference
import com.example.media.TmdbCredentialProvider
import com.example.media.TmdbDiscoverFilters
import com.example.media.TmdbProxyClientFactory
import com.example.media.TrendingTimeWindow
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TmdbProxyIntegrationTest {

    private fun jsonResponse(req: okhttp3.Request, code: Int, json: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()

    @Test
    fun `proxy client enforces https and rejects embedded tmdb host or credentials`() {
        assertNull(TmdbProxyClientFactory.normalizeProxyBaseUrl("http://insecure.example.com"))
        assertNull(TmdbProxyClientFactory.normalizeProxyBaseUrl("https://api.themoviedb.org/3"))
        assertNull(TmdbProxyClientFactory.normalizeProxyBaseUrl("https://proxy.example.com?api_key=secret"))
        assertEquals(
            "https://mixmedia-tmdb-proxy.user.workers.dev/",
            TmdbProxyClientFactory.normalizeProxyBaseUrl("https://mixmedia-tmdb-proxy.user.workers.dev")
        )
        assertEquals(
            "",
            BuildConfig.TMDB_PROXY_BASE_URL
        )
    }

    @Test
    fun `trending switches cleanly between DAY and WEEK and invalidates window cache`() = runBlocking {
        val requestedUrls = CopyOnWriteArrayList<String>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                requestedUrls.add(url)
                assertNull(req.header("Authorization"))
                assertFalse(url.contains("api_key", ignoreCase = true))

                val body = when {
                    url.contains("/v1/trending/movie/day") -> """{ "page": 1, "total_pages": 1, "results": [{ "id": 11 }] }"""
                    url.contains("/v1/trending/movie/week") -> """{ "page": 1, "total_pages": 1, "results": [{ "id": 12 }] }"""
                    url.contains("/v1/trending/tv/day") -> """{ "page": 1, "total_pages": 1, "results": [] }"""
                    url.contains("/v1/trending/tv/week") -> """{ "page": 1, "total_pages": 1, "results": [] }"""
                    url.contains("/v1/discover/movie") -> """{ "page": 1, "total_pages": 1, "results": [] }"""
                    url.contains("/v1/movie/11") -> """
                        {
                          "id": 11,
                          "title": "Daily Trending Movie",
                          "release_date": "2026-10-01",
                          "watch/providers": {
                            "results": {
                              "US": {
                                "link": "https://www.themoviedb.org/movie/11/watch",
                                "flatrate": [{ "provider_name": "Netflix" }]
                              }
                            }
                          }
                        }
                    """.trimIndent()
                    url.contains("/v1/movie/12") -> """
                        {
                          "id": 12,
                          "title": "Weekly Trending Movie",
                          "release_date": "2026-10-01",
                          "watch/providers": {
                            "results": {
                              "US": {
                                "link": "https://www.themoviedb.org/movie/12/watch",
                                "flatrate": [{ "provider_name": "Max" }]
                              }
                            }
                          }
                        }
                    """.trimIndent()
                    else -> "{}"
                }
                jsonResponse(req, 200, body)
            })
            .build()

        val repo = MetadataRepository(mockClient)
        val dayBundle = repo.getTrendingFeed(
            preferences = MediaPreferences(
                tmdbProxyUrl = "https://mixmedia-tmdb-proxy.test.workers.dev",
                trendingWindow = TrendingTimeWindow.DAY,
                searchLanguage = SearchLanguagePreference.EN,
            ),
            persistence = null,
            forceRefresh = false,
            nowMillis = 1_795_500_000_000L,
        )
        assertEquals(TrendingTimeWindow.DAY, dayBundle.window)
        assertEquals(listOf("Daily Trending Movie"), dayBundle.movies.map { it.title })

        val weekBundle = repo.getTrendingFeed(
            preferences = MediaPreferences(
                tmdbProxyUrl = "https://mixmedia-tmdb-proxy.test.workers.dev",
                trendingWindow = TrendingTimeWindow.WEEK,
                searchLanguage = SearchLanguagePreference.EN,
            ),
            persistence = null,
            forceRefresh = false,
            nowMillis = 1_795_500_060_000L,
        )
        assertEquals(TrendingTimeWindow.WEEK, weekBundle.window)
        assertEquals(listOf("Weekly Trending Movie"), weekBundle.movies.map { it.title })
        assertTrue(requestedUrls.any { it.contains("/v1/trending/movie/day") })
        assertTrue(requestedUrls.any { it.contains("/v1/trending/movie/week") })
    }

    @Test
    fun `discover filters, genres, strict regional watch providers, and health diagnostics work via proxy`() = runBlocking {
        val requestedUrls = CopyOnWriteArrayList<String>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                requestedUrls.add(url)
                assertNull(req.header("Authorization"))
                assertFalse(url.contains("api_key", ignoreCase = true))

                val body = when {
                    url.contains("/health") -> """
                        {
                          "ok": true,
                          "service": "mixmedia-tmdb-proxy",
                          "tmdbConfigured": true,
                          "authMode": "bearer"
                        }
                    """.trimIndent()

                    url.contains("/v1/genres/movie") -> """
                        { "genres": [{ "id": 878, "name": "Science Fiction" }, { "id": 18, "name": "Drama" }] }
                    """.trimIndent()

                    url.contains("/v1/genres/tv") -> """
                        { "genres": [{ "id": 10765, "name": "Sci-Fi & Fantasy" }, { "id": 18, "name": "Drama" }] }
                    """.trimIndent()

                    url.contains("/v1/discover/movie") -> """
                        {
                          "page": 1,
                          "total_pages": 2,
                          "total_results": 25,
                          "results": [
                            { "id": 501, "title": "Filtered Sci-Fi Movie", "release_date": "2024-03-01", "vote_average": 8.4 }
                          ]
                        }
                    """.trimIndent()

                    url.contains("/v1/movie/501/watch/providers") -> """
                        {
                          "id": 501,
                          "region": "GB",
                          "attribution": "Watch provider availability powered by JustWatch via TMDB.",
                          "results": {
                            "GB": {
                              "link": "https://www.themoviedb.org/movie/501/watch?locale=GB",
                              "flatrate": [{ "provider_name": "BBC iPlayer" }],
                              "rent": [{ "provider_name": "Sky Store" }]
                            },
                            "US": {
                              "link": "https://www.themoviedb.org/movie/501/watch?locale=US",
                              "flatrate": [{ "provider_name": "Hulu" }]
                            }
                          }
                        }
                    """.trimIndent()

                    else -> "{}"
                }
                jsonResponse(req, 200, body)
            })
            .build()

        val repo = MetadataRepository(mockClient)
        val prefs = MediaPreferences(
            tmdbProxyUrl = "https://mixmedia-tmdb-proxy.test.workers.dev",
            watchRegion = "GB",
            searchLanguage = SearchLanguagePreference.EN,
        )

        val health = repo.testBackendConnection(prefs.tmdbProxyUrl)
        assertTrue(health.isConfigured)
        assertEquals(true, health.isReachable)
        assertEquals(true, health.tmdbSecretConfigured)
        assertEquals("bearer", health.authMode)
        assertEquals(200, health.httpStatus)

        val genres = repo.getAvailableGenres(prefs)
        assertEquals(listOf("Drama", "Sci-Fi & Fantasy", "Science Fiction"), genres.map { it.name })

        val discoverPage = repo.searchAndDiscoverMedia(
            rawQuery = "",
            mediaTypeFilter = MediaType.MOVIE,
            filters = TmdbDiscoverFilters(genreId = 878, minRating = 7.5, releaseYear = 2024),
            page = 1,
            preferences = prefs,
        ).getOrThrow()
        assertTrue(discoverPage.usedTmdbBackend)
        assertEquals(2, discoverPage.totalPages)
        assertEquals("Filtered Sci-Fi Movie", discoverPage.items.single().title)
        val discoverUrl = requestedUrls.first { it.contains("/v1/discover/movie") }
        assertTrue(discoverUrl.contains("with_genres=878"))
        assertTrue(discoverUrl.contains("vote_average_gte=7.5"))
        assertTrue(discoverUrl.contains("year=2024"))
        assertTrue(discoverUrl.contains("region=GB"))

        val (providers, link) = repo.getWatchProvidersForRegion(
            summary = MediaSearchResult(id = "tmdb:movie:501", mediaType = MediaType.MOVIE, title = "Filtered Sci-Fi Movie", tmdbId = 501),
            region = "GB",
            preferences = prefs,
        )
        assertEquals(2, providers.size)
        assertEquals(listOf("BBC iPlayer", "Sky Store"), providers.map { it.name })
        assertEquals(ProviderCategory.STREAMING, providers[0].category)
        assertEquals(ProviderCategory.RENTAL_PURCHASE, providers[1].category)
        assertEquals("GB", providers[0].region)
        assertEquals("https://www.themoviedb.org/movie/501/watch?locale=GB", link)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `viewmodel paginates discover results cleanly via loadNextSearchPage`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directExecutor = object : java.util.concurrent.AbstractExecutorService() {
                @Volatile private var shutdown = false
                override fun shutdown() { shutdown = true }
                override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
                override fun isShutdown(): Boolean = shutdown
                override fun isTerminated(): Boolean = shutdown
                override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
                override fun execute(command: Runnable) { command.run() }
            }
            val mockClient = OkHttpClient.Builder()
                .dispatcher(okhttp3.Dispatcher(directExecutor))
                .addInterceptor(Interceptor { chain ->
                    val req = chain.request()
                    val url = req.url.toString()
                    val body = when {
                        url.contains("/v1/discover/movie") && url.contains("page=1") -> """
                            {
                              "page": 1,
                              "total_pages": 2,
                              "total_results": 2,
                              "results": [{ "id": 701, "title": "Page One Hit", "release_date": "2025-02-01" }]
                            }
                        """.trimIndent()
                        url.contains("/v1/discover/movie") && url.contains("page=2") -> """
                            {
                              "page": 2,
                              "total_pages": 2,
                              "total_results": 2,
                              "results": [{ "id": 702, "title": "Page Two Hit", "release_date": "2025-03-01" }]
                            }
                        """.trimIndent()
                        else -> "{}"
                    }
                    jsonResponse(req, 200, body)
                })
                .build()

            val vm = MovieDiscoveryViewModel(
                metadataRepo = MetadataRepository(client = mockClient, ioDispatcher = dispatcher)
            )
            vm.updatePreferences {
                it.copy(tmdbProxyUrl = "https://mixmedia-tmdb-proxy.test.workers.dev")
            }
            vm.setTypeFilter(MediaType.MOVIE)
            vm.updateDiscoverFilters { TmdbDiscoverFilters(minRating = 7.0) }
            advanceUntilIdle()

            assertEquals(1, vm.state.value.currentPage)
            assertEquals(2, vm.state.value.totalPages)
            assertTrue(vm.state.value.hasMorePages)
            assertEquals(listOf("Page One Hit"), vm.state.value.searchResults.map { it.title })

            vm.loadNextSearchPage()
            advanceUntilIdle()

            assertEquals(2, vm.state.value.currentPage)
            assertFalse(vm.state.value.hasMorePages)
            assertEquals(listOf("Page One Hit", "Page Two Hit"), vm.state.value.searchResults.map { it.title })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `proxy failure falls back cleanly to public Apple TV and TVmaze feeds`() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                when {
                    url.contains("mixmedia-tmdb-proxy.test.workers.dev") ->
                        jsonResponse(req, 502, """{"error":"Upstream error"}""")
                    url.contains("itunes.apple.com/us/rss/topmovies") ->
                        jsonResponse(
                            req,
                            200,
                            """
                            {
                              "feed": {
                                "entry": [
                                  {
                                    "id": { "label": "https://tv.apple.com/us/movie/safe/umc.1", "attributes": { "im:id": "99" } },
                                    "im:name": { "label": "Fallback Apple Movie" },
                                    "im:releaseDate": { "label": "2026-09-01T00:00:00-07:00" }
                                  }
                                ]
                              }
                            }
                            """.trimIndent()
                        )
                    url.contains("api.tvmaze.com/shows?page=0") ->
                        jsonResponse(req, 200, """[]""")
                    else -> jsonResponse(req, 404, "{}")
                }
            })
            .build()

        val repo = MetadataRepository(mockClient)
        val bundle = repo.getTrendingFeed(
            preferences = MediaPreferences(tmdbProxyUrl = "https://mixmedia-tmdb-proxy.test.workers.dev"),
            persistence = null,
            forceRefresh = true,
            nowMillis = 1_795_500_000_000L,
        )
        assertTrue(bundle.isUsingFallbackSources)
        assertEquals(listOf("Fallback Apple Movie"), bundle.movies.map { it.title })
        assertNotNull(repo)
    }

    @Test
    fun `credential provider masks and decodes tmdb secrets without plaintext exposure and ignores placeholders`() {
        val sampleToken = "token-read-sample-9988"
        val sampleKey = "key_sample_4455"
        val maskBytes = ByteArray(16) { ((it * 37 + 19) and 0xFF).toByte() }
        val maskHex = maskBytes.joinToString("") { "%02x".format(it) }
        val tokenBlob = TmdbCredentialProvider.encodeMaskedHex(sampleToken, maskBytes)
        val keyBlob = TmdbCredentialProvider.encodeMaskedHex(sampleKey, maskBytes)

        assertFalse(tokenBlob.contains(sampleToken))
        assertFalse(keyBlob.contains(sampleKey))

        val provider = TmdbCredentialProvider(
            tokenBlobHex = tokenBlob,
            keyBlobHex = keyBlob,
            maskHex = maskHex,
        )
        assertTrue(provider.hasDirectCredentials())
        assertEquals("bearer", provider.authMode())
        assertEquals(sampleToken, provider.readAccessToken())
        assertEquals(sampleKey, provider.apiKey())

        val placeholderProvider = TmdbCredentialProvider(
            tokenBlobHex = TmdbCredentialProvider.encodeMaskedHex("MY_TMDB_API_READ_ACCESS_TOKEN", maskBytes),
            keyBlobHex = TmdbCredentialProvider.encodeMaskedHex("MY_TMDB_API_KEY", maskBytes),
            maskHex = maskHex,
        )
        assertFalse(placeholderProvider.hasDirectCredentials())
        assertEquals("none", placeholderProvider.authMode())
    }

    @Test
    fun `direct tmdb mode connects to api themoviedb org without worker proxy`() = runBlocking {
        val sampleToken = "direct-tmdb-bearer-token-test"
        val maskBytes = ByteArray(16) { ((it * 29 + 7) and 0xFF).toByte() }
        val maskHex = maskBytes.joinToString("") { "%02x".format(it) }
        val provider = TmdbCredentialProvider(
            tokenBlobHex = TmdbCredentialProvider.encodeMaskedHex(sampleToken, maskBytes),
            keyBlobHex = "",
            maskHex = maskHex,
        )

        val requestedUrls = CopyOnWriteArrayList<String>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                requestedUrls.add(url)
                if (req.url.host == "api.themoviedb.org") {
                    assertEquals("Bearer $sampleToken", req.header("Authorization"))
                } else {
                    assertNull("Non-TMDB hosts must never receive TMDB Authorization header", req.header("Authorization"))
                    assertFalse(url.contains(sampleToken))
                    return@Interceptor jsonResponse(req, 404, "{}")
                }

                val body = when {
                    url.contains("/3/configuration") -> """{"images":{"secure_base_url":"https://image.tmdb.org/t/p/"}}"""
                    url.contains("/3/genre/movie/list") -> """{"genres":[{"id":28,"name":"Action"}]}"""
                    url.contains("/3/genre/tv/list") -> """{"genres":[{"id":18,"name":"Drama"}]}"""
                    url.contains("/3/discover/movie") -> """
                        {
                          "page": 1,
                          "total_pages": 1,
                          "total_results": 1,
                          "results": [
                            { "id": 808, "title": "Direct TMDB Film", "release_date": "2025-04-01", "vote_average": 8.1 }
                          ]
                        }
                    """.trimIndent()
                    url.contains("/3/find/tt0816692") -> """
                        {
                          "movie_results": [{ "id": 157336, "title": "Interstellar" }],
                          "tv_results": []
                        }
                    """.trimIndent()
                    url.contains("/3/movie/157336") -> """
                        {
                          "id": 157336,
                          "title": "Interstellar",
                          "release_date": "2014-11-05",
                          "vote_average": 8.4,
                          "external_ids": { "imdb_id": "tt0816692" }
                        }
                    """.trimIndent()
                    else -> "{}"
                }
                jsonResponse(req, 200, body)
            })
            .build()

        val repo = MetadataRepository(client = mockClient, credentialProvider = provider)
        val blankPrefs = MediaPreferences(tmdbProxyUrl = "", watchRegion = "US", searchLanguage = SearchLanguagePreference.EN)

        assertTrue(repo.isUsingDirectTmdb(blankPrefs))
        assertTrue(repo.isTmdbBackendConfigured(blankPrefs))

        val diag = repo.testBackendConnection("")
        assertTrue(diag.isConfigured)
        assertEquals(true, diag.isReachable)
        assertEquals("api.themoviedb.org (Direct)", diag.endpointHost)
        assertEquals("bearer", diag.authMode)
        assertEquals(true, diag.tmdbSecretConfigured)

        val genres = repo.getAvailableGenres(blankPrefs)
        assertEquals(listOf("Action", "Drama"), genres.map { it.name })

        val discover = repo.searchAndDiscoverMedia(
            rawQuery = "",
            mediaTypeFilter = MediaType.MOVIE,
            filters = TmdbDiscoverFilters(genreId = 28, minRating = 7.5, releaseYear = 2025),
            page = 1,
            preferences = blankPrefs,
        ).getOrThrow()
        assertTrue(discover.usedTmdbBackend)
        assertEquals("Direct TMDB Film", discover.items.single().title)

        val discoverUrl = requestedUrls.first { it.contains("/3/discover/movie") }
        assertTrue(discoverUrl.contains("vote_average.gte=7.5"))
        assertTrue(discoverUrl.contains("with_genres=28"))
        assertTrue(discoverUrl.contains("year=2025"))

        val details = repo.getMediaDetails(
            summary = MediaSearchResult(
                id = "imdb:tt0816692",
                mediaType = MediaType.MOVIE,
                title = "Interstellar",
                imdbId = "tt0816692",
            ),
            preferences = blankPrefs,
        ).getOrThrow()
        assertEquals("Interstellar", details.title)
        assertTrue(requestedUrls.any { it.contains("/3/find/tt0816692") && it.contains("external_source=imdb_id") })
        assertTrue(requestedUrls.any { it.contains("/3/movie/157336") })
    }
}
