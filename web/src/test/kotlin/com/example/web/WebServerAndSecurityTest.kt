package com.example.web

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebServerAndSecurityTest {

    @Test
    fun `url security guard blocks loopback, private, metadata, and non-http schemes`() {
        assertNull(UrlSecurityGuard.validateExternalHttpUrl("http://127.0.0.1:8080/secret"))
        assertNull(UrlSecurityGuard.validateExternalHttpUrl("http://localhost/admin"))
        assertNull(UrlSecurityGuard.validateExternalHttpUrl("http://169.254.169.254/latest/meta-data/"))
        assertNull(UrlSecurityGuard.validateExternalHttpUrl("http://10.0.0.5/internal"))
        assertNull(UrlSecurityGuard.validateExternalHttpUrl("http://192.168.1.1/router"))
        assertNull(UrlSecurityGuard.validateExternalHttpUrl("http://172.16.0.10/db"))
        assertNull(UrlSecurityGuard.validateExternalHttpUrl("http://[::1]/loopback"))
        assertNull(UrlSecurityGuard.validateExternalHttpUrl("file:///etc/passwd"))
        assertNull(UrlSecurityGuard.validateExternalHttpUrl("javascript:alert(1)"))
        assertNull(UrlSecurityGuard.validateExternalHttpUrl("https://user:pass@example.com/file.mp4"))

        assertFalse(UrlSecurityGuard.isPublicAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(UrlSecurityGuard.isPublicAddress(InetAddress.getByName("169.254.169.254")))
        assertFalse(UrlSecurityGuard.isPublicAddress(InetAddress.getByName("10.10.10.10")))
        assertFalse(UrlSecurityGuard.isPublicAddress(InetAddress.getByName("192.168.0.1")))
        assertFalse(UrlSecurityGuard.isPublicAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(UrlSecurityGuard.isPublicAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun `filename sanitizer removes path traversal and header injection characters`() {
        assertEquals(
            "movie_2025.mp4",
            UrlSecurityGuard.sanitizeDownloadFileName("../../evil\r\nSet-Cookie: a=b/movie_2025.mp4")
        )
        assertEquals("fallback.mp4", UrlSecurityGuard.sanitizeDownloadFileName("...", "fallback.mp4"))
    }

    @Test
    fun `web server serves PWA shell, config without secrets, and rejects private SSRF URLs`() {
        val server = MediaXWebServer(host = "127.0.0.1", port = 0)
        server.start()
        try {
            val base = "http://127.0.0.1:${server.boundPort}"

            val htmlRes = get("$base/")
            assertEquals(200, htmlRes.first)
            assertTrue(htmlRes.second.contains("apple-mobile-web-app-capable"))
            assertTrue(htmlRes.second.contains("manifest.webmanifest"))

            val manifestRes = get("$base/manifest.webmanifest")
            assertEquals(200, manifestRes.first)
            assertTrue(manifestRes.second.contains("\"MediaX\""))

            val cfgRes = get("$base/api/config")
            assertEquals(200, cfgRes.first)
            val cfgJson = JSONObject(cfgRes.second)
            assertEquals(WebBuildConfig.VERSION_NAME, cfgJson.getString("version"))
            assertFalse(cfgRes.second.contains("TMDB_TOKEN_BLOB"))
            assertFalse(cfgRes.second.contains("TMDB_KEY_BLOB"))
            assertTrue(cfgJson.getJSONArray("builtInSites").length() > 0)

            val ssrfResolve = postJson("$base/api/resolve", """{"url":"http://169.254.169.254/latest/meta-data/"}""")
            assertEquals(400, ssrfResolve.first)

            val ssrfVerify = postJson("$base/api/sources/verify", """{"url":"http://127.0.0.1:8080/api/health"}""")
            assertEquals(400, ssrfVerify.first)

            val ssrfProxy = get("$base/api/download-proxy?url=http://localhost/test.mp4")
            assertEquals(400, ssrfProxy.first)

            val iconConn = URL("$base/apple-touch-icon.png").openConnection() as HttpURLConnection
            assertEquals(200, iconConn.responseCode)
            assertNotNull(iconConn.inputStream.readBytes())
        } finally {
            server.stop()
        }
    }

    private fun get(url: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        return code to body
    }

    private fun postJson(url: String, json: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5000
            readTimeout = 5000
        }
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        return code to body
    }
}
