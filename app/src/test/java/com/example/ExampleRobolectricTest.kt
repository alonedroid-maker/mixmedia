package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.download.DownloadEngine
import com.example.download.DownloadPersistence
import com.example.download.DownloadSegment
import com.example.download.DownloadStatus
import com.example.download.DownloadTask
import com.example.download.QueueConcurrency
import com.example.intent.VlcIntentHelper
import com.example.network.InstagramExtractor
import com.example.network.YouTubeExtractor
import com.example.ui.theme.ThemeMode
import java.io.File
import java.util.Properties
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
class ExampleRobolectricTest {

    @Test
    fun `read app name unique package id and centralized release version from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MediaX", appName)
        assertEquals("com.aistudio.mixmedia.vpxkqm", context.packageName)

        // Verify BuildConfig version fields match root release-info.properties when accessible
        assertEquals(BuildConfig.VERSION_NAME, context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        assertTrue(BuildConfig.VERSION_CODE >= 2)
        val releaseFile = listOf(
            File("release-info.properties"),
            File("../release-info.properties"),
        ).firstOrNull { it.isFile }
        if (releaseFile != null) {
            val props = Properties().apply { releaseFile.inputStream().use(::load) }
            assertEquals(props.getProperty("VERSION_NAME")?.trim(), BuildConfig.VERSION_NAME)
            assertEquals(props.getProperty("VERSION_CODE")?.trim()?.toInt(), BuildConfig.VERSION_CODE)
        }

        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromCode(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromCode("dark"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromCode("LIGHT"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromCode("unknown"))
    }

    @Test
    fun `toggle vlc interceptor alias updates component state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(VlcIntentHelper.isVlcInterceptorEnabled(context))

        VlcIntentHelper.setVlcInterceptorEnabled(context, true)
        assertTrue(VlcIntentHelper.isVlcInterceptorEnabled(context))

        VlcIntentHelper.setVlcInterceptorEnabled(context, false)
        assertFalse(VlcIntentHelper.isVlcInterceptorEnabled(context))
    }

    @Test
    fun `extracts incoming url from vlc view and share intents`() {
        val playUrl = "http://googfilmazappfordownfilmmedis.xyz/play-link/play.php?t=serie&i=575432"

        val viewVideoIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(playUrl), "video/*")
        }
        assertEquals(playUrl, VlcIntentHelper.extractUrlFromIntent(viewVideoIntent))

        val broadHttpIntent = Intent(Intent.ACTION_VIEW, Uri.parse(playUrl))
        assertEquals(playUrl, VlcIntentHelper.extractUrlFromIntent(broadHttpIntent))

        val vlcSchemeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vlc://$playUrl"))
        assertEquals(playUrl, VlcIntentHelper.extractUrlFromIntent(vlcSchemeIntent))

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, playUrl)
        }
        assertEquals(playUrl, VlcIntentHelper.extractUrlFromIntent(shareIntent))

        val doubleQueryPlayUrl =
            "http://googfilmazappfordownfilmmedis.xyz/play-link/play.php?play-link/play.php?&t=movie&i=72648&m=09210076896&ap=Fj?&t=movie&i=72648&m=09210076896&s=vcp"
        val intentScheme = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("intent://googfilmazappfordownfilmmedis.xyz/play-link/play.php?play-link/play.php?&t=movie&i=72648&m=09210076896&ap=Fj?&t=movie&i=72648&m=09210076896&s=vcp#Intent;scheme=http;package=org.videolan.vlc;end"),
        )
        assertEquals(doubleQueryPlayUrl, VlcIntentHelper.extractUrlFromIntent(intentScheme))
    }

    @Test
    fun `download persistence saves segments and normalizes active tasks to paused on reload`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageFile = File(context.cacheDir, "test_downloads_persistence.json").apply { delete() }
        val persistence = DownloadPersistence(storageFile)

        val activeTask = DownloadTask(
            id = "task-1",
            url = "http://cdn.example.com/movie.mkv?expire=999",
            sourcePlayUrl = "http://example.com/play.php?i=1",
            fileName = "movie.mkv",
            extension = "MKV",
            totalBytes = 10_000L,
            downloadedBytes = 4_000L,
            status = DownloadStatus.DOWNLOADING,
            supportsRange = true,
            segments = listOf(
                DownloadSegment(index = 0, startByte = 0L, endByte = 4_999L, downloadedBytes = 3_000L),
                DownloadSegment(index = 1, startByte = 5_000L, endByte = 9_999L, downloadedBytes = 1_000L),
            ),
            partFilePath = "/tmp/task-1.part",
            connectionCount = 8,
            overwriteExisting = true,
            customUserAgent = "CustomTestAgent/1.0",
        )
        val completedTask = DownloadTask(
            id = "task-2",
            url = "http://cdn.example.com/done.mp4",
            fileName = "done.mp4",
            extension = "MP4",
            totalBytes = 5_000L,
            downloadedBytes = 5_000L,
            status = DownloadStatus.COMPLETED,
            localFilePath = "/tmp/done.mp4",
        )

        persistence.saveTasks(listOf(activeTask, completedTask))
        val reloaded = persistence.loadTasks()

        assertEquals(2, reloaded.size)
        assertEquals(DownloadStatus.PAUSED, reloaded[0].status)
        assertEquals(2, reloaded[0].segments.size)
        assertEquals(3_000L, reloaded[0].segments[0].downloadedBytes)
        assertEquals(8, reloaded[0].connectionCount)
        assertTrue(reloaded[0].overwriteExisting)
        assertEquals("CustomTestAgent/1.0", reloaded[0].customUserAgent)
        assertEquals("http://example.com/play.php?i=1", reloaded[0].sourcePlayUrl)
        assertEquals(DownloadStatus.COMPLETED, reloaded[1].status)
        assertEquals("/tmp/done.mp4", reloaded[1].localFilePath)

        storageFile.delete()
    }

    @Test
    fun `download engine supports 1 to 8 parts custom folder and duplicate file conflict checks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = DownloadEngine(context)

        engine.setConcurrencyLimit(QueueConcurrency.ONE)
        engine.setConnectionsPerDownload(8)
        assertEquals(QueueConcurrency.ONE, engine.concurrencyLimit.value)
        assertEquals(8, engine.connectionsPerDownload.value)

        engine.setConnectionsPerDownload(99)
        assertEquals(8, engine.connectionsPerDownload.value)
        engine.setConnectionsPerDownload(0)
        assertEquals(1, engine.connectionsPerDownload.value)
        engine.setConnectionsPerDownload(8)

        val customUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies%2FMediaX")
        engine.setCustomSaveLocation(customUri)
        assertTrue(engine.saveLocation.value.isCustom)
        assertEquals(customUri.toString(), engine.saveLocation.value.treeUriString)

        engine.resetSaveLocationToDefault()
        assertFalse(engine.saveLocation.value.isCustom)
        assertEquals("Downloads/MediaX", engine.saveLocation.value.displayPath)

        val first = engine.enqueue(
            url = "http://127.0.0.1:1/video1.mkv?expire=111",
            sourcePlayUrl = "http://example.com/play.php?i=610537",
            suggestedFileName = "video1.mkv",
        )
        val second = engine.enqueue("http://127.0.0.1:1/video2.mp4", suggestedFileName = "video2.mp4")

        engine.pause(first.id)
        engine.pause(second.id)

        assertTrue(engine.hasFileConflict("video1.mkv"))
        assertTrue(
            engine.hasFileConflict(
                fileName = "different_name.mkv",
                url = "http://127.0.0.1:1/other.mkv",
                sourcePlayUrl = "http://example.com/play.php?i=610537",
            )
        )
        assertEquals("video1 (1).mkv", engine.suggestUniqueFileName("video1.mkv"))

        val replaced = engine.enqueue(
            url = "http://127.0.0.1:1/video1_new.mkv",
            suggestedFileName = "video1.mkv",
            overwriteExisting = true,
        )
        engine.pause(replaced.id)
        assertTrue(engine.tasks.value.none { it.id == first.id })
        assertTrue(engine.tasks.value.any { it.id == replaced.id && it.overwriteExisting })

        engine.moveToTop(second.id)
        assertEquals(second.id, engine.tasks.value.first().id)

        engine.remove(replaced.id)
        engine.remove(second.id)
        assertTrue(engine.tasks.value.none { it.id == replaced.id || it.id == second.id })
    }

    @Test
    fun `youtube extractor parses oembed, data api v3, iso8601 durations, and unavailable items`() {
        assertEquals(3750L, YouTubeExtractor.parseIso8601DurationSeconds("PT1H2M30S"))
        assertEquals(300L, YouTubeExtractor.parseIso8601DurationSeconds("PT5M"))
        assertEquals(45L, YouTubeExtractor.parseIso8601DurationSeconds("PT45S"))
        assertNull(YouTubeExtractor.parseIso8601DurationSeconds("PT0S"))
        assertNull(YouTubeExtractor.parseIso8601DurationSeconds("invalid"))

        val extractor = YouTubeExtractor()
        val canonical = YouTubeExtractor.canonicalWatchUrl("dQw4w9WgXcQ")
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", canonical)

        val oembedJson = """
            {
              "title": "Official oEmbed Title",
              "author_name": "Creator Studio",
              "author_url": "https://www.youtube.com/@creator",
              "thumbnail_url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
            }
        """.trimIndent()
        val oembedParsed = extractor.parseOEmbedJson(oembedJson, "dQw4w9WgXcQ", canonical)
        assertNotNull(oembedParsed)
        assertEquals("Official oEmbed Title", oembedParsed?.title)
        assertEquals("Creator Studio", oembedParsed?.channelTitle)
        assertTrue(oembedParsed?.isAvailable == true)

        val dataApiJson = """
            {
              "items": [
                {
                  "snippet": {
                    "title": "Official Data API Title",
                    "channelTitle": "Verified Channel",
                    "channelId": "UC_test_123",
                    "publishedAt": "2025-09-14T18:00:00Z",
                    "thumbnails": {
                      "high": { "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg" },
                      "maxres": { "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg" }
                    }
                  },
                  "contentDetails": {
                    "duration": "PT3M15S"
                  }
                }
              ]
            }
        """.trimIndent()
        val dataParsed = extractor.parseDataApiJson(dataApiJson, "dQw4w9WgXcQ", canonical)
        assertNotNull(dataParsed)
        assertEquals("Official Data API Title", dataParsed?.title)
        assertEquals("Verified Channel", dataParsed?.channelTitle)
        assertEquals("https://www.youtube.com/channel/UC_test_123", dataParsed?.channelUrl)
        assertEquals("2025-09-14", dataParsed?.publishedAt)
        assertEquals(195L, dataParsed?.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", dataParsed?.thumbnailUrl)

        val emptyItemsJson = """{ "items": [] }"""
        val unavailableParsed = extractor.parseDataApiJson(emptyItemsJson, "dQw4w9WgXcQ", canonical)
        assertNotNull(unavailableParsed)
        assertFalse(unavailableParsed!!.isAvailable)
    }

    @Test
    fun `instagram extractor parses graphql video versions and embed html`() {
        val extractor = InstagramExtractor()

        val graphQlPayload = """
            {
              "data": {
                "xdt_shortcode_media": {
                  "video_versions": [
                    {"width": 720, "height": 1280, "url": "https://scontent.cdninstagram.com/v/reel_720.mp4"},
                    {"width": 1080, "height": 1920, "url": "https://scontent.cdninstagram.com/v/reel_1080.mp4"}
                  ]
                }
              }
            }
        """.trimIndent()
        assertEquals(
            "https://scontent.cdninstagram.com/v/reel_1080.mp4",
            extractor.extractVideoFromGraphQlJson(graphQlPayload),
        )

        val embedHtml = """<script>{"video_url":"https:\/\/scontent.cdninstagram.com\/v\/embed.mp4?token=1\u0026b=2"}</script>"""
        assertEquals(
            "https://scontent.cdninstagram.com/v/embed.mp4?token=1&b=2",
            extractor.extractVideoUrlFromHtmlOrJson(embedHtml),
        )
    }
}
