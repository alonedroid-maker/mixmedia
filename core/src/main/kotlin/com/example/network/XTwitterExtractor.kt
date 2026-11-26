package com.example.network

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class XStatusReference(
    val tweetId: String,
    val screenName: String?,
    val canonicalUrl: String,
)

class XTwitterExtractor(
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        val SUPPORTED_HOSTS = setOf(
            "x.com",
            "www.x.com",
            "mobile.x.com",
            "twitter.com",
            "www.twitter.com",
            "mobile.twitter.com",
        )

        private val STATUS_PATH_REGEX = Regex(
            """^/(?:i/(?:web/)?status|([A-Za-z0-9_]{1,30})/status(?:es)?)/(\d{1,25})(?:/|$)""",
            RegexOption.IGNORE_CASE,
        )

        private val RESOLUTION_FROM_URL_REGEX = Regex(
            """/(?:vid|avc1)/(\d{2,4})x(\d{2,4})/""",
            RegexOption.IGNORE_CASE,
        )

        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(14, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        fun isXTwitterUrl(rawUrl: String): Boolean {
            return extractStatusReference(rawUrl) != null
        }

        fun isXTwitterHost(rawUrl: String): Boolean {
            val httpUrl = normalizeScheme(rawUrl).toHttpUrlOrNull() ?: return false
            return httpUrl.host.lowercase(Locale.US) in SUPPORTED_HOSTS
        }

        fun extractStatusReference(rawUrl: String): XStatusReference? {
            val trimmed = rawUrl.trim().trimEnd('\'', '"', ',', ';', '.', '!', ')', ']')
            if (trimmed.isEmpty()) return null
            val httpUrl = normalizeScheme(trimmed).toHttpUrlOrNull() ?: return null
            val host = httpUrl.host.lowercase(Locale.US)
            if (host !in SUPPORTED_HOSTS) return null

            val match = STATUS_PATH_REGEX.find(httpUrl.encodedPath) ?: return null
            val screenName = match.groupValues.getOrNull(1)?.takeIf {
                it.isNotBlank() && !it.equals("i", ignoreCase = true)
            }
            val tweetId = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
            val canonical = if (screenName != null) {
                "https://x.com/$screenName/status/$tweetId"
            } else {
                "https://x.com/i/status/$tweetId"
            }
            return XStatusReference(
                tweetId = tweetId,
                screenName = screenName,
                canonicalUrl = canonical,
            )
        }

        private fun normalizeScheme(raw: String): String {
            val t = raw.trim()
            return if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) {
                t
            } else if (SUPPORTED_HOSTS.any { t.startsWith("$it/", ignoreCase = true) }) {
                "https://$t"
            } else {
                t
            }
        }

        internal fun computeSyndicationToken(tweetId: String): String {
            val num = tweetId.toDoubleOrNull() ?: return "0"
            val value = (num / 1e15) * PI
            val scaled = kotlin.math.round(value * 1_000_000_000.0).toLong().coerceAtLeast(1L)
            return scaled.toString(36).replace("0", "").replace(".", "").ifEmpty { "1" }
        }
    }

    suspend fun extract(
        inputUrl: String,
        onLogUpdate: (traceSoFar: String, currentStep: Int) -> Unit = { _, _ -> },
    ): XExtractionResult = withContext(ioDispatcher) {
        val statusRef = extractStatusReference(inputUrl)
            ?: throw XExtractionException(
                error = XExtractionError.INVALID_URL,
                verboseLog = "[X] Unsupported or invalid X/Twitter post URL: $inputUrl",
            )

        val log = StringBuilder()
        val hops = mutableListOf<RedirectHop>()
        var step = 0

        fun trace(msg: String) {
            log.appendLine(msg)
        }

        trace("[X] Target tweetId=${statusRef.tweetId} url=${statusRef.canonicalUrl}")
        onLogUpdate(log.toString(), 1)

        var bestResult: XExtractionResult? = null
        var sawUnavailable = false
        var sawNoVideo = false
        var lastIoException: IOException? = null

        // Strategy 1: Public Twitter Syndication Tweet Result JSON
        currentCoroutineContext().ensureActive()
        step++
        val token = computeSyndicationToken(statusRef.tweetId)
        val syndicationUrl = "https://cdn.syndication.twimg.com/tweet-result?id=${statusRef.tweetId}&lang=en&token=$token"
        trace("> GET $syndicationUrl")
        onLogUpdate(log.toString(), step)

        try {
            val req = Request.Builder()
                .url(syndicationUrl)
                .get()
                .header("User-Agent", WEB_USER_AGENT)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                trace("< HTTP ${resp.code} (${body.length} bytes)")
                hops.add(RedirectHop(step, syndicationUrl, resp.code, resp.message, null, resp.header("Content-Type"), body.length.toLong()))

                when {
                    resp.code in setOf(401, 403, 404, 410) -> sawUnavailable = true
                    resp.isSuccessful && body.isNotBlank() -> {
                        val parsed = parseSyndicationJson(body, statusRef.tweetId, statusRef.canonicalUrl)
                        when {
                            parsed.variants.isNotEmpty() -> bestResult = parsed
                            parsed.error == XExtractionError.UNAVAILABLE_OR_RESTRICTED -> sawUnavailable = true
                            parsed.error == XExtractionError.NO_VIDEO_IN_POST -> {
                                sawNoVideo = true
                                bestResult = parsed
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            lastIoException = e
            trace("* Syndication IO error: ${e.message}")
        } catch (e: Exception) {
            trace("* Syndication parse error: ${e.message}")
        }

        // Strategy 2: Public FxTwitter JSON API (returns full MP4 resolution formats when available)
        if (bestResult == null || bestResult?.variants.isNullOrEmpty()) {
            currentCoroutineContext().ensureActive()
            step++
            val handlePath = statusRef.screenName ?: "i"
            val fxUrl = "https://api.fxtwitter.com/$handlePath/status/${statusRef.tweetId}"
            trace("> GET $fxUrl")
            onLogUpdate(log.toString(), step)

            try {
                val req = Request.Builder()
                    .url(fxUrl)
                    .get()
                    .header("User-Agent", "MediaXAndroid/1.1 (+https://github.com/alonedroid-maker/mixmedia)")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    trace("< HTTP ${resp.code} (${body.length} bytes)")
                    hops.add(RedirectHop(step, fxUrl, resp.code, resp.message, null, resp.header("Content-Type"), body.length.toLong()))

                    when {
                        resp.code in setOf(401, 403, 404, 410) -> sawUnavailable = true
                        resp.isSuccessful && body.isNotBlank() -> {
                            val parsed = parseFxTwitterJson(body, statusRef.tweetId, statusRef.canonicalUrl)
                            when {
                                parsed.variants.isNotEmpty() -> bestResult = parsed
                                parsed.error == XExtractionError.UNAVAILABLE_OR_RESTRICTED -> sawUnavailable = true
                                parsed.error == XExtractionError.NO_VIDEO_IN_POST -> {
                                    sawNoVideo = true
                                    if (bestResult == null) bestResult = parsed
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastIoException = e
                trace("* FxTwitter IO error: ${e.message}")
            } catch (e: Exception) {
                trace("* FxTwitter parse error: ${e.message}")
            }
        }

        // Strategy 3: Public VxTwitter JSON API fallback
        if (bestResult == null || bestResult?.variants.isNullOrEmpty()) {
            currentCoroutineContext().ensureActive()
            step++
            val handlePath = statusRef.screenName ?: "i"
            val vxUrl = "https://api.vxtwitter.com/$handlePath/status/${statusRef.tweetId}"
            trace("> GET $vxUrl")
            onLogUpdate(log.toString(), step)

            try {
                val req = Request.Builder()
                    .url(vxUrl)
                    .get()
                    .header("User-Agent", "MediaXAndroid/1.1 (+https://github.com/alonedroid-maker/mixmedia)")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    trace("< HTTP ${resp.code} (${body.length} bytes)")
                    hops.add(RedirectHop(step, vxUrl, resp.code, resp.message, null, resp.header("Content-Type"), body.length.toLong()))

                    when {
                        resp.code in setOf(401, 403, 404, 410) -> sawUnavailable = true
                        resp.isSuccessful && body.isNotBlank() -> {
                            val parsed = parseVxTwitterJson(body, statusRef.tweetId, statusRef.canonicalUrl)
                            when {
                                parsed.variants.isNotEmpty() -> bestResult = parsed
                                parsed.error == XExtractionError.UNAVAILABLE_OR_RESTRICTED -> sawUnavailable = true
                                parsed.error == XExtractionError.NO_VIDEO_IN_POST -> {
                                    sawNoVideo = true
                                    if (bestResult == null) bestResult = parsed
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastIoException = e
                trace("* VxTwitter IO error: ${e.message}")
            } catch (e: Exception) {
                trace("* VxTwitter parse error: ${e.message}")
            }
        }

        val resolved = bestResult
        if (resolved == null || resolved.variants.isEmpty()) {
            if (lastIoException != null && !sawUnavailable && !sawNoVideo && hops.isEmpty()) {
                throw lastIoException
            }
            val finalError = when {
                sawNoVideo -> XExtractionError.NO_VIDEO_IN_POST
                sawUnavailable -> XExtractionError.UNAVAILABLE_OR_RESTRICTED
                lastIoException != null && hops.isEmpty() -> throw lastIoException
                else -> XExtractionError.EXTRACTION_FAILED
            }
            trace("* Extraction ended with status=$finalError")
            return@withContext XExtractionResult(
                tweetId = statusRef.tweetId,
                canonicalUrl = statusRef.canonicalUrl,
                authorName = resolved?.authorName,
                authorHandle = resolved?.authorHandle,
                postText = resolved?.postText,
                thumbnailUrl = resolved?.thumbnailUrl,
                durationSeconds = resolved?.durationSeconds,
                variants = emptyList(),
                error = finalError,
                hops = hops,
                verboseLog = log.toString().trimEnd(),
            )
        }

        // Probe exact MP4 file sizes in parallel for top variants when missing
        val enrichedVariants = enrichVariantSizes(resolved.variants)
        trace("* Extracted ${enrichedVariants.size} MP4 variant(s): ${enrichedVariants.joinToString { it.qualityLabel }}")
        onLogUpdate(log.toString(), step)

        resolved.copy(
            variants = enrichedVariants,
            error = null,
            hops = hops,
            verboseLog = log.toString().trimEnd(),
        )
    }

    fun parseSyndicationJson(
        rawJson: String,
        tweetId: String,
        canonicalUrl: String,
    ): XExtractionResult {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return XExtractionResult(tweetId, canonicalUrl, error = XExtractionError.EXTRACTION_FAILED)

        val typename = root.optString("__typename")
        if (typename.contains("Tombstone", ignoreCase = true) || root.has("tombstone")) {
            return XExtractionResult(tweetId, canonicalUrl, error = XExtractionError.UNAVAILABLE_OR_RESTRICTED)
        }

        val userObj = root.optJSONObject("user")
        val authorName = userObj?.optString("name")?.takeIf { it.isNotBlank() }
        val authorHandle = userObj?.optString("screen_name")?.takeIf { it.isNotBlank() }
        val text = root.optString("text").takeIf { it.isNotBlank() }
        val baseTitle = buildSafeFileBase(authorHandle, tweetId)

        var thumbnailUrl: String? = null
        var durationSec: Long? = null
        val collected = mutableListOf<XVideoVariant>()

        val videoObj = root.optJSONObject("video")
        if (videoObj != null) {
            thumbnailUrl = videoObj.optString("poster").takeIf { it.startsWith("http") }
            val durationMs = videoObj.optLong("durationMs", 0L)
            if (durationMs > 0L) durationSec = (durationMs / 1000L).coerceAtLeast(1L)
            val aspectRatio = videoObj.optJSONArray("aspectRatio")
            collectVariantsFromArray(
                variantsArray = videoObj.optJSONArray("variants"),
                baseFileName = baseTitle,
                aspectWidth = aspectRatio?.optInt(0, 0) ?: 0,
                aspectHeight = aspectRatio?.optInt(1, 0) ?: 0,
                out = collected,
            )
        }

        val mediaDetails = root.optJSONArray("mediaDetails")
        var hasPhotoOrOtherMedia = false
        if (mediaDetails != null) {
            for (i in 0 until mediaDetails.length()) {
                val media = mediaDetails.optJSONObject(i) ?: continue
                val mType = media.optString("type").lowercase(Locale.US)
                if (thumbnailUrl == null) {
                    thumbnailUrl = media.optString("media_url_https").takeIf { it.startsWith("http") }
                }
                if (mType == "video" || mType == "animated_gif") {
                    val vInfo = media.optJSONObject("video_info")
                    if (vInfo != null) {
                        val dMs = vInfo.optLong("duration_millis", 0L)
                        if (durationSec == null && dMs > 0L) {
                            durationSec = (dMs / 1000L).coerceAtLeast(1L)
                        }
                        val aspect = vInfo.optJSONArray("aspect_ratio")
                        collectVariantsFromArray(
                            variantsArray = vInfo.optJSONArray("variants"),
                            baseFileName = baseTitle,
                            aspectWidth = aspect?.optInt(0, 0) ?: 0,
                            aspectHeight = aspect?.optInt(1, 0) ?: 0,
                            out = collected,
                        )
                    }
                } else {
                    hasPhotoOrOtherMedia = true
                }
            }
        }

        val sorted = normalizeAndSortVariants(collected, baseTitle)
        val hasTweetBody = text != null || authorHandle != null || hasPhotoOrOtherMedia
        val error = when {
            sorted.isNotEmpty() -> null
            hasTweetBody -> XExtractionError.NO_VIDEO_IN_POST
            else -> XExtractionError.EXTRACTION_FAILED
        }
        return XExtractionResult(
            tweetId = tweetId,
            canonicalUrl = canonicalUrl,
            authorName = authorName,
            authorHandle = authorHandle,
            postText = text,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSec,
            variants = sorted,
            error = error,
        )
    }

    fun parseFxTwitterJson(
        rawJson: String,
        tweetId: String,
        canonicalUrl: String,
    ): XExtractionResult {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return XExtractionResult(tweetId, canonicalUrl, error = XExtractionError.EXTRACTION_FAILED)

        val code = root.optInt("code", 200)
        if (code in setOf(401, 403, 404, 410)) {
            return XExtractionResult(tweetId, canonicalUrl, error = XExtractionError.UNAVAILABLE_OR_RESTRICTED)
        }

        val tweet = root.optJSONObject("tweet")
            ?: return XExtractionResult(tweetId, canonicalUrl, error = XExtractionError.EXTRACTION_FAILED)

        val author = tweet.optJSONObject("author")
        val authorName = author?.optString("name")?.takeIf { it.isNotBlank() }
        val authorHandle = author?.optString("screen_name")?.takeIf { it.isNotBlank() }
        val text = tweet.optString("text").takeIf { it.isNotBlank() }
        val baseTitle = buildSafeFileBase(authorHandle, tweetId)

        var thumbnailUrl: String? = null
        var durationSec: Long? = null
        val collected = mutableListOf<XVideoVariant>()

        val media = tweet.optJSONObject("media")
        val videos = media?.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val vid = videos.optJSONObject(i) ?: continue
                if (thumbnailUrl == null) {
                    thumbnailUrl = vid.optString("thumbnail_url").takeIf { it.startsWith("http") }
                }
                val dur = vid.optDouble("duration", 0.0)
                if (durationSec == null && !dur.isNaN() && dur > 0.0) {
                    durationSec = dur.toLong().coerceAtLeast(1L)
                }

                val formats = vid.optJSONArray("formats")
                if (formats != null && formats.length() > 0) {
                    for (j in 0 until formats.length()) {
                        val fmt = formats.optJSONObject(j) ?: continue
                        val url = fmt.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val container = fmt.optString("container").ifBlank { "mp4" }.uppercase(Locale.US)
                        val mime = fmt.optString("mime_type").lowercase(Locale.US)
                        if (container != "MP4" && !mime.contains("mp4")) continue
                        val bitrateBps = fmt.optInt("bitrate", 0)
                        val parsedDims = extractResolutionFromUrl(url)
                        val width = fmt.optInt("width", 0).takeIf { it > 0 } ?: parsedDims?.first
                        val height = fmt.optInt("height", 0).takeIf { it > 0 } ?: parsedDims?.second
                        collected.add(
                            buildVariant(
                                url = url,
                                width = width,
                                height = height,
                                bitrateKbps = if (bitrateBps > 0) (bitrateBps / 1000) else null,
                                container = "MP4",
                                baseFileName = baseTitle,
                            )
                        )
                    }
                }

                val directUrl = vid.optString("url").takeIf { it.startsWith("http") }
                if (directUrl != null && !directUrl.contains(".m3u8", ignoreCase = true)) {
                    val w = vid.optInt("width", 0).takeIf { it > 0 } ?: extractResolutionFromUrl(directUrl)?.first
                    val h = vid.optInt("height", 0).takeIf { it > 0 } ?: extractResolutionFromUrl(directUrl)?.second
                    collected.add(
                        buildVariant(
                            url = directUrl,
                            width = w,
                            height = h,
                            bitrateKbps = null,
                            container = "MP4",
                            baseFileName = baseTitle,
                        )
                    )
                }
            }
        }

        val sorted = normalizeAndSortVariants(collected, baseTitle)
        return XExtractionResult(
            tweetId = tweetId,
            canonicalUrl = canonicalUrl,
            authorName = authorName,
            authorHandle = authorHandle,
            postText = text,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSec,
            variants = sorted,
            error = if (sorted.isEmpty()) XExtractionError.NO_VIDEO_IN_POST else null,
        )
    }

    fun parseVxTwitterJson(
        rawJson: String,
        tweetId: String,
        canonicalUrl: String,
    ): XExtractionResult {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return XExtractionResult(tweetId, canonicalUrl, error = XExtractionError.EXTRACTION_FAILED)

        if (root.has("error")) {
            val errMsg = root.optString("error").lowercase(Locale.US)
            val errType = if (errMsg.contains("not found") || errMsg.contains("private") || errMsg.contains("protected") || errMsg.contains("suspended")) {
                XExtractionError.UNAVAILABLE_OR_RESTRICTED
            } else {
                XExtractionError.EXTRACTION_FAILED
            }
            return XExtractionResult(tweetId, canonicalUrl, error = errType)
        }

        val authorName = root.optString("user_name").takeIf { it.isNotBlank() }
        val authorHandle = root.optString("user_screen_name").takeIf { it.isNotBlank() }
        val text = root.optString("text").takeIf { it.isNotBlank() }
        val baseTitle = buildSafeFileBase(authorHandle, tweetId)

        var thumbnailUrl: String? = null
        var durationSec: Long? = null
        val collected = mutableListOf<XVideoVariant>()

        val mediaList = root.optJSONArray("media_extended")
        if (mediaList != null) {
            for (i in 0 until mediaList.length()) {
                val media = mediaList.optJSONObject(i) ?: continue
                val mType = media.optString("type").lowercase(Locale.US)
                if (mType == "video" || mType == "gif") {
                    val url = media.optString("url").takeIf { it.startsWith("http") } ?: continue
                    if (url.contains(".m3u8", ignoreCase = true)) continue
                    if (thumbnailUrl == null) {
                        thumbnailUrl = media.optString("thumbnail_url").takeIf { it.startsWith("http") }
                    }
                    val dMs = media.optLong("duration_millis", 0L)
                    if (durationSec == null && dMs > 0L) {
                        durationSec = (dMs / 1000L).coerceAtLeast(1L)
                    }
                    val sizeObj = media.optJSONObject("size")
                    val dimsFromUrl = extractResolutionFromUrl(url)
                    val w = sizeObj?.optInt("width", 0)?.takeIf { it > 0 } ?: dimsFromUrl?.first
                    val h = sizeObj?.optInt("height", 0)?.takeIf { it > 0 } ?: dimsFromUrl?.second
                    collected.add(
                        buildVariant(
                            url = url,
                            width = w,
                            height = h,
                            bitrateKbps = null,
                            container = "MP4",
                            baseFileName = baseTitle,
                        )
                    )
                }
            }
        }

        val sorted = normalizeAndSortVariants(collected, baseTitle)
        val hasTweetBody = authorHandle != null || text != null
        return XExtractionResult(
            tweetId = tweetId,
            canonicalUrl = canonicalUrl,
            authorName = authorName,
            authorHandle = authorHandle,
            postText = text,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSec,
            variants = sorted,
            error = when {
                sorted.isNotEmpty() -> null
                hasTweetBody -> XExtractionError.NO_VIDEO_IN_POST
                else -> XExtractionError.EXTRACTION_FAILED
            },
        )
    }

    private fun collectVariantsFromArray(
        variantsArray: JSONArray?,
        baseFileName: String,
        aspectWidth: Int,
        aspectHeight: Int,
        out: MutableList<XVideoVariant>,
    ) {
        if (variantsArray == null) return
        for (i in 0 until variantsArray.length()) {
            val v = variantsArray.optJSONObject(i) ?: continue
            val contentType = v.optString("content_type").ifBlank { v.optString("type") }.lowercase(Locale.US)
            val url = (v.optString("src").ifBlank { v.optString("url") }).trim()
            if (!url.startsWith("http")) continue
            if (contentType.isNotEmpty() && !contentType.contains("mp4")) continue
            if (url.contains(".m3u8", ignoreCase = true) && !url.contains(".mp4", ignoreCase = true)) continue

            val bitrateBps = v.optInt("bitrate", 0)
            val dims = extractResolutionFromUrl(url)
            val width = dims?.first ?: aspectWidth.takeIf { it > 0 && dims != null }
            val height = dims?.second ?: aspectHeight.takeIf { it > 0 && dims != null }
            out.add(
                buildVariant(
                    url = url,
                    width = width,
                    height = height,
                    bitrateKbps = if (bitrateBps > 0) bitrateBps / 1000 else null,
                    container = "MP4",
                    baseFileName = baseFileName,
                )
            )
        }
    }

    private fun buildVariant(
        url: String,
        width: Int?,
        height: Int?,
        bitrateKbps: Int?,
        container: String,
        baseFileName: String,
    ): XVideoVariant {
        val shortEdge = when {
            width != null && height != null -> minOf(width, height)
            height != null -> height
            width != null -> width
            else -> null
        }
        val qualityLabel = when {
            shortEdge != null && shortEdge >= 1080 -> "1080p Full HD"
            shortEdge != null && shortEdge >= 720 -> "720p HD"
            shortEdge != null && shortEdge >= 480 -> "${shortEdge}p SD"
            shortEdge != null && shortEdge > 0 -> "${shortEdge}p"
            bitrateKbps != null && bitrateKbps > 0 -> "$bitrateKbps kbps"
            else -> "MP4"
        }
        val qualityTag = qualityLabel.substringBefore(' ').replace(Regex("[^A-Za-z0-9_-]"), "")
        return XVideoVariant(
            url = url,
            qualityLabel = qualityLabel,
            width = width,
            height = height,
            bitrateKbps = bitrateKbps,
            container = container,
            suggestedFileName = "${baseFileName}_${qualityTag.ifBlank { "video" }}.mp4",
        )
    }

    private fun normalizeAndSortVariants(
        raw: List<XVideoVariant>,
        baseFileName: String,
    ): List<XVideoVariant> {
        if (raw.isEmpty()) return emptyList()
        return raw
            .distinctBy { it.url.substringBefore('?') }
            .sortedWith(
                compareByDescending<XVideoVariant> { (it.width ?: 0) * (it.height ?: 0) }
                    .thenByDescending { it.height ?: 0 }
                    .thenByDescending { it.bitrateKbps ?: 0 }
            )
            .mapIndexed { idx, variant ->
                if (variant.qualityLabel == "MP4" && raw.size > 1) {
                    val label = when (idx) {
                        0 -> "High Quality"
                        raw.lastIndex -> "Data Saver"
                        else -> "Medium Quality"
                    }
                    variant.copy(
                        qualityLabel = label,
                        suggestedFileName = "${baseFileName}_${label.replace(' ', '_')}.mp4",
                    )
                } else {
                    variant
                }
            }
    }

    private suspend fun enrichVariantSizes(variants: List<XVideoVariant>): List<XVideoVariant> = coroutineScope {
        variants.mapIndexed { idx, variant ->
            if (idx >= 4 || variant.contentLengthBytes != null) {
                async { variant }
            } else {
                async {
                    val len = probeContentLength(variant.url)
                    if (len != null && len > 0L) {
                        variant.copy(
                            contentLengthBytes = len,
                            fileSizeFormatted = VideoLinkExtractor.formatByteSize(len),
                        )
                    } else {
                        variant
                    }
                }
            }
        }.awaitAll()
    }

    private fun probeContentLength(url: String): Long? {
        return runCatching {
            val headReq = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", WEB_USER_AGENT)
                .build()
            probeClient.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
                }
            }
            val rangeReq = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .header("User-Agent", WEB_USER_AGENT)
                .build()
            probeClient.newCall(rangeReq).execute().use { resp ->
                val cr = resp.header("Content-Range")
                if (resp.code == 206 && !cr.isNullOrBlank()) {
                    cr.substringAfterLast('/', "").trim().toLongOrNull()?.takeIf { it > 0L }
                } else {
                    resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                }
            }
        }.getOrNull()
    }

    internal fun extractResolutionFromUrl(url: String): Pair<Int, Int>? {
        val m = RESOLUTION_FROM_URL_REGEX.find(url) ?: return null
        val w = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val h = m.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return w to h
    }

    private fun buildSafeFileBase(authorHandle: String?, tweetId: String): String {
        val safeAuthor = authorHandle
            ?.replace(Regex("[^A-Za-z0-9_.-]"), "")
            ?.take(24)
            ?.takeIf { it.isNotBlank() }
        return if (safeAuthor != null) "X_${safeAuthor}_$tweetId" else "X_$tweetId"
    }
}
