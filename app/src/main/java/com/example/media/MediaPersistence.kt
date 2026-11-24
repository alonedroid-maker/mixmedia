package com.example.media

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class MediaPersistence(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mediaDir = File(appContext.filesDir, "fixlink_media").apply { mkdirs() }
    private val cacheDir = File(appContext.cacheDir, "fixlink_media_cache").apply { mkdirs() }
    private val lock = Any()

    private val favoritesFile = File(mediaDir, "favorites.json")
    private val recentlyViewedFile = File(mediaDir, "recently_viewed.json")
    private val recentSearchesFile = File(mediaDir, "recent_searches.json")
    private val savedSourcesFile = File(mediaDir, "saved_sources.json")
    private val sourceSitesFile = File(mediaDir, "source_sites.json")

    fun loadPreferences(): MediaPreferences = MediaPreferences(
        tmdbApiKey = prefs.getString(KEY_TMDB_API_KEY, "") ?: "",
        omdbApiKey = prefs.getString(KEY_OMDB_API_KEY, "") ?: "",
        searchLanguage = SearchLanguagePreference.fromCode(prefs.getString(KEY_SEARCH_LANG, null)),
        searchEngine = SearchEngineOption.fromCode(prefs.getString(KEY_SEARCH_ENGINE, null)),
        preferInternalBrowser = prefs.getBoolean(KEY_PREFER_INTERNAL_BROWSER, true),
        downloadNotificationsEnabled = prefs.getBoolean(KEY_DOWNLOAD_NOTIFICATIONS, true),
    )

    fun savePreferences(preferences: MediaPreferences) {
        prefs.edit()
            .putString(KEY_TMDB_API_KEY, preferences.tmdbApiKey.trim())
            .putString(KEY_OMDB_API_KEY, preferences.omdbApiKey.trim())
            .putString(KEY_SEARCH_LANG, preferences.searchLanguage.code)
            .putString(KEY_SEARCH_ENGINE, preferences.searchEngine.code)
            .putBoolean(KEY_PREFER_INTERNAL_BROWSER, preferences.preferInternalBrowser)
            .putBoolean(KEY_DOWNLOAD_NOTIFICATIONS, preferences.downloadNotificationsEnabled)
            .apply()
    }

    fun loadFavorites(): List<MediaSearchResult> = synchronized(lock) {
        readMediaList(favoritesFile)
    }

    fun saveFavorites(items: List<MediaSearchResult>) = synchronized(lock) {
        writeMediaList(favoritesFile, items.take(MAX_SAVED_ITEMS))
    }

    fun loadRecentlyViewed(): List<MediaSearchResult> = synchronized(lock) {
        readMediaList(recentlyViewedFile)
    }

    fun saveRecentlyViewed(items: List<MediaSearchResult>) = synchronized(lock) {
        writeMediaList(recentlyViewedFile, items.take(MAX_HISTORY_ITEMS))
    }

    fun loadRecentSearches(): List<String> = synchronized(lock) {
        if (!recentSearchesFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(recentSearchesFile.readText())
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
            val arr = JSONArray(savedSourcesFile.readText())
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
        val defaults = defaultSourceSites()
        if (!sourceSitesFile.exists()) return defaults
        return try {
            val arr = JSONArray(sourceSitesFile.readText())
            val saved = buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val baseUrl = obj.optString("baseUrl")
                    if (!FuzzyTitleMatcher.isSafeHttpUrl(baseUrl)) continue
                    add(
                        SourceSiteConfig(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            baseUrl = baseUrl,
                            searchUrlTemplate = obj.optString("searchUrlTemplate", baseUrl),
                            isArchive = obj.optBoolean("isArchive", false),
                            enabled = obj.optBoolean("enabled", true),
                            isCustom = obj.optBoolean("isCustom", false),
                            requiresDomesticIpHint = obj.optBoolean("requiresDomesticIpHint", false),
                        )
                    )
                }
            }
            if (saved.isEmpty()) {
                defaults
            } else {
                mergeWithBuiltInCatalog(saved = saved, defaults = defaults)
            }
        } catch (_: Exception) {
            defaults
        }
    }

    internal fun mergeWithBuiltInCatalog(
        saved: List<SourceSiteConfig>,
        defaults: List<SourceSiteConfig>,
    ): List<SourceSiteConfig> {
        val savedById = saved.associateBy { it.id }
        val builtInMerged = defaults.map { builtIn ->
            val previous = savedById[builtIn.id]
            if (previous != null) {
                builtIn.copy(enabled = previous.enabled)
            } else {
                builtIn
            }
        }
        val customSites = saved.filter { it.isCustom }
        return builtInMerged + customSites
    }

    fun saveSourceSites(sites: List<SourceSiteConfig>) = synchronized(lock) {
        val arr = JSONArray()
        sites.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("baseUrl", s.baseUrl)
                    put("searchUrlTemplate", s.searchUrlTemplate)
                    put("isArchive", s.isArchive)
                    put("enabled", s.enabled)
                    put("isCustom", s.isCustom)
                    put("requiresDomesticIpHint", s.requiresDomesticIpHint)
                }
            )
        }
        atomicWrite(sourceSitesFile, arr.toString())
    }

    fun archiveCacheFile(siteId: String): File {
        val safeId = siteId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(cacheDir, "archive_${safeId}.tsv")
    }

    fun getCacheSizeBytes(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun clearMediaCache() {
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun readMediaList(file: File): List<MediaSearchResult> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(parseMediaSummary(obj))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeMediaList(file: File, items: List<MediaSearchResult>) {
        val arr = JSONArray()
        items.forEach { arr.put(serializeMediaSummary(it)) }
        atomicWrite(file, arr.toString())
    }

    private fun atomicWrite(target: File, content: String) {
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        } catch (_: Exception) {
            // Ignore write errors gracefully
        }
    }

    private fun serializeMediaSummary(item: MediaSearchResult): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("mediaType", item.mediaType.name)
        put("title", item.title)
        put("persianTitle", item.persianTitle ?: JSONObject.NULL)
        put("originalTitle", item.originalTitle ?: JSONObject.NULL)
        put("releaseYear", item.releaseYear ?: JSONObject.NULL)
        put("posterUrl", item.posterUrl ?: JSONObject.NULL)
        put("runtimeMinutes", item.runtimeMinutes ?: JSONObject.NULL)
        put("genres", JSONArray(item.genres))
        put("directors", JSONArray(item.directors))
        put("topCast", JSONArray(item.topCast))
        put("imdbRating", item.imdbRating ?: JSONObject.NULL)
        put("overview", item.overview ?: JSONObject.NULL)
        put("persianOverview", item.persianOverview ?: JSONObject.NULL)
        put("imdbId", item.imdbId ?: JSONObject.NULL)
        put("tmdbId", item.tmdbId ?: JSONObject.NULL)
        put("tvMazeId", item.tvMazeId ?: JSONObject.NULL)
        put("wikidataId", item.wikidataId ?: JSONObject.NULL)
    }

    private fun parseMediaSummary(obj: JSONObject): MediaSearchResult {
        val type = runCatching {
            MediaType.valueOf(obj.optString("mediaType", MediaType.MOVIE.name))
        }.getOrDefault(MediaType.MOVIE)
        return MediaSearchResult(
            id = obj.getString("id"),
            mediaType = type,
            title = obj.getString("title"),
            persianTitle = obj.optNullableString("persianTitle"),
            originalTitle = obj.optNullableString("originalTitle"),
            releaseYear = obj.optNullableInt("releaseYear"),
            posterUrl = obj.optNullableString("posterUrl"),
            runtimeMinutes = obj.optNullableInt("runtimeMinutes"),
            genres = obj.optStringList("genres"),
            directors = obj.optStringList("directors"),
            topCast = obj.optStringList("topCast"),
            imdbRating = obj.optNullableDouble("imdbRating"),
            overview = obj.optNullableString("overview"),
            persianOverview = obj.optNullableString("persianOverview"),
            imdbId = obj.optNullableString("imdbId"),
            tmdbId = obj.optNullableInt("tmdbId"),
            tvMazeId = obj.optNullableInt("tvMazeId"),
            wikidataId = obj.optNullableString("wikidataId"),
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    companion object {
        private const val PREFS_NAME = "fixlink_media_prefs"
        private const val KEY_TMDB_API_KEY = "tmdb_api_key"
        private const val KEY_OMDB_API_KEY = "omdb_api_key"
        private const val KEY_SEARCH_LANG = "search_language"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_PREFER_INTERNAL_BROWSER = "prefer_internal_browser"
        private const val KEY_DOWNLOAD_NOTIFICATIONS = "download_notifications_enabled"
        private const val MAX_SAVED_ITEMS = 50
        private const val MAX_HISTORY_ITEMS = 15

        fun defaultSourceSites(): List<SourceSiteConfig> = SourceSitesCatalog.BUILT_IN_SITES
    }
}
