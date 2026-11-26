package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.media.DigitalReleaseStatus
import com.example.media.MediaPersistence
import com.example.media.MediaPreferences
import com.example.media.MediaType
import com.example.media.MetadataRepository
import com.example.media.ProviderCategory
import com.example.media.SearchLanguagePreference
import com.example.media.TrendingTimeWindow
import com.example.media.TvReleaseStatus
import com.example.network.CurlRedirectResolver
import com.example.network.YouTubeExtractor
import com.example.ui.AppLanguage
import com.example.ui.ErrorType
import com.example.ui.ExtractorUiState
import com.example.ui.LinkExtractorViewModel
import com.example.ui.stringsFor
import java.util.concurrent.atomic.AtomicInteger
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
class TrendingAndComplianceTest {

    @Test
    fun `tmdb trending movies filters out theatrical only releases and maps legal providers`() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                assertNull("Android client must never send Authorization headers to proxy", req.header("Authorization"))
                assertFalse("Android client must never send api_key query param", url.contains("api_key", ignoreCase = true))
                assertFalse("Android client must never call api.themoviedb.org directly", url.contains("api.themoviedb.org"))
                val body = when {
                    url.contains("/v1/trending/movie/week") -> """
                        {
                          "page": 1,
                          "total_pages": 1,
                          "total_results": 4,
                          "results": [
                            { "id": 101 },
                            { "id": 102 },
                            { "id": 103 },
                            { "id": 104 }
                          ]
                        }
                    """.trimIndent()

                    url.contains("/v1/discover/movie") -> """{ "page": 1, "total_pages": 1, "results": [] }"""

                    // 101: Theatrical only (type 3) + no watch/providers -> MUST be excluded
                    url.contains("/v1/movie/101") -> """
                        {
                          "id": 101,
                          "title": "Cinema Only Film",
                          "release_date": "2026-10-15",
                          "release_dates": {
                            "results": [
                              {
                                "iso_3166_1": "US",
                                "release_dates": [
                                  { "type": 3, "release_date": "2026-10-15T00:00:00.000Z" }
                                ]
                              }
                            ]
                          },
                          "watch/providers": { "results": {} }
                        }
                    """.trimIndent()

                    // 102: Confirmed digital release date (type 4 <= today) + Netflix
                    url.contains("/v1/movie/102") -> """
                        {
                          "id": 102,
                          "title": "Streaming Hit",
                          "release_date": "2026-09-01",
                          "runtime": 118,
                          "vote_average": 7.9,
                          "genres": [{ "name": "Sci-Fi" }],
                          "release_dates": {
                            "results": [
                              {
                                "iso_3166_1": "US",
                                "release_dates": [
                                  { "type": 4, "release_date": "2026-10-05T00:00:00.000Z" }
                                ]
                              }
                            ]
                          },
                          "watch/providers": {
                            "results": {
                              "US": {
                                "link": "https://www.themoviedb.org/movie/102/watch",
                                "flatrate": [{ "provider_name": "Netflix", "logo_path": "/nf.jpg" }],
                                "rent": [{ "provider_name": "Apple TV", "logo_path": "/atv.jpg" }]
                              }
                            }
                          }
                        }
                    """.trimIndent()

                    // 103: Future digital date (2099) + no providers -> MUST be excluded
                    url.contains("/v1/movie/103") -> """
                        {
                          "id": 103,
                          "title": "Future Release",
                          "release_date": "2026-11-20",
                          "release_dates": {
                            "results": [
                              {
                                "iso_3166_1": "US",
                                "release_dates": [
                                  { "type": 4, "release_date": "2099-01-10T00:00:00.000Z" }
                                ]
                              }
                            ]
                          },
                          "watch/providers": { "results": {} }
                        }
                    """.trimIndent()

                    // 104: No release_dates digital entry, but has verified watch/providers -> MUST be included
                    url.contains("/v1/movie/104") -> """
                        {
                          "id": 104,
                          "title": "Provider Confirmed Movie",
                          "release_date": "2026-08-12",
                          "watch/providers": {
                            "results": {
                              "US": {
                                "link": "https://www.themoviedb.org/movie/104/watch",
                                "flatrate": [{ "provider_name": "Prime Video", "logo_path": "/pv.jpg" }]
                              }
                            }
                          }
                        }
                    """.trimIndent()

                    url.contains("/v1/trending/tv/week") -> """{ "page": 1, "total_pages": 1, "results": [{ "id": 201 }] }"""

                    url.contains("/v1/tv/201") -> """
                        {
                          "id": 201,
                          "name": "Dark Matter Series",
                          "first_air_date": "2024-05-08",
                          "status": "Returning Series",
                          "number_of_seasons": 2,
                          "next_episode_to_air": { "episode_number": 4 },
                          "watch/providers": {
                            "results": {
                              "US": {
                                "link": "https://www.themoviedb.org/tv/201/watch",
                                "flatrate": [{ "provider_name": "Apple TV+", "logo_path": "/atvp.jpg" }]
                              }
                            }
                          }
                        }
                    """.trimIndent()

                    else -> "{}"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val repo = MetadataRepository(mockClient)
        val bundle = repo.getTrendingFeed(
            preferences = MediaPreferences(
                tmdbProxyUrl = "https://proxy.example.workers.dev",
                trendingWindow = TrendingTimeWindow.WEEK,
                searchLanguage = SearchLanguagePreference.EN,
            ),
            persistence = null,
            forceRefresh = true,
            nowMillis = 1795500000000L, // 2026-11
        )

        assertFalse(bundle.isUsingFallbackSources)
        assertEquals(2, bundle.movies.size)
        assertEquals(listOf("Streaming Hit", "Provider Confirmed Movie"), bundle.movies.map { it.title })

        val hit = bundle.movies[0]
        assertEquals(DigitalReleaseStatus.CONFIRMED_DIGITAL, hit.digitalReleaseStatus)
        assertEquals("2026-10-05", hit.digitalReleaseDate)
        assertEquals(2, hit.watchProviders.size)
        assertEquals(ProviderCategory.STREAMING, hit.watchProviders[0].category)
        assertEquals("Netflix", hit.watchProviders[0].name)
        assertEquals(ProviderCategory.RENTAL_PURCHASE, hit.watchProviders[1].category)
        assertEquals("https://www.themoviedb.org/movie/102/watch", hit.watchProvidersPageUrl)

        assertEquals(1, bundle.tvShows.size)
        assertEquals(TvReleaseStatus.CURRENTLY_AIRING, bundle.tvShows[0].tvReleaseStatus)
        assertEquals(2, bundle.tvShows[0].seasonCount)
    }

    @Test
    fun `tmdb trending tv status parser distinguishes airing, season completed, upcoming, and ended`() {
        val repo = MetadataRepository()

        val completedSeasonJson = """
            {
              "id": 301,
              "name": "Completed Season Show",
              "first_air_date": "2023-01-15",
              "status": "Returning Series",
              "number_of_seasons": 3,
              "last_episode_to_air": { "episode_number": 10 },
              "next_episode_to_air": null
            }
        """.trimIndent()
        val completed = repo.parseTmdbTvTrendingDetail(completedSeasonJson, null)
        assertNotNull(completed)
        assertEquals(TvReleaseStatus.SEASON_COMPLETED, completed?.tvReleaseStatus)
        assertEquals(3, completed?.seasonCount)

        val endedJson = """
            {
              "id": 302,
              "name": "Finished Show",
              "first_air_date": "2018-03-01",
              "status": "Ended",
              "number_of_seasons": 5
            }
        """.trimIndent()
        val ended = repo.parseTmdbTvTrendingDetail(endedJson, null)
        assertEquals(TvReleaseStatus.ENDED, ended?.tvReleaseStatus)

        val upcomingJson = """
            {
              "id": 303,
              "name": "Upcoming Show",
              "first_air_date": "2027-01-01",
              "status": "In Production",
              "number_of_seasons": 1
            }
        """.trimIndent()
        val upcoming = repo.parseTmdbTvTrendingDetail(upcomingJson, null)
        assertEquals(TvReleaseStatus.UPCOMING, upcoming?.tvReleaseStatus)
    }

    @Test
    fun `zero key fallback loads apple top movies and tvmaze trending tv with 30 min cache ttl`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val persistence = MediaPersistence(context)
        persistence.clearMediaCache()

        val networkHits = AtomicInteger(0)
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                networkHits.incrementAndGet()
                val req = chain.request()
                val url = req.url.toString()
                val body = when {
                    url.contains("itunes.apple.com/us/rss/topmovies") -> """
                        {
                          "feed": {
                            "entry": [
                              {
                                "id": {
                                  "label": "https://tv.apple.com/us/movie/sample-one/umc.cmc.1",
                                  "attributes": { "im:id": "1001" }
                                },
                                "im:name": { "label": "Apple Movie One" },
                                "im:image": [
                                  { "label": "http://is1-ssl.mzstatic.com/image/thumb/100x100.jpg" },
                                  { "label": "http://is1-ssl.mzstatic.com/image/thumb/600x600.jpg" }
                                ],
                                "im:releaseDate": { "label": "2026-10-01T00:00:00-07:00" },
                                "category": { "attributes": { "label": "Action & Adventure" } },
                                "summary": { "label": "Digital hit movie" }
                              }
                            ]
                          }
                        }
                    """.trimIndent()

                    url.contains("api.tvmaze.com/shows?page=0") -> """
                        [
                          {
                            "id": 77,
                            "url": "https://www.tvmaze.com/shows/77/fallback-show",
                            "officialSite": "https://www.hbo.com/fallback-show",
                            "name": "Fallback Series",
                            "status": "Running",
                            "premiered": "2022-04-10",
                            "averageRuntime": 52,
                            "weight": 98,
                            "rating": { "average": 8.7 },
                            "genres": ["Drama"],
                            "network": { "name": "HBO" },
                            "image": { "medium": "https://static.tvmaze.com/uploads/images/medium_portrait/1.jpg" },
                            "externals": { "imdb": "tt1234567" }
                          }
                        ]
                    """.trimIndent()

                    url.contains("api.tvmaze.com/shows/77/seasons") -> """[{"id":1},{"id":2},{"id":3}]"""
                    else -> "{}"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val repo = MetadataRepository(mockClient)
        val t0 = 1_700_000_000_000L
        val first = repo.getTrendingFeed(
            preferences = MediaPreferences(tmdbProxyUrl = ""),
            persistence = persistence,
            forceRefresh = false,
            nowMillis = t0,
        )

        assertTrue(first.isUsingFallbackSources)
        assertEquals(1, first.movies.size)
        assertEquals(MediaType.MOVIE, first.movies[0].mediaType)
        assertEquals(DigitalReleaseStatus.CONFIRMED_DIGITAL, first.movies[0].digitalReleaseStatus)
        assertEquals("Apple TV", first.movies[0].watchProviders.firstOrNull()?.name)
        assertEquals(1, first.tvShows.size)
        assertEquals(TvReleaseStatus.CURRENTLY_AIRING, first.tvShows[0].tvReleaseStatus)
        assertEquals(3, first.tvShows[0].seasonCount)
        assertEquals("HBO", first.tvShows[0].watchProviders.firstOrNull()?.name)

        val hitsAfterFirst = networkHits.get()
        assertEquals(3, hitsAfterFirst)

        // Within 30-min cache window: should hit memory/disk cache without network calls
        val repoReloaded = MetadataRepository(mockClient)
        val cached = repoReloaded.getTrendingFeed(
            preferences = MediaPreferences(tmdbProxyUrl = ""),
            persistence = persistence,
            forceRefresh = false,
            nowMillis = t0 + 10L * 60L * 1000L,
        )
        assertEquals(hitsAfterFirst, networkHits.get())
        assertEquals("Apple Movie One", cached.movies.firstOrNull()?.title)

        // Manual refresh bypasses 30-min cache and updates timestamp
        val t1 = t0 + 5L * 60L * 1000L
        val refreshed = repoReloaded.getTrendingFeed(
            preferences = MediaPreferences(tmdbProxyUrl = ""),
            persistence = persistence,
            forceRefresh = true,
            nowMillis = t1,
        )
        assertTrue(networkHits.get() > hitsAfterFirst)
        assertEquals(t1, refreshed.lastUpdatedMillis)

        persistence.clearMediaCache()
    }

    @Test
    fun `en and fa localization strings cover trending, watch providers, settings, and compliant youtube ui`() {
        for (lang in AppLanguage.entries) {
            val s = stringsFor(lang)
            assertTrue(s.errorYouTubeUnavailable.isNotBlank())
            assertTrue(s.ytCardBadge.isNotBlank())
            assertTrue(s.ytOpenOfficialApp.isNotBlank())
            assertTrue(s.ytCreatorBackupsTitle.isNotBlank())
            assertTrue(s.ytOpenStudio.isNotBlank())
            assertTrue(s.ytOpenTakeout.isNotBlank())
            assertTrue(s.ytAuthorizedUrlLabel.isNotBlank())
            assertTrue(s.ytAuthorizedDownloadUnavailable.isNotBlank())
            assertTrue(s.ytPremiumOfflineNote.isNotBlank())
            assertTrue(s.ytDurationLabel("03:15").isNotBlank())

            assertTrue(s.discover.trendingSectionTitle.isNotBlank())
            assertTrue(s.discover.trendingMoviesTitle.isNotBlank())
            assertTrue(s.discover.trendingTvTitle.isNotBlank())
            assertTrue(s.discover.trendingWindowDay.isNotBlank())
            assertTrue(s.discover.trendingWindowWeek.isNotBlank())
            assertTrue(s.discover.trendingRefreshAction.isNotBlank())
            assertTrue(s.discover.trendingLastUpdated("12:00").isNotBlank())
            assertTrue(s.discover.trendingFallbackNotice.isNotBlank())
            assertFalse(s.discover.trendingFallbackNotice.contains("API key", ignoreCase = true))
            assertTrue(s.discover.watchRegionLabel.isNotBlank())
            assertTrue(s.discover.justWatchAttributionText.contains("JustWatch"))
            assertTrue(
                s.discover.tmdbAttributionText.contains(
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."
                )
            )
            assertTrue(s.discover.filterGenreLabel.isNotBlank())
            assertTrue(s.discover.filterMinRatingLabel.isNotBlank())
            assertTrue(s.discover.filterYearLabel.isNotBlank())
            assertTrue(s.discover.actionLoadMore.isNotBlank())
            assertTrue(s.discover.statusDigitalReleased.isNotBlank())
            assertTrue(s.discover.statusTvAiring.isNotBlank())
            assertTrue(s.discover.statusTvSeasonCompleted.isNotBlank())
            assertTrue(s.discover.statusTvUpcoming.isNotBlank())
            assertTrue(s.discover.statusTvEnded.isNotBlank())
            assertTrue(s.discover.whereToWatchTitle.isNotBlank())
            assertTrue(s.discover.whereToWatchOpenAll.isNotBlank())
            assertTrue(s.discover.providerCatStreaming.isNotBlank())
            assertTrue(s.discover.providerCatRentBuy.isNotBlank())
            assertTrue(s.discover.providerCatFree.isNotBlank())
            assertTrue(s.discover.providerCatOfficial.isNotBlank())
            assertTrue(s.discover.authorizedDirectDownloadAction.isNotBlank())

            assertTrue(s.discover.youtubeKeyLabel.isNotBlank())
            assertTrue(s.discover.youtubeKeyHelper.isNotBlank())

            assertTrue(s.ui.sectionNavigationLayout.isNotBlank())
            assertTrue(s.ui.separateTabsTitle.isNotBlank())
            assertTrue(s.ui.separateTabsDesc.isNotBlank())
            assertTrue(s.ui.unifiedModeBadge.isNotBlank())
            assertTrue(s.ui.separateModeBadge.isNotBlank())
            assertTrue(s.ui.unifiedHeroTitle.isNotBlank())
            assertTrue(s.ui.unifiedHeroSubtitle.isNotBlank())
            assertTrue(s.ui.unifiedSmartPlaceholder.isNotBlank())
            assertTrue(s.ui.unifiedActionResolve.isNotBlank())
            assertTrue(s.ui.unifiedQuickBrowser.isNotBlank())
            assertTrue(s.ui.unifiedQuickResolverTools.isNotBlank())
            assertTrue(s.ui.unifiedHideResolverTools.isNotBlank())
            assertTrue(s.ui.unifiedInlineResolverTitle.isNotBlank())
            assertTrue(s.ui.unifiedDismissResult.isNotBlank())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `viewmodel resolves compliant youtube metadata and reports unavailable videos cleanly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val req = chain.request()
                    val url = req.url.toString()
                    when {
                        url.contains("youtube.com/oembed") && url.contains("UHrrwnJ4hUI") -> Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                """
                                {
                                  "title": "Official Compliant Video",
                                  "author_name": "Creator Channel",
                                  "author_url": "https://www.youtube.com/@creator",
                                  "thumbnail_url": "https://i.ytimg.com/vi/UHrrwnJ4hUI/hqdefault.jpg"
                                }
                                """.trimIndent().toResponseBody("application/json".toMediaType())
                            )
                            .build()

                        url.contains("youtube.com/oembed") -> Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("Not Found".toResponseBody("text/plain".toMediaType()))
                            .build()

                        else -> Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("".toResponseBody(null))
                            .build()
                    }
                })
                .build()

            val resolver = CurlRedirectResolver(
                client = mockClient,
                youtubeExtractor = YouTubeExtractor(client = mockClient, ioDispatcher = dispatcher),
                ioDispatcher = dispatcher,
            )
            val vm = LinkExtractorViewModel(resolver = resolver)

            vm.onInputChanged("https://youtu.be/UHrrwnJ4hUI")
            vm.executeExtraction()
            advanceUntilIdle()

            val success = vm.screenState.value.uiState as ExtractorUiState.Success
            assertNull(success.result.directVideoUrl)
            assertNotNull(success.result.youtubeInfo)
            assertEquals("Official Compliant Video", success.result.youtubeInfo?.title)
            assertEquals("https://www.youtube.com/watch?v=UHrrwnJ4hUI", success.result.youtubeInfo?.canonicalUrl)
            assertEquals(1, vm.screenState.value.sessionHistory.size)

            vm.onInputChanged("https://www.youtube.com/watch?v=UHrrwnJ4hUI")
            vm.executeExtraction()
            advanceUntilIdle()
            assertEquals(1, vm.screenState.value.sessionHistory.size)

            vm.onInputChanged("https://www.youtube.com/watch?v=aaaaaaaaaaa")
            vm.executeExtraction()
            advanceUntilIdle()
            val error = vm.screenState.value.uiState as ExtractorUiState.Error
            assertEquals(ErrorType.YOUTUBE_UNAVAILABLE, error.errorType)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
