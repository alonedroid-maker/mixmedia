package com.example.web

import com.example.media.MediaPreferences
import com.example.media.MediaSearchResult
import com.example.media.MediaType
import com.example.media.MetadataRepository
import com.example.media.SourceDiscoveryEngine
import com.example.media.SourceSitesCatalog
import com.example.media.TmdbDiscoverFilters
import com.example.media.WatchRegions
import com.example.network.CurlRedirectResolver
import com.example.network.InstagramExtractor
import com.example.network.UnsupportedPlatformException
import com.example.network.XExtractionException
import com.example.network.XTwitterExtractor
import com.example.network.YouTubeExtractor
import com.example.network.YouTubeUnavailableException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class MediaXWebServer(
    private val host: String = "0.0.0.0",
    private val port: Int = 8080,
    private val allowedCorsOrigins: Set<String> = emptySet(),
    private val allowWildcardCors: Boolean = false,
) {
    private val safeRedirectClient: OkHttpClient = UrlSecurityGuard.createSafeOkHttpClient(followRedirects = true)
    private val safeNoRedirectClient: OkHttpClient = UrlSecurityGuard.createSafeOkHttpClient(followRedirects = false)

    private val metadataRepo = MetadataRepository(client = safeRedirectClient)
    private val sourceEngine = SourceDiscoveryEngine(client = safeRedirectClient)
    private val resolver = CurlRedirectResolver(
        client = safeNoRedirectClient,
        youtubeExtractor = YouTubeExtractor(client = safeRedirectClient),
        instagramExtractor = InstagramExtractor(client = safeRedirectClient),
        xTwitterExtractor = XTwitterExtractor(client = safeRedirectClient),
    )

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    val boundPort: Int
        get() = server?.address?.port ?: port

    fun start() {
        val srv = HttpServer.create(InetSocketAddress(host, port), 64)
        val pool = Executors.newCachedThreadPool()
        srv.executor = pool
        srv.createContext("/") { exchange ->
            handleExchange(exchange)
        }
        srv.start()
        server = srv
        executor = pool
    }

    fun stop() {
        server?.stop(1)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun handleExchange(exchange: HttpExchange) {
        try {
            val method = exchange.requestMethod.uppercase(Locale.US)
            val path = exchange.requestURI.rawPath ?: "/"
            applyCommonHeaders(exchange, path)

            if (method == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }

            if (path.startsWith("/api/")) {
                handleApi(exchange, method, path)
            } else {
                handleStatic(exchange, method, path)
            }
        } catch (e: Exception) {
            runCatching {
                sendJson(
                    exchange,
                    500,
                    JSONObject().apply {
                        put("ok", false)
                        put("error", e.message ?: "Internal server error")
                    },
                )
            }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun applyCommonHeaders(exchange: HttpExchange, path: String) {
        val headers = exchange.responseHeaders
        headers.set("X-Content-Type-Options", "nosniff")
        headers.set("Referrer-Policy", "no-referrer")
        if (!path.startsWith("/api/download-proxy")) {
            headers.set(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; " +
                    "script-src 'self'; connect-src 'self' https:; media-src 'self' https:; frame-ancestors 'self';"
            )
        }
        val origin = exchange.requestHeaders.getFirst("Origin")?.trim().orEmpty()
        if (origin.isNotEmpty()) {
            if (allowWildcardCors) {
                headers.set("Access-Control-Allow-Origin", "*")
                headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                headers.set("Access-Control-Allow-Headers", "Content-Type")
            } else if (allowedCorsOrigins.contains(origin)) {
                headers.set("Access-Control-Allow-Origin", origin)
                headers.set("Vary", "Origin")
                headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                headers.set("Access-Control-Allow-Headers", "Content-Type")
            }
        }
    }

    private fun handleStatic(exchange: HttpExchange, method: String, rawPath: String) {
        if (method != "GET" && method != "HEAD") {
            sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8")
            return
        }
        val normalized = rawPath.removePrefix("/").ifBlank { "index.html" }
        val safeName = if (normalized.contains("..") || normalized.contains('\\')) {
            "index.html"
        } else {
            normalized
        }
        val resolvedFile = STATIC_ALIASES[safeName] ?: safeName
        val resourcePath = if (STATIC_FILES.containsKey(resolvedFile)) "/web/$resolvedFile" else "/web/index.html"
        val contentType = STATIC_FILES[resolvedFile] ?: STATIC_FILES["index.html"]!!
        val bytes = javaClass.getResourceAsStream(resourcePath)?.use { it.readBytes() }
        if (bytes == null) {
            sendText(exchange, 404, "Not Found", "text/plain; charset=utf-8")
            return
        }
        val headers = exchange.responseHeaders
        headers.set("Content-Type", contentType)
        headers.set(
            "Cache-Control",
            if (resourcePath.endsWith("sw.js") || resourcePath.endsWith("index.html")) {
                "no-cache, must-revalidate"
            } else {
                "public, max-age=3600"
            },
        )
        if (method == "HEAD") {
            exchange.sendResponseHeaders(200, -1)
        } else {
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun handleApi(exchange: HttpExchange, method: String, path: String) {
        val query = parseQueryParams(exchange.requestURI.rawQuery)
        when {
            path == "/api/health" && method == "GET" -> {
                sendJson(
                    exchange,
                    200,
                    JSONObject().apply {
                        put("ok", true)
                        put("service", "MediaX-Web")
                        put("version", WebBuildConfig.VERSION_NAME)
                        put("tmdbConfigured", metadataRepo.isTmdbBackendConfigured(MediaPreferences()))
                    },
                )
            }

            path == "/api/config" && method == "GET" -> {
                val prefs = MediaPreferences()
                val regions = JSONArray().apply {
                    WatchRegions.SUPPORTED.forEach { r ->
                        put(JSONObject().apply {
                            put("code", r.code)
                            put("labelEn", r.labelEn)
                            put("labelFa", r.labelFa)
                        })
                    }
                }
                val sites = JSONArray().apply {
                    SourceSitesCatalog.BUILT_IN_SITES.forEach { put(WebJsonMapper.siteConfigToJson(it)) }
                }
                sendJson(
                    exchange,
                    200,
                    JSONObject().apply {
                        put("version", WebBuildConfig.VERSION_NAME)
                        put("versionCode", WebBuildConfig.VERSION_CODE)
                        put("tmdbConfigured", metadataRepo.isTmdbBackendConfigured(prefs))
                        put("builtInSites", sites)
                        put("watchRegions", regions)
                    },
                )
            }

            path == "/api/trending" && method == "GET" -> {
                val prefs = WebJsonMapper.parsePreferences(
                    langCode = query["lang"],
                    regionCode = query["region"],
                    trendingWindow = query["window"],
                )
                val forceRefresh = query["refresh"] == "true"
                val bundle = runBlocking {
                    metadataRepo.getTrendingFeed(
                        preferences = prefs,
                        persistence = null,
                        forceRefresh = forceRefresh,
                    )
                }
                sendJson(exchange, 200, WebJsonMapper.trendingBundleToJson(bundle))
            }

            path == "/api/genres" && method == "GET" -> {
                val prefs = WebJsonMapper.parsePreferences(
                    langCode = query["lang"],
                    regionCode = query["region"],
                    trendingWindow = null,
                )
                val genres = runBlocking { metadataRepo.getAvailableGenres(prefs) }
                sendJson(
                    exchange,
                    200,
                    JSONObject().apply {
                        put("genres", JSONArray().apply { genres.forEach { put(WebJsonMapper.genreToJson(it)) } })
                    },
                )
            }

            path == "/api/search" && method == "GET" -> {
                val q = query["q"]?.take(200).orEmpty()
                val typeParam = query["type"]?.trim()?.uppercase(Locale.US)
                val mediaType = when (typeParam) {
                    "MOVIE" -> MediaType.MOVIE
                    "TV" -> MediaType.TV
                    else -> null
                }
                val filters = TmdbDiscoverFilters(
                    genreId = query["genreId"]?.toIntOrNull()?.takeIf { it > 0 },
                    minRating = query["minRating"]?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 },
                    releaseYear = query["year"]?.toIntOrNull()?.takeIf { it in 1880..2100 },
                )
                val page = query["page"]?.toIntOrNull()?.coerceIn(1, 100) ?: 1
                val prefs = WebJsonMapper.parsePreferences(
                    langCode = query["lang"],
                    regionCode = query["region"],
                    trendingWindow = query["window"],
                )
                val result = runBlocking {
                    metadataRepo.searchAndDiscoverMedia(
                        rawQuery = q,
                        mediaTypeFilter = mediaType,
                        filters = filters,
                        page = page,
                        preferences = prefs,
                    )
                }
                result.fold(
                    onSuccess = { sendJson(exchange, 200, WebJsonMapper.searchPageToJson(it)) },
                    onFailure = { sendError(exchange, 502, it.message ?: "Search failed") },
                )
            }

            path == "/api/details" && method == "GET" -> {
                val title = query["title"]?.trim()?.take(200).orEmpty()
                val id = query["id"]?.trim()?.take(120).orEmpty()
                val tmdbId = query["tmdbId"]?.toIntOrNull()?.takeIf { it > 0 }
                val imdbId = query["imdbId"]?.trim()?.takeIf { Regex("^tt\\d+$").matches(it) }
                val tvMazeId = query["tvMazeId"]?.toIntOrNull()?.takeIf { it > 0 }
                if (title.isBlank() && tmdbId == null && imdbId == null && tvMazeId == null) {
                    sendError(exchange, 400, "Missing media identifier or title.")
                    return
                }
                val type = if (query["type"]?.equals("TV", ignoreCase = true) == true) MediaType.TV else MediaType.MOVIE
                val year = query["year"]?.toIntOrNull()?.takeIf { it in 1880..2100 }
                val prefs = WebJsonMapper.parsePreferences(
                    langCode = query["lang"],
                    regionCode = query["region"],
                    trendingWindow = null,
                )
                val summary = MediaSearchResult(
                    id = id.ifBlank { tmdbId?.toString() ?: imdbId ?: title },
                    mediaType = type,
                    title = title.ifBlank { imdbId ?: "Title" },
                    releaseYear = year,
                    imdbId = imdbId,
                    tmdbId = tmdbId,
                    tvMazeId = tvMazeId,
                )
                val detailsRes = runBlocking { metadataRepo.getMediaDetails(summary, prefs) }
                detailsRes.fold(
                    onSuccess = { sendJson(exchange, 200, WebJsonMapper.mediaDetailsToJson(it)) },
                    onFailure = { sendError(exchange, 502, it.message ?: "Details lookup failed") },
                )
            }

            path == "/api/sources" && method == "POST" -> {
                val body = readJsonBody(exchange) ?: run {
                    sendError(exchange, 400, "Invalid JSON request body.")
                    return
                }
                val targetObj = body.optJSONObject("target") ?: run {
                    sendError(exchange, 400, "Missing search target.")
                    return
                }
                val target = WebJsonMapper.parseSourceSearchTarget(targetObj) ?: run {
                    sendError(exchange, 400, "Invalid search target.")
                    return
                }
                val providedSites = WebJsonMapper.parseSiteConfigList(body.optJSONArray("sites"))
                val effectiveSites = providedSites.ifEmpty { SourceSitesCatalog.BUILT_IN_SITES }
                val modifiers = WebJsonMapper.parseQueryModifiers(body.optJSONObject("modifiers"))
                val customQuery = body.optString("customQuery", "").trim().take(200).takeIf { it.isNotEmpty() }
                val prefs = WebJsonMapper.parsePreferences(
                    langCode = body.optString("lang", null),
                    regionCode = body.optString("region", null),
                    trendingWindow = null,
                    searchEngineCode = body.optString("searchEngine", null),
                )
                val bundle = runBlocking {
                    sourceEngine.discoverSources(
                        target = target,
                        sites = effectiveSites,
                        preferences = prefs,
                        modifiers = modifiers,
                        customQueryOverride = customQuery,
                    )
                }
                sendJson(exchange, 200, WebJsonMapper.sourceBundleToJson(bundle))
            }

            path == "/api/sources/verify" && method == "POST" -> {
                val body = readJsonBody(exchange) ?: run {
                    sendError(exchange, 400, "Invalid JSON request body.")
                    return
                }
                val rawUrl = body.optString("url", "").trim()
                val safeUrl = UrlSecurityGuard.validateExternalHttpUrl(rawUrl)
                if (safeUrl == null) {
                    sendError(exchange, 400, "URL must be a valid public HTTP/HTTPS address.")
                    return
                }
                val (status, note) = runBlocking { sourceEngine.verifyUrlStatus(safeUrl.toString()) }
                sendJson(
                    exchange,
                    200,
                    JSONObject().apply {
                        put("status", status.name)
                        put("statusNote", note ?: JSONObject.NULL)
                    },
                )
            }

            path == "/api/resolve" && method == "POST" -> {
                val body = readJsonBody(exchange) ?: run {
                    sendError(exchange, 400, "Invalid JSON request body.")
                    return
                }
                val rawUrl = body.optString("url", "").trim()
                val safeUrl = UrlSecurityGuard.validateExternalHttpUrl(rawUrl)
                if (safeUrl == null) {
                    sendError(exchange, 400, "URL must be a valid public HTTP/HTTPS link.")
                    return
                }
                try {
                    val result = runBlocking { resolver.resolve(safeUrl.toString()) }
                    sendJson(exchange, 200, WebJsonMapper.resolutionResultToJson(result))
                } catch (e: YouTubeUnavailableException) {
                    sendJson(
                        exchange,
                        422,
                        JSONObject().apply {
                            put("ok", false)
                            put("errorCode", "YOUTUBE_UNAVAILABLE")
                            put("error", e.message ?: "YouTube video unavailable")
                        },
                    )
                } catch (e: UnsupportedPlatformException) {
                    sendJson(
                        exchange,
                        422,
                        JSONObject().apply {
                            put("ok", false)
                            put("errorCode", "UNSUPPORTED_PLATFORM")
                            put("error", e.message ?: "Unsupported platform")
                        },
                    )
                } catch (e: XExtractionException) {
                    sendJson(
                        exchange,
                        422,
                        JSONObject().apply {
                            put("ok", false)
                            put("errorCode", "X_${e.error.name}")
                            put("error", e.message ?: "X/Twitter extraction failed")
                        },
                    )
                } catch (e: Exception) {
                    sendError(exchange, 502, e.message ?: "Link resolution failed")
                }
            }

            path == "/api/download-proxy" && (method == "GET" || method == "HEAD") -> {
                handleDownloadProxy(exchange, method, query)
            }

            else -> sendError(exchange, 404, "Endpoint not found.")
        }
    }

    private fun handleDownloadProxy(
        exchange: HttpExchange,
        method: String,
        query: Map<String, String>,
    ) {
        val rawUrl = query["url"].orEmpty().trim()
        val safeUrl = UrlSecurityGuard.validateExternalHttpUrl(rawUrl)
        if (safeUrl == null) {
            sendError(exchange, 400, "Invalid or blocked download URL.")
            return
        }
        val filename = UrlSecurityGuard.sanitizeDownloadFileName(
            query["filename"],
            fallback = UrlSecurityGuard.sanitizeDownloadFileName(safeUrl.pathSegments.lastOrNull(), "mediax-file.bin"),
        )
        val reqBuilder = Request.Builder()
            .url(safeUrl)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15")
        val rangeHeader = exchange.requestHeaders.getFirst("Range")?.trim()
        if (!rangeHeader.isNullOrEmpty() && Regex("^bytes=\\d*-\\d*$").matches(rangeHeader)) {
            reqBuilder.header("Range", rangeHeader)
        }
        if (method == "HEAD") {
            reqBuilder.head()
        } else {
            reqBuilder.get()
        }

        safeRedirectClient.newCall(reqBuilder.build()).execute().use { resp ->
            val contentType = resp.header("Content-Type")?.substringBefore(';')?.trim()
                ?.takeIf { !it.equals("text/html", ignoreCase = true) }
                ?: "application/octet-stream"
            val headers = exchange.responseHeaders
            headers.set("Content-Type", contentType)
            val encodedName = URLEncoder.encode(filename, Charsets.UTF_8.name()).replace("+", "%20")
            headers.set("Content-Disposition", "attachment; filename=\"$filename\"; filename*=UTF-8''$encodedName")
            resp.header("Accept-Ranges")?.let { headers.set("Accept-Ranges", it) }
            resp.header("Content-Range")?.let { headers.set("Content-Range", it) }

            val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val statusCode = if (resp.code in 200..299) resp.code else 502
            if (statusCode == 502) {
                sendError(exchange, 502, "Upstream server returned HTTP ${resp.code}")
                return
            }
            if (method == "HEAD") {
                exchange.sendResponseHeaders(statusCode, -1)
                return
            }
            val lengthToSend = when {
                contentLength > 0L -> contentLength
                contentLength == 0L -> -1L
                else -> 0L
            }
            exchange.sendResponseHeaders(statusCode, lengthToSend)
            val bodyStream = resp.body?.byteStream()
            if (bodyStream != null) {
                exchange.responseBody.use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = bodyStream.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
            }
        }
    }

    private fun readJsonBody(exchange: HttpExchange): JSONObject? {
        val lenHeader = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: -1L
        if (lenHeader > MAX_JSON_BODY_BYTES) return null
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        exchange.requestBody.use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                total += read
                if (total > MAX_JSON_BODY_BYTES) return null
                baos.write(buf, 0, read)
            }
        }
        val text = baos.toString(Charsets.UTF_8.name()).trim()
        if (text.isEmpty()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = decodeParam(if (idx >= 0) pair.substring(0, idx) else pair)
            val value = if (idx >= 0) decodeParam(pair.substring(idx + 1)) else ""
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun decodeParam(raw: String): String =
        runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)

    private fun sendError(exchange: HttpExchange, code: Int, message: String) {
        sendJson(
            exchange,
            code,
            JSONObject().apply {
                put("ok", false)
                put("error", message)
            },
        )
    }

    private fun sendJson(exchange: HttpExchange, code: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendText(exchange: HttpExchange, code: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private const val MAX_JSON_BODY_BYTES = 128 * 1024

        private val STATIC_ALIASES = mapOf(
            "apple-touch-icon-precomposed.png" to "apple-touch-icon.png",
            "favicon.ico" to "favicon.png",
        )

        private val STATIC_FILES = mapOf(
            "index.html" to "text/html; charset=utf-8",
            "styles.css" to "text/css; charset=utf-8",
            "app.js" to "application/javascript; charset=utf-8",
            "sw.js" to "application/javascript; charset=utf-8",
            "manifest.webmanifest" to "application/manifest+json; charset=utf-8",
            "apple-touch-icon.png" to "image/png",
            "icon-192.png" to "image/png",
            "icon-512.png" to "image/png",
            "favicon.png" to "image/png",
        )
    }
}
