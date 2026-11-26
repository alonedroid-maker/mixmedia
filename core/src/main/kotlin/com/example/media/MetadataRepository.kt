package com.example.media

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class MetadataRepository(
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val proxyServiceOverride: TmdbProxyApiService? = null,
    private val allowCleartextProxyForTests: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val credentialProvider: TmdbCredentialProvider = TmdbCredentialProvider(),
) {

    private val zeroKey = ZeroKeyMetadataEnricher(client)

    private val cacheLock = Any()
    private val searchCache = object : LinkedHashMap<String, MediaSearchPage>(40, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaSearchPage>?): Boolean = size > 40
    }
    private val detailsCache = object : LinkedHashMap<String, MediaDetails>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaDetails>?): Boolean = size > 32
    }
    private val watchProvidersCache = object : LinkedHashMap<String, Pair<List<WatchProviderInfo>, String?>>(48, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<List<WatchProviderInfo>, String?>>?,
        ): Boolean = size > 48
    }
    private var cachedGenres: List<MediaGenre> = emptyList()
    private var cachedGenresLang: String = ""
    private var cachedPopularFeed: List<MediaSearchResult> = emptyList()
    private var cachedTrendingFeed: TrendingFeedBundle? = null
    private var cachedProxyUrlKey: String = ""
    private var cachedProxyService: TmdbProxyApiService? = null
    private var cachedDirectService: TmdbDirectApiService? = null

    fun clearMemoryCaches() {
        synchronized(cacheLock) {
            searchCache.clear()
            detailsCache.clear()
            watchProvidersCache.clear()
            cachedGenres = emptyList()
            cachedGenresLang = ""
            cachedPopularFeed = emptyList()
            cachedTrendingFeed = null
        }
    }

    internal fun resolveProxyService(preferences: MediaPreferences): TmdbProxyApiService? {
        if (proxyServiceOverride != null) return proxyServiceOverride
        val rawUrl = preferences.effectiveProxyBaseUrl()
        val normalized = TmdbProxyClientFactory.normalizeProxyBaseUrl(
            rawUrl = rawUrl,
            allowHttpForLocalhost = allowCleartextProxyForTests,
        ) ?: return null

        synchronized(cacheLock) {
            if (normalized == cachedProxyUrlKey && cachedProxyService != null) {
                return cachedProxyService
            }
            val created = TmdbProxyClientFactory.create(
                baseUrl = normalized,
                client = client,
                allowHttpForLocalhost = allowCleartextProxyForTests,
            )
            if (created != null) {
                cachedProxyUrlKey = normalized
                cachedProxyService = created
            }
            return created
        }
    }

    private fun resolveDirectService(): TmdbDirectApiService? {
        if (!credentialProvider.hasDirectCredentials()) return null
        synchronized(cacheLock) {
            cachedDirectService?.let { return it }
            val created = TmdbProxyClientFactory.createDirect(
                baseClient = client,
                credentialProvider = credentialProvider,
            )
            if (created != null) {
                cachedDirectService = created
            }
            return created
        }
    }

    internal fun resolveTmdbBackend(preferences: MediaPreferences): TmdbBackendClient? {
        resolveProxyService(preferences)?.let { return WorkerTmdbBackendClient(it) }
        resolveDirectService()?.let { return DirectTmdbBackendClient(it) }
        return null
    }

    fun isUsingDirectTmdb(preferences: MediaPreferences): Boolean =
        resolveProxyService(preferences) == null && credentialProvider.hasDirectCredentials()

    fun isTmdbBackendConfigured(preferences: MediaPreferences): Boolean =
        resolveTmdbBackend(preferences) != null

    suspend fun searchMedia(
        rawQuery: String,
        preferences: MediaPreferences,
    ): Result<List<MediaSearchResult>> {
        return searchAndDiscoverMedia(
            rawQuery = rawQuery,
            mediaTypeFilter = null,
            filters = TmdbDiscoverFilters(),
            page = 1,
            preferences = preferences,
        ).map { it.items }
    }

    suspend fun searchAndDiscoverMedia(
        rawQuery: String,
        mediaTypeFilter: MediaType? = null,
        filters: TmdbDiscoverFilters = TmdbDiscoverFilters(),
        page: Int = 1,
        preferences: MediaPreferences,
    ): Result<MediaSearchPage> = withContext(ioDispatcher) {
        val trimmed = rawQuery.trim()
        val safePage = page.coerceIn(1, 500)
        if (trimmed.length < 2 && !filters.hasActiveFilters()) {
            return@withContext Result.success(MediaSearchPage(emptyList(), page = 1, totalPages = 1))
        }

        val backend = resolveTmdbBackend(preferences)
        val region = WatchRegions.normalize(preferences.watchRegion)
        val cacheKey = listOf(
            trimmed.lowercase(Locale.US),
            mediaTypeFilter?.name ?: "ALL",
            filters.genreId?.toString() ?: "",
            filters.minRating?.toString() ?: "",
            filters.releaseYear?.toString() ?: "",
            safePage.toString(),
            backend != null,
            backend?.isDirectTmdb ?: false,
            preferences.searchLanguage.code,
            region,
        ).joinToString("|")

        synchronized(cacheLock) {
            searchCache[cacheKey]?.let { return@withContext Result.success(it) }
        }

        try {
            val pageResult = if (trimmed.length < 2 && filters.hasActiveFilters()) {
                discoverViaProxyOrFallback(
                    proxy = backend,
                    mediaTypeFilter = mediaTypeFilter,
                    filters = filters,
                    page = safePage,
                    region = region,
                    preferences = preferences,
                )
            } else {
                searchWithOptionalProxyAndZeroKey(
                    proxy = backend,
                    rawQuery = trimmed,
                    mediaTypeFilter = mediaTypeFilter,
                    filters = filters,
                    page = safePage,
                    region = region,
                    preferences = preferences,
                )
            }

            synchronized(cacheLock) {
                if (pageResult.items.isNotEmpty()) {
                    searchCache[cacheKey] = pageResult
                }
            }
            Result.success(pageResult)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun discoverViaProxyOrFallback(
        proxy: TmdbBackendClient?,
        mediaTypeFilter: MediaType?,
        filters: TmdbDiscoverFilters,
        page: Int,
        region: String,
        preferences: MediaPreferences,
    ): MediaSearchPage = supervisorScope {
        if (proxy == null) {
            // Zero-key local filtering over TVmaze popular when no backend URL is active
            val fallback = if (mediaTypeFilter != MediaType.MOVIE && page == 1) {
                zeroKey.fetchPopularTvMazeShows(limit = 20).filter { item ->
                    val yearOk = filters.releaseYear == null || item.releaseYear == filters.releaseYear
                    val ratingOk = filters.minRating == null || (item.imdbRating ?: 0.0) >= filters.minRating
                    yearOk && ratingOk
                }
            } else {
                emptyList()
            }
            return@supervisorScope MediaSearchPage(
                items = fallback,
                page = 1,
                totalPages = 1,
                totalResults = fallback.size,
                usedTmdbBackend = false,
            )
        }

        val lang = if (preferences.searchLanguage == SearchLanguagePreference.FA) "fa-IR" else "en-US"
        val genresCsv = filters.genreId?.toString()

        val movieDef = if (mediaTypeFilter != MediaType.TV) {
            async {
                runCatching {
                    proxy.discoverMovies(
                        language = lang,
                        page = page,
                        withGenres = genresCsv,
                        minRating = filters.minRating,
                        year = filters.releaseYear,
                        region = region,
                    )
                }.getOrNull()
            }
        } else null

        val tvDef = if (mediaTypeFilter != MediaType.MOVIE) {
            async {
                runCatching {
                    proxy.discoverTv(
                        language = lang,
                        page = page,
                        withGenres = genresCsv,
                        minRating = filters.minRating,
                        year = filters.releaseYear,
                        region = region,
                    )
                }.getOrNull()
            }
        } else null

        val movieBody = movieDef?.await()?.takeIf { it.isSuccessful }?.body()
        val tvBody = tvDef?.await()?.takeIf { it.isSuccessful }?.body()

        val mappedMovies = movieBody?.let { TmdbDtoMappers.mapSearchResponse(it, MediaType.MOVIE) }.orEmpty()
        val mappedTv = tvBody?.let { TmdbDtoMappers.mapSearchResponse(it, MediaType.TV) }.orEmpty()
        val combined = interleaveMovieAndTv(mappedMovies, mappedTv)
        val totalPages = maxOf(movieBody?.totalPages ?: 1, tvBody?.totalPages ?: 1)
        val totalResults = (movieBody?.totalResults ?: 0) + (tvBody?.totalResults ?: 0)

        MediaSearchPage(
            items = combined,
            page = page,
            totalPages = totalPages.coerceAtLeast(1),
            totalResults = totalResults,
            usedTmdbBackend = movieBody != null || tvBody != null,
        )
    }

    private suspend fun searchWithOptionalProxyAndZeroKey(
        proxy: TmdbBackendClient?,
        rawQuery: String,
        mediaTypeFilter: MediaType?,
        filters: TmdbDiscoverFilters,
        page: Int,
        region: String,
        preferences: MediaPreferences,
    ): MediaSearchPage = supervisorScope {
        val parsed = FuzzyTitleMatcher.parseQuery(rawQuery)
        val tmdbLang = if (parsed.isPersian || preferences.searchLanguage == SearchLanguagePreference.FA) "fa-IR" else "en-US"
        val effectiveYear = filters.releaseYear ?: parsed.year

        val tmdbDeferred = if (proxy != null) {
            async {
                when (mediaTypeFilter) {
                    MediaType.MOVIE -> runCatching {
                        proxy.searchMovies(
                            query = parsed.cleanTitle,
                            language = tmdbLang,
                            page = page,
                            year = effectiveYear,
                        )
                    }.getOrNull()?.takeIf { it.isSuccessful }?.body()?.let {
                        TmdbDtoMappers.mapSearchResponse(it, MediaType.MOVIE) to it.totalPages
                    }

                    MediaType.TV -> runCatching {
                        proxy.searchTv(
                            query = parsed.cleanTitle,
                            language = tmdbLang,
                            page = page,
                            year = effectiveYear,
                        )
                    }.getOrNull()?.takeIf { it.isSuccessful }?.body()?.let {
                        TmdbDtoMappers.mapSearchResponse(it, MediaType.TV) to it.totalPages
                    }

                    null -> runCatching {
                        proxy.searchMulti(
                            query = parsed.cleanTitle,
                            language = tmdbLang,
                            page = page,
                        )
                    }.getOrNull()?.takeIf { it.isSuccessful }?.body()?.let {
                        TmdbDtoMappers.mapSearchResponse(it, null) to it.totalPages
                    }
                }
            }
        } else null

        // On page 1, keep zero-key Wikipedia Persian bridge + IMDb Suggestions + TVmaze running in parallel
        val zeroKeyItems = if (page == 1) {
            val faBridgeHits = if (parsed.isPersian) {
                zeroKey.resolvePersianQueryViaWikipedia(parsed.cleanTitle)
            } else {
                emptyList()
            }
            val englishTerms = LinkedHashSet<String>()
            if (!parsed.isPersian) {
                englishTerms.add(parsed.cleanTitle)
            }
            faBridgeHits.forEach { hit ->
                hit.englishTitle?.takeIf { it.isNotBlank() }?.let { englishTerms.add(it) }
            }
            val primaryEn = englishTerms.firstOrNull()

            val imdbDeferred = async {
                val direct = mutableListOf<MediaSearchResult>()
                for (term in englishTerms.take(5)) {
                    direct.addAll(zeroKey.searchImdbSuggestion(term, limit = 8))
                    if (parsed.isPersian && direct.size >= 8) break
                }
                if (direct.isEmpty() && primaryEn != null) {
                    val wikiRepair = zeroKey.suggestEnglishCorrection(primaryEn)
                    if (wikiRepair != null && !wikiRepair.equals(primaryEn, ignoreCase = true)) {
                        direct.addAll(zeroKey.searchImdbSuggestion(wikiRepair, limit = 8))
                    }
                    if (direct.isEmpty()) {
                        for (fallback in FuzzyTitleMatcher.generateLightweightLookupFallbacks(primaryEn)) {
                            direct.addAll(zeroKey.searchImdbSuggestion(fallback, limit = 6))
                            if (direct.isNotEmpty()) break
                        }
                    }
                }
                direct
            }

            val tvMazeDeferred = async {
                val tvHits = mutableListOf<MediaSearchResult>()
                for (term in englishTerms.take(3)) {
                    tvHits.addAll(zeroKey.searchTvMaze(term, limit = 6))
                    if (parsed.isPersian && tvHits.size >= 6) break
                }
                if (tvHits.isEmpty() && primaryEn != null) {
                    val wikiRepair = zeroKey.suggestEnglishCorrection(primaryEn)
                    if (wikiRepair != null) {
                        tvHits.addAll(zeroKey.searchTvMaze(wikiRepair, limit = 5))
                    }
                }
                tvHits
            }

            mergeAndEnrichPersianHits(
                imdbHits = imdbDeferred.await(),
                tvMazeHits = tvMazeDeferred.await(),
                faBridgeHits = faBridgeHits,
            )
        } else {
            emptyList()
        }

        val tmdbResult = tmdbDeferred?.await()
        val tmdbItems = tmdbResult?.first.orEmpty()
        val totalPages = (tmdbResult?.second ?: 1).coerceAtLeast(1)

        val collected = (tmdbItems + zeroKeyItems).filter { item ->
            val typeOk = mediaTypeFilter == null || item.mediaType == mediaTypeFilter
            val ratingOk = filters.minRating == null || (item.imdbRating ?: 0.0) >= filters.minRating
            val yearOk = filters.releaseYear == null || item.releaseYear == filters.releaseYear
            typeOk && ratingOk && yearOk
        }

        val ranked = deduplicateAndRank(collected, parsed)
        MediaSearchPage(
            items = ranked,
            page = page,
            totalPages = totalPages,
            totalResults = ranked.size,
            usedTmdbBackend = tmdbResult != null && tmdbItems.isNotEmpty(),
        )
    }

    suspend fun getAvailableGenres(preferences: MediaPreferences): List<MediaGenre> = withContext(ioDispatcher) {
        val backend = resolveTmdbBackend(preferences) ?: return@withContext emptyList()
        val lang = if (preferences.searchLanguage == SearchLanguagePreference.FA) "fa-IR" else "en-US"
        synchronized(cacheLock) {
            if (cachedGenres.isNotEmpty() && cachedGenresLang == lang) {
                return@withContext cachedGenres
            }
        }
        val combined = supervisorScope {
            val movieDef = async { runCatching { backend.getMovieGenres(lang) }.getOrNull() }
            val tvDef = async { runCatching { backend.getTvGenres(lang) }.getOrNull() }
            val mGenres = movieDef.await()?.takeIf { it.isSuccessful }?.body()?.genres.orEmpty()
            val tGenres = tvDef.await()?.takeIf { it.isSuccessful }?.body()?.genres.orEmpty()
            (mGenres + tGenres)
                .filter { it.id > 0 && it.name.isNotBlank() }
                .distinctBy { it.id }
                .map { MediaGenre(id = it.id, name = it.name) }
                .sortedBy { it.name.lowercase(Locale.US) }
        }
        if (combined.isNotEmpty()) {
            synchronized(cacheLock) {
                cachedGenres = combined
                cachedGenresLang = lang
            }
        }
        combined
    }

    suspend fun getWatchProvidersForRegion(
        summary: MediaSearchResult,
        region: String,
        preferences: MediaPreferences,
    ): Pair<List<WatchProviderInfo>, String?> = withContext(ioDispatcher) {
        val normalizedRegion = WatchRegions.normalize(region)
        val tmdbId = summary.tmdbId ?: return@withContext (summary.watchProviders to summary.watchProvidersPageUrl)
        val cacheKey = "${summary.mediaType.name}:$tmdbId:$normalizedRegion"
        synchronized(cacheLock) {
            watchProvidersCache[cacheKey]?.let { return@withContext it }
        }
        val backend = resolveTmdbBackend(preferences) ?: return@withContext (summary.watchProviders to summary.watchProvidersPageUrl)
        val response = runCatching {
            if (summary.mediaType == MediaType.TV) {
                backend.getTvWatchProviders(tmdbId, region = normalizedRegion)
            } else {
                backend.getMovieWatchProviders(tmdbId, region = normalizedRegion)
            }
        }.getOrNull()

        if (response?.isSuccessful == true) {
            val body = response.body()
            val mapped = TmdbDtoMappers.mapWatchProviders(body, normalizedRegion, strictRegion = true)
            synchronized(cacheLock) {
                watchProvidersCache[cacheKey] = mapped
            }
            return@withContext mapped
        }
        summary.watchProviders to summary.watchProvidersPageUrl
    }

    suspend fun testBackendConnection(proxyBaseUrl: String): TmdbBackendDiagnostic = withContext(ioDispatcher) {
        val trimmedUrl = proxyBaseUrl.trim()
        if (trimmedUrl.isEmpty() && credentialProvider.hasDirectCredentials()) {
            val directService = resolveDirectService()
            if (directService != null) {
                val started = System.currentTimeMillis()
                return@withContext try {
                    val resp = directService.getConfiguration()
                    resp.body()?.close()
                    val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                    TmdbBackendDiagnostic(
                        endpointHost = "api.themoviedb.org (Direct)",
                        isConfigured = true,
                        isTesting = false,
                        isReachable = resp.isSuccessful,
                        tmdbSecretConfigured = true,
                        authMode = credentialProvider.authMode(),
                        httpStatus = resp.code(),
                        latencyMs = latency,
                        checkedAtMillis = System.currentTimeMillis(),
                        errorMessage = if (resp.isSuccessful) null else "HTTP ${resp.code()}",
                    )
                } catch (e: Exception) {
                    val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                    TmdbBackendDiagnostic(
                        endpointHost = "api.themoviedb.org (Direct)",
                        isConfigured = true,
                        isTesting = false,
                        isReachable = false,
                        tmdbSecretConfigured = true,
                        authMode = credentialProvider.authMode(),
                        latencyMs = latency,
                        checkedAtMillis = System.currentTimeMillis(),
                        errorMessage = sanitizeErrorMessage(e.message),
                    )
                }
            }
        }

        val normalized = TmdbProxyClientFactory.normalizeProxyBaseUrl(
            rawUrl = trimmedUrl,
            allowHttpForLocalhost = allowCleartextProxyForTests,
        )
        if (normalized == null) {
            return@withContext TmdbBackendDiagnostic(
                endpointHost = trimmedUrl,
                isConfigured = false,
                isTesting = false,
                isReachable = false,
                tmdbSecretConfigured = false,
                checkedAtMillis = System.currentTimeMillis(),
                errorMessage = "TMDB build credentials and Proxy URL are both unconfigured.",
            )
        }
        val host = normalized.toHttpUrlOrNull()?.host ?: normalized
        val service = TmdbProxyClientFactory.create(
            baseUrl = normalized,
            client = client,
            allowHttpForLocalhost = allowCleartextProxyForTests,
        ) ?: return@withContext TmdbBackendDiagnostic(
            endpointHost = host,
            isConfigured = false,
            isTesting = false,
            isReachable = false,
            checkedAtMillis = System.currentTimeMillis(),
            errorMessage = "Invalid HTTPS backend URL.",
        )

        val started = System.currentTimeMillis()
        try {
            val resp = service.getHealth()
            val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
            val body = resp.body()
            TmdbBackendDiagnostic(
                endpointHost = host,
                isConfigured = true,
                isTesting = false,
                isReachable = resp.isSuccessful && (body?.ok == true),
                tmdbSecretConfigured = body?.tmdbConfigured,
                authMode = body?.authMode,
                httpStatus = resp.code(),
                latencyMs = latency,
                checkedAtMillis = System.currentTimeMillis(),
                errorMessage = if (resp.isSuccessful) null else "HTTP ${resp.code()}",
            )
        } catch (e: Exception) {
            val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
            TmdbBackendDiagnostic(
                endpointHost = host,
                isConfigured = true,
                isTesting = false,
                isReachable = false,
                latencyMs = latency,
                checkedAtMillis = System.currentTimeMillis(),
                errorMessage = sanitizeErrorMessage(e.message),
            )
        }
    }

    private fun sanitizeErrorMessage(raw: String?): String {
        val msg = raw ?: return "Connection failed"
        return msg.replace(Regex("api_key=[^&\\s]+"), "api_key=***")
            .replace(Regex("Bearer\\s+[^\\s]+", RegexOption.IGNORE_CASE), "Bearer ***")
    }

    suspend fun getPopularDiscoverFeed(preferences: MediaPreferences): List<MediaSearchResult> = withContext(ioDispatcher) {
        synchronized(cacheLock) {
            if (cachedPopularFeed.isNotEmpty()) return@withContext cachedPopularFeed
        }
        val backend = resolveTmdbBackend(preferences)
        val items = if (backend != null) {
            fetchProxyTrendingOverview(backend, preferences).ifEmpty {
                zeroKey.fetchPopularTvMazeShows(limit = 12)
            }
        } else {
            zeroKey.fetchPopularTvMazeShows(limit = 12)
        }
        synchronized(cacheLock) {
            if (items.isNotEmpty()) cachedPopularFeed = items
        }
        items
    }

    private suspend fun fetchProxyTrendingOverview(
        proxy: TmdbBackendClient,
        preferences: MediaPreferences,
    ): List<MediaSearchResult> = supervisorScope {
        val lang = if (preferences.searchLanguage == SearchLanguagePreference.FA) "fa-IR" else "en-US"
        val window = preferences.trendingWindow.apiValue
        val region = WatchRegions.normalize(preferences.watchRegion)
        val moviesDef = async {
            runCatching { proxy.getTrendingMovies(window, lang, page = 1, region = region) }.getOrNull()
        }
        val tvDef = async {
            runCatching { proxy.getTrendingTv(window, lang, page = 1, region = region) }.getOrNull()
        }
        val mItems = moviesDef.await()?.takeIf { it.isSuccessful }?.body()
            ?.let { TmdbDtoMappers.mapSearchResponse(it, MediaType.MOVIE) }.orEmpty()
        val tItems = tvDef.await()?.takeIf { it.isSuccessful }?.body()
            ?.let { TmdbDtoMappers.mapSearchResponse(it, MediaType.TV) }.orEmpty()
        interleaveMovieAndTv(mItems, tItems).take(12)
    }

    suspend fun getTrendingFeed(
        preferences: MediaPreferences,
        persistence: ArchiveCacheStore?,
        forceRefresh: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): TrendingFeedBundle = withContext(ioDispatcher) {
        val proxy = resolveTmdbBackend(preferences)
        val expectsFallback = proxy == null
        val requestedWindow = preferences.trendingWindow
        val requestedRegion = WatchRegions.normalize(preferences.watchRegion)

        if (!forceRefresh) {
            val memCached = synchronized(cacheLock) { cachedTrendingFeed }
            if (isTrendingCacheValid(memCached, nowMillis, expectsFallback, requestedWindow, requestedRegion)) {
                return@withContext memCached!!
            }

            val diskCached = persistence?.loadTrendingCache()
            if (isTrendingCacheValid(diskCached, nowMillis, expectsFallback, requestedWindow, requestedRegion)) {
                synchronized(cacheLock) { cachedTrendingFeed = diskCached }
                return@withContext diskCached!!
            }
        }

        val todayIso = formatIsoDateUtc(nowMillis)
        var tmdbMovies = emptyList<MediaSearchResult>()
        var tmdbTv = emptyList<MediaSearchResult>()

        if (proxy != null) {
            supervisorScope {
                val moviesDef = async {
                    fetchProxyTrendingMoviesDigital(
                        proxy = proxy,
                        window = requestedWindow,
                        langPref = preferences.searchLanguage,
                        region = requestedRegion,
                        todayIso = todayIso,
                    )
                }
                val tvDef = async {
                    fetchProxyTrendingTvDetailed(
                        proxy = proxy,
                        window = requestedWindow,
                        langPref = preferences.searchLanguage,
                        region = requestedRegion,
                    )
                }
                tmdbMovies = moviesDef.await()
                tmdbTv = tvDef.await()
            }
        }

        val needMovieFallback = expectsFallback || tmdbMovies.isEmpty()
        val needTvFallback = expectsFallback || tmdbTv.isEmpty()

        var fallbackMovies = emptyList<MediaSearchResult>()
        var fallbackTv = emptyList<MediaSearchResult>()
        if (needMovieFallback || needTvFallback) {
            supervisorScope {
                val mDef = if (needMovieFallback) async { zeroKey.fetchAppleTopMoviesFallback(limit = 5) } else null
                val tDef = if (needTvFallback) async { zeroKey.fetchTrendingTvMazeFallback(limit = 5) } else null
                fallbackMovies = mDef?.await().orEmpty()
                fallbackTv = tDef?.await().orEmpty()
            }
        }

        val finalMovies = (if (tmdbMovies.isNotEmpty()) tmdbMovies else fallbackMovies).take(5)
        val finalTv = (if (tmdbTv.isNotEmpty()) tmdbTv else fallbackTv).take(5)
        val usedFallback = needMovieFallback || needTvFallback

        val attrParts = buildList {
            if (tmdbMovies.isNotEmpty() || tmdbTv.isNotEmpty()) add("TMDB")
            if (needMovieFallback && finalMovies.isNotEmpty()) add("Apple TV RSS")
            if (needTvFallback && finalTv.isNotEmpty()) add("TVmaze")
        }.distinct()

        val bundle = TrendingFeedBundle(
            movies = finalMovies,
            tvShows = finalTv,
            lastUpdatedMillis = nowMillis,
            isUsingFallbackSources = usedFallback,
            attribution = attrParts.joinToString(" • "),
            window = requestedWindow,
            region = requestedRegion,
        )
        if (finalMovies.isNotEmpty() || finalTv.isNotEmpty()) {
            synchronized(cacheLock) { cachedTrendingFeed = bundle }
            persistence?.saveTrendingCache(bundle)
        }
        bundle
    }

    private fun isTrendingCacheValid(
        cache: TrendingFeedBundle?,
        nowMillis: Long,
        expectsFallback: Boolean,
        requestedWindow: TrendingTimeWindow,
        requestedRegion: String,
    ): Boolean {
        if (cache == null) return false
        if (cache.movies.isEmpty() && cache.tvShows.isEmpty()) return false
        if (nowMillis - cache.lastUpdatedMillis >= TRENDING_CACHE_TTL_MS) return false
        if (cache.isUsingFallbackSources != expectsFallback) return false
        if (!expectsFallback && (cache.window != requestedWindow || !cache.region.equals(requestedRegion, ignoreCase = true))) {
            return false
        }
        return true
    }

    private suspend fun fetchProxyTrendingMoviesDigital(
        proxy: TmdbBackendClient,
        window: TrendingTimeWindow,
        langPref: SearchLanguagePreference,
        region: String,
        todayIso: String,
    ): List<MediaSearchResult> = supervisorScope {
        val candidateIds = LinkedHashSet<Int>()
        runCatching { proxy.getTrendingMovies(window.apiValue, "en-US", page = 1, region = region) }
            .getOrNull()?.takeIf { it.isSuccessful }?.body()?.results?.forEach {
                if (it.id > 0) candidateIds.add(it.id)
            }

        if (candidateIds.size < 10) {
            runCatching {
                proxy.discoverMovies(
                    language = "en-US",
                    page = 1,
                    sortBy = "popularity.desc",
                    region = region,
                    releaseType = "4|6",
                )
            }.getOrNull()?.takeIf { it.isSuccessful }?.body()?.results?.forEach {
                if (it.id > 0) candidateIds.add(it.id)
            }
        }

        val inspected = candidateIds.take(14).map { id ->
            async {
                val enDef = async {
                    runCatching { proxy.getMovieDetails(id, language = "en-US") }.getOrNull()
                        ?.takeIf { it.isSuccessful }?.body()
                }
                val faDef = if (langPref != SearchLanguagePreference.EN) {
                    async {
                        runCatching { proxy.getMovieDetails(id, language = "fa-IR", appendToResponse = null) }.getOrNull()
                            ?.takeIf { it.isSuccessful }?.body()
                    }
                } else null
                val enBody = enDef.await() ?: return@async null
                val faBody = faDef?.await()
                TmdbDtoMappers.mapTrendingMovieDetail(enBody, faBody, todayIso, region)
            }
        }.mapNotNull { it.await() }

        inspected.take(5)
    }

    private suspend fun fetchProxyTrendingTvDetailed(
        proxy: TmdbBackendClient,
        window: TrendingTimeWindow,
        langPref: SearchLanguagePreference,
        region: String,
    ): List<MediaSearchResult> = supervisorScope {
        val candidateIds = LinkedHashSet<Int>()
        runCatching { proxy.getTrendingTv(window.apiValue, "en-US", page = 1, region = region) }
            .getOrNull()?.takeIf { it.isSuccessful }?.body()?.results?.forEach {
                if (it.id > 0) candidateIds.add(it.id)
            }

        val inspected = candidateIds.take(8).map { id ->
            async {
                val enDef = async {
                    runCatching { proxy.getTvDetails(id, language = "en-US") }.getOrNull()
                        ?.takeIf { it.isSuccessful }?.body()
                }
                val faDef = if (langPref != SearchLanguagePreference.EN) {
                    async {
                        runCatching { proxy.getTvDetails(id, language = "fa-IR", appendToResponse = null) }.getOrNull()
                            ?.takeIf { it.isSuccessful }?.body()
                    }
                } else null
                val enBody = enDef.await() ?: return@async null
                val faBody = faDef?.await()
                TmdbDtoMappers.mapTrendingTvDetail(enBody, faBody, region)
            }
        }.mapNotNull { it.await() }

        inspected.take(5)
    }

    suspend fun getMediaDetails(
        summary: MediaSearchResult,
        preferences: MediaPreferences,
    ): Result<MediaDetails> = withContext(ioDispatcher) {
        val proxy = resolveTmdbBackend(preferences)
        val region = WatchRegions.normalize(preferences.watchRegion)
        val cacheKey = "${summary.id}|${proxy != null}|${proxy?.isDirectTmdb ?: false}|${preferences.omdbApiKey.isNotBlank()}|$region|${preferences.searchLanguage.code}"
        synchronized(cacheLock) {
            detailsCache[cacheKey]?.let { return@withContext Result.success(it) }
        }

        try {
            var details: MediaDetails? = null

            supervisorScope {
                val tmdbDeferred = if (proxy != null) {
                    async { fetchProxyFullDetails(proxy, summary, preferences.searchLanguage, region) }
                } else null

                val tvMazeDeferred = if (summary.mediaType == MediaType.TV) {
                    async { zeroKey.fetchTvMazeDetails(summary.tvMazeId, summary.imdbId, summary.title) }
                } else null

                val wikiDeferred = async {
                    zeroKey.fetchWikidataAndWikipediaEnrichment(
                        title = summary.title,
                        imdbId = summary.imdbId,
                        knownWikidataId = summary.wikidataId,
                    )
                }

                val omdbDeferred = if (preferences.omdbApiKey.isNotBlank()) {
                    async { fetchOmdbEnrichment(summary, preferences.omdbApiKey) }
                } else null

                val tmdbDetails = tmdbDeferred?.await()
                val tvMaze = tvMazeDeferred?.await()
                val wiki = wikiDeferred.await()
                val omdb = omdbDeferred?.await()

                val imdbId = tmdbDetails?.imdbId ?: omdb?.imdbId ?: tvMaze?.imdbId ?: wiki?.imdbId ?: summary.imdbId
                val castList = when {
                    tmdbDetails != null && tmdbDetails.cast.isNotEmpty() -> tmdbDetails.cast
                    tvMaze != null && tvMaze.cast.isNotEmpty() -> tvMaze.cast
                    omdb != null && omdb.actors.isNotEmpty() -> omdb.actors.map { CastMember(it) }
                    wiki != null && wiki.cast.isNotEmpty() -> wiki.cast.map { CastMember(it) }
                    else -> summary.topCast.map { CastMember(it) }
                }
                val directorsList = when {
                    tmdbDetails != null && tmdbDetails.directors.isNotEmpty() -> tmdbDetails.directors
                    omdb != null && omdb.directors.isNotEmpty() -> omdb.directors
                    wiki != null && wiki.directors.isNotEmpty() -> wiki.directors
                    else -> summary.directors
                }
                val creatorsList = when {
                    tmdbDetails != null && tmdbDetails.creators.isNotEmpty() -> tmdbDetails.creators
                    tvMaze != null && tvMaze.creators.isNotEmpty() -> tvMaze.creators
                    else -> emptyList()
                }
                val genresList = listOfNotNull(
                    tmdbDetails?.genres?.takeIf { it.isNotEmpty() },
                    tvMaze?.genres?.takeIf { it.isNotEmpty() },
                    omdb?.genres?.takeIf { it.isNotEmpty() },
                    wiki?.genres?.takeIf { it.isNotEmpty() },
                    summary.genres.takeIf { it.isNotEmpty() },
                ).firstOrNull().orEmpty()

                val seasonsList = when {
                    tmdbDetails != null && tmdbDetails.seasons.isNotEmpty() -> tmdbDetails.seasons
                    tvMaze != null && tvMaze.seasons.isNotEmpty() -> tvMaze.seasons
                    else -> emptyList()
                }

                val enOverview = tmdbDetails?.overview
                    ?: omdb?.plot
                    ?: tvMaze?.overview
                    ?: wiki?.englishOverview
                    ?: summary.overview

                val faOverview = tmdbDetails?.persianOverview
                    ?: wiki?.persianOverview
                    ?: summary.persianOverview

                val faTitle = tmdbDetails?.persianTitle
                    ?: wiki?.persianTitle
                    ?: summary.persianTitle

                val resolvedTmdbId = tmdbDetails?.tmdbId ?: summary.tmdbId
                val resolvedProviders = tmdbDetails?.watchProviders?.takeIf { it.isNotEmpty() }
                    ?: summary.watchProviders
                val resolvedProvidersPage = tmdbDetails?.watchProvidersPageUrl ?: summary.watchProvidersPageUrl
                val resolvedDigitalStatus = tmdbDetails?.digitalReleaseStatus
                    ?.takeIf { it != DigitalReleaseStatus.UNKNOWN }
                    ?: summary.digitalReleaseStatus
                val resolvedDigitalDate = tmdbDetails?.digitalReleaseDate ?: summary.digitalReleaseDate
                val resolvedTvStatus = tmdbDetails?.tvReleaseStatus
                    ?.takeIf { it != TvReleaseStatus.UNKNOWN }
                    ?: summary.tvReleaseStatus

                details = MediaDetails(
                    id = summary.id,
                    mediaType = summary.mediaType,
                    title = tmdbDetails?.title ?: summary.title,
                    persianTitle = faTitle?.takeIf { !it.equals(summary.title, ignoreCase = true) },
                    originalTitle = tmdbDetails?.originalTitle ?: summary.originalTitle,
                    releaseYear = tmdbDetails?.releaseYear ?: tvMaze?.startYear ?: omdb?.year ?: summary.releaseYear,
                    releaseDate = tmdbDetails?.releaseDate ?: tvMaze?.premiered ?: omdb?.released ?: wiki?.releaseDate,
                    posterUrl = tmdbDetails?.posterUrl ?: summary.posterUrl ?: tvMaze?.posterUrl ?: omdb?.poster,
                    backdropUrl = tmdbDetails?.backdropUrl,
                    runtimeMinutes = tmdbDetails?.runtimeMinutes ?: omdb?.runtimeMinutes ?: wiki?.runtimeMinutes ?: summary.runtimeMinutes,
                    episodeRuntimeMinutes = tmdbDetails?.episodeRuntimeMinutes ?: tvMaze?.episodeRuntimeMinutes,
                    genres = genresList,
                    directors = directorsList,
                    creators = creatorsList,
                    cast = castList,
                    imdbRating = omdb?.imdbRating ?: tvMaze?.rating ?: tmdbDetails?.imdbRating ?: summary.imdbRating,
                    overview = enOverview,
                    persianOverview = faOverview,
                    country = tmdbDetails?.country ?: omdb?.country ?: tvMaze?.country ?: wiki?.country,
                    originalLanguage = tmdbDetails?.originalLanguage ?: omdb?.language ?: tvMaze?.originalLanguage ?: wiki?.originalLanguage,
                    status = tmdbDetails?.status ?: tvMaze?.status,
                    startYear = tmdbDetails?.startYear ?: tvMaze?.startYear ?: summary.releaseYear,
                    endYear = tmdbDetails?.endYear ?: tvMaze?.endYear,
                    totalSeasons = tmdbDetails?.totalSeasons ?: seasonsList.size.takeIf { it > 0 } ?: summary.seasonCount,
                    totalEpisodes = tmdbDetails?.totalEpisodes ?: tvMaze?.totalEpisodes,
                    seasons = seasonsList,
                    imdbId = imdbId,
                    tmdbId = resolvedTmdbId,
                    tvMazeId = tvMaze?.tvMazeId ?: summary.tvMazeId,
                    wikidataId = wiki?.wikidataId ?: summary.wikidataId,
                    imdbUrl = imdbId?.let { "https://www.imdb.com/title/$it/" },
                    tmdbUrl = resolvedTmdbId?.let {
                        val path = if (summary.mediaType == MediaType.TV) "tv" else "movie"
                        "https://www.themoviedb.org/$path/$it"
                    },
                    wikipediaUrl = (wiki?.wikidataId ?: summary.wikidataId)?.let { "https://www.wikidata.org/wiki/$it" },
                    sourceAttribution = buildAttribution(tmdbDetails != null, omdb != null, tvMaze != null, wiki != null),
                    digitalReleaseStatus = resolvedDigitalStatus,
                    digitalReleaseDate = resolvedDigitalDate,
                    tvReleaseStatus = resolvedTvStatus,
                    watchProviders = resolvedProviders,
                    watchProvidersPageUrl = resolvedProvidersPage,
                    authorizedDirectDownloadUrl = summary.authorizedDirectDownloadUrl,
                )
            }

            val finalDetails = details ?: return@withContext Result.failure(IllegalStateException("Unable to load details"))
            synchronized(cacheLock) {
                detailsCache[cacheKey] = finalDetails
            }
            Result.success(finalDetails)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchProxyFullDetails(
        proxy: TmdbBackendClient,
        summary: MediaSearchResult,
        langPref: SearchLanguagePreference,
        region: String,
    ): MediaDetails? = supervisorScope {
        var tmdbId = summary.tmdbId
        if (tmdbId == null && !summary.imdbId.isNullOrBlank()) {
            val found = runCatching { proxy.findByImdbId(summary.imdbId) }.getOrNull()
                ?.takeIf { it.isSuccessful }?.body()
            tmdbId = if (summary.mediaType == MediaType.TV) {
                found?.tvResults?.firstOrNull()?.id?.takeIf { it > 0 }
            } else {
                found?.movieResults?.firstOrNull()?.id?.takeIf { it > 0 }
            }
        }
        val resolvedId = tmdbId ?: return@supervisorScope null
        val todayIso = formatIsoDateUtc(System.currentTimeMillis())

        if (summary.mediaType == MediaType.MOVIE) {
            val enDef = async {
                runCatching { proxy.getMovieDetails(resolvedId, language = "en-US") }.getOrNull()
                    ?.takeIf { it.isSuccessful }?.body()
            }
            val faDef = if (langPref != SearchLanguagePreference.EN) {
                async {
                    runCatching { proxy.getMovieDetails(resolvedId, language = "fa-IR", appendToResponse = null) }.getOrNull()
                        ?.takeIf { it.isSuccessful }?.body()
                }
            } else null
            val enDto = enDef.await() ?: return@supervisorScope null
            val faDto = faDef?.await()
            return@supervisorScope TmdbDtoMappers.mapMovieDetails(enDto, faDto, todayIso, region)
        }

        val enDef = async {
            runCatching { proxy.getTvDetails(resolvedId, language = "en-US") }.getOrNull()
                ?.takeIf { it.isSuccessful }?.body()
        }
        val faDef = if (langPref != SearchLanguagePreference.EN) {
            async {
                runCatching { proxy.getTvDetails(resolvedId, language = "fa-IR", appendToResponse = null) }.getOrNull()
                    ?.takeIf { it.isSuccessful }?.body()
            }
        } else null
        val enDto = enDef.await() ?: return@supervisorScope null
        val faDto = faDef?.await()
        val parsed = TmdbDtoMappers.mapTvDetails(enDto, faDto, region) ?: return@supervisorScope null

        if (parsed.seasons.isNotEmpty()) {
            val seasonsToLoad = parsed.seasons.filter { it.seasonNumber > 0 }.take(8)
            val loadedSeasons = seasonsToLoad.map { season ->
                async {
                    val sDto = runCatching {
                        proxy.getTvSeasonDetails(resolvedId, season.seasonNumber, "en-US")
                    }.getOrNull()?.takeIf { it.isSuccessful }?.body()
                    val eps = sDto?.episodes.orEmpty().mapIndexed { idx, ep ->
                        EpisodeInfo(
                            episodeNumber = ep.episodeNumber.takeIf { it > 0 } ?: (idx + 1),
                            seasonNumber = season.seasonNumber,
                            title = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${idx + 1}",
                            airDate = ep.airDate?.takeIf { it.isNotBlank() },
                            runtimeMinutes = ep.runtime?.takeIf { it > 0 },
                            overview = ep.overview?.takeIf { it.isNotBlank() },
                        )
                    }
                    season.copy(episodes = eps)
                }
            }.map { it.await() }
            val remaining = parsed.seasons.filter { s -> loadedSeasons.none { it.seasonNumber == s.seasonNumber } }
            parsed.copy(seasons = (loadedSeasons + remaining).sortedBy { it.seasonNumber })
        } else {
            parsed
        }
    }

    private fun mergeAndEnrichPersianHits(
        imdbHits: List<MediaSearchResult>,
        tvMazeHits: List<MediaSearchResult>,
        faBridgeHits: List<ZeroKeyMetadataEnricher.WikipediaBridgeHit>,
    ): List<MediaSearchResult> {
        val result = mutableListOf<MediaSearchResult>()
        val faLookup = faBridgeHits.mapNotNull { hit ->
            val en = hit.englishTitle ?: return@mapNotNull null
            FuzzyTitleMatcher.normalizeText(en) to hit
        }

        fun findMatchingBridge(title: String): ZeroKeyMetadataEnricher.WikipediaBridgeHit? {
            val norm = FuzzyTitleMatcher.normalizeText(title)
            return faLookup.firstOrNull { (enNorm, _) ->
                enNorm == norm || FuzzyTitleMatcher.stringSimilarity(enNorm, norm) > 0.78
            }?.second
        }

        for (hit in imdbHits) {
            val bridge = findMatchingBridge(hit.title)
            result.add(
                hit.copy(
                    persianTitle = bridge?.persianTitle ?: hit.persianTitle,
                    persianOverview = bridge?.persianExtract ?: hit.persianOverview,
                    wikidataId = bridge?.wikidataId ?: hit.wikidataId,
                )
            )
        }
        for (tv in tvMazeHits) {
            val bridge = findMatchingBridge(tv.title)
            result.add(
                tv.copy(
                    persianTitle = bridge?.persianTitle ?: tv.persianTitle,
                    persianOverview = bridge?.persianExtract ?: tv.persianOverview,
                    wikidataId = bridge?.wikidataId ?: tv.wikidataId,
                )
            )
        }
        return result
    }

    private fun deduplicateAndRank(
        items: List<MediaSearchResult>,
        query: ParsedSearchQuery,
    ): List<MediaSearchResult> {
        val mergedByKey = linkedMapOf<String, MediaSearchResult>()
        for (item in items) {
            val dedupKey = when {
                !item.imdbId.isNullOrBlank() -> "imdb:${item.imdbId}"
                item.tmdbId != null -> "tmdb:${item.mediaType.name}:${item.tmdbId}"
                else -> "${item.mediaType.name}:${FuzzyTitleMatcher.normalizeText(item.title)}:${item.releaseYear ?: 0}"
            }
            val existing = mergedByKey[dedupKey]
            mergedByKey[dedupKey] = if (existing == null) item else mergeResults(existing, item)
        }

        return mergedByKey.values.map { item ->
            val boost = (item.imdbRating ?: 0.0) * 0.5 + (if (item.posterUrl != null) 2.0 else 0.0)
            val score = FuzzyTitleMatcher.scoreCandidate(
                query = query,
                candidateTitle = item.title,
                candidateAltTitle = item.persianTitle ?: item.originalTitle,
                candidateYear = item.releaseYear,
                basePopularityBoost = boost,
            )
            item.copy(relevanceScore = score)
        }.sortedByDescending { it.relevanceScore }.take(25)
    }

    private fun mergeResults(a: MediaSearchResult, b: MediaSearchResult): MediaSearchResult {
        return a.copy(
            persianTitle = a.persianTitle ?: b.persianTitle,
            originalTitle = a.originalTitle ?: b.originalTitle,
            releaseYear = a.releaseYear ?: b.releaseYear,
            posterUrl = a.posterUrl ?: b.posterUrl,
            runtimeMinutes = a.runtimeMinutes ?: b.runtimeMinutes,
            genres = if (a.genres.isNotEmpty()) a.genres else b.genres,
            directors = if (a.directors.isNotEmpty()) a.directors else b.directors,
            topCast = if (a.topCast.isNotEmpty()) a.topCast else b.topCast,
            imdbRating = a.imdbRating ?: b.imdbRating,
            overview = a.overview ?: b.overview,
            persianOverview = a.persianOverview ?: b.persianOverview,
            imdbId = a.imdbId ?: b.imdbId,
            tmdbId = a.tmdbId ?: b.tmdbId,
            tvMazeId = a.tvMazeId ?: b.tvMazeId,
            wikidataId = a.wikidataId ?: b.wikidataId,
        )
    }

    private fun interleaveMovieAndTv(
        movies: List<MediaSearchResult>,
        tv: List<MediaSearchResult>,
    ): List<MediaSearchResult> {
        val maxLen = maxOf(movies.size, tv.size)
        return buildList(movies.size + tv.size) {
            for (i in 0 until maxLen) {
                if (i < movies.size) add(movies[i])
                if (i < tv.size) add(tv[i])
            }
        }
    }

    // Pure offline JSON helper retained for unit testing TMDB TV release-status logic without network keys
    fun parseTmdbTvTrendingDetail(
        enJson: String,
        faJson: String?,
    ): MediaSearchResult? {
        return try {
            val obj = JSONObject(enJson)
            val tmdbId = obj.optInt("id", -1).takeIf { it > 0 } ?: return null
            val title = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
            val origTitle = obj.optString("original_name").takeIf { it.isNotBlank() }
            val faObj = faJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            val faTitle = faObj?.optString("name")?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
            val firstAir = obj.optString("first_air_date")
            val poster = obj.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" }
            val status = obj.optString("status")
            val tvStatus = when {
                status.equals("Ended", ignoreCase = true) ||
                    status.equals("Canceled", ignoreCase = true) -> TvReleaseStatus.ENDED
                status.equals("In Production", ignoreCase = true) ||
                    status.equals("Planned", ignoreCase = true) -> TvReleaseStatus.UPCOMING
                status.equals("Returning Series", ignoreCase = true) -> {
                    val hasNextEpisode = !obj.isNull("next_episode_to_air") && obj.optJSONObject("next_episode_to_air") != null
                    val hasLastEpisode = !obj.isNull("last_episode_to_air") && obj.optJSONObject("last_episode_to_air") != null
                    when {
                        hasNextEpisode -> TvReleaseStatus.CURRENTLY_AIRING
                        hasLastEpisode -> TvReleaseStatus.SEASON_COMPLETED
                        obj.optBoolean("in_production", false) -> TvReleaseStatus.CURRENTLY_AIRING
                        else -> TvReleaseStatus.SEASON_COMPLETED
                    }
                }
                else -> TvReleaseStatus.UNKNOWN
            }
            MediaSearchResult(
                id = "tmdb:tv:$tmdbId",
                mediaType = MediaType.TV,
                title = title,
                persianTitle = faTitle,
                originalTitle = origTitle,
                releaseYear = firstAir.take(4).toIntOrNull(),
                posterUrl = poster,
                tmdbId = tmdbId,
                tvReleaseStatus = tvStatus,
                seasonCount = obj.optInt("number_of_seasons", 0).takeIf { it > 0 },
            )
        } catch (_: Exception) {
            null
        }
    }

    // --- Optional OMDb Support (non-TMDB user option) ---

    private data class OmdbEnrichment(
        val imdbId: String?,
        val year: Int?,
        val released: String?,
        val runtimeMinutes: Int?,
        val genres: List<String>,
        val directors: List<String>,
        val actors: List<String>,
        val plot: String?,
        val language: String?,
        val country: String?,
        val poster: String?,
        val imdbRating: Double?,
    )

    private fun fetchOmdbEnrichment(summary: MediaSearchResult, apiKey: String): OmdbEnrichment? {
        val queryParam = if (!summary.imdbId.isNullOrBlank()) {
            "i=${URLEncoder.encode(summary.imdbId, "UTF-8")}"
        } else {
            val y = summary.releaseYear?.let { "&y=$it" }.orEmpty()
            "t=${URLEncoder.encode(summary.title, "UTF-8")}$y"
        }
        val body = getString("https://www.omdbapi.com/?$queryParam&apikey=$apiKey&plot=full") ?: return null
        return try {
            val obj = JSONObject(body)
            if (!obj.optString("Response").equals("True", ignoreCase = true)) return null
            val runtime = obj.optString("Runtime").substringBefore(" ").toIntOrNull()
            val rating = obj.optString("imdbRating").toDoubleOrNull()
            fun cleanCsv(key: String): List<String> = obj.optString(key)
                .takeIf { it.isNotBlank() && it != "N/A" }
                ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            fun cleanStr(key: String): String? = obj.optString(key).takeIf { it.isNotBlank() && it != "N/A" }

            OmdbEnrichment(
                imdbId = cleanStr("imdbID"),
                year = cleanStr("Year")?.take(4)?.toIntOrNull(),
                released = cleanStr("Released"),
                runtimeMinutes = runtime,
                genres = cleanCsv("Genre"),
                directors = cleanCsv("Director"),
                actors = cleanCsv("Actors"),
                plot = cleanStr("Plot"),
                language = cleanStr("Language"),
                country = cleanStr("Country"),
                poster = cleanStr("Poster")?.takeIf { it.startsWith("http") },
                imdbRating = rating,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildAttribution(tmdb: Boolean, omdb: Boolean, tvMaze: Boolean, wiki: Boolean): String {
        val parts = mutableListOf<String>()
        if (tmdb) parts.add("TMDB")
        if (omdb) parts.add("OMDb")
        if (tvMaze) parts.add("TVmaze")
        if (wiki) parts.add("Wikipedia/Wikidata")
        parts.add("IMDb Suggestions")
        return parts.distinct().joinToString(" • ")
    }

    private fun getString(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "MediaXAndroid/1.1")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val TRENDING_CACHE_TTL_MS: Long = 30L * 60L * 1000L

        internal fun formatIsoDateUtc(millis: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return fmt.format(Date(millis))
        }

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
