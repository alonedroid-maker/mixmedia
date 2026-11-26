package com.example.network

import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class InstagramExtractionResult(
    val shortcode: String?,
    val directVideoUrl: String?,
    val title: String?,
    val fileSizeFormatted: String?,
    val userAgent: String,
    val hops: List<RedirectHop>,
    val verboseLog: String,
)

class InstagramExtractor(
    private val client: OkHttpClient = defaultClient(),
) {
    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    companion object {
        private val IG_SHORTCODE_REGEX = Regex(
            """(?:instagram\.com|instagr\.am)/(?:[^/]+/)?(p|reel|reels|tv)/([A-Za-z0-9_-]+)""",
            RegexOption.IGNORE_CASE
        )

        private val IG_HOST_REGEX = Regex(
            """^https?://(?:www\.|m\.|dd|kk|vx|uu|ee)?(?:instagram\.com|instagr\.am)/""",
            RegexOption.IGNORE_CASE
        )

        private val VIDEO_URL_JSON_REGEX = Regex(
            """"video_url"\s*:\s*"(https?:[^"]+)"""",
            RegexOption.IGNORE_CASE
        )

        private val OG_VIDEO_REGEX = Regex(
            """<meta[^>]+(?:property|name)=["']og:video(?::secure_url)?["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        private val OG_VIDEO_ALT_REGEX = Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:video(?::secure_url)?["']""",
            RegexOption.IGNORE_CASE
        )

        private val VIDEO_SRC_REGEX = Regex(
            """<video[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val BOT_USER_AGENT =
            "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)"

        private const val IG_WEB_APP_ID = "936619743392459"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(14, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        fun isInstagramUrl(url: String): Boolean {
            return IG_HOST_REGEX.containsMatchIn(url.trim())
        }

        fun extractShortcode(url: String): String? {
            return IG_SHORTCODE_REGEX.find(url)?.groupValues?.getOrNull(2)
        }

        private fun extractPostKind(url: String): String {
            val raw = IG_SHORTCODE_REGEX.find(url)?.groupValues?.getOrNull(1)?.lowercase()
            return when (raw) {
                "p" -> "p"
                "tv" -> "tv"
                else -> "reel"
            }
        }
    }

    suspend fun extract(
        inputUrl: String,
        cookieHeader: String? = null,
        onLogUpdate: (traceSoFar: String, currentStep: Int) -> Unit = { _, _ -> },
    ): InstagramExtractionResult = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        val hops = mutableListOf<RedirectHop>()
        var step = 0

        fun trace(msg: String) {
            log.appendLine(msg)
        }

        var canonicalUrl = inputUrl.trim()
        var shortcode = extractShortcode(canonicalUrl)

        // Resolve share links like /share/reel/... to canonical /reel/{shortcode}/
        if (shortcode == null) {
            currentCoroutineContext().ensureActive()
            step++
            trace("> GET $canonicalUrl [Resolving Instagram share link]")
            onLogUpdate(log.toString(), step)
            runCatching {
                val req = Request.Builder()
                    .url(canonicalUrl)
                    .get()
                    .header("User-Agent", WEB_USER_AGENT)
                    .apply { if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader) }
                    .build()
                client.newCall(req).execute().use { resp ->
                    canonicalUrl = resp.request.url.toString()
                    shortcode = extractShortcode(canonicalUrl)
                    trace("< HTTP ${resp.code} -> canonical=$canonicalUrl (shortcode=$shortcode)")
                    hops.add(RedirectHop(step, inputUrl, resp.code, resp.message, canonicalUrl, resp.header("Content-Type"), null))
                }
            }
        }

        val resolvedShortcode = shortcode ?: run {
            trace("* Could not determine Instagram post shortcode from URL.")
            return@withContext InstagramExtractionResult(
                shortcode = null,
                directVideoUrl = null,
                title = null,
                fileSizeFormatted = null,
                userAgent = WEB_USER_AGENT,
                hops = hops,
                verboseLog = log.toString().trimEnd(),
            )
        }
        val postKind = extractPostKind(canonicalUrl)
        trace("[Instagram] kind=$postKind shortcode=$resolvedShortcode")

        var foundVideoUrl: String? = null
        var downloadUserAgent = WEB_USER_AGENT

        // Strategy 1: Public mirror direct MP4 redirect (kkinstagram -> fbcdn.net)
        currentCoroutineContext().ensureActive()
        step++
        val kkUrl = "https://www.kkinstagram.com/$postKind/$resolvedShortcode/"
        trace("> GET $kkUrl")
        onLogUpdate(log.toString(), step)

        runCatching {
            val req = Request.Builder()
                .url(kkUrl)
                .get()
                .header("User-Agent", BOT_USER_AGENT)
                .build()
            noRedirectClient.newCall(req).execute().use { resp ->
                val loc = resp.header("Location")?.trim()
                val ct = resp.header("Content-Type").orEmpty()
                trace("< HTTP ${resp.code} Location=${loc?.take(80)} Content-Type=$ct")
                hops.add(RedirectHop(step, kkUrl, resp.code, resp.message, loc, ct, null))

                if (!loc.isNullOrBlank() && loc.startsWith("http")) {
                    val resolvedFinal = resolveFinalMp4Url(loc)
                    if (verifyVideoUrlReachable(resolvedFinal, WEB_USER_AGENT)) {
                        foundVideoUrl = resolvedFinal
                        trace("* Resolved direct CDN MP4 via kkinstagram.")
                    }
                } else if (resp.isSuccessful && ct.startsWith("video/", ignoreCase = true)) {
                    foundVideoUrl = kkUrl
                    downloadUserAgent = BOT_USER_AGENT
                } else if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    val rawCandidate = extractVideoUrlFromHtmlOrJson(html)
                    if (!rawCandidate.isNullOrBlank()) {
                        val resolved = kkUrl.toHttpUrlOrNull()?.resolve(rawCandidate)?.toString() ?: rawCandidate
                        val finalCdn = resolveFinalMp4Url(resolved)
                        if (verifyVideoUrlReachable(finalCdn, WEB_USER_AGENT)) {
                            foundVideoUrl = finalCdn
                            trace("* Resolved direct MP4 via kkinstagram HTML.")
                        }
                    }
                }
            }
        }.onFailure { if (it is CancellationException) throw it else trace("* kkinstagram error: ${it.message}") }

        // Strategy 2: Public mirror embeds (vxinstagram, uuinstagram, eeinstagram)
        if (foundVideoUrl == null) {
            val mirrors = listOf(
                "https://www.vxinstagram.com/$postKind/$resolvedShortcode/",
                "https://www.uuinstagram.com/$postKind/$resolvedShortcode/",
                "https://www.eeinstagram.com/$postKind/$resolvedShortcode/",
            )
            for (mirrorUrl in mirrors) {
                if (foundVideoUrl != null) break
                currentCoroutineContext().ensureActive()
                step++
                trace("> GET $mirrorUrl")
                onLogUpdate(log.toString(), step)

                runCatching {
                    val req = Request.Builder()
                        .url(mirrorUrl)
                        .get()
                        .header("User-Agent", BOT_USER_AGENT)
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val html = resp.body?.string().orEmpty()
                        trace("< HTTP ${resp.code} (${html.length} bytes)")
                        hops.add(RedirectHop(step, mirrorUrl, resp.code, resp.message, null, resp.header("Content-Type"), html.length.toLong()))
                        val rawCandidate = extractVideoUrlFromHtmlOrJson(html)
                        if (!rawCandidate.isNullOrBlank()) {
                            val resolved = mirrorUrl.toHttpUrlOrNull()?.resolve(rawCandidate)?.toString() ?: rawCandidate
                            val finalCdn = resolveFinalMp4Url(resolved)
                            if (verifyVideoUrlReachable(finalCdn, WEB_USER_AGENT)) {
                                foundVideoUrl = finalCdn
                                trace("* Resolved direct MP4 via $mirrorUrl")
                            }
                        }
                    }
                }.onFailure { if (it is CancellationException) throw it else trace("* mirror $mirrorUrl error: ${it.message}") }
            }
        }

        // Strategy 3: Native Instagram Embed Captioned page
        if (foundVideoUrl == null) {
            currentCoroutineContext().ensureActive()
            step++
            val embedUrl = "https://www.instagram.com/p/$resolvedShortcode/embed/captioned/"
            trace("> GET $embedUrl")
            onLogUpdate(log.toString(), step)

            runCatching {
                val req = Request.Builder()
                    .url(embedUrl)
                    .get()
                    .header("User-Agent", WEB_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .apply { if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader) }
                    .build()
                client.newCall(req).execute().use { resp ->
                    val html = resp.body?.string().orEmpty()
                    trace("< HTTP ${resp.code} (${html.length} bytes)")
                    hops.add(RedirectHop(step, embedUrl, resp.code, resp.message, null, resp.header("Content-Type"), html.length.toLong()))

                    val candidate = extractVideoUrlFromHtmlOrJson(html)
                    if (candidate != null && verifyVideoUrlReachable(candidate, WEB_USER_AGENT)) {
                        foundVideoUrl = candidate
                        trace("* Found direct video URL in Instagram Embed.")
                    }
                }
            }.onFailure { if (it is CancellationException) throw it else trace("* Embed error: ${it.message}") }
        }

        // Strategy 4: Native Instagram page + CSRF/LSD cookies + GraphQL
        if (foundVideoUrl == null) {
            currentCoroutineContext().ensureActive()
            step++
            val postUrl = "https://www.instagram.com/reel/$resolvedShortcode/"
            trace("> GET $postUrl")
            onLogUpdate(log.toString(), step)

            var sessionCookies = cookieHeader.orEmpty()
            var csrfToken: String? = null
            var lsdToken: String? = null

            runCatching {
                val req = Request.Builder()
                    .url(postUrl)
                    .get()
                    .header("User-Agent", WEB_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .apply { if (sessionCookies.isNotBlank()) header("Cookie", sessionCookies) }
                    .build()
                client.newCall(req).execute().use { resp ->
                    val finalUri = resp.request.url.toString()
                    val html = resp.body?.string().orEmpty()
                    trace("< HTTP ${resp.code} (${html.length} bytes) -> $finalUri")
                    hops.add(RedirectHop(step, postUrl, resp.code, resp.message, finalUri, resp.header("Content-Type"), html.length.toLong()))

                    if (finalUri.contains("/accounts/login") || finalUri.contains("/challenge")) {
                        trace("* Instagram redirected to login/challenge wall.")
                    }

                    val setCookies = resp.headers("Set-Cookie").joinToString("; ") { it.substringBefore(';') }
                    if (setCookies.isNotBlank()) {
                        sessionCookies = listOf(sessionCookies, setCookies).filter { it.isNotBlank() }.joinToString("; ")
                    }
                    csrfToken = Regex("""csrftoken=([^;]+)""").find(sessionCookies)?.groupValues?.getOrNull(1)
                        ?: Regex(""""csrf_token":"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
                    lsdToken = Regex(""""LSD",\[\],\{"token":"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)

                    val candidate = extractVideoUrlFromHtmlOrJson(html)
                    if (candidate != null && verifyVideoUrlReachable(candidate, WEB_USER_AGENT)) {
                        foundVideoUrl = candidate
                    }
                }
            }.onFailure { if (it is CancellationException) throw it else trace("* Reel HTML error: ${it.message}") }

            if (foundVideoUrl == null && csrfToken != null) {
                currentCoroutineContext().ensureActive()
                step++
                val graphqlUrl = "https://www.instagram.com/graphql/query"
                trace("> POST $graphqlUrl")
                onLogUpdate(log.toString(), step)

                val form = FormBody.Builder()
                    .add("variables", JSONObject().apply { put("shortcode", resolvedShortcode) }.toString())
                    .add("doc_id", "8845758582119845")
                    .apply { if (lsdToken != null) add("lsd", lsdToken!!) }
                    .build()

                runCatching {
                    val req = Request.Builder()
                        .url(graphqlUrl)
                        .post(form)
                        .header("User-Agent", WEB_USER_AGENT)
                        .header("X-IG-App-ID", IG_WEB_APP_ID)
                        .header("X-CSRFToken", csrfToken!!)
                        .apply { if (lsdToken != null) header("X-FB-LSD", lsdToken!!) }
                        .apply { if (sessionCookies.isNotBlank()) header("Cookie", sessionCookies) }
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        trace("< HTTP ${resp.code} (${body.length} bytes)")
                        hops.add(RedirectHop(step, graphqlUrl, resp.code, resp.message, null, resp.header("Content-Type"), body.length.toLong()))

                        if (resp.isSuccessful && body.isNotBlank()) {
                            val candidate = extractVideoFromGraphQlJson(body) ?: extractVideoUrlFromHtmlOrJson(body)
                            if (candidate != null && verifyVideoUrlReachable(candidate, WEB_USER_AGENT)) {
                                foundVideoUrl = candidate
                                trace("* Found direct video URL in GraphQL response.")
                            }
                        }
                    }
                }.onFailure { if (it is CancellationException) throw it else trace("* GraphQL error: ${it.message}") }
            }
        }

        val finalUrl = foundVideoUrl
        if (finalUrl == null) {
            trace("* All Instagram extraction strategies failed or post is login-restricted.")
            return@withContext InstagramExtractionResult(
                shortcode = resolvedShortcode,
                directVideoUrl = null,
                title = null,
                fileSizeFormatted = null,
                userAgent = downloadUserAgent,
                hops = hops,
                verboseLog = log.toString().trimEnd(),
            )
        }

        // Probe file size quickly with Range/HEAD
        val sizeFormatted = probeVideoSize(finalUrl, downloadUserAgent)

        InstagramExtractionResult(
            shortcode = resolvedShortcode,
            directVideoUrl = finalUrl,
            title = "Instagram_$resolvedShortcode.mp4",
            fileSizeFormatted = sizeFormatted,
            userAgent = downloadUserAgent,
            hops = hops,
            verboseLog = log.toString().trimEnd(),
        )
    }

    private fun resolveFinalMp4Url(url: String): String {
        var current = url
        repeat(4) {
            val req = Request.Builder()
                .url(current)
                .get()
                .header("User-Agent", WEB_USER_AGENT)
                .header("Range", "bytes=0-0")
                .build()
            val next = runCatching {
                noRedirectClient.newCall(req).execute().use { resp ->
                    if (resp.code in setOf(301, 302, 303, 307, 308)) {
                        val loc = resp.header("Location")?.trim()
                        if (!loc.isNullOrBlank()) {
                            current.toHttpUrlOrNull()?.resolve(loc)?.toString() ?: loc
                        } else null
                    } else null
                }
            }.getOrNull() ?: return current
            current = next
        }
        return current
    }

    private fun verifyVideoUrlReachable(url: String, userAgent: String): Boolean {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-63")
                .header("User-Agent", userAgent)
                .build()
            client.newCall(req).execute().use { resp ->
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                (resp.code == 200 || resp.code == 206) &&
                    !ct.startsWith("text/html")
            }
        }.getOrDefault(false)
    }

    private fun probeVideoSize(url: String, userAgent: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .header("User-Agent", userAgent)
                .build()
            client.newCall(req).execute().use { resp ->
                val contentRange = resp.header("Content-Range")
                val total = if (resp.code == 206 && !contentRange.isNullOrBlank()) {
                    contentRange.substringAfterLast('/', "").trim().toLongOrNull()
                } else {
                    resp.header("Content-Length")?.toLongOrNull()
                }
                VideoLinkExtractor.formatByteSize(total)
            }
        }.getOrNull()
    }

    fun extractVideoUrlFromHtmlOrJson(raw: String): String? {
        if (raw.isBlank()) return null

        VIDEO_URL_JSON_REGEX.find(raw)?.groupValues?.getOrNull(1)?.let { escaped ->
            val unescaped = unescapeJsonUrl(escaped)
            if (unescaped.startsWith("http")) return unescaped
        }

        OG_VIDEO_REGEX.find(raw)?.groupValues?.getOrNull(1)?.let { og ->
            val clean = og.replace("&amp;", "&")
            if (clean.isNotBlank()) return clean
        }

        OG_VIDEO_ALT_REGEX.find(raw)?.groupValues?.getOrNull(1)?.let { og ->
            val clean = og.replace("&amp;", "&")
            if (clean.isNotBlank()) return clean
        }

        VIDEO_SRC_REGEX.find(raw)?.groupValues?.getOrNull(1)?.let { src ->
            val clean = src.replace("&amp;", "&")
            if (clean.isNotBlank()) return clean
        }

        val normalized = unescapeJsonUrl(raw)
        return VideoLinkExtractor.extractVideoUrlsFromText(normalized).firstOrNull()
    }

    fun extractVideoFromGraphQlJson(rawJson: String): String? {
        return runCatching {
            val root = JSONObject(rawJson)
            val data = root.optJSONObject("data") ?: return@runCatching null
            val media = data.optJSONObject("xdt_shortcode_media") ?: return@runCatching null

            pickHighestVideoVersion(media.optJSONArray("video_versions"))?.let { return@runCatching it }
            media.optString("video_url", "").takeIf { it.startsWith("http") }?.let { return@runCatching it }

            val sidecarEdges = media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
            if (sidecarEdges != null) {
                for (i in 0 until sidecarEdges.length()) {
                    val node = sidecarEdges.optJSONObject(i)?.optJSONObject("node") ?: continue
                    pickHighestVideoVersion(node.optJSONArray("video_versions"))?.let { return@runCatching it }
                    node.optString("video_url", "").takeIf { it.startsWith("http") }?.let { return@runCatching it }
                }
            }
            null
        }.getOrNull()
    }

    private fun pickHighestVideoVersion(versions: JSONArray?): String? {
        if (versions == null || versions.length() == 0) return null
        var bestUrl: String? = null
        var bestPixels = -1
        for (i in 0 until versions.length()) {
            val v = versions.optJSONObject(i) ?: continue
            val url = v.optString("url", "")
            if (!url.startsWith("http")) continue
            val pixels = v.optInt("width", 0) * v.optInt("height", 0)
            if (pixels >= bestPixels) {
                bestPixels = pixels
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun unescapeJsonUrl(raw: String): String {
        return raw
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("\\/", "/")
            .replace("&amp;", "&")
    }
}
