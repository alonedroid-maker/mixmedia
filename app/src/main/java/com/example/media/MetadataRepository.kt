package com.example.media

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class MetadataRepository(
    private val client: OkHttpClient = defaultOkHttpClient(),
) {

    private val zeroKey = ZeroKeyMetadataEnricher(client)

    private val cacheLock = Any()
    private val searchCache = object : LinkedHashMap<String, List<MediaSearchResult>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaSearchResult>>?): Boolean = size > 25
    }
    private val detailsCache = object : LinkedHashMap<String, MediaDetails>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaDetails>?): Boolean = size > 25
    }
    private var cachedPopularFeed: List<MediaSearchResult> = emptyList()
    private var cachedTrendingFeed: TrendingFeedBundle? = null

    fun clearMemoryCaches() {
        synchronized(cacheLock) {
            searchCache.clear()
            detailsCache.clear()
            cachedPopularFeed = emptyList()
            cachedTrendingFeed = null
        }
    }

    suspend fun searchMedia(
        rawQuery: String,
        preferences: MediaPreferences,
    ): Result<List<MediaSearchResult>> = withContext(Dispatchers.IO) {
        val trimmed = rawQuery.trim()
        if (trimmed.length < 2) return@withContext Result.success(emptyList())

        val cacheKey = "${trimmed.lowercase()}|${preferences.tmdbApiKey.isNotBlank()}|${preferences.searchLanguage.code}"
        synchronized(cacheLock) {
            searchCache[cacheKey]?.let { return@withContext Result.success(it) }
        }

        try {
            val parsed = FuzzyTitleMatcher.parseQuery(trimmed)
            val collected = mutableListOf<MediaSearchResult>()

            supervisorScope {
                // 1. If TMDB API key is configured, query TMDB search/multi (+ fa-IR if applicable)
                val tmdbDeferred = if (preferences.tmdbApiKey.isNotBlank()) {
                    async { searchTmdb(parsed, preferences.tmdbApiKey, preferences.searchLanguage) }
                } else null

                // 2. If query contains Persian characters (e.g. "میان ستاره ای", "برکینگ بد"), bridge via fa.wikipedia.org
                val faBridgeHits = if (parsed.isPersian) {
                    zeroKey.resolvePersianQueryViaWikipedia(parsed.cleanTitle)
                } else emptyList()

                val englishTermsToQuery = LinkedHashSet<String>()
                if (!parsed.isPersian) {
                    englishTermsToQuery.add(parsed.cleanTitle)
                }
                faBridgeHits.forEach { hit ->
                    hit.englishTitle?.takeIf { it.isNotBlank() }?.let { englishTermsToQuery.add(it) }
                }

                // 3. Query IMDb public suggestions and TVmaze for English terms (works with zero API keys!)
                val primaryEn = englishTermsToQuery.firstOrNull()
                val imdbDeferred = async {
                    val direct = mutableListOf<MediaSearchResult>()
                    for (term in englishTermsToQuery.take(3)) {
                        direct.addAll(zeroKey.searchImdbSuggestion(term, limit = 8))
                    }
                    // If spelling typo produced sparse results, repair via Wikipedia suggestion + lightweight fallbacks
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
                    for (term in englishTermsToQuery.take(2)) {
                        tvHits.addAll(zeroKey.searchTvMaze(term, limit = 6))
                    }
                    if (tvHits.isEmpty() && primaryEn != null) {
                        val wikiRepair = zeroKey.suggestEnglishCorrection(primaryEn)
                        if (wikiRepair != null) {
                            tvHits.addAll(zeroKey.searchTvMaze(wikiRepair, limit = 5))
                        }
                    }
                    tvHits
                }

                tmdbDeferred?.await()?.let { collected.addAll(it) }
                val imdbHits = imdbDeferred.await()
                val tvHits = tvMazeDeferred.await()

                // Attach Persian title/overview from Wikipedia bridge to matched candidates
                val mergedZeroKey = mergeAndEnrichPersianHits(
                    imdbHits = imdbHits,
                    tvMazeHits = tvHits,
                    faBridgeHits = faBridgeHits,
                )
                collected.addAll(mergedZeroKey)
            }

            val deduplicated = deduplicateAndRank(collected, parsed)
            synchronized(cacheLock) {
                if (deduplicated.isNotEmpty()) {
                    searchCache[cacheKey] = deduplicated
                }
            }
            Result.success(deduplicated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPopularDiscoverFeed(preferences: MediaPreferences): List<MediaSearchResult> = withContext(Dispatchers.IO) {
        synchronized(cacheLock) {
            if (cachedPopularFeed.isNotEmpty()) return@withContext cachedPopularFeed
        }
        val items = if (preferences.tmdbApiKey.isNotBlank()) {
            fetchTmdbTrending(preferences.tmdbApiKey, preferences.searchLanguage).ifEmpty {
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

    suspend fun getTrendingFeed(
        preferences: MediaPreferences,
        persistence: MediaPersistence?,
        forceRefresh: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): TrendingFeedBundle = withContext(Dispatchers.IO) {
        val expectsFallback = preferences.tmdbApiKey.isBlank()
        if (!forceRefresh) {
            val memCached = synchronized(cacheLock) { cachedTrendingFeed }
            if (memCached != null &&
                (memCached.movies.isNotEmpty() || memCached.tvShows.isNotEmpty()) &&
                nowMillis - memCached.lastUpdatedMillis < TRENDING_CACHE_TTL_MS &&
                (!expectsFallback || memCached.isUsingFallbackSources) &&
                (expectsFallback || !memCached.isUsingFallbackSources)
            ) {
                return@withContext memCached
            }

            val diskCached = persistence?.loadTrendingCache()
            if (diskCached != null &&
                (diskCached.movies.isNotEmpty() || diskCached.tvShows.isNotEmpty()) &&
                nowMillis - diskCached.lastUpdatedMillis < TRENDING_CACHE_TTL_MS &&
                (!expectsFallback || diskCached.isUsingFallbackSources) &&
                (expectsFallback || !diskCached.isUsingFallbackSources)
            ) {
                synchronized(cacheLock) { cachedTrendingFeed = diskCached }
                return@withContext diskCached
            }
        }

        val todayIso = formatIsoDateUtc(nowMillis)
        var tmdbMovies = emptyList<MediaSearchResult>()
        var tmdbTv = emptyList<MediaSearchResult>()

        if (!expectsFallback) {
            supervisorScope {
                val moviesDef = async {
                    fetchTmdbTrendingMoviesDigital(
                        apiKey = preferences.tmdbApiKey,
                        langPref = preferences.searchLanguage,
                        todayIso = todayIso,
                    )
                }
                val tvDef = async {
                    fetchTmdbTrendingTvDetailed(
                        apiKey = preferences.tmdbApiKey,
                        langPref = preferences.searchLanguage,
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
        )
        if (finalMovies.isNotEmpty() || finalTv.isNotEmpty()) {
            synchronized(cacheLock) { cachedTrendingFeed = bundle }
            persistence?.saveTrendingCache(bundle)
        }
        bundle
    }

    suspend fun getMediaDetails(
        summary: MediaSearchResult,
        preferences: MediaPreferences,
    ): Result<MediaDetails> = withContext(Dispatchers.IO) {
        val cacheKey = "${summary.id}|${preferences.tmdbApiKey.isNotBlank()}|${preferences.omdbApiKey.isNotBlank()}"
        synchronized(cacheLock) {
            detailsCache[cacheKey]?.let { return@withContext Result.success(it) }
        }

        try {
            var details: MediaDetails? = null

            supervisorScope {
                // Path 1: TMDB (when API key provided)
                val tmdbDeferred = if (preferences.tmdbApiKey.isNotBlank()) {
                    async { fetchTmdbFullDetails(summary, preferences.tmdbApiKey, preferences.searchLanguage) }
                } else null

                // Path 2: TVmaze (zero-key TV series seasons/episodes/cast)
                val tvMazeDeferred = if (summary.mediaType == MediaType.TV) {
                    async { zeroKey.fetchTvMazeDetails(summary.tvMazeId, summary.imdbId, summary.title) }
                } else null

                // Path 3: Wikidata + Wikipedia (zero-key director/cast/runtime/Persian metadata)
                val wikiDeferred = async {
                    zeroKey.fetchWikidataAndWikipediaEnrichment(
                        title = summary.title,
                        imdbId = summary.imdbId,
                        knownWikidataId = summary.wikidataId,
                    )
                }

                // Path 4: OMDb (when optional OMDb key provided)
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

    // --- Optional TMDB API v3 Support (never logs API key) ---

    private fun searchTmdb(
        query: ParsedSearchQuery,
        apiKey: String,
        langPref: SearchLanguagePreference,
    ): List<MediaSearchResult> {
        val encoded = URLEncoder.encode(query.cleanTitle, "UTF-8")
        val tmdbLang = if (query.isPersian || langPref == SearchLanguagePreference.FA) "fa-IR" else "en-US"
        val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$encoded&language=$tmdbLang&include_adult=false"
        val body = getString(url) ?: return emptyList()
        return parseTmdbMultiSearchJson(body)
    }

    internal fun parseTmdbMultiSearchJson(rawJson: String): List<MediaSearchResult> {
        return try {
            val arr = JSONObject(rawJson).optJSONArray("results") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val mediaTypeStr = obj.optString("media_type")
                    when (mediaTypeStr) {
                        "movie", "tv" -> {
                            val type = if (mediaTypeStr == "tv") MediaType.TV else MediaType.MOVIE
                            val tmdbId = obj.optInt("id", -1).takeIf { it > 0 } ?: continue
                            val title = if (type == MediaType.MOVIE) obj.optString("title") else obj.optString("name")
                            val origTitle = if (type == MediaType.MOVIE) obj.optString("original_title") else obj.optString("original_name")
                            val dateStr = if (type == MediaType.MOVIE) obj.optString("release_date") else obj.optString("first_air_date")
                            val year = dateStr.take(4).toIntOrNull()
                            val posterPath = obj.optString("poster_path").takeIf { it.startsWith("/") }
                            val rating = obj.optDouble("vote_average", 0.0).takeIf { !it.isNaN() && it > 0.0 }
                            val overview = obj.optString("overview").takeIf { it.isNotBlank() }
                            val isFaTitle = FuzzyTitleMatcher.containsPersian(title)
                            add(
                                MediaSearchResult(
                                    id = "tmdb:${mediaTypeStr}:$tmdbId",
                                    mediaType = type,
                                    title = if (isFaTitle && origTitle.isNotBlank()) origTitle else title,
                                    persianTitle = if (isFaTitle) title else null,
                                    originalTitle = origTitle.takeIf { it.isNotBlank() },
                                    releaseYear = year,
                                    posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                                    imdbRating = rating,
                                    overview = if (!isFaTitle) overview else null,
                                    persianOverview = if (isFaTitle) overview else null,
                                    tmdbId = tmdbId,
                                )
                            )
                        }
                        "person" -> {
                            // Support actor search when TMDB returns known_for works for an actor query
                            val knownFor = obj.optJSONArray("known_for") ?: continue
                            val personName = obj.optString("name")
                            for (j in 0 until knownFor.length()) {
                                val work = knownFor.optJSONObject(j) ?: continue
                                val workTypeStr = work.optString("media_type")
                                if (workTypeStr != "movie" && workTypeStr != "tv") continue
                                val type = if (workTypeStr == "tv") MediaType.TV else MediaType.MOVIE
                                val tmdbId = work.optInt("id", -1).takeIf { it > 0 } ?: continue
                                val title = if (type == MediaType.MOVIE) work.optString("title") else work.optString("name")
                                val dateStr = if (type == MediaType.MOVIE) work.optString("release_date") else work.optString("first_air_date")
                                val posterPath = work.optString("poster_path").takeIf { it.startsWith("/") }
                                add(
                                    MediaSearchResult(
                                        id = "tmdb:${workTypeStr}:$tmdbId",
                                        mediaType = type,
                                        title = title,
                                        releaseYear = dateStr.take(4).toIntOrNull(),
                                        posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                                        topCast = listOfNotNull(personName.takeIf { it.isNotBlank() }),
                                        overview = work.optString("overview").takeIf { it.isNotBlank() },
                                        tmdbId = tmdbId,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchTmdbTrending(apiKey: String, langPref: SearchLanguagePreference): List<MediaSearchResult> {
        val lang = if (langPref == SearchLanguagePreference.FA) "fa-IR" else "en-US"
        val body = getString("https://api.themoviedb.org/3/trending/all/week?api_key=$apiKey&language=$lang")
            ?: return emptyList()
        return parseTmdbMultiSearchJson(body).take(12)
    }

    private suspend fun fetchTmdbTrendingMoviesDigital(
        apiKey: String,
        langPref: SearchLanguagePreference,
        todayIso: String,
    ): List<MediaSearchResult> = supervisorScope {
        val candidateIds = LinkedHashSet<Int>()
        getString("https://api.themoviedb.org/3/trending/movie/week?api_key=$apiKey&language=en-US")?.let { body ->
            val arr = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optInt("id", -1)?.takeIf { it > 0 }?.let { candidateIds.add(it) }
                }
            }
        }
        if (candidateIds.size < 10) {
            getString(
                "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey&language=en-US&sort_by=popularity.desc&with_release_type=4|6&release_date.lte=$todayIso"
            )?.let { body ->
                val arr = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optInt("id", -1)?.takeIf { it > 0 }?.let { candidateIds.add(it) }
                    }
                }
            }
        }

        val inspected = candidateIds.take(14).map { id ->
            async {
                val enDef = async {
                    getString(
                        "https://api.themoviedb.org/3/movie/$id?api_key=$apiKey&language=en-US&append_to_response=release_dates,watch/providers,credits,external_ids"
                    )
                }
                val faDef = if (langPref != SearchLanguagePreference.EN) {
                    async { getString("https://api.themoviedb.org/3/movie/$id?api_key=$apiKey&language=fa-IR") }
                } else null
                val enBody = enDef.await() ?: return@async null
                val faBody = faDef?.await()
                parseTmdbMovieTrendingDetail(enBody, faBody, todayIso)
            }
        }.mapNotNull { it.await() }

        inspected.take(5)
    }

    private suspend fun fetchTmdbTrendingTvDetailed(
        apiKey: String,
        langPref: SearchLanguagePreference,
    ): List<MediaSearchResult> = supervisorScope {
        val candidateIds = LinkedHashSet<Int>()
        getString("https://api.themoviedb.org/3/trending/tv/week?api_key=$apiKey&language=en-US")?.let { body ->
            val arr = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optInt("id", -1)?.takeIf { it > 0 }?.let { candidateIds.add(it) }
                }
            }
        }
        val inspected = candidateIds.take(8).map { id ->
            async {
                val enDef = async {
                    getString(
                        "https://api.themoviedb.org/3/tv/$id?api_key=$apiKey&language=en-US&append_to_response=watch/providers,credits,external_ids"
                    )
                }
                val faDef = if (langPref != SearchLanguagePreference.EN) {
                    async { getString("https://api.themoviedb.org/3/tv/$id?api_key=$apiKey&language=fa-IR") }
                } else null
                val enBody = enDef.await() ?: return@async null
                val faBody = faDef?.await()
                parseTmdbTvTrendingDetail(enBody, faBody)
            }
        }.mapNotNull { it.await() }

        inspected.take(5)
    }

    internal fun parseTmdbMovieTrendingDetail(
        enJson: String,
        faJson: String?,
        todayIso: String = formatIsoDateUtc(System.currentTimeMillis()),
    ): MediaSearchResult? {
        return try {
            val obj = JSONObject(enJson)
            val tmdbId = obj.optInt("id", -1).takeIf { it > 0 } ?: return null
            val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
            val origTitle = obj.optString("original_title").takeIf { it.isNotBlank() }
            val faObj = faJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            val faTitle = faObj?.optString("title")?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
            val (digitalDate, hasConfirmedDigitalDate) = extractConfirmedDigitalRelease(obj, todayIso)
            val (providers, providersPage) = parseTmdbWatchProviders(obj.optJSONObject("watch/providers"))

            // Strict digital-availability gate: reject theatrical-only or unconfirmed titles
            if (!hasConfirmedDigitalDate && providers.isEmpty()) {
                return null
            }

            val releaseDate = obj.optString("release_date")
            val poster = obj.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" }
            val genresArr = obj.optJSONArray("genres") ?: JSONArray()
            val genres = (0 until genresArr.length()).mapNotNull { genresArr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            val runtime = obj.optInt("runtime", 0).takeIf { it > 0 }
            val rating = obj.optDouble("vote_average", 0.0).takeIf { !it.isNaN() && it > 0.0 }
            val imdbId = obj.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
            val directors = extractTmdbDirectors(obj.optJSONObject("credits"))

            MediaSearchResult(
                id = "tmdb:movie:$tmdbId",
                mediaType = MediaType.MOVIE,
                title = title,
                persianTitle = faTitle,
                originalTitle = origTitle,
                releaseYear = releaseDate.take(4).toIntOrNull(),
                posterUrl = poster,
                runtimeMinutes = runtime,
                genres = genres,
                directors = directors,
                imdbRating = rating,
                overview = obj.optString("overview").takeIf { it.isNotBlank() },
                persianOverview = faObj?.optString("overview")?.takeIf { it.isNotBlank() },
                imdbId = imdbId,
                tmdbId = tmdbId,
                digitalReleaseStatus = DigitalReleaseStatus.CONFIRMED_DIGITAL,
                digitalReleaseDate = digitalDate,
                watchProviders = providers,
                watchProvidersPageUrl = providersPage,
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseTmdbTvTrendingDetail(
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
            val (providers, providersPage) = parseTmdbWatchProviders(obj.optJSONObject("watch/providers"))
            val firstAir = obj.optString("first_air_date")
            val poster = obj.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" }
            val genresArr = obj.optJSONArray("genres") ?: JSONArray()
            val genres = (0 until genresArr.length()).mapNotNull { genresArr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            val epRuntime = obj.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
            val rating = obj.optDouble("vote_average", 0.0).takeIf { !it.isNaN() && it > 0.0 }
            val imdbId = obj.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
            val creators = (obj.optJSONArray("created_by") ?: JSONArray()).let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            }
            val tvStatus = mapTmdbTvStatus(obj)
            val seasonCount = obj.optInt("number_of_seasons", 0).takeIf { it > 0 }

            MediaSearchResult(
                id = "tmdb:tv:$tmdbId",
                mediaType = MediaType.TV,
                title = title,
                persianTitle = faTitle,
                originalTitle = origTitle,
                releaseYear = firstAir.take(4).toIntOrNull(),
                posterUrl = poster,
                runtimeMinutes = epRuntime,
                genres = genres,
                directors = creators,
                imdbRating = rating,
                overview = obj.optString("overview").takeIf { it.isNotBlank() },
                persianOverview = faObj?.optString("overview")?.takeIf { it.isNotBlank() },
                imdbId = imdbId,
                tmdbId = tmdbId,
                tvReleaseStatus = tvStatus,
                seasonCount = seasonCount,
                watchProviders = providers,
                watchProvidersPageUrl = providersPage,
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseTmdbWatchProviders(watchProvidersObj: JSONObject?): Pair<List<WatchProviderInfo>, String?> {
        val results = watchProvidersObj?.optJSONObject("results") ?: return emptyList<WatchProviderInfo>() to null
        val preferredRegions = listOf("US", "GB", "CA", "DE", "FR")
        var selectedRegionCode: String? = null
        var selectedRegionObj: JSONObject? = null
        for (region in preferredRegions) {
            val candidate = results.optJSONObject(region)
            if (candidate != null && hasAnyProviderEntries(candidate)) {
                selectedRegionCode = region
                selectedRegionObj = candidate
                break
            }
        }
        if (selectedRegionObj == null) {
            val keys = results.keys()
            while (keys.hasNext()) {
                val region = keys.next()
                val candidate = results.optJSONObject(region)
                if (candidate != null && hasAnyProviderEntries(candidate)) {
                    selectedRegionCode = region
                    selectedRegionObj = candidate
                    break
                }
            }
        }
        if (selectedRegionObj == null || selectedRegionCode == null) {
            return emptyList<WatchProviderInfo>() to null
        }

        val pageUrl = selectedRegionObj.optString("link").takeIf { FuzzyTitleMatcher.isSafeHttpUrl(it) }
        val collected = mutableListOf<WatchProviderInfo>()
        val seen = HashSet<String>()

        fun collectBucket(key: String, category: ProviderCategory) {
            val arr = selectedRegionObj.optJSONArray(key) ?: return
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val name = item.optString("provider_name").takeIf { it.isNotBlank() } ?: continue
                val dedup = "${name.lowercase(Locale.US)}:${category.name}"
                if (!seen.add(dedup)) continue
                val logo = item.optString("logo_path").takeIf { it.startsWith("/") }
                    ?.let { "https://image.tmdb.org/t/p/w92$it" }
                collected.add(
                    WatchProviderInfo(
                        name = name,
                        category = category,
                        logoUrl = logo,
                        url = pageUrl,
                        region = selectedRegionCode,
                    )
                )
            }
        }

        collectBucket("flatrate", ProviderCategory.STREAMING)
        collectBucket("free", ProviderCategory.FREE_AD_SUPPORTED)
        collectBucket("ads", ProviderCategory.FREE_AD_SUPPORTED)
        collectBucket("rent", ProviderCategory.RENTAL_PURCHASE)
        collectBucket("buy", ProviderCategory.RENTAL_PURCHASE)

        return collected.take(8) to pageUrl
    }

    private fun hasAnyProviderEntries(regionObj: JSONObject): Boolean {
        val keys = listOf("flatrate", "free", "ads", "rent", "buy")
        return keys.any { (regionObj.optJSONArray(it)?.length() ?: 0) > 0 }
    }

    private fun extractConfirmedDigitalRelease(obj: JSONObject, todayIso: String): Pair<String?, Boolean> {
        val countryResults = obj.optJSONObject("release_dates")?.optJSONArray("results") ?: return null to false
        var earliestDate: String? = null
        for (i in 0 until countryResults.length()) {
            val countryObj = countryResults.optJSONObject(i) ?: continue
            val entries = countryObj.optJSONArray("release_dates") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val type = entry.optInt("type", -1)
                // TMDB types: 4 = Digital, 5 = Physical, 6 = TV
                if (type !in 4..6) continue
                val dateIso = entry.optString("release_date").take(10)
                if (dateIso.length != 10 || dateIso > todayIso) continue
                if (earliestDate == null || dateIso < earliestDate) {
                    earliestDate = dateIso
                }
            }
        }
        return earliestDate to (earliestDate != null)
    }

    private fun mapTmdbTvStatus(obj: JSONObject): TvReleaseStatus {
        val status = obj.optString("status")
        return when {
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
    }

    private fun extractTmdbDirectors(credits: JSONObject?): List<String> {
        val crewArr = credits?.optJSONArray("crew") ?: return emptyList()
        return buildList {
            for (i in 0 until crewArr.length()) {
                val c = crewArr.optJSONObject(i) ?: continue
                if (c.optString("job").equals("Director", ignoreCase = true)) {
                    c.optString("name").takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.distinct()
    }

    private suspend fun fetchTmdbFullDetails(
        summary: MediaSearchResult,
        apiKey: String,
        langPref: SearchLanguagePreference,
    ): MediaDetails? = supervisorScope {
        val pathType = if (summary.mediaType == MediaType.TV) "tv" else "movie"
        var tmdbId = summary.tmdbId
        if (tmdbId == null && !summary.imdbId.isNullOrBlank()) {
            val findBody = getString(
                "https://api.themoviedb.org/3/find/${summary.imdbId}?api_key=$apiKey&external_source=imdb_id"
            )
            if (findBody != null) {
                val obj = runCatching { JSONObject(findBody) }.getOrNull()
                val arrKey = if (summary.mediaType == MediaType.TV) "tv_results" else "movie_results"
                tmdbId = obj?.optJSONArray(arrKey)?.optJSONObject(0)?.optInt("id", -1)?.takeIf { it > 0 }
            }
        }
        val resolvedId = tmdbId ?: return@supervisorScope null

        val appendFields = if (summary.mediaType == MediaType.MOVIE) {
            "credits,external_ids,release_dates,watch/providers"
        } else {
            "credits,external_ids,watch/providers"
        }
        val enDeferred = async {
            getString("https://api.themoviedb.org/3/$pathType/$resolvedId?api_key=$apiKey&language=en-US&append_to_response=$appendFields")
        }
        val faDeferred = if (langPref != SearchLanguagePreference.EN) {
            async { getString("https://api.themoviedb.org/3/$pathType/$resolvedId?api_key=$apiKey&language=fa-IR") }
        } else null

        val enBody = enDeferred.await() ?: return@supervisorScope null
        val faBody = faDeferred?.await()
        val parsed = parseTmdbDetailJson(enBody, faBody, summary.mediaType, resolvedId) ?: return@supervisorScope null

        if (summary.mediaType == MediaType.TV && parsed.seasons.isNotEmpty()) {
            val seasonsToLoad = parsed.seasons.filter { it.seasonNumber > 0 }.take(8)
            val loadedSeasons = seasonsToLoad.map { season ->
                async {
                    val sBody = getString(
                        "https://api.themoviedb.org/3/tv/$resolvedId/season/${season.seasonNumber}?api_key=$apiKey&language=en-US"
                    )
                    val episodes = if (sBody != null) parseTmdbSeasonEpisodes(sBody, season.seasonNumber) else emptyList()
                    season.copy(episodes = episodes)
                }
            }.map { it.await() }
            val remaining = parsed.seasons.filter { s -> loadedSeasons.none { it.seasonNumber == s.seasonNumber } }
            parsed.copy(seasons = (loadedSeasons + remaining).sortedBy { it.seasonNumber })
        } else {
            parsed
        }
    }

    internal fun parseTmdbDetailJson(
        enJson: String,
        faJson: String?,
        mediaType: MediaType,
        tmdbId: Int,
    ): MediaDetails? {
        return try {
            val obj = JSONObject(enJson)
            val faObj = faJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            val title = if (mediaType == MediaType.TV) obj.optString("name") else obj.optString("title")
            val origTitle = if (mediaType == MediaType.TV) obj.optString("original_name") else obj.optString("original_title")
            val faTitle = faObj?.let { if (mediaType == MediaType.TV) it.optString("name") else it.optString("title") }
                ?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
            val releaseDate = if (mediaType == MediaType.TV) obj.optString("first_air_date") else obj.optString("release_date")
            val lastAir = if (mediaType == MediaType.TV) obj.optString("last_air_date") else ""
            val poster = obj.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdrop = obj.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w780$it" }
            val genresArr = obj.optJSONArray("genres") ?: JSONArray()
            val genres = (0 until genresArr.length()).mapNotNull { genresArr.optJSONObject(it)?.optString("name") }
            val runtime = if (mediaType == MediaType.MOVIE) {
                obj.optInt("runtime", 0).takeIf { it > 0 }
            } else null
            val epRuntime = if (mediaType == MediaType.TV) {
                obj.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
            } else null

            val credits = obj.optJSONObject("credits")
            val castArr = credits?.optJSONArray("cast") ?: JSONArray()
            val cast = buildList {
                for (i in 0 until castArr.length()) {
                    if (size >= 15) break
                    val c = castArr.optJSONObject(i) ?: continue
                    val name = c.optString("name").takeIf { it.isNotBlank() } ?: continue
                    add(CastMember(name, c.optString("character").takeIf { it.isNotBlank() }))
                }
            }
            val crewArr = credits?.optJSONArray("crew") ?: JSONArray()
            val directors = buildList {
                for (i in 0 until crewArr.length()) {
                    val c = crewArr.optJSONObject(i) ?: continue
                    if (c.optString("job").equals("Director", ignoreCase = true)) {
                        c.optString("name").takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }.distinct()

            val creatorsArr = obj.optJSONArray("created_by") ?: JSONArray()
            val creators = (0 until creatorsArr.length()).mapNotNull {
                creatorsArr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() }
            }

            val seasonsArr = obj.optJSONArray("seasons") ?: JSONArray()
            val seasons = buildList {
                for (i in 0 until seasonsArr.length()) {
                    val s = seasonsArr.optJSONObject(i) ?: continue
                    val num = s.optInt("season_number", -1)
                    if (num <= 0) continue
                    add(
                        SeasonInfo(
                            seasonNumber = num,
                            title = s.optString("name", "Season $num"),
                            episodeCount = s.optInt("episode_count", 0),
                            airDate = s.optString("air_date").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }

            val imdbId = obj.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
            val countries = obj.optJSONArray("production_countries")?.optJSONObject(0)?.optString("name")
                ?.takeIf { it.isNotBlank() }
            val origLang = obj.optString("original_language").takeIf { it.isNotBlank() }
            val rating = obj.optDouble("vote_average", 0.0).takeIf { !it.isNaN() && it > 0.0 }
            val (providers, providersPage) = parseTmdbWatchProviders(obj.optJSONObject("watch/providers"))
            val todayIso = formatIsoDateUtc(System.currentTimeMillis())
            val (digitalDate, hasDigitalDate) = if (mediaType == MediaType.MOVIE) {
                extractConfirmedDigitalRelease(obj, todayIso)
            } else {
                null to false
            }
            val digitalStatus = when {
                mediaType != MediaType.MOVIE -> DigitalReleaseStatus.UNKNOWN
                hasDigitalDate || providers.isNotEmpty() -> DigitalReleaseStatus.CONFIRMED_DIGITAL
                releaseDate.isNotBlank() -> DigitalReleaseStatus.THEATRICAL_ONLY_OR_UNCONFIRMED
                else -> DigitalReleaseStatus.UNKNOWN
            }
            val tvReleaseStatus = if (mediaType == MediaType.TV) mapTmdbTvStatus(obj) else TvReleaseStatus.UNKNOWN

            MediaDetails(
                id = "tmdb:${mediaType.name.lowercase()}:$tmdbId",
                mediaType = mediaType,
                title = title,
                persianTitle = faTitle,
                originalTitle = origTitle.takeIf { it.isNotBlank() },
                releaseYear = releaseDate.take(4).toIntOrNull(),
                releaseDate = releaseDate.takeIf { it.isNotBlank() },
                posterUrl = poster,
                backdropUrl = backdrop,
                runtimeMinutes = runtime,
                episodeRuntimeMinutes = epRuntime,
                genres = genres,
                directors = directors,
                creators = creators,
                cast = cast,
                imdbRating = rating,
                overview = obj.optString("overview").takeIf { it.isNotBlank() },
                persianOverview = faObj?.optString("overview")?.takeIf { it.isNotBlank() },
                country = countries,
                originalLanguage = origLang,
                status = obj.optString("status").takeIf { it.isNotBlank() },
                startYear = releaseDate.take(4).toIntOrNull(),
                endYear = lastAir.take(4).toIntOrNull(),
                totalSeasons = obj.optInt("number_of_seasons", 0).takeIf { it > 0 },
                totalEpisodes = obj.optInt("number_of_episodes", 0).takeIf { it > 0 },
                seasons = seasons,
                imdbId = imdbId,
                tmdbId = tmdbId,
                digitalReleaseStatus = digitalStatus,
                digitalReleaseDate = digitalDate,
                tvReleaseStatus = tvReleaseStatus,
                watchProviders = providers,
                watchProvidersPageUrl = providersPage,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTmdbSeasonEpisodes(rawJson: String, seasonNumber: Int): List<EpisodeInfo> {
        return try {
            val eps = JSONObject(rawJson).optJSONArray("episodes") ?: return emptyList()
            buildList {
                for (i in 0 until eps.length()) {
                    val e = eps.optJSONObject(i) ?: continue
                    add(
                        EpisodeInfo(
                            episodeNumber = e.optInt("episode_number", i + 1),
                            seasonNumber = seasonNumber,
                            title = e.optString("name", "Episode ${i + 1}"),
                            airDate = e.optString("air_date").takeIf { it.isNotBlank() },
                            runtimeMinutes = e.optInt("runtime", 0).takeIf { it > 0 },
                            overview = e.optString("overview").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // --- Optional OMDb Support (never logs API key) ---

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
