package com.example.desktop

import com.example.media.ArchiveCacheStore
import com.example.media.MediaPersistenceKeys
import com.example.media.MediaPreferences
import com.example.media.MediaSearchResult
import com.example.media.MediaType
import com.example.media.SavedSourcePage
import com.example.media.SearchEngineOption
import com.example.media.SearchLanguagePreference
import com.example.media.SourceSiteConfig
import com.example.media.SourceSitesCatalog
import com.example.media.TrendingFeedBundle
import com.example.media.TrendingTimeWindow
import com.example.media.TvReleaseStatus
import com.example.media.WatchRegions
import com.example.media.FuzzyTitleMatcher
import com.example.ui.AppLanguage
import com.example.ui.theme.ThemeMode
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class DesktopAppSettings(
    val language: AppLanguage = AppLanguage.EN,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val developerMode: Boolean = false,
    val downloadDirectoryPath: String = DesktopPersistence.defaultDownloadsDir().absolutePath,
    val connectionsPerDownload: Int = 4,
    val concurrencyLimitCode: String = "TWO",
    val mediaPreferences: MediaPreferences = MediaPreferences(),
)

/**
 * Cross-version Windows (7 / 8 / 10 / 11+) and JVM disk persistence under %APPDATA%\MediaX.
 */
class DesktopPersistence(
    baseDir: File = defaultDataDir(),
) : ArchiveCacheStore {

    private val dataDir = baseDir.apply { mkdirs() }
    private val cacheDir = File(dataDir, "cache").apply { mkdirs() }
    private val lock = Any()

    private val settingsFile = File(dataDir, "settings.json")
    private val favoritesFile = File(dataDir, "favorites.json")
    private val recentlyViewedFile = File(dataDir, "recently_viewed.json")
    private val recentSearchesFile = File(dataDir, "recent_searches.json")
    private val savedSourcesFile = File(dataDir, "saved_sources.json")
    private val sourceSitesFile = File(dataDir, "source_sites.json")
    private val trendingCacheFile = File(cacheDir, "trending_cache.json")
    val downloadsFile = File(dataDir, "downloads.json")
    val imageCacheDir = File(cacheDir, "images").apply { mkdirs() }

    fun loadSettings(): DesktopAppSettings = synchronized(lock) {
        if (!settingsFile.exists()) return DesktopAppSettings()
        return try {
            val root = JSONObject(settingsFile.readText(Charsets.UTF_8))
            val lang = AppLanguage.entries.firstOrNull {
                it.code.equals(root.optString("language", "en"), ignoreCase = true)
            } ?: AppLanguage.EN
            val theme = ThemeMode.fromCode(root.optString("themeMode", ThemeMode.SYSTEM.code))
            val devMode = root.optBoolean("developerMode", false)
            val dlDirRaw = root.optString("downloadDirectoryPath", "").trim()
            val dlDir = dlDirRaw.takeIf { it.isNotEmpty() } ?: defaultDownloadsDir().absolutePath
            val threads = root.optInt("connectionsPerDownload", 4).coerceIn(1, 8)
            val concurrency = root.optString("concurrencyLimitCode", "TWO").ifBlank { "TWO" }
            val prefsObj = root.optJSONObject("mediaPreferences") ?: JSONObject()
            val mediaPrefs = MediaPreferences(
                omdbApiKey = prefsObj.optString("omdbApiKey", ""),
                youtubeApiKey = prefsObj.optString("youtubeApiKey", ""),
                searchLanguage = SearchLanguagePreference.fromCode(prefsObj.optString("searchLanguage", null)),
                searchEngine = SearchEngineOption.fromCode(prefsObj.optString("searchEngine", null)),
                preferInternalBrowser = prefsObj.optBoolean("preferInternalBrowser", true),
                downloadNotificationsEnabled = prefsObj.optBoolean("downloadNotificationsEnabled", true),
                watchRegion = WatchRegions.normalize(prefsObj.optString("watchRegion", "US")),
                trendingWindow = TrendingTimeWindow.fromApi(prefsObj.optString("trendingWindow", null)),
                tmdbProxyUrl = prefsObj.optString("tmdbProxyUrl", ""),
                useSeparateTabs = prefsObj.optBoolean("useSeparateTabs", false),
            )
            DesktopAppSettings(
                language = lang,
                themeMode = theme,
                developerMode = devMode,
                downloadDirectoryPath = dlDir,
                connectionsPerDownload = threads,
                concurrencyLimitCode = concurrency,
                mediaPreferences = mediaPrefs,
            )
        } catch (_: Exception) {
            DesktopAppSettings()
        }
    }

    fun saveSettings(settings: DesktopAppSettings) = synchronized(lock) {
        val prefs = settings.mediaPreferences
        val prefsObj = JSONObject().apply {
            put("omdbApiKey", prefs.omdbApiKey.trim())
            put("youtubeApiKey", prefs.youtubeApiKey.trim())
            put("searchLanguage", prefs.searchLanguage.code)
            put("searchEngine", prefs.searchEngine.code)
            put("preferInternalBrowser", prefs.preferInternalBrowser)
            put("downloadNotificationsEnabled", prefs.downloadNotificationsEnabled)
            put("watchRegion", WatchRegions.normalize(prefs.watchRegion))
            put("trendingWindow", prefs.trendingWindow.apiValue)
            put("tmdbProxyUrl", prefs.tmdbProxyUrl.trim())
            put("useSeparateTabs", prefs.useSeparateTabs)
        }
        val root = JSONObject().apply {
            put("language", settings.language.code)
            put("themeMode", settings.themeMode.code)
            put("developerMode", settings.developerMode)
            put("downloadDirectoryPath", settings.downloadDirectoryPath)
            put("connectionsPerDownload", settings.connectionsPerDownload.coerceIn(1, 8))
            put("concurrencyLimitCode", settings.concurrencyLimitCode)
            put("mediaPreferences", prefsObj)
        }
        atomicWrite(settingsFile, root.toString(2))
    }

    fun loadFavorites(): List<MediaSearchResult> = synchronized(lock) { readMediaList(favoritesFile) }

    fun saveFavorites(items: List<MediaSearchResult>) = synchronized(lock) {
        writeMediaList(favoritesFile, items.take(MAX_SAVED_ITEMS))
    }

    fun loadRecentlyViewed(): List<MediaSearchResult> = synchronized(lock) { readMediaList(recentlyViewedFile) }

    fun saveRecentlyViewed(items: List<MediaSearchResult>) = synchronized(lock) {
        writeMediaList(recentlyViewedFile, items.take(MAX_HISTORY_ITEMS))
    }

    fun loadRecentSearches(): List<String> = synchronized(lock) {
        if (!recentSearchesFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(recentSearchesFile.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveRecentSearches(queries: List<String>) = synchronized(lock) {
        val arr = JSONArray()
        queries.take(MAX_HISTORY_ITEMS).forEach { arr.put(it) }
        atomicWrite(recentSearchesFile, arr.toString())
    }

    fun clearSearchHistory() = synchronized(lock) {
        recentSearchesFile.delete()
    }

    fun loadSavedSourcePages(): List<SavedSourcePage> = synchronized(lock) {
        if (!savedSourcesFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(savedSourcesFile.readText(Charsets.UTF_8))
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val url = obj.optString("url")
                    if (!FuzzyTitleMatcher.isSafeHttpUrl(url)) continue
                    add(
                        SavedSourcePage(
                            url = url,
                            title = obj.optString("title", url),
                            domain = obj.optString("domain", ""),
                            mediaTitle = obj.optNullableString("mediaTitle"),
                            savedAt = obj.optLong("savedAt", 0L),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveSavedSourcePages(pages: List<SavedSourcePage>) = synchronized(lock) {
        val arr = JSONArray()
        pages.take(MAX_SAVED_ITEMS).forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("url", p.url)
                    put("title", p.title)
                    put("domain", p.domain)
                    put("mediaTitle", p.mediaTitle ?: JSONObject.NULL)
                    put("savedAt", p.savedAt)
                }
            )
        }
        atomicWrite(savedSourcesFile, arr.toString())
    }

    fun loadSourceSites(): List<SourceSiteConfig> = synchronized(lock) {
        if (!sourceSitesFile.exists()) return SourceSitesCatalog.defaultSites()
        return try {
            val arr = JSONArray(sourceSitesFile.readText(Charsets.UTF_8))
            val loaded = buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val baseUrl = obj.optString("baseUrl")
                    if (!FuzzyTitleMatcher.isSafeHttpUrl(baseUrl)) continue
                    add(
                        SourceSiteConfig(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            baseUrl = baseUrl,
                            searchPathTemplate = obj.optNullableString("searchPathTemplate"),
                            archiveIndexUrl = obj.optNullableString("archiveIndexUrl"),
                            enabled = obj.optBoolean("enabled", true),
                            isCustom = obj.optBoolean("isCustom", false),
                            requiresDomesticIpHint = obj.optBoolean("requiresDomesticIpHint", false),
                        )
                    )
                }
            }
            if (loaded.isEmpty()) SourceSitesCatalog.defaultSites()
            else SourceSitesCatalog.mergeWithBuiltinCatalog(loaded)
        } catch (_: Exception) {
            SourceSitesCatalog.defaultSites()
        }
    }

    fun saveSourceSites(sites: List<SourceSiteConfig>) = synchronized(lock) {
        val arr = JSONArray()
        sites.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("baseUrl", s.baseUrl)
                    put("searchPathTemplate", s.searchPathTemplate ?: JSONObject.NULL)
                    put("archiveIndexUrl", s.archiveIndexUrl ?: JSONObject.NULL)
                    put("enabled", s.enabled)
                    put("isCustom", s.isCustom)
                    put("requiresDomesticIpHint", s.requiresDomesticIpHint)
                }
            )
        }
        atomicWrite(sourceSitesFile, arr.toString())
    }

    override fun archiveCacheFile(siteId: String): File {
        val safeId = siteId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(cacheDir, "archive_${safeId}.tsv")
    }

    override fun loadTrendingCache(): TrendingFeedBundle? = synchronized(lock) {
        if (!trendingCacheFile.exists()) return null
        return try {
            val root = JSONObject(trendingCacheFile.readText(Charsets.UTF_8))
            val moviesArr = root.optJSONArray("movies") ?: JSONArray()
            val tvArr = root.optJSONArray("tvShows") ?: JSONArray()
            val movies = buildList(moviesArr.length()) {
                for (i in 0 until moviesArr.length()) {
                    moviesArr.optJSONObject(i)?.let { add(parseMediaSummary(it)) }
                }
            }
            val tvShows = buildList(tvArr.length()) {
                for (i in 0 until tvArr.length()) {
                    tvArr.optJSONObject(i)?.let { add(parseMediaSummary(it)) }
                }
            }
            TrendingFeedBundle(
                movies = movies,
                tvShows = tvShows,
                lastUpdatedMillis = root.optLong("lastUpdatedMillis", 0L),
                isUsingFallbackSources = root.optBoolean("isUsingFallbackSources", false),
                attribution = root.optString("attribution", ""),
                window = TrendingTimeWindow.fromApi(root.optString("window", TrendingTimeWindow.DAY.apiValue)),
                region = WatchRegions.normalize(root.optString("region", "US")),
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun saveTrendingCache(bundle: TrendingFeedBundle) = synchronized(lock) {
        val moviesArr = JSONArray().apply { bundle.movies.forEach { put(serializeMediaSummary(it)) } }
        val tvArr = JSONArray().apply { bundle.tvShows.forEach { put(serializeMediaSummary(it)) } }
        val root = JSONObject().apply {
            put("movies", moviesArr)
            put("tvShows", tvArr)
            put("lastUpdatedMillis", bundle.lastUpdatedMillis)
            put("isUsingFallbackSources", bundle.isUsingFallbackSources)
            put("attribution", bundle.attribution)
            put("window", bundle.window.apiValue)
            put("region", bundle.region)
        }
        atomicWrite(trendingCacheFile, root.toString())
    }

    private fun readMediaList(file: File): List<MediaSearchResult> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(parseMediaSummary(it)) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseMediaSummary(obj: JSONObject): MediaSearchResult {
        val genreArr = obj.optJSONArray("genres") ?: JSONArray()
        val genres = (0 until genreArr.length()).mapNotNull {
            genreArr.optString(it).takeIf { s -> s.isNotBlank() }
        }
        return MediaSearchResult(
            id = obj.optString("id"),
            mediaType = runCatching { MediaType.valueOf(obj.optString("mediaType")) }.getOrDefault(MediaType.MOVIE),
            title = obj.optString("title"),
            originalTitle = obj.optNullableString("originalTitle"),
            persianTitle = obj.optNullableString("persianTitle"),
            releaseYear = obj.optInt("releaseYear", -1).takeIf { it > 1800 },
            endYear = obj.optInt("endYear", -1).takeIf { it > 1800 },
            posterUrl = obj.optNullableString("posterUrl"),
            backdropUrl = obj.optNullableString("backdropUrl"),
            rating = obj.optDouble("rating", Double.NaN).takeIf { !it.isNaN() && it > 0.0 },
            overview = obj.optNullableString("overview"),
            genres = genres,
            imdbId = obj.optNullableString("imdbId"),
            tmdbId = obj.optInt("tmdbId", -1).takeIf { it > 0 },
            tvMazeId = obj.optInt("tvMazeId", -1).takeIf { it > 0 },
            seasonCount = obj.optInt("seasonCount", -1).takeIf { it > 0 },
            releaseStatus = TvReleaseStatus.fromCode(obj.optNullableString("releaseStatus")),
            sourceLabel = obj.optString("sourceLabel", ""),
        )
    }

    private fun writeMediaList(file: File, items: List<MediaSearchResult>) {
        val arr = JSONArray()
        items.forEach { item -> arr.put(serializeMediaSummary(item)) }
        atomicWrite(file, arr.toString())
    }

    private fun serializeMediaSummary(item: MediaSearchResult): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("mediaType", item.mediaType.name)
        put("title", item.title)
        put("originalTitle", item.originalTitle ?: JSONObject.NULL)
        put("persianTitle", item.persianTitle ?: JSONObject.NULL)
        put("releaseYear", item.releaseYear ?: JSONObject.NULL)
        put("endYear", item.endYear ?: JSONObject.NULL)
        put("posterUrl", item.posterUrl ?: JSONObject.NULL)
        put("backdropUrl", item.backdropUrl ?: JSONObject.NULL)
        put("rating", item.rating ?: JSONObject.NULL)
        put("overview", item.overview ?: JSONObject.NULL)
        put("genres", JSONArray(item.genres))
        put("imdbId", item.imdbId ?: JSONObject.NULL)
        put("tmdbId", item.tmdbId ?: JSONObject.NULL)
        put("tvMazeId", item.tvMazeId ?: JSONObject.NULL)
        put("seasonCount", item.seasonCount ?: JSONObject.NULL)
        put("releaseStatus", item.releaseStatus.code)
        put("sourceLabel", item.sourceLabel)
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val MAX_SAVED_ITEMS = 80
        private const val MAX_HISTORY_ITEMS = 25

        fun defaultDataDir(): File {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            if (os.contains("win")) {
                val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                    ?: System.getProperty("user.home")
                return File(base, "MediaX")
            }
            return File(System.getProperty("user.home"), ".mediax")
        }

        fun defaultDownloadsDir(): File {
            val home = File(System.getProperty("user.home"))
            val dl = File(home, "Downloads")
            return File(if (dl.exists()) dl else home, "MediaX")
        }
    }
}
