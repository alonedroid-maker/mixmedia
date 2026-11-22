package com.example.network

import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class YouTubeExtractionResult(
    val videoId: String,
    val title: String,
    val author: String?,
    val durationSeconds: Long?,
    val options: List<YouTubeStreamOption>,
    val hops: List<RedirectHop>,
    val verboseLog: String,
)

class YouTubeExtractor(
    private val client: OkHttpClient = defaultClient(),
) {
    private data class InnerTubeClientSpec(
        val label: String,
        val clientId: String,
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val extraContext: Map<String, Any>,
        val thirdPartyEmbedUrl: String? = null,
    )

    companion object {
        private val YOUTUBE_ID_REGEX = Regex(
            """(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/|live/|v/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
            RegexOption.IGNORE_CASE
        )

        private val API_KEY_REGEX = Regex(""""INNERTUBE_API_KEY"\s*:\s*"([^"]+)"""")
        private val VISITOR_DATA_REGEX = Regex(""""visitorData"\s*:\s*"([^"]+)"""")
        private val PLAYER_BASE_URL = "https://www.youtube.com/youtubei/v1/player"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val CLIENTS = listOf(
            InnerTubeClientSpec(
                label = "ANDROID_VR",
                clientId = "28",
                clientName = "ANDROID_VR",
                clientVersion = "1.65.10",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                extraContext = mapOf(
                    "deviceMake" to "Oculus",
                    "deviceModel" to "Quest 3",
                    "osName" to "Android",
                    "osVersion" to "12L",
                    "androidSdkVersion" to 32,
                ),
            ),
            InnerTubeClientSpec(
                label = "IOS",
                clientId = "5",
                clientName = "IOS",
                clientVersion = "20.10.4",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                extraContext = mapOf(
                    "deviceMake" to "Apple",
                    "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone",
                    "osVersion" to "18.3.2.22D82",
                ),
            ),
            InnerTubeClientSpec(
                label = "ANDROID",
                clientId = "3",
                clientName = "ANDROID",
                clientVersion = "20.10.38",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
                extraContext = mapOf(
                    "osName" to "Android",
                    "osVersion" to "14",
                    "androidSdkVersion" to 34,
                ),
            ),
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(14, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        fun isYouTubeUrl(url: String): Boolean = extractVideoId(url) != null

        fun extractVideoId(url: String): String? {
            return YOUTUBE_ID_REGEX.find(url)?.groupValues?.getOrNull(1)
        }
    }

    suspend fun extract(
        inputUrl: String,
        cookieHeader: String? = null,
        onLogUpdate: (traceSoFar: String, currentStep: Int) -> Unit = { _, _ -> },
    ): YouTubeExtractionResult = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(inputUrl)
            ?: throw IllegalArgumentException("Invalid YouTube URL")

        val log = StringBuilder()
        val hops = mutableListOf<RedirectHop>()
        var step = 0
        var sawExplicitBotGate = false

        fun trace(msg: String) {
            log.appendLine(msg)
        }

        trace("[YouTube] Target videoId=$videoId")
        if (!cookieHeader.isNullOrBlank()) {
            trace("[YouTube] Using verified session cookies (${cookieHeader.length} chars)")
        }
        onLogUpdate(log.toString(), 1)

        var bestTitle: String? = null
        var bestAuthor: String? = null
        var bestDuration: Long? = null
        var apiKey: String? = null
        var visitorData: String? = null
        val collectedOptions = mutableListOf<YouTubeStreamOption>()

        // 0. Fast metadata via oEmbed (title + author)
        runCatching {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            client.newCall(Request.Builder().url(oembedUrl).get().build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val oj = JSONObject(resp.body?.string().orEmpty())
                    bestTitle = oj.optString("title").takeIf { it.isNotBlank() }
                    bestAuthor = oj.optString("author_name").takeIf { it.isNotBlank() }
                    trace("* oEmbed metadata: title=\"$bestTitle\" author=\"$bestAuthor\"")
                }
            }
        }

        // 1. Fetch Watch page for apiKey, visitorData, and optional direct ytInitialPlayerResponse
        currentCoroutineContext().ensureActive()
        step++
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        trace("> GET $watchUrl")
        onLogUpdate(log.toString(), step)

        runCatching {
            val reqBuilder = Request.Builder()
                .url(watchUrl)
                .get()
                .header("User-Agent", WEB_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
            if (!cookieHeader.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookieHeader)
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                trace("< HTTP ${resp.code} (${html.length} bytes)")
                hops.add(RedirectHop(step, watchUrl, resp.code, resp.message, null, resp.header("Content-Type"), html.length.toLong()))

                apiKey = API_KEY_REGEX.find(html)?.groupValues?.getOrNull(1)
                visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)

                extractJsonAfterMarker(html, "ytInitialPlayerResponse = ")?.let { playerJson ->
                    val playStatus = playerJson.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                    val reason = playerJson.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
                    if (playStatus.equals("LOGIN_REQUIRED", ignoreCase = true) ||
                        reason.contains("not a bot", ignoreCase = true)
                    ) {
                        sawExplicitBotGate = true
                        trace("* Watch playabilityStatus=$playStatus ($reason)")
                    }
                    val parsed = parsePlayerJson(playerJson, videoId, WEB_USER_AGENT)
                    if (parsed.title != null && bestTitle == null) bestTitle = parsed.title
                    if (parsed.author != null && bestAuthor == null) bestAuthor = parsed.author
                    if (parsed.durationSeconds != null && bestDuration == null) bestDuration = parsed.durationSeconds
                    if (parsed.options.isNotEmpty() && isStreamUrlReachable(parsed.options.first())) {
                        collectedOptions.addAll(parsed.options)
                        trace("* Watch HTML yielded ${parsed.options.size} verified direct streams.")
                    }
                }
            }
        }.onFailure { if (it is CancellationException) throw it else trace("* Watch fetch error: ${it.message}") }

        // 2. Try native InnerTube clients with extracted apiKey & visitorData
        if (collectedOptions.isEmpty()) {
            val playerEndpoint = if (!apiKey.isNullOrBlank()) {
                "$PLAYER_BASE_URL?key=$apiKey&prettyPrint=false"
            } else {
                "$PLAYER_BASE_URL?prettyPrint=false"
            }

            for (spec in CLIENTS) {
                currentCoroutineContext().ensureActive()
                step++
                trace("> POST $PLAYER_BASE_URL [client=${spec.label}]")
                onLogUpdate(log.toString(), step)

                val payload = buildInnerTubePayload(videoId, spec, visitorData)
                val reqBuilder = Request.Builder()
                    .url(playerEndpoint)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", spec.userAgent)
                    .header("Origin", "https://www.youtube.com")
                    .header("X-YouTube-Client-Name", spec.clientId)
                    .header("X-YouTube-Client-Version", spec.clientVersion)

                if (!visitorData.isNullOrBlank()) {
                    reqBuilder.header("X-Goog-Visitor-Id", visitorData!!)
                }
                if (!cookieHeader.isNullOrBlank()) {
                    reqBuilder.header("Cookie", cookieHeader)
                }

                try {
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        val status = resp.code
                        val body = resp.body?.string().orEmpty()
                        trace("< HTTP $status (${body.length} bytes)")
                        hops.add(
                            RedirectHop(
                                step = step,
                                requestUrl = "$PLAYER_BASE_URL (${spec.label})",
                                statusCode = status,
                                statusMessage = resp.message,
                                locationHeader = null,
                                contentType = resp.header("Content-Type"),
                                contentLength = body.length.toLong(),
                            )
                        )

                        if (resp.isSuccessful && body.isNotBlank()) {
                            val json = runCatching { JSONObject(body) }.getOrNull()
                            if (json != null) {
                                val playStatus = json.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                                val reason = json.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
                                trace("* playabilityStatus=$playStatus ${if (reason.isNotBlank()) "($reason)" else ""}")

                                if (playStatus.equals("LOGIN_REQUIRED", ignoreCase = true) ||
                                    reason.contains("not a bot", ignoreCase = true)
                                ) {
                                    sawExplicitBotGate = true
                                }

                                val parsed = parsePlayerJson(json, videoId, spec.userAgent)
                                if (parsed.title != null && bestTitle == null) bestTitle = parsed.title
                                if (parsed.author != null && bestAuthor == null) bestAuthor = parsed.author
                                if (parsed.durationSeconds != null && bestDuration == null) bestDuration = parsed.durationSeconds

                                if (parsed.options.isNotEmpty() && isStreamUrlReachable(parsed.options.first())) {
                                    trace("* Verified ${parsed.options.size} direct streams from ${spec.label}")
                                    collectedOptions.addAll(parsed.options)
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    trace("* ${spec.label} failed: ${e.message}")
                }

                onLogUpdate(log.toString(), step)

                val hasMuxed = collectedOptions.any { it.category == MediaFormatCategory.VIDEO_WITH_AUDIO }
                val hasAudio = collectedOptions.any { it.category == MediaFormatCategory.AUDIO_ONLY }
                if (hasMuxed && hasAudio) break
            }
        }

        val finalOptions = deduplicateAndSortOptions(collectedOptions)
        if (finalOptions.isEmpty()) {
            trace(
                if (sawExplicitBotGate) {
                    "* YouTube blocked extraction with bot/login gate."
                } else {
                    "* No playable YouTube streams could be extracted."
                }
            )
        }

        YouTubeExtractionResult(
            videoId = videoId,
            title = bestTitle ?: "YouTube_$videoId",
            author = bestAuthor,
            durationSeconds = bestDuration,
            options = finalOptions,
            hops = hops,
            verboseLog = log.toString().trimEnd(),
        )
    }

    private fun isStreamUrlReachable(option: YouTubeStreamOption): Boolean {
        return runCatching {
            val req = Request.Builder()
                .url(option.url)
                .get()
                .header("Range", "bytes=0-63")
                .header("User-Agent", option.userAgent ?: WEB_USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                resp.code == 200 || resp.code == 206
            }
        }.getOrDefault(false)
    }

    private fun isHlsPlaylistReachable(playlistUrl: String): Boolean {
        return runCatching {
            val req = Request.Builder()
                .url(playlistUrl)
                .get()
                .header("User-Agent", WEB_USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val text = resp.body?.string().orEmpty()
                text.contains("#EXTM3U")
            }
        }.getOrDefault(false)
    }

    internal data class ParsedVkrData(
        val title: String?,
        val options: List<YouTubeStreamOption>,
    )

    internal fun parseVkrResponse(rawJson: String, videoId: String, fallbackTitle: String? = null): ParsedVkrData {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return ParsedVkrData(fallbackTitle, emptyList())
        val data = root.optJSONObject("data") ?: return ParsedVkrData(fallbackTitle, emptyList())
        val rawTitle = data.optString("title").takeIf { it.isNotBlank() } ?: fallbackTitle
        val safeTitle = sanitizeForFileName(rawTitle ?: "YouTube_$videoId")
        val downloads = data.optJSONArray("downloads") ?: return ParsedVkrData(rawTitle, emptyList())

        val options = mutableListOf<YouTubeStreamOption>()
        for (i in 0 until downloads.length()) {
            val item = downloads.optJSONObject(i) ?: continue
            val fid = item.optString("format_id", "")
            val url = item.optString("url", "").trim()
            if (url.isEmpty() || fid.contains("storyboard", ignoreCase = true) || fid.contains("-drc", ignoreCase = true)) {
                continue
            }

            val isHls = url.contains("manifest.googlevideo.com", ignoreCase = true)
            val ext = item.optString("ext", "").lowercase(Locale.US)
            val formatLabel = item.optString("format", "")
            val width = item.optInt("width", 0)
            val height = item.optInt("height", 0)
            val sizeStr = item.optString("size", "").takeIf { it.isNotBlank() && it != "0 KB" }

            if (isHls) {
                // HLS streams on manifest.googlevideo.com: 96 = 1080p TS (Video+Audio), 233/234 = AAC Audio fMP4
                when {
                    fid == "96" || height >= 720 -> {
                        val q = if (height > 0) "${height}p HD" else "1080p HD"
                        options.add(
                            YouTubeStreamOption(
                                itag = fid.toIntOrNull() ?: 96,
                                url = url,
                                qualityLabel = q,
                                container = "TS",
                                category = MediaFormatCategory.VIDEO_WITH_AUDIO,
                                fileSizeFormatted = sizeStr,
                                suggestedFileName = "${safeTitle}_${if (height > 0) "${height}p" else "1080p"}.ts",
                                userAgent = WEB_USER_AGENT,
                            )
                        )
                    }
                    fid == "234" || fid == "233" || (width == 0 && height == 0) -> {
                        val isHigh = fid == "234"
                        val q = if (isHigh) "128 kbps" else "48 kbps"
                        options.add(
                            YouTubeStreamOption(
                                itag = fid.toIntOrNull() ?: if (isHigh) 234 else 233,
                                url = url,
                                qualityLabel = q,
                                container = "M4A",
                                category = MediaFormatCategory.AUDIO_ONLY,
                                bitrateKbps = if (isHigh) 128 else 48,
                                fileSizeFormatted = sizeStr,
                                suggestedFileName = "${safeTitle}_${if (isHigh) "HQ_Audio" else "Audio"}.m4a",
                                userAgent = WEB_USER_AGENT,
                            )
                        )
                    }
                }
            } else {
                val itag = fid.toIntOrNull() ?: 0
                val isAudio = ext in setOf("m4a", "mp3", "opus", "ogg") || (width == 0 && height == 0)
                val isMuxed = itag in setOf(18, 22, 37, 38) || (!isAudio && formatLabel.contains("18", ignoreCase = true))
                val category = when {
                    isAudio -> MediaFormatCategory.AUDIO_ONLY
                    isMuxed -> MediaFormatCategory.VIDEO_WITH_AUDIO
                    else -> MediaFormatCategory.VIDEO_ONLY
                }
                val container = when (ext) {
                    "m4a" -> "M4A"
                    "mp3" -> "MP3"
                    "webm" -> if (isAudio) "OPUS" else "WEBM"
                    "mp4" -> "MP4"
                    else -> if (isAudio) "M4A" else "MP4"
                }
                val qualityLabel = when {
                    isAudio -> if (itag == 140 || itag == 251) "128 kbps" else "Audio"
                    height > 0 -> "${height}p"
                    else -> formatLabel.ifBlank { "Video" }
                }
                val finalExt = when (container) {
                    "M4A" -> "m4a"
                    "MP3" -> "mp3"
                    "OPUS", "WEBM" -> "webm"
                    else -> "mp4"
                }
                options.add(
                    YouTubeStreamOption(
                        itag = itag,
                        url = url,
                        qualityLabel = qualityLabel,
                        container = container,
                        category = category,
                        fileSizeFormatted = sizeStr,
                        suggestedFileName = "${safeTitle}_${qualityLabel.replace(" ", "_")}.$finalExt",
                        userAgent = WEB_USER_AGENT,
                    )
                )
            }
        }
        return ParsedVkrData(rawTitle, options)
    }

    internal data class ParsedPlayerData(
        val title: String?,
        val author: String?,
        val durationSeconds: Long?,
        val options: List<YouTubeStreamOption>,
    )

    internal fun parsePlayerJson(
        root: JSONObject,
        videoId: String,
        userAgent: String,
    ): ParsedPlayerData {
        val details = root.optJSONObject("videoDetails")
        val rawTitle = details?.optString("title")?.takeIf { it.isNotBlank() }
        val author = details?.optString("author")?.takeIf { it.isNotBlank() }
        val durationSec = details?.optString("lengthSeconds")?.toLongOrNull()

        val streamingData = root.optJSONObject("streamingData")
            ?: return ParsedPlayerData(rawTitle, author, durationSec, emptyList())

        val safeTitle = sanitizeForFileName(rawTitle ?: "YouTube_$videoId")
        val options = mutableListOf<YouTubeStreamOption>()

        streamingData.optJSONArray("formats")?.let { formats ->
            parseFormatArray(
                array = formats,
                isAdaptive = false,
                safeTitle = safeTitle,
                durationSec = durationSec,
                userAgent = userAgent,
                out = options,
            )
        }

        streamingData.optJSONArray("adaptiveFormats")?.let { adaptive ->
            parseFormatArray(
                array = adaptive,
                isAdaptive = true,
                safeTitle = safeTitle,
                durationSec = durationSec,
                userAgent = userAgent,
                out = options,
            )
        }

        return ParsedPlayerData(rawTitle, author, durationSec, options)
    }

    private fun parseFormatArray(
        array: JSONArray,
        isAdaptive: Boolean,
        safeTitle: String,
        durationSec: Long?,
        userAgent: String,
        out: MutableList<YouTubeStreamOption>,
    ) {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val directUrl = item.optString("url", "").trim()
            if (directUrl.isEmpty()) continue

            val itag = item.optInt("itag", 0)
            val mimeType = item.optString("mimeType", "").lowercase(Locale.US)
            val bitrateBps = item.optLong("bitrate", 0L).takeIf { it > 0L }
                ?: item.optLong("averageBitrate", 0L).takeIf { it > 0L }
            val bitrateKbps = bitrateBps?.let { (it / 1000L).toInt().coerceAtLeast(1) }

            val contentLength = item.optString("contentLength", "").toLongOrNull()
                ?: if (bitrateBps != null && durationSec != null && durationSec > 0L) {
                    (bitrateBps * durationSec) / 8L
                } else {
                    null
                }

            val isAudioOnly = mimeType.startsWith("audio/")
            val category = when {
                isAudioOnly -> MediaFormatCategory.AUDIO_ONLY
                !isAdaptive -> MediaFormatCategory.VIDEO_WITH_AUDIO
                else -> MediaFormatCategory.VIDEO_ONLY
            }

            val container = when {
                mimeType.contains("mp4") && isAudioOnly -> "M4A"
                mimeType.contains("mp4") -> "MP4"
                mimeType.contains("webm") && isAudioOnly -> "OPUS"
                mimeType.contains("webm") -> "WEBM"
                mimeType.contains("3gpp") -> "3GP"
                else -> if (isAudioOnly) "M4A" else "MP4"
            }

            val height = item.optInt("height", 0)
            val fps = item.optInt("fps", 0)
            val qualityLabel = when {
                isAudioOnly -> "${bitrateKbps ?: 128} kbps"
                item.optString("qualityLabel").isNotBlank() -> item.optString("qualityLabel")
                height > 0 -> if (fps >= 50) "${height}p${fps}" else "${height}p"
                else -> item.optString("quality", "Standard").uppercase(Locale.US)
            }

            val extLower = when (container) {
                "M4A" -> "m4a"
                "OPUS", "WEBM" -> "webm"
                "3GP" -> "3gp"
                else -> "mp4"
            }
            val labelSlug = qualityLabel.replace(Regex("""\s+"""), "_")
            val fileName = "${safeTitle}_${labelSlug}.$extLower"

            out.add(
                YouTubeStreamOption(
                    itag = itag,
                    url = directUrl,
                    qualityLabel = qualityLabel,
                    container = container,
                    category = category,
                    bitrateKbps = bitrateKbps,
                    contentLengthBytes = contentLength,
                    fileSizeFormatted = VideoLinkExtractor.formatByteSize(contentLength),
                    suggestedFileName = fileName,
                    userAgent = userAgent,
                )
            )
        }
    }

    private fun deduplicateAndSortOptions(options: List<YouTubeStreamOption>): List<YouTubeStreamOption> {
        val unique = linkedMapOf<String, YouTubeStreamOption>()
        for (opt in options) {
            val key = "${opt.category.name}|${opt.qualityLabel}|${opt.container}"
            val existing = unique[key]
            if (existing == null || (opt.contentLengthBytes ?: 0L) > (existing.contentLengthBytes ?: 0L)) {
                unique[key] = opt
            }
        }

        return unique.values.sortedWith(
            compareBy<YouTubeStreamOption> {
                when (it.category) {
                    MediaFormatCategory.VIDEO_WITH_AUDIO -> 0
                    MediaFormatCategory.AUDIO_ONLY -> 1
                    MediaFormatCategory.VIDEO_ONLY -> 2
                }
            }.thenByDescending {
                if (it.isAudioOnly) {
                    it.bitrateKbps ?: 0
                } else {
                    it.qualityLabel.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0
                }
            }.thenBy { if (it.container == "MP4" || it.container == "M4A" || it.container == "TS") 0 else 1 }
        )
    }

    private fun buildInnerTubePayload(
        videoId: String,
        spec: InnerTubeClientSpec,
        visitorData: String?,
    ): String {
        val clientObj = JSONObject().apply {
            put("clientName", spec.clientName)
            put("clientVersion", spec.clientVersion)
            put("hl", "en")
            put("gl", "US")
            if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
            spec.extraContext.forEach { (k, v) -> put(k, v) }
        }
        val contextObj = JSONObject().apply {
            put("client", clientObj)
            if (!spec.thirdPartyEmbedUrl.isNullOrBlank()) {
                put("thirdParty", JSONObject().apply { put("embedUrl", spec.thirdPartyEmbedUrl) })
            }
        }
        return JSONObject().apply {
            put("context", contextObj)
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()
    }

    private fun extractJsonAfterMarker(html: String, marker: String): JSONObject? {
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val start = html.indexOf('{', idx + marker.length)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        val jsonStr = html.substring(start, i + 1)
                        return runCatching { JSONObject(jsonStr) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun sanitizeForFileName(raw: String): String {
        return raw.replace(Regex("""[\\/:*?"<>|]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), "_")
            .take(64)
            .ifBlank { "YouTube_Video" }
    }
}
