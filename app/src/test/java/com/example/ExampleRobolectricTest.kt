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
import com.example.network.MediaFormatCategory
import com.example.network.YouTubeExtractor
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name and unique package id from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MIxmedia", appName)
        assertEquals("com.aistudio.mixmedia.vpxkqm", context.packageName)
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

        val customUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies%2FMIxmedia")
        engine.setCustomSaveLocation(customUri)
        assertTrue(engine.saveLocation.value.isCustom)
        assertEquals(customUri.toString(), engine.saveLocation.value.treeUriString)

        engine.resetSaveLocationToDefault()
        assertFalse(engine.saveLocation.value.isCustom)
        assertEquals("Downloads/MIxmedia", engine.saveLocation.value.displayPath)

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
    fun `youtube player json parser extracts muxed video adaptive video and audio music streams`() {
        val sampleJson = JSONObject(
            """
            {
              "videoDetails": {
                "title": "Sample Music Video",
                "author": "Test Artist",
                "lengthSeconds": "180"
              },
              "streamingData": {
                "formats": [
                  {
                    "itag": 22,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=22",
                    "mimeType": "video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"",
                    "qualityLabel": "720p",
                    "bitrate": 1500000,
                    "contentLength": "33750000"
                  }
                ],
                "adaptiveFormats": [
                  {
                    "itag": 137,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=137",
                    "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                    "qualityLabel": "1080p",
                    "bitrate": 3200000,
                    "contentLength": "72000000"
                  },
                  {
                    "itag": 140,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=140",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 128000,
                    "contentLength": "2880000"
                  },
                  {
                    "itag": 251,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=251",
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "bitrate": 160000
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val extractor = YouTubeExtractor()
        val parsed = extractor.parsePlayerJson(sampleJson, "dQw4w9WgXcQ", "TestUA")

        assertEquals("Sample Music Video", parsed.title)
        assertEquals("Test Artist", parsed.author)
        assertEquals(180L, parsed.durationSeconds)
        assertEquals(4, parsed.options.size)

        val muxed = parsed.options.first { it.itag == 22 }
        assertEquals(MediaFormatCategory.VIDEO_WITH_AUDIO, muxed.category)
        assertEquals("720p", muxed.qualityLabel)
        assertEquals("MP4", muxed.container)

        val videoOnly = parsed.options.first { it.itag == 137 }
        assertEquals(MediaFormatCategory.VIDEO_ONLY, videoOnly.category)
        assertEquals("1080p", videoOnly.qualityLabel)

        val audioM4a = parsed.options.first { it.itag == 140 }
        assertEquals(MediaFormatCategory.AUDIO_ONLY, audioM4a.category)
        assertEquals("M4A", audioM4a.container)
        assertEquals("128 kbps", audioM4a.qualityLabel)

        val audioOpus = parsed.options.first { it.itag == 251 }
        assertEquals(MediaFormatCategory.AUDIO_ONLY, audioOpus.category)
        assertEquals("OPUS", audioOpus.container)
        assertEquals("160 kbps", audioOpus.qualityLabel)
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
