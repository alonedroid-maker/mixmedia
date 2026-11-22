package com.example.media

import com.example.network.VideoLinkExtractor
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class SourceDiscoveryEngine(
    private val persistence: MediaPersistence? = null,
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun discoverSources(
        target: SourceSearchTarget,
        sites: List<SourceSiteConfig>,
        preferences: MediaPreferences,
        modifiers: QueryModifiers = QueryModifiers(),
        customQueryOverride: String? = null,
    ): SourceDiscoveryBundle = withContext(Dispatchers.IO) {
        val queries = if (!customQueryOverride.isNullOrBlank()) {
            listOf(customQueryOverride.trim())
        } else {
            FuzzyTitleMatcher.buildWebSearchQueries(
                target = target,
                searchLanguage = preferences.searchLanguage,
                modifiers = modifiers,
            )
        }

        val primarySearchQuery = customQueryOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: buildSiteSearchTerm(target)

        val primaryWebQuery = queries.firstOrNull() ?: primarySearchQuery
        val launchUrls = listOf(
            preferences.searchEngine to FuzzyTitleMatcher.buildSearchEngineUrl(preferences.searchEngine, primaryWebQuery),
            SearchEngineOption.GOOGLE to FuzzyTitleMatcher.buildSearchEngineUrl(SearchEngineOption.GOOGLE, primaryWebQuery),
            SearchEngineOption.DUCKDUCKGO to FuzzyTitleMatcher.buildSearchEngineUrl(SearchEngineOption.DUCKDUCKGO, primaryWebQuery),
            SearchEngineOption.BING to FuzzyTitleMatcher.buildSearchEngineUrl(SearchEngineOption.BING, primaryWebQuery),
        ).distinctBy { it.first }

        val siteResults = mutableListOf<WebSourceResult>()
        val siteNotices = mutableListOf<String>()
        val webResults = mutableListOf<WebSourceResult>()

        supervisorScope {
            val enabledSites = sites.filter { it.enabled }
            val siteJobs = enabledSites.map { site ->
                async { searchConfiguredSite(site, primarySearchQuery, target) }
            }

            val webJob = async {
                searchDuckDuckGoHtml(primaryWebQuery)
            }

            for (job in siteJobs) {
                val (items, notice) = job.await()
                siteResults.addAll(items)
                if (notice != null) siteNotices.add(notice)
            }
            webResults.addAll(webJob.await())
        }

        SourceDiscoveryBundle(
            target = target,
            generatedQueries = queries,
            searchEngineLaunchUrls = launchUrls,
            siteResults = siteResults.distinctBy { it.url },
            webSearchResults = webResults.distinctBy { it.url },
            siteNotices = siteNotices.distinct(),
        )
    }

    suspend fun verifyUrlStatus(url: String): Pair<SourceLinkStatus, String?> = withContext(Dispatchers.IO) {
        if (!FuzzyTitleMatcher.isSafeHttpUrl(url)) {
            return@withContext SourceLinkStatus.UNAVAILABLE to "Unsupported URL scheme"
        }
        try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", BROWSER_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val note = buildString {
                    append("HTTP $code")
                    val contentType = resp.header("Content-Type")?.substringBefore(';')?.trim()
                    if (!contentType.isNullOrBlank()) append(" • $contentType")
                }
                when (code) {
                    in 200..399 -> SourceLinkStatus.VERIFIED_ACCESSIBLE to note
                    403, 418, 429 -> SourceLinkStatus.WEBSITE_PAGE to "$note (Browser check)"
                    404, 410 -> SourceLinkStatus.UNAVAILABLE to note
                    405 -> {
                        // Some servers disallow HEAD; retry range GET 0-0 cleanly
                        verifyWithMiniGet(url)
                    }
                    else -> SourceLinkStatus.UNKNOWN to note
                }
            }
        } catch (e: Exception) {
            SourceLinkStatus.UNAVAILABLE to (e.message?.take(60) ?: "Connection failed")
        }
    }

    private fun verifyWithMiniGet(url: String): Pair<SourceLinkStatus, String?> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    in 200..399 -> SourceLinkStatus.VERIFIED_ACCESSIBLE to "HTTP ${resp.code}"
                    in 400..499 -> SourceLinkStatus.UNAVAILABLE to "HTTP ${resp.code}"
                    else -> SourceLinkStatus.UNKNOWN to "HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            SourceLinkStatus.UNAVAILABLE to e.message?.take(60)
        }
    }

    private fun searchConfiguredSite(
        site: SourceSiteConfig,
        query: String,
        target: SourceSearchTarget,
    ): Pair<List<WebSourceResult>, String?> {
        if (site.isArchive) {
            return searchArchiveSite(site, target)
        }

        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchPageUrl = site.searchUrlTemplate.replace("{query}", encoded)
        val domain = site.baseUrl.toHttpUrlOrNull()?.host ?: site.baseUrl

        // Special fast path for WordPress JSON sites (SerialBlog follows redirect serialblog1.top -> serialblog2.top)
        if (site.id == "serialblog" || site.isCustom) {
            val wpHits = tryWordPressApiSearch(site, encoded)
            if (wpHits != null) {
                return wpHits to null
            }
        }

        // Try fetching the site search URL directly
        return try {
            val req = Request.Builder()
                .url(searchPageUrl)
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8")
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val finalDomain = resp.request.url.host
                val body = resp.body?.string().orEmpty()
                val looksBlocked = resp.code in listOf(403, 418, 429, 503) ||
                    body.contains("_dgjsc=", ignoreCase = true) ||
                    body.contains("cf-browser-verification", ignoreCase = true) ||
                    body.contains("Just a moment...", ignoreCase = true)

                if (looksBlocked) {
                    val launchItem = WebSourceResult(
                        id = "${site.id}_launch",
                        title = "${site.name}: Open search for \"$query\"",
                        url = finalUrl,
                        domain = finalDomain,
                        siteName = site.name,
                        snippet = "Site requires interactive browser verification. Tap to open inside MIxmedia Browser.",
                        status = SourceLinkStatus.WEBSITE_PAGE,
                        statusNote = "HTTP ${resp.code} (Browser required)",
                    )
                    return listOf(launchItem) to "${site.name}: Browser check active (HTTP ${resp.code}) — tap to browse inside app"
                }

                if (!resp.isSuccessful) {
                    return emptyList<WebSourceResult>() to "${site.name}: HTTP ${resp.code}"
                }

                val parsedLinks = extractPostLinksFromHtml(
                    html = body,
                    baseUrl = finalUrl,
                    siteName = site.name,
                    siteId = site.id,
                    query = query,
                )
                if (parsedLinks.isNotEmpty()) {
                    parsedLinks to null
                } else {
                    val pageItem = WebSourceResult(
                        id = "${site.id}_search_page",
                        title = "${site.name}: Search results page for \"$query\"",
                        url = finalUrl,
                        domain = finalDomain,
                        siteName = site.name,
                        snippet = "Open this search page in the internal browser to inspect available releases.",
                        status = SourceLinkStatus.WEBSITE_PAGE,
                        statusNote = "Website page",
                    )
                    listOf(pageItem) to null
                }
            }
        } catch (e: Exception) {
            val fallbackItem = WebSourceResult(
                id = "${site.id}_fallback",
                title = "${site.name}: Open \"$query\"",
                url = searchPageUrl,
                domain = domain,
                siteName = site.name,
                snippet = "Direct background check failed (${e.javaClass.simpleName}). Open in internal browser.",
                status = SourceLinkStatus.NOT_CHECKED,
                statusNote = "Not checked",
            )
            listOf(fallbackItem) to null
        }
    }

    private fun tryWordPressApiSearch(site: SourceSiteConfig, encodedQuery: String): List<WebSourceResult>? {
        return try {
            // Resolve base URL redirect first (e.g. serialblog1.top -> serialblog2.top)
            val rootReq = Request.Builder()
                .url(site.baseUrl)
                .head()
                .header("User-Agent", BROWSER_UA)
                .build()
            val resolvedBase = client.newCall(rootReq).execute().use { resp ->
                val u = resp.request.url
                "${u.scheme}://${u.host}"
            }

            val apiUrl = "$resolvedBase/wp-json/wp/v2/posts?search=$encodedQuery&_fields=id,link,title,excerpt&per_page=8"
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (!body.trimStart().startsWith("[")) return null
                parseWordPressPostsJson(body, site.name, site.id)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseWordPressPostsJson(
        rawJson: String,
        siteName: String,
        siteId: String,
    ): List<WebSourceResult> {
        return try {
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val link = obj.optString("link")
                    if (!FuzzyTitleMatcher.isSafeHttpUrl(link)) continue
                    val title = decodeHtmlEntities(obj.optJSONObject("title")?.optString("rendered").orEmpty())
                        .ifBlank { link }
                    val excerpt = decodeHtmlEntities(
                        obj.optJSONObject("excerpt")?.optString("rendered").orEmpty()
                            .replace(Regex("<[^>]+>"), " ")
                    ).replace(Regex("\\s+"), " ").trim().take(180)
                    val host = link.toHttpUrlOrNull()?.host ?: siteName
                    val isDirect = VideoLinkExtractor.isDirectDownloadableUrl(link)
                    add(
                        WebSourceResult(
                            id = "${siteId}_wp_${obj.optInt("id", i)}",
                            title = title,
                            url = link,
                            domain = host,
                            siteName = siteName,
                            snippet = excerpt,
                            status = SourceLinkStatus.WEBSITE_PAGE,
                            statusNote = "Verified match on $host",
                            isDirectDownloadCandidate = isDirect,
                            detectedExtension = if (isDirect) VideoLinkExtractor.extractExtension(link) else null,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun searchArchiveSite(
        site: SourceSiteConfig,
        target: SourceSearchTarget,
    ): Pair<List<WebSourceResult>, String?> {
        val cacheFile = persistence?.archiveCacheFile(site.id)
        val cacheValid = cacheFile != null && cacheFile.exists() &&
            cacheFile.length() > 0L &&
            (System.currentTimeMillis() - cacheFile.lastModified() < ARCHIVE_CACHE_TTL_MS)

        if (!cacheValid) {
            val refreshNotice = refreshArchiveIndexStreaming(site, cacheFile)
            if (refreshNotice != null) {
                return emptyList<WebSourceResult>() to refreshNotice
            }
        }

        if (cacheFile == null || !cacheFile.exists()) return emptyList<WebSourceResult>() to null

        val matches = searchCachedArchiveTsv(
            cacheFile = cacheFile,
            target = target,
            siteName = site.name,
            siteId = site.id,
        )
        return matches to null
    }

    private fun refreshArchiveIndexStreaming(site: SourceSiteConfig, cacheFile: File?): String? {
        return try {
            val req = Request.Builder()
                .url(site.baseUrl)
                .header("User-Agent", BROWSER_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 503 || resp.code == 403) {
                    val sample = resp.body?.byteStream()?.let { stream ->
                        val buf = ByteArray(512)
                        val read = stream.read(buf)
                        if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
                    }.orEmpty()
                    return if (sample.contains("آی پی داخلی") || site.requiresDomesticIpHint) {
                        "${site.name}: Requires Iranian domestic IP without VPN (HTTP ${resp.code})"
                    } else {
                        "${site.name}: Unavailable (HTTP ${resp.code})"
                    }
                }
                if (!resp.isSuccessful) {
                    return "${site.name}: HTTP ${resp.code}"
                }
                val stream = resp.body?.byteStream() ?: return "${site.name}: Empty response"
                if (cacheFile != null) {
                    streamParseArchiveHtmlToTsv(stream, site.baseUrl, cacheFile)
                }
                null
            }
        } catch (e: Exception) {
            "${site.name}: ${e.javaClass.simpleName}"
        }
    }

    /**
     * Memory-safe line-by-line streaming HTML parser for 26k+ title single-page archives.
     * Avoids loading multi-MB HTML documents into heap.
     */
    internal fun streamParseArchiveHtmlToTsv(
        inputStream: java.io.InputStream,
        baseUrl: String,
        destTsvFile: File,
    ): Int {
        val tempFile = File(destTsvFile.parentFile, "${destTsvFile.name}.tmp")
        var count = 0
        val baseHttpUrl = baseUrl.toHttpUrlOrNull()

        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 16 * 1024).use { reader ->
            tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                parseArchiveLines(reader, baseHttpUrl, writer) { count++ }
            }
        }
        if (count > 0) {
            if (!tempFile.renameTo(destTsvFile)) {
                tempFile.copyTo(destTsvFile, overwrite = true)
                tempFile.delete()
            }
        } else {
            tempFile.delete()
        }
        return count
    }

    private fun parseArchiveLines(
        reader: BufferedReader,
        baseHttpUrl: okhttp3.HttpUrl?,
        writer: BufferedWriter,
        onEntry: () -> Unit,
    ) {
        val maxEntries = 65_000
        var lineCount = 0
        var line: String? = reader.readLine()
        while (line != null && lineCount < maxEntries) {
            val matches = anchorRegex.findAll(line)
            for (m in matches) {
                val rawHref = m.groupValues[1].trim()
                val rawTitle = decodeHtmlEntities(m.groupValues[2].replace(Regex("<[^>]+>"), " "))
                    .replace("\t", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (rawTitle.length < 2 || rawHref.isBlank() || rawHref.startsWith("#")) continue
                val resolvedUrl = baseHttpUrl?.resolve(rawHref)?.toString() ?: rawHref
                if (!FuzzyTitleMatcher.isSafeHttpUrl(resolvedUrl)) continue
                val norm = FuzzyTitleMatcher.normalizeText(rawTitle)
                if (norm.isBlank()) continue
                writer.write(rawTitle)
                writer.write('\t'.code)
                writer.write(norm)
                writer.write('\t'.code)
                writer.write(resolvedUrl)
                writer.newLine()
                onEntry()
                lineCount++
                if (lineCount >= maxEntries) break
            }
            line = reader.readLine()
        }
    }

    internal fun searchCachedArchiveTsv(
        cacheFile: File,
        target: SourceSearchTarget,
        siteName: String,
        siteId: String,
    ): List<WebSourceResult> {
        val normTitle = FuzzyTitleMatcher.normalizeText(target.title)
        val normFa = target.persianTitle?.let { FuzzyTitleMatcher.normalizeText(it) }?.takeIf { it.isNotBlank() }
        if (normTitle.isBlank()) return emptyList()

        val tokensEn = normTitle.split(' ').filter { it.length >= 2 }
        val scored = ArrayList<Pair<Double, WebSourceResult>>(16)

        cacheFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size < 3) continue
                val rawTitle = parts[0]
                val normEntry = parts[1]
                val url = parts[2]

                val containsEn = normEntry.contains(normTitle) ||
                    (tokensEn.size >= 2 && tokensEn.all { normEntry.contains(it) })
                val containsFa = normFa != null && normEntry.contains(normFa)

                val sim = if (containsEn || containsFa) {
                    1.0
                } else {
                    FuzzyTitleMatcher.stringSimilarity(normTitle, normEntry)
                }

                if (sim >= 0.72) {
                    var score = sim * 100.0
                    if (target.releaseYear != null && normEntry.contains(target.releaseYear.toString())) {
                        score += 20.0
                    }
                    val isDirect = VideoLinkExtractor.isDirectDownloadableUrl(url)
                    val host = url.toHttpUrlOrNull()?.host ?: siteName
                    scored.add(
                        score to WebSourceResult(
                            id = "${siteId}_${url.hashCode()}",
                            title = rawTitle,
                            url = url,
                            domain = host,
                            siteName = siteName,
                            snippet = "Archive entry from $siteName",
                            status = SourceLinkStatus.WEBSITE_PAGE,
                            statusNote = "Archive match",
                            isDirectDownloadCandidate = isDirect,
                            detectedExtension = if (isDirect) VideoLinkExtractor.extractExtension(url) else null,
                        )
                    )
                }
            }
        }
        return scored.sortedByDescending { it.first }.map { it.second }.take(10)
    }

    private fun searchDuckDuckGoHtml(query: String): List<WebSourceResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "en-US,en;q=0.9,fa;q=0.8")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val html = resp.body?.string().orEmpty()
                parseDuckDuckGoHtml(html)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseDuckDuckGoHtml(html: String): List<WebSourceResult> {
        val results = mutableListOf<WebSourceResult>()
        val links = ddgResultAnchorRegex.findAll(html).toList()
        val snippets = ddgSnippetRegex.findAll(html).map {
            decodeHtmlEntities(it.groupValues[1].replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ").trim()
        }.toList()

        links.forEachIndexed { idx, match ->
            if (results.size >= 12) return@forEachIndexed
            val rawHref = decodeHtmlEntities(match.groupValues[1])
            val resolvedUrl = unwrapDuckDuckGoRedirect(rawHref)
            if (!FuzzyTitleMatcher.isSafeHttpUrl(resolvedUrl)) return@forEachIndexed
            val title = decodeHtmlEntities(match.groupValues[2].replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ").trim()
            if (title.isBlank()) return@forEachIndexed
            val domain = resolvedUrl.toHttpUrlOrNull()?.host ?: ""
            val isDirect = VideoLinkExtractor.isDirectDownloadableUrl(resolvedUrl)
            results.add(
                WebSourceResult(
                    id = "ddg_${idx}_${resolvedUrl.hashCode()}",
                    title = title,
                    url = resolvedUrl,
                    domain = domain,
                    siteName = "DuckDuckGo Search",
                    snippet = snippets.getOrNull(idx).orEmpty().take(200),
                    status = SourceLinkStatus.SEARCH_RESULT,
                    statusNote = "Search result",
                    isDirectDownloadCandidate = isDirect,
                    detectedExtension = if (isDirect) VideoLinkExtractor.extractExtension(resolvedUrl) else null,
                )
            )
        }
        return results
    }

    private fun unwrapDuckDuckGoRedirect(rawHref: String): String {
        val normalized = if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
        val uddgIndex = normalized.indexOf("uddg=")
        if (uddgIndex >= 0) {
            val encoded = normalized.substring(uddgIndex + 5).substringBefore('&')
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(normalized)
        }
        return normalized
    }

    private fun extractPostLinksFromHtml(
        html: String,
        baseUrl: String,
        siteName: String,
        siteId: String,
        query: String,
    ): List<WebSourceResult> {
        val baseHttp = baseUrl.toHttpUrlOrNull() ?: return emptyList()
        val normQuery = FuzzyTitleMatcher.normalizeText(query)
        val hits = mutableListOf<WebSourceResult>()
        for (m in headingAnchorRegex.findAll(html)) {
            if (hits.size >= 8) break
            val href = m.groupValues[1].trim()
            val resolved = baseHttp.resolve(href)?.toString() ?: continue
            if (!FuzzyTitleMatcher.isSafeHttpUrl(resolved)) continue
            val text = decodeHtmlEntities(m.groupValues[2].replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ").trim()
            if (text.length < 3) continue
            if (normQuery.isNotBlank() && FuzzyTitleMatcher.stringSimilarity(normQuery, text) < 0.2) continue
            val isDirect = VideoLinkExtractor.isDirectDownloadableUrl(resolved)
            hits.add(
                WebSourceResult(
                    id = "${siteId}_html_${resolved.hashCode()}",
                    title = text,
                    url = resolved,
                    domain = resolved.toHttpUrlOrNull()?.host ?: baseHttp.host,
                    siteName = siteName,
                    snippet = "Matched on $siteName",
                    status = SourceLinkStatus.WEBSITE_PAGE,
                    statusNote = "Website page",
                    isDirectDownloadCandidate = isDirect,
                    detectedExtension = if (isDirect) VideoLinkExtractor.extractExtension(resolved) else null,
                )
            )
        }
        return hits
    }

    private fun buildSiteSearchTerm(target: SourceSearchTarget): String {
        val se = FuzzyTitleMatcher.formatSeasonEpisodeTag(target.seasonNumber, target.episodeNumber)
        return if (se != null) "${target.title} $se" else target.title
    }

    private fun decodeHtmlEntities(input: String): String = input
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#8211;", "–")
        .replace("&#8217;", "'")
        .replace("&laquo;", "«")
        .replace("&raquo;", "»")
        .replace("&nbsp;", " ")

    companion object {
        private const val ARCHIVE_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val anchorRegex = Regex("""<a\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
        private val ddgResultAnchorRegex = Regex(
            """<a[^>]*class\s*=\s*["'][^"']*result__a[^"']*["'][^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val ddgSnippetRegex = Regex(
            """class\s*=\s*["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val headingAnchorRegex = Regex(
            """<h[1-4][^>]*>\s*<a[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
