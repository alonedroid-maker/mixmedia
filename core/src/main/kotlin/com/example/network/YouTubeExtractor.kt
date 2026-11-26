package com.example.network

import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Policy-compliant YouTube handler.
 *
 * Uses only official YouTube URLs, the public YouTube oEmbed endpoint, and the official
 * YouTube Data API v3 (`videos?part=snippet,contentDetails`) when the user provides a key
 * in Settings. Never scrapes watch HTML or calls unauthorized streaming endpoints.
 */
class YouTubeExtractor(
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    companion object {
        val SUPPORTED_HOSTS = setOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "music.youtube.com",
            "youtu.be",
            "www.youtu.be",
            "youtube-nocookie.com",
            "www.youtube-nocookie.com",
        )

        const val YOUTUBE_STUDIO_URL = "https://studio.youtube.com/"
        const val GOOGLE_TAKEOUT_URL = "https://takeout.google.com/"

        private val VIDEO_ID_PATTERN = Regex("""^[A-Za-z0-9_-]{11}$""")
        private val PATH_PREFIX_SEGMENTS = setOf("shorts", "live", "embed", "v")
        private val ISO_DURATION_REGEX = Regex("""^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""")
        private const val USER_AGENT = "MediaXAndroid/1.5 (+https://github.com/alonedroid-maker/mixmedia)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(16, TimeUnit.SECONDS)
            .build()

        fun isYouTubeUrl(rawUrl: String): Boolean = extractVideoId(rawUrl) != null

        fun canonicalWatchUrl(videoId: String): String =
            "https://www.youtube.com/watch?v=$videoId"

        fun defaultThumbnailUrl(videoId: String): String =
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        fun extractVideoId(rawUrl: String): String? {
            val cleaned = rawUrl.trim().trimEnd('\'', '"', ',', ';', '.', '!', ')', ']')
            if (cleaned.isEmpty()) return null
            val normalized = normalizeScheme(cleaned)
            val httpUrl = normalized.toHttpUrlOrNull() ?: return null
            val host = httpUrl.host.lowercase(Locale.US)
            if (host !in SUPPORTED_HOSTS) return null

            val segments = httpUrl.pathSegments.filter { it.isNotBlank() }
            if (host == "youtu.be" || host == "www.youtu.be") {
                val candidate = segments.firstOrNull() ?: return null
                return candidate.takeIf { VIDEO_ID_PATTERN.matches(it) }
            }

            if (segments.isEmpty() || segments.first().equals("watch", ignoreCase = true)) {
                val vParam = httpUrl.queryParameter("v")?.trim()
                if (!vParam.isNullOrBlank() && VIDEO_ID_PATTERN.matches(vParam)) {
                    return vParam
                }
            }

            if (segments.size >= 2 && segments[0].lowercase(Locale.US) in PATH_PREFIX_SEGMENTS) {
                val candidate = segments[1].trim()
                if (VIDEO_ID_PATTERN.matches(candidate)) {
                    return candidate
                }
            }
            return null
        }

        fun parseIso8601DurationSeconds(raw: String?): Long? {
            val trimmed = raw?.trim()?.uppercase(Locale.US) ?: return null
            val match = ISO_DURATION_REGEX.matchEntire(trimmed) ?: return null
            val hours = match.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
            val minutes = match.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
            val seconds = match.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
            val total = hours * 3600L + minutes * 60L + seconds
            return total.takeIf { it > 0L }
        }

        private fun normalizeScheme(raw: String): String {
            return if (raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)
            ) {
                raw
            } else if (SUPPORTED_HOSTS.any { raw.startsWith("$it/", ignoreCase = true) }) {
                "https://$raw"
            } else {
                raw
            }
        }
    }

    suspend fun fetchMetadata(
        inputUrl: String,
        youtubeApiKey: String? = null,
        onLogUpdate: (traceSoFar: String, currentStep: Int) -> Unit = { _, _ -> },
    ): YouTubeMetadataInfo = withContext(ioDispatcher) {
        val videoId = extractVideoId(inputUrl)
            ?: throw IllegalArgumentException("Invalid YouTube URL")
        val canonical = canonicalWatchUrl(videoId)
        val trimmedApiKey = youtubeApiKey?.trim().orEmpty()

        val log = StringBuilder()
        val hops = mutableListOf<RedirectHop>()
        var step = 0

        fun trace(msg: String) {
            log.appendLine(msg)
        }

        trace("[YouTube] Official lookup videoId=$videoId canonical=$canonical")
        onLogUpdate(log.toString(), 1)

        var title: String? = null
        var channelTitle: String? = null
        var channelUrl: String? = null
        var thumbnailUrl: String? = null
        var durationSeconds: Long? = null
        var publishedAt: String? = null
        var unavailableByStatus = false
        var unavailableReason: String? = null
        var hadSuccessfulHttpResponse = false
        var lastIoException: IOException? = null

        // Step 1: Official public YouTube oEmbed metadata endpoint
        currentCoroutineContext().ensureActive()
        step++
        val encodedWatch = URLEncoder.encode(canonical, "UTF-8")
        val oembedUrl = "https://www.youtube.com/oembed?url=$encodedWatch&format=json"
        trace("> GET $oembedUrl")
        onLogUpdate(log.toString(), step)

        try {
            val req = Request.Builder()
                .url(oembedUrl)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                trace("< HTTP ${resp.code} (${body.length} bytes)")
                hops.add(
                    RedirectHop(
                        step = step,
                        requestUrl = oembedUrl,
                        statusCode = resp.code,
                        statusMessage = resp.message,
                        locationHeader = null,
                        contentType = resp.header("Content-Type"),
                        contentLength = body.length.toLong(),
                    )
                )
                hadSuccessfulHttpResponse = true
                when {
                    resp.isSuccessful && body.isNotBlank() -> {
                        val parsed = parseOEmbedJson(body, videoId, canonical)
                        if (parsed != null) {
                            title = parsed.title
                            channelTitle = parsed.channelTitle
                            channelUrl = parsed.channelUrl
                            thumbnailUrl = parsed.thumbnailUrl
                            unavailableByStatus = false
                        }
                    }
                    resp.code in setOf(400, 401, 403, 404, 410) -> {
                        unavailableByStatus = true
                        unavailableReason = "HTTP ${resp.code}"
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            lastIoException = e
            trace("* oEmbed IO error: ${e.message}")
        } catch (e: Exception) {
            trace("* oEmbed parse error: ${e.message}")
        }

        // Step 2: Optional Official YouTube Data API v3 (`videos?part=snippet,contentDetails`)
        if (trimmedApiKey.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            step++
            val safeLoggedUrl =
                "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=$videoId&key=***"
            val requestUrl =
                "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=$videoId&key=${URLEncoder.encode(trimmedApiKey, "UTF-8")}"
            trace("> GET $safeLoggedUrl")
            onLogUpdate(log.toString(), step)

            try {
                val req = Request.Builder()
                    .url(requestUrl)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    trace("< HTTP ${resp.code} (${body.length} bytes)")
                    hops.add(
                        RedirectHop(
                            step = step,
                            requestUrl = safeLoggedUrl,
                            statusCode = resp.code,
                            statusMessage = resp.message,
                            locationHeader = null,
                            contentType = resp.header("Content-Type"),
                            contentLength = body.length.toLong(),
                        )
                    )
                    hadSuccessfulHttpResponse = true
                    if (resp.isSuccessful && body.isNotBlank()) {
                        val dataParsed = parseDataApiJson(body, videoId, canonical)
                        if (dataParsed != null) {
                            if (!dataParsed.isAvailable) {
                                unavailableByStatus = true
                                unavailableReason = dataParsed.unavailableReason
                            } else {
                                unavailableByStatus = false
                                title = dataParsed.title.takeIf { it.isNotBlank() } ?: title
                                channelTitle = dataParsed.channelTitle ?: channelTitle
                                channelUrl = dataParsed.channelUrl ?: channelUrl
                                thumbnailUrl = dataParsed.thumbnailUrl ?: thumbnailUrl
                                durationSeconds = dataParsed.durationSeconds ?: durationSeconds
                                publishedAt = dataParsed.publishedAt ?: publishedAt
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastIoException = e
                trace("* YouTube Data API IO error: ${e.message}")
            } catch (e: Exception) {
                trace("* YouTube Data API parse error: ${e.message}")
            }
        }

        if (!hadSuccessfulHttpResponse && lastIoException != null) {
            throw lastIoException
        }

        if (unavailableByStatus && title == null) {
            trace("* YouTube video unavailable ($unavailableReason).")
            return@withContext YouTubeMetadataInfo(
                videoId = videoId,
                canonicalUrl = canonical,
                title = "YouTube ($videoId)",
                isAvailable = false,
                unavailableReason = unavailableReason ?: "Unavailable or private video",
                hops = hops,
                verboseLog = log.toString().trimEnd(),
            )
        }

        YouTubeMetadataInfo(
            videoId = videoId,
            canonicalUrl = canonical,
            title = title ?: "YouTube ($videoId)",
            channelTitle = channelTitle,
            channelUrl = channelUrl,
            thumbnailUrl = thumbnailUrl ?: defaultThumbnailUrl(videoId),
            durationSeconds = durationSeconds,
            publishedAt = publishedAt,
            isAvailable = true,
            hops = hops,
            verboseLog = log.toString().trimEnd(),
        )
    }

    fun parseOEmbedJson(
        rawJson: String,
        videoId: String,
        canonicalUrl: String,
    ): YouTubeMetadataInfo? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val title = root.optString("title").takeIf { it.isNotBlank() } ?: return null
        val author = root.optString("author_name").takeIf { it.isNotBlank() }
        val authorUrl = root.optString("author_url").takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
        val thumb = root.optString("thumbnail_url").takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        } ?: defaultThumbnailUrl(videoId)

        return YouTubeMetadataInfo(
            videoId = videoId,
            canonicalUrl = canonicalUrl,
            title = title,
            channelTitle = author,
            channelUrl = authorUrl,
            thumbnailUrl = thumb,
            isAvailable = true,
        )
    }

    fun parseDataApiJson(
        rawJson: String,
        videoId: String,
        canonicalUrl: String,
    ): YouTubeMetadataInfo? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val items = root.optJSONArray("items") ?: return null
        if (items.length() == 0) {
            return YouTubeMetadataInfo(
                videoId = videoId,
                canonicalUrl = canonicalUrl,
                title = "YouTube ($videoId)",
                isAvailable = false,
                unavailableReason = "Video not found or private",
            )
        }
        val item = items.optJSONObject(0) ?: return null
        val snippet = item.optJSONObject("snippet")
        val contentDetails = item.optJSONObject("contentDetails")

        val title = snippet?.optString("title")?.takeIf { it.isNotBlank() } ?: "YouTube ($videoId)"
        val channelTitle = snippet?.optString("channelTitle")?.takeIf { it.isNotBlank() }
        val channelId = snippet?.optString("channelId")?.takeIf { it.isNotBlank() }
        val channelUrl = channelId?.let { "https://www.youtube.com/channel/$it" }
        val publishedAt = snippet?.optString("publishedAt")?.takeIf { it.length >= 10 }?.take(10)

        val thumbs = snippet?.optJSONObject("thumbnails")
        val thumb = listOf("maxres", "standard", "high", "medium", "default")
            .firstNotNullOfOrNull { key ->
                thumbs?.optJSONObject(key)?.optString("url")?.takeIf {
                    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
                }
            } ?: defaultThumbnailUrl(videoId)

        val durationSeconds = parseIso8601DurationSeconds(contentDetails?.optString("duration"))

        return YouTubeMetadataInfo(
            videoId = videoId,
            canonicalUrl = canonicalUrl,
            title = title,
            channelTitle = channelTitle,
            channelUrl = channelUrl,
            thumbnailUrl = thumb,
            durationSeconds = durationSeconds,
            publishedAt = publishedAt,
            isAvailable = true,
        )
    }
}
