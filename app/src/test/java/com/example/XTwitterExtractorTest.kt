package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.download.DownloadEngine
import com.example.intent.VlcIntentHelper
import com.example.network.CurlRedirectResolver
import com.example.network.XExtractionError
import com.example.network.XTwitterExtractor
import com.example.ui.AppDestination
import com.example.ui.AppLanguage
import com.example.ui.ErrorType
import com.example.ui.ExtractorUiState
import com.example.ui.HomeInputAction
import com.example.ui.LinkExtractorViewModel
import com.example.ui.classifyHomeInput
import com.example.ui.stringsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XTwitterExtractorTest {

    @Test
    fun `validates supported x and twitter status urls and rejects unrelated domains`() {
        val validUrls = listOf(
            "https://x.com/NASA/status/1823456789012345678?s=20",
            "https://www.x.com/AndroidDev/status/1790123456789012345",
            "https://mobile.x.com/i/status/1790123456789012345",
            "https://twitter.com/jack/status/20",
            "https://www.twitter.com/i/web/status/1650123456789012345",
        )
        validUrls.forEach { url ->
            assertTrue("Expected valid X URL: $url", XTwitterExtractor.isXTwitterUrl(url))
            assertNotNull("Expected status reference for: $url", XTwitterExtractor.extractStatusReference(url))
        }

        val invalidUrls = listOf(
            "",
            "https://evilx.com/user/status/1234567890",
            "https://x.com.attacker.org/user/status/1234567890",
            "https://x.com/home",
            "https://twitter.com/explore",
            "https://instagram.com/reel/Df-HWOzsvcb/",
        )
        invalidUrls.forEach { url ->
            assertFalse("Expected invalid X URL: $url", XTwitterExtractor.isXTwitterUrl(url))
        }
    }

    @Test
    fun `android share intent extracts x url from shared post text`() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "NASA (@NASA) posted:\nhttps://x.com/NASA/status/1823456789012345678?s=20!",
            )
        }
        val extracted = VlcIntentHelper.extractUrlFromIntent(shareIntent)
        assertEquals("https://x.com/NASA/status/1823456789012345678?s=20", extracted)
        assertTrue(XTwitterExtractor.isXTwitterUrl(extracted!!))
    }

    @Test
    fun `syndication parser extracts sorted mp4 variants and honest photo or tombstone errors`() {
        val extractor = XTwitterExtractor()

        val videoPayload = """
            {
              "text": "Launch countdown clip",
              "user": { "name": "Space Agency", "screen_name": "space_agency" },
              "video": {
                "poster": "https://pbs.twimg.com/ext_tw_video_thumb/123/img/poster.jpg",
                "durationMs": 42500,
                "variants": [
                  { "type": "application/x-mpegURL", "src": "https://video.twimg.com/ext_tw_video/123/pu/pl/master.m3u8" },
                  { "type": "video/mp4", "src": "https://video.twimg.com/ext_tw_video/123/pu/vid/avc1/640x360/low.mp4" },
                  { "type": "video/mp4", "src": "https://video.twimg.com/ext_tw_video/123/pu/vid/avc1/1920x1080/full.mp4" },
                  { "type": "video/mp4", "src": "https://video.twimg.com/ext_tw_video/123/pu/vid/avc1/1280x720/hd.mp4" }
                ]
              }
            }
        """.trimIndent()

        val parsed = extractor.parseSyndicationJson(
            rawJson = videoPayload,
            tweetId = "1823456789012345678",
            canonicalUrl = "https://x.com/space_agency/status/1823456789012345678",
        )
        assertNull(parsed.error)
        assertEquals("Space Agency", parsed.authorName)
        assertEquals("space_agency", parsed.authorHandle)
        assertEquals(42L, parsed.durationSeconds)
        assertEquals(3, parsed.variants.size)
        assertEquals("1080p Full HD", parsed.variants[0].qualityLabel)
        assertEquals("720p HD", parsed.variants[1].qualityLabel)
        assertEquals("360p", parsed.variants[2].qualityLabel)
        assertTrue(parsed.variants[0].suggestedFileName.endsWith(".mp4"))

        val photoOnlyPayload = """
            {
              "text": "Just a photo post",
              "user": { "name": "PhotoUser", "screen_name": "photouser" },
              "mediaDetails": [
                { "type": "photo", "media_url_https": "https://pbs.twimg.com/media/abc.jpg" }
              ]
            }
        """.trimIndent()
        val photoRes = extractor.parseSyndicationJson(photoOnlyPayload, "12345", "https://x.com/photouser/status/12345")
        assertTrue(photoRes.variants.isEmpty())
        assertEquals(XExtractionError.NO_VIDEO_IN_POST, photoRes.error)

        val tombstonePayload = """{ "__typename": "TweetTombstone", "tombstone": {} }"""
        val tombstoneRes = extractor.parseSyndicationJson(tombstonePayload, "12345", "https://x.com/i/status/12345")
        assertTrue(tombstoneRes.variants.isEmpty())
        assertEquals(XExtractionError.UNAVAILABLE_OR_RESTRICTED, tombstoneRes.error)
    }

    @Test
    fun `fxtwitter and vxtwitter parsers extract mp4 formats and handle unavailable responses`() {
        val extractor = XTwitterExtractor()

        val fxPayload = """
            {
              "code": 200,
              "tweet": {
                "text": "Demo clip from FxTwitter",
                "author": { "name": "Dev", "screen_name": "dev" },
                "media": {
                  "videos": [
                    {
                      "duration": 15.2,
                      "thumbnail_url": "https://pbs.twimg.com/thumb.jpg",
                      "formats": [
                        { "container": "mp4", "bitrate": 832000, "width": 640, "height": 360, "url": "https://video.twimg.com/v_360.mp4" },
                        { "container": "mp4", "bitrate": 2176000, "width": 1280, "height": 720, "url": "https://video.twimg.com/v_720.mp4" }
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        val fxRes = extractor.parseFxTwitterJson(fxPayload, "99999", "https://x.com/dev/status/99999")
        assertNull(fxRes.error)
        assertEquals(2, fxRes.variants.size)
        assertEquals("720p HD", fxRes.variants[0].qualityLabel)
        assertEquals(2176, fxRes.variants[0].bitrateKbps)

        val vxPayload = """
            {
              "user_name": "VX Author",
              "user_screen_name": "vxauthor",
              "text": "VxTwitter sample",
              "media_extended": [
                {
                  "type": "video",
                  "url": "https://video.twimg.com/ext_tw_video/555/pu/vid/1280x720/clip.mp4",
                  "thumbnail_url": "https://pbs.twimg.com/vx_thumb.jpg",
                  "duration_millis": 12000,
                  "size": { "width": 1280, "height": 720 }
                }
              ]
            }
        """.trimIndent()
        val vxRes = extractor.parseVxTwitterJson(vxPayload, "55555", "https://x.com/vxauthor/status/55555")
        assertNull(vxRes.error)
        assertEquals(1, vxRes.variants.size)
        assertEquals("720p HD", vxRes.variants[0].qualityLabel)
        assertEquals(12L, vxRes.durationSeconds)

        val vxPrivate = """{ "error": "Tweet belongs to a protected account" }"""
        val vxErr = extractor.parseVxTwitterJson(vxPrivate, "77777", "https://x.com/i/status/77777")
        assertEquals(XExtractionError.UNAVAILABLE_OR_RESTRICTED, vxErr.error)
    }

    @Test
    fun `extractor falls back across public endpoints and probes mp4 file size`() = runTest {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                when {
                    url.contains("cdn.syndication.twimg.com") -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"__typename":"TweetTombstone"}""".toResponseBody("application/json".toMediaType()))
                        .build()

                    url.contains("api.fxtwitter.com") -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """
                            {
                              "code": 200,
                              "tweet": {
                                "text": "Fallback video",
                                "author": { "name": "MediaX User", "screen_name": "mediax" },
                                "media": {
                                  "videos": [
                                    {
                                      "duration": 20.0,
                                      "thumbnail_url": "https://pbs.twimg.com/thumb.jpg",
                                      "formats": [
                                        { "container": "mp4", "bitrate": 2176000, "width": 1280, "height": 720, "url": "https://video.twimg.com/fallback_720.mp4" }
                                      ]
                                    }
                                  ]
                                }
                              }
                            }
                            """.trimIndent().toResponseBody("application/json".toMediaType())
                        )
                        .build()

                    req.method == "HEAD" && url == "https://video.twimg.com/fallback_720.mp4" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Length", "5242880")
                        .body(ByteArray(0).toResponseBody(null))
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

        val extractor = XTwitterExtractor(mockClient)
        val result = extractor.extract("https://x.com/mediax/status/1823456789012345678")

        assertNull(result.error)
        assertEquals(1, result.variants.size)
        assertEquals("720p HD", result.variants[0].qualityLabel)
        assertEquals(5242880L, result.variants[0].contentLengthBytes)
        assertNotNull(result.variants[0].fileSizeFormatted)
    }

    @Test
    fun `classifyHomeInput distinguishes links and curl traces from movie or tv titles`() {
        assertEquals(HomeInputAction.OpenDiscover, classifyHomeInput("   "))

        val linkInputs = listOf(
            "https://x.com/NASA/status/1823456789012345678?s=20",
            "x.com/NASA/status/1823456789012345678",
            "twitter.com/jack/status/20",
            "vlc://https://cdn.example.com/movie.mp4",
            "curl -L -v https://example.com/stream",
            "myf2ms.top/film/interstellar",
            "dl.example.com/video.mp4",
        )
        linkInputs.forEach { raw ->
            val classified = classifyHomeInput(raw)
            assertTrue("Expected ResolveLink for: $raw, got $classified", classified is HomeInputAction.ResolveLink)
        }

        val mediaQueries = listOf(
            "Interstellar 2014",
            "برکینگ بد",
            "Dr. No",
            "Mr. Robot",
            "S.W.A.T",
            "Spider-Man: Across the Spider-Verse",
        )
        mediaQueries.forEach { query ->
            assertEquals(
                "Expected SearchMedia for: $query",
                HomeInputAction.SearchMedia(query),
                classifyHomeInput(query),
            )
        }
    }

    @Test
    fun `viewmodel routes x share intent and home link into unified resolver with quality picker`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val req = chain.request()
                    val url = req.url.toString()
                    when {
                        url.contains("cdn.syndication.twimg.com") && url.contains("id=999000111") -> Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                """
                                {
                                  "text": "Photo only post",
                                  "user": { "name": "PhotoUser", "screen_name": "photouser" },
                                  "mediaDetails": [
                                    { "type": "photo", "media_url_https": "https://pbs.twimg.com/media/abc.jpg" }
                                  ]
                                }
                                """.trimIndent().toResponseBody("application/json".toMediaType())
                            )
                            .build()

                        url.contains("cdn.syndication.twimg.com") -> Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                """
                                {
                                  "text": "Shared from X app",
                                  "user": { "name": "TestX", "screen_name": "testx" },
                                  "video": {
                                    "durationMs": 10000,
                                    "variants": [
                                      { "type": "video/mp4", "src": "https://video.twimg.com/ext_tw_video/1/pu/vid/1280x720/hi.mp4" },
                                      { "type": "video/mp4", "src": "https://video.twimg.com/ext_tw_video/1/pu/vid/640x360/lo.mp4" }
                                    ]
                                  }
                                }
                                """.trimIndent().toResponseBody("application/json".toMediaType())
                            )
                            .build()

                        req.method == "HEAD" -> Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Length", "1048576")
                            .body(ByteArray(0).toResponseBody(null))
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
                xTwitterExtractor = XTwitterExtractor(
                    client = mockClient,
                    ioDispatcher = dispatcher,
                ),
                ioDispatcher = dispatcher,
            )
            val vm = LinkExtractorViewModel(resolver = resolver)

            // 1. Validate photo-only X URL produces honest X_NO_VIDEO inside unified DISCOVER hub by default
            vm.submitLinkFromHome("https://x.com/photouser/status/999000111")
            advanceUntilIdle()
            assertEquals(AppDestination.DISCOVER, vm.screenState.value.destination)
            assertEquals(
                ErrorType.X_NO_VIDEO,
                (vm.screenState.value.uiState as ExtractorUiState.Error).errorType,
            )

            // 2. Validate schemeless X link submitted from unified bar normalizes and resolves
            vm.submitLinkFromHome("x.com/testx/status/1823456789012345678")
            advanceUntilIdle()
            assertEquals(AppDestination.DISCOVER, vm.screenState.value.destination)
            val homeSuccess = vm.screenState.value.uiState as ExtractorUiState.Success
            assertEquals(2, homeSuccess.result.xVideoVariants.size)

            // 3. Simulate Android Share Intent from X app routing into RESOLVER when classic tabs are enabled
            vm.setUseSeparateTabs(true)
            vm.handleIncomingExternalUrl("https://x.com/testx/status/1823456789012345678?s=20")
            advanceUntilIdle()

            val state = vm.screenState.value
            assertEquals(AppDestination.RESOLVER, state.destination)
            assertTrue(state.capturedFromExternalIntent)

            val success = state.uiState as ExtractorUiState.Success
            assertEquals(2, success.result.xVideoVariants.size)
            assertEquals(success.result.xVideoVariants[0].url, success.result.directVideoUrl)

            // 4. Open quality picker, pick lower quality variant, and enqueue in DownloadEngine
            vm.openXQualityPicker()
            assertNotNull(vm.screenState.value.pendingXResult)

            val lower = success.result.xVideoVariants[1]
            val updated = vm.selectXVariantOption(lower)
            assertNotNull(updated)
            assertNull(vm.screenState.value.pendingXResult)
            assertEquals(lower.url, updated?.directVideoUrl)
            assertEquals(
                lower.url,
                (vm.screenState.value.uiState as ExtractorUiState.Success).result.directVideoUrl,
            )

            val context = ApplicationProvider.getApplicationContext<Context>()
            val engine = DownloadEngine(context)
            val task = vm.enqueueResolvedDownload(engine, updated!!)
            assertNotNull(task)
            assertEquals(lower.url, task?.url)
            engine.pause(task!!.id)
            engine.remove(task.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `persian and english smart home, resolver, and adaptive icon resources resolve cleanly`() {
        val en = stringsFor(AppLanguage.EN)
        val fa = stringsFor(AppLanguage.FA)

        assertTrue(en.ui.smartHomePlaceholder.isNotBlank() && fa.ui.smartHomePlaceholder.isNotBlank())
        assertTrue(en.ui.smartHomeHelper.isNotBlank() && fa.ui.smartHomeHelper.isNotBlank())
        assertTrue(en.ui.smartHomeLinkDetected.isNotBlank() && fa.ui.smartHomeLinkDetected.isNotBlank())
        assertTrue(en.ui.smartHomeMediaDetected.isNotBlank() && fa.ui.smartHomeMediaDetected.isNotBlank())
        assertTrue(en.ui.actionChangeXQuality(2).isNotBlank() && fa.ui.actionChangeXQuality(2).isNotBlank())

        assertTrue(en.xDownloader.errorInvalidUrl.isNotBlank() && fa.xDownloader.errorInvalidUrl.isNotBlank())
        assertTrue(en.xDownloader.errorNoVideo.isNotBlank() && fa.xDownloader.errorNoVideo.isNotBlank())
        assertTrue(en.xDownloader.errorUnavailable.isNotBlank() && fa.xDownloader.errorUnavailable.isNotBlank())
        assertTrue(en.xDownloader.qualitySelectorTitle.isNotBlank() && fa.xDownloader.qualitySelectorTitle.isNotBlank())

        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(context.resources.getDrawable(R.mipmap.ic_launcher, null))
        assertNotNull(context.resources.getDrawable(R.mipmap.ic_launcher_round, null))
        assertNotNull(context.resources.getDrawable(R.drawable.ic_launcher_background, null))
        assertNotNull(context.resources.getDrawable(R.drawable.ic_launcher_foreground, null))
        assertNotNull(context.resources.getDrawable(R.drawable.mediax_icon_foreground, null))
    }
}
