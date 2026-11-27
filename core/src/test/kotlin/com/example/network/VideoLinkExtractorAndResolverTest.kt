package com.example.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLinkExtractorAndResolverTest {

    @Test
    fun `extractRichDownloadCandidates finds all movie qualities, subtitles, relative hrefs, labels, and size hints`() {
        val html = """
            <html>
              <head><title>Way of the Warrior Kid (2026) - Download</title></head>
              <body>
                <div class="downloads">
                  <a href="/files/Way.of.the.Warrior.Kid.2026.720p.WEB-DL.mkv">دانلود کیفیت 720p WEB-DL - حجم 950 MB</a>
                  <a href="https://dl.example.com/movies/Way.of.the.Warrior.Kid.2026.1080p.WEB-DL.x265.10bit.mkv">کیفیت 1080p x265 10bit - 1.8 GB</a>
                  <a href="https://dl.example.com/movies/Way.of.the.Warrior.Kid.2026.480p.mkv">کیفیت 480p - 520 MB</a>
                  <a href="https://dl.example.com/subs/Way.of.the.Warrior.Kid.2026.fa.srt">زیرنویس فارسی هماهنگ</a>
                  <a href="https://dl.example.com/poster-thumb.jpg">Cover Image</a>
                </div>
              </body>
            </html>
        """.trimIndent()

        val candidates = VideoLinkExtractor.extractRichDownloadCandidates(
            text = html,
            baseUrl = "https://www.example.com/90571/way-of-the-warrior-kid-2026/",
            fromHtmlBody = true,
        )

        assertEquals(4, candidates.size)
        val urls = candidates.map { it.url }
        assertTrue(urls.contains("https://www.example.com/files/Way.of.the.Warrior.Kid.2026.720p.WEB-DL.mkv"))
        assertTrue(urls.contains("https://dl.example.com/movies/Way.of.the.Warrior.Kid.2026.1080p.WEB-DL.x265.10bit.mkv"))
        assertTrue(urls.contains("https://dl.example.com/movies/Way.of.the.Warrior.Kid.2026.480p.mkv"))
        assertTrue(urls.contains("https://dl.example.com/subs/Way.of.the.Warrior.Kid.2026.fa.srt"))
        assertFalse(urls.any { it.endsWith(".jpg") })

        val opt1080 = candidates.first { it.url.contains("1080p") }
        assertTrue(opt1080.qualityLabel?.contains("1080p") == true)
        assertEquals("1.8 GB", opt1080.sizeHint)
    }

    @Test
    fun `CurlRedirectResolver returns all sorted downloadOptions across large movie HTML pages`() = runTest {
        val padding = "<!-- css-padding ${"x".repeat(4096)} -->\n".repeat(80) // > 320 KB to exceed old 256 KB limit
        val html = """
            <!doctype html>
            <html>
              <head><title>Way of the Warrior Kid 2026</title></head>
              <body>
                $padding
                <section id="dl-links">
                  <a href="https://cdn.example.com/movie.480p.mkv">نسخه 480p حجم 490 MB</a>
                  <a href="https://cdn.example.com/movie.1080p.BluRay.mkv">نسخه 1080p BluRay حجم 2.1 GB</a>
                  <a href="/subtitles/movie.fa.srt">زیرنویس فارسی</a>
                </section>
              </body>
            </html>
        """.trimIndent()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/movie-page") { exchange ->
            val bytes = html.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/movie-page"
            val resolver = CurlRedirectResolver()
            val result = resolver.resolve(url)

            assertNotNull(result.directVideoUrl)
            assertEquals("Way of the Warrior Kid 2026", result.mediaTitle)
            assertEquals(3, result.downloadOptions.size)
            assertEquals("https://cdn.example.com/movie.1080p.BluRay.mkv", result.downloadOptions[0].url)
            assertEquals("https://cdn.example.com/movie.480p.mkv", result.downloadOptions[1].url)
            assertTrue(result.downloadOptions[2].url.endsWith("/subtitles/movie.fa.srt"))
            assertEquals("SRT", result.downloadOptions[2].extension)
        } finally {
            server.stop(0)
        }
    }
}
