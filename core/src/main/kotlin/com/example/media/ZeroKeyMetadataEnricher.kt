package com.example.media

import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure zero-key metadata lookups using public IMDb suggestion JSON, TVmaze API,
 * Wikipedia (EN/FA), and Wikidata entity claims.
 */
class ZeroKeyMetadataEnricher(private val client: OkHttpClient) {

    fun searchImdbSuggestion(query: String, limit: Int = 10): List<MediaSearchResult> {
        val cleaned = query.trim().lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), "_")
            .trim('_')
        if (cleaned.isEmpty()) return emptyList()
        val firstChar = cleaned.first().takeIf { it.isLetterOrDigit() } ?: 'x'
        val encoded = URLEncoder.encode(cleaned, "UTF-8")
        val url = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$encoded.json"
        val body = getString(url) ?: return emptyList()
        return parseImdbSuggestionJson(body, limit)
    }

    fun parseImdbSuggestionJson(rawJson: String, limit: Int = 10): List<MediaSearchResult> {
        return try {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("d") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    if (size >= limit) break
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (!id.startsWith("tt")) continue
                    val qid = obj.optString("qid", "")
                    val type = when {
                        qid.contains("tv", ignoreCase = true) ||
                            obj.optString("q").contains("TV", ignoreCase = true) -> MediaType.TV
                        qid == "movie" || qid == "short" || qid == "tvMovie" ||
                            obj.optString("q").equals("feature", ignoreCase = true) -> MediaType.MOVIE
                        else -> continue
                    }
                    val title = obj.optString("l").takeIf { it.isNotBlank() } ?: continue
                    val year = obj.optInt("y", 0).takeIf { it in 1888..2035 }
                    val stars = obj.optString("s")
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .take(3)
                    val imgUrl = obj.optJSONObject("i")?.optString("imageUrl")
                        ?.takeIf { it.startsWith("http") }
                        ?.let { shrinkImdbPoster(it) }
                    add(
                        MediaSearchResult(
                            id = "imdb:$id",
                            mediaType = type,
                            title = title,
                            originalTitle = title,
                            releaseYear = year,
                            posterUrl = imgUrl,
                            topCast = stars,
                            imdbId = id,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun searchTvMaze(query: String, limit: Int = 8): List<MediaSearchResult> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val body = getString("https://api.tvmaze.com/search/shows?q=$encoded") ?: return emptyList()
        return parseTvMazeSearchJson(body, limit)
    }

    internal fun parseTvMazeSearchJson(rawJson: String, limit: Int = 8): List<MediaSearchResult> {
        return try {
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    if (size >= limit) break
                    val wrapper = arr.optJSONObject(i) ?: continue
                    val show = wrapper.optJSONObject("show") ?: continue
                    val tvMazeId = show.optInt("id", -1).takeIf { it > 0 } ?: continue
                    val name = show.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val premiered = show.optString("premiered")
                    val year = premiered.take(4).toIntOrNull()
                    val rating = show.optJSONObject("rating")?.optDouble("average")
                        ?.takeIf { !it.isNaN() && it > 0.0 }
                    val genres = show.optJSONArray("genres")?.toStringList().orEmpty()
                    val runtime = show.optInt("averageRuntime", 0)
                        .takeIf { it > 0 }
                        ?: show.optInt("runtime", 0).takeIf { it > 0 }
                    val poster = show.optJSONObject("image")?.let { img ->
                        img.optString("medium").ifBlank { img.optString("original") }
                    }?.takeIf { it.startsWith("http") }
                    val imdbId = show.optJSONObject("externals")?.optString("imdb")
                        ?.takeIf { it.startsWith("tt") }
                    val summary = stripHtmlTags(show.optString("summary"))
                    add(
                        MediaSearchResult(
                            id = imdbId?.let { "imdb:$it" } ?: "tvmaze:$tvMazeId",
                            mediaType = MediaType.TV,
                            title = name,
                            originalTitle = name,
                            releaseYear = year,
                            posterUrl = poster,
                            runtimeMinutes = runtime,
                            genres = genres,
                            imdbRating = rating,
                            overview = summary.takeIf { it.isNotBlank() },
                            imdbId = imdbId,
                            tvMazeId = tvMazeId,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun fetchPopularTvMazeShows(limit: Int = 12): List<MediaSearchResult> {
        val body = getString("https://api.tvmaze.com/shows?page=0") ?: return emptyList()
        return parsePopularTvMazeShows(body, limit)
    }

    internal fun parsePopularTvMazeShows(rawJson: String, limit: Int = 12): List<MediaSearchResult> {
        return try {
            val arr = JSONArray(rawJson)
            val candidates = ArrayList<Pair<Double, MediaSearchResult>>(60)
            for (i in 0 until arr.length()) {
                val show = arr.optJSONObject(i) ?: continue
                val rating = show.optJSONObject("rating")?.optDouble("average")?.takeIf { !it.isNaN() } ?: 0.0
                val weight = show.optDouble("weight", 0.0).takeIf { !it.isNaN() } ?: 0.0
                if (rating < 8.2 && weight < 92.0) continue
                val parsed = parseTvMazeShowSummary(show) ?: continue
                candidates.add((weight + rating * 5.0) to parsed)
            }
            candidates.sortedByDescending { it.first }.map { it.second }.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchTrendingTvMazeFallback(limit: Int = 5): List<MediaSearchResult> = coroutineScope {
        val body = getString("https://api.tvmaze.com/shows?page=0") ?: return@coroutineScope emptyList()
        val topShows = parseTrendingTvMazeJson(body, limit)
        topShows.map { show ->
            async {
                val id = show.tvMazeId ?: return@async show
                val seasonsBody = getString("https://api.tvmaze.com/shows/$id/seasons")
                val seasonCount = seasonsBody?.let {
                    runCatching { JSONArray(it).length().takeIf { count -> count > 0 } }.getOrNull()
                }
                if (seasonCount != null) show.copy(seasonCount = seasonCount) else show
            }
        }.map { it.await() }
    }

    internal fun parseTrendingTvMazeJson(rawJson: String, limit: Int = 5): List<MediaSearchResult> {
        return try {
            val arr = JSONArray(rawJson)
            val candidates = ArrayList<Pair<Double, MediaSearchResult>>(60)
            for (i in 0 until arr.length()) {
                val show = arr.optJSONObject(i) ?: continue
                val rating = show.optJSONObject("rating")?.optDouble("average")?.takeIf { !it.isNaN() } ?: 0.0
                val weight = show.optDouble("weight", 0.0).takeIf { !it.isNaN() } ?: 0.0
                val status = show.optString("status")
                val statusBoost = if (status.equals("Running", ignoreCase = true)) 15.0 else 0.0
                if (rating < 8.0 && weight < 90.0) continue
                val parsed = parseTvMazeShowSummary(show) ?: continue
                candidates.add((weight + rating * 5.0 + statusBoost) to parsed)
            }
            candidates.sortedByDescending { it.first }.map { it.second }.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseTvMazeShowSummary(show: JSONObject): MediaSearchResult? {
        val tvMazeId = show.optInt("id", -1).takeIf { it > 0 } ?: return null
        val name = show.optString("name").takeIf { it.isNotBlank() } ?: return null
        val year = show.optString("premiered").take(4).toIntOrNull()
        val genres = show.optJSONArray("genres")?.toStringList().orEmpty()
        val runtime = show.optInt("averageRuntime", 0).takeIf { it > 0 }
            ?: show.optInt("runtime", 0).takeIf { it > 0 }
        val rating = show.optJSONObject("rating")?.optDouble("average")?.takeIf { !it.isNaN() && it > 0.0 }
        val poster = show.optJSONObject("image")?.let { img ->
            img.optString("medium").ifBlank { img.optString("original") }
        }?.takeIf { it.startsWith("http") }
        val imdbId = show.optJSONObject("externals")?.optString("imdb")?.takeIf { it.startsWith("tt") }
        val summary = stripHtmlTags(show.optString("summary"))
        val tvStatus = mapTvMazeStatus(show.optString("status"))
        val officialSite = show.optString("officialSite").takeIf { FuzzyTitleMatcher.isSafeHttpUrl(it) }
        val networkName = show.optJSONObject("network")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: show.optJSONObject("webChannel")?.optString("name")?.takeIf { it.isNotBlank() }
        val tvMazePage = show.optString("url").takeIf { FuzzyTitleMatcher.isSafeHttpUrl(it) }
        val providers = buildList {
            if (officialSite != null) {
                add(
                    WatchProviderInfo(
                        name = networkName ?: "Official Site",
                        category = ProviderCategory.OFFICIAL_PAGE,
                        url = officialSite,
                    )
                )
            } else if (tvMazePage != null) {
                add(
                    WatchProviderInfo(
                        name = networkName?.let { "$it (TVmaze)" } ?: "TVmaze",
                        category = ProviderCategory.OFFICIAL_PAGE,
                        url = tvMazePage,
                    )
                )
            }
        }
        return MediaSearchResult(
            id = imdbId?.let { "imdb:$it" } ?: "tvmaze:$tvMazeId",
            mediaType = MediaType.TV,
            title = name,
            originalTitle = name,
            releaseYear = year,
            posterUrl = poster,
            runtimeMinutes = runtime,
            genres = genres,
            imdbRating = rating,
            overview = summary.takeIf { it.isNotBlank() },
            imdbId = imdbId,
            tvMazeId = tvMazeId,
            tvReleaseStatus = tvStatus,
            watchProviders = providers,
            watchProvidersPageUrl = officialSite ?: tvMazePage,
        )
    }

    fun fetchAppleTopMoviesFallback(limit: Int = 5): List<MediaSearchResult> {
        val body = getString("https://itunes.apple.com/us/rss/topmovies/limit=10/json") ?: return emptyList()
        return parseAppleTopMoviesRss(body, limit)
    }

    internal fun parseAppleTopMoviesRss(rawJson: String, limit: Int = 5): List<MediaSearchResult> {
        return try {
            val feed = JSONObject(rawJson).optJSONObject("feed") ?: return emptyList()
            val entries = feed.optJSONArray("entry") ?: return emptyList()
            buildList {
                for (i in 0 until entries.length()) {
                    if (size >= limit) break
                    val entry = entries.optJSONObject(i) ?: continue
                    val title = entry.optJSONObject("im:name")?.optString("label")
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val idObj = entry.optJSONObject("id")
                    val storeUrl = idObj?.optString("label")?.takeIf { FuzzyTitleMatcher.isSafeHttpUrl(it) }
                    val appleId = idObj?.optJSONObject("attributes")?.optString("im:id")
                        ?.takeIf { it.isNotBlank() } ?: "rank_$i"
                    val images = entry.optJSONArray("im:image")
                    val posterUrl = if (images != null && images.length() > 0) {
                        images.optJSONObject(images.length() - 1)?.optString("label")
                            ?.takeIf { FuzzyTitleMatcher.isSafeHttpUrl(it) }
                            ?.replace("http://", "https://")
                    } else {
                        null
                    }
                    val rawDate = entry.optJSONObject("im:releaseDate")?.optString("label")
                        ?.takeIf { it.isNotBlank() }
                    val year = rawDate?.take(4)?.toIntOrNull()
                    val genre = entry.optJSONObject("category")
                        ?.optJSONObject("attributes")
                        ?.optString("label")
                        ?.takeIf { it.isNotBlank() }
                    val summary = entry.optJSONObject("summary")?.optString("label")
                        ?.takeIf { it.isNotBlank() }

                    add(
                        MediaSearchResult(
                            id = "apple:$appleId",
                            mediaType = MediaType.MOVIE,
                            title = title,
                            originalTitle = title,
                            releaseYear = year,
                            posterUrl = posterUrl,
                            genres = listOfNotNull(genre),
                            overview = summary,
                            digitalReleaseStatus = DigitalReleaseStatus.CONFIRMED_DIGITAL,
                            digitalReleaseDate = rawDate?.take(10),
                            watchProviders = listOf(
                                WatchProviderInfo(
                                    name = "Apple TV",
                                    category = ProviderCategory.RENTAL_PURCHASE,
                                    url = storeUrl,
                                    region = "US",
                                )
                            ),
                            watchProvidersPageUrl = storeUrl,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun mapTvMazeStatus(status: String?): TvReleaseStatus {
        return when (status?.trim()?.lowercase()) {
            "running" -> TvReleaseStatus.CURRENTLY_AIRING
            "ended" -> TvReleaseStatus.ENDED
            "in development", "to be determined" -> TvReleaseStatus.UPCOMING
            else -> TvReleaseStatus.UNKNOWN
        }
    }

    data class WikipediaBridgeHit(
        val persianTitle: String?,
        val englishTitle: String?,
        val persianExtract: String?,
        val wikidataId: String?,
    )

    /**
     * Resolves Persian movie/TV queries (e.g. "میان ستاره ای", "برکینگ بد") into exact English titles
     * and Persian synopses via fa.wikipedia.org and Wikidata wbsearchentities without any API key.
     */
    fun resolvePersianQueryViaWikipedia(query: String): List<WikipediaBridgeHit> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val normQuery = FuzzyTitleMatcher.normalizeText(trimmed)
        val directHits = queryPersianWikipediaBridge(trimmed)
        val hasCloseEnglishBridge = directHits.any { hit ->
            !hit.englishTitle.isNullOrBlank() &&
                FuzzyTitleMatcher.stringSimilarity(
                    FuzzyTitleMatcher.normalizeText(cleanWikipediaTitle(hit.persianTitle.orEmpty())),
                    normQuery,
                ) >= 0.72
        }
        if (hasCloseEnglishBridge) {
            return directHits
        }

        val combined = LinkedHashMap<String, WikipediaBridgeHit>()
        fun addAllUnique(hits: List<WikipediaBridgeHit>) {
            for (hit in hits) {
                val key = (hit.wikidataId ?: hit.englishTitle ?: hit.persianTitle ?: continue).lowercase()
                if (!combined.containsKey(key)) {
                    combined[key] = hit
                }
            }
        }

        val wikidataHits = queryWikidataPersianBridge(trimmed)
        val fuzzyQuery = trimmed.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { token -> if (token.length >= 4) "$token~1" else token }
        val fuzzyHits = if (fuzzyQuery.isNotBlank() && fuzzyQuery != trimmed) {
            queryPersianWikipediaBridge(fuzzyQuery)
        } else {
            emptyList()
        }

        // Put high-confidence media entities first, then Wikipedia fuzzy/direct hits
        addAllUnique(wikidataHits)
        addAllUnique(fuzzyHits)
        addAllUnique(directHits)
        return combined.values.toList()
    }

    private fun queryWikidataPersianBridge(query: String): List<WikipediaBridgeHit> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val body = getString(
            "https://www.wikidata.org/w/api.php?action=wbsearchentities&search=$encoded&language=fa&uselang=en&type=item&limit=6&format=json"
        ) ?: return emptyList()
        return try {
            val arr = JSONObject(body).optJSONArray("search") ?: return emptyList()
            val scored = (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val qid = obj.optString("id").takeIf { it.startsWith("Q") }
                val enLabel = obj.optString("label").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val desc = obj.optString("description", "")
                val faMatched = obj.optJSONObject("match")?.optString("text")?.takeIf { it.isNotBlank() }
                val isMediaLikely = desc.contains("film", ignoreCase = true) ||
                    desc.contains("movie", ignoreCase = true) ||
                    desc.contains("series", ignoreCase = true) ||
                    desc.contains("television", ignoreCase = true) ||
                    desc.contains("show", ignoreCase = true) ||
                    desc.contains("drama", ignoreCase = true) ||
                    desc.contains("comedy", ignoreCase = true) ||
                    desc.contains("thriller", ignoreCase = true) ||
                    desc.contains("anime", ignoreCase = true) ||
                    desc.contains("documentary", ignoreCase = true)
                WikipediaBridgeHit(
                    persianTitle = faMatched ?: query,
                    englishTitle = cleanWikipediaTitle(enLabel),
                    persianExtract = null,
                    wikidataId = qid,
                ) to isMediaLikely
            }
            scored.sortedByDescending { it.second }.map { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun queryPersianWikipediaBridge(searchExpression: String): List<WikipediaBridgeHit> {
        val encoded = URLEncoder.encode(searchExpression, "UTF-8")
        val searchBody = getString(
            "https://fa.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srinfo=suggestion&format=json&utf8=1&srlimit=6"
        ) ?: return emptyList()

        val pageTitles = try {
            val queryObj = JSONObject(searchBody).optJSONObject("query") ?: return emptyList()
            val suggestion = queryObj.optJSONObject("searchinfo")?.optString("suggestion")
                ?.takeIf { it.isNotBlank() }
            val arr = queryObj.optJSONArray("search")
            val rawTitles = if (arr != null) {
                (0 until arr.length()).mapNotNull { idx ->
                    val item = arr.optJSONObject(idx) ?: return@mapNotNull null
                    val title = item.optString("title")
                    val snippet = item.optString("snippet")
                    val isMediaLikely = title.contains("فیلم") || title.contains("مجموعه") || title.contains("سریال") ||
                        snippet.contains("فیلم") || snippet.contains("سریال") || snippet.contains("مجموعه تلویزیونی") ||
                        snippet.contains("کارگردان")
                    (title to isMediaLikely)
                }
            } else {
                emptyList()
            }
            val prioritized = rawTitles.sortedByDescending { it.second }.map { it.first }.toMutableList()
            if (suggestion != null && prioritized.none { it.equals(suggestion, ignoreCase = true) }) {
                prioritized.add(0, suggestion)
            }
            prioritized.distinct().take(4)
        } catch (_: Exception) {
            emptyList()
        }
        if (pageTitles.isEmpty()) return emptyList()

        val joined = URLEncoder.encode(pageTitles.joinToString("|"), "UTF-8")
        val detailBody = getString(
            "https://fa.wikipedia.org/w/api.php?action=query&titles=$joined&prop=langlinks|extracts|pageprops&ppprop=wikibase_item|disambiguation&lllang=en&exintro=1&explaintext=1&exlimit=max&redirects=1&format=json"
        ) ?: return emptyList()

        return try {
            val pages = JSONObject(detailBody).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
            buildList {
                val keys = pages.keys()
                while (keys.hasNext()) {
                    val page = pages.optJSONObject(keys.next()) ?: continue
                    val pageProps = page.optJSONObject("pageprops")
                    if (pageProps?.has("disambiguation") == true) continue
                    val faTitle = page.optString("title").takeIf { it.isNotBlank() }
                    val enTitleRaw = page.optJSONArray("langlinks")?.optJSONObject(0)?.optString("*")
                    val enClean = enTitleRaw?.let { cleanWikipediaTitle(it) }
                    val rawExtract = page.optString("extract").takeIf { it.isNotBlank() }
                    val verifiedExtract = rawExtract?.let { cleanWikipediaExtract(it, maxChars = 650) }
                        ?.takeIf { isValidPersianMediaExtract(it, requireMediaKeywords = true) }
                    val wikidataId = pageProps?.optString("wikibase_item")
                        ?.takeIf { it.startsWith("Q") }
                    if (enClean != null || faTitle != null) {
                        add(WikipediaBridgeHit(faTitle, enClean, verifiedExtract, wikidataId))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Suggests spelling correction via English Wikipedia's ElasticSearch engine for typos like "Interstelar".
     */
    fun suggestEnglishCorrection(query: String): String? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val body = getString(
            "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srinfo=suggestion&format=json&srlimit=3"
        ) ?: return null
        return try {
            val queryObj = JSONObject(body).optJSONObject("query") ?: return null
            val suggestion = queryObj.optJSONObject("searchinfo")?.optString("suggestion")
                ?.takeIf { it.isNotBlank() }
            if (suggestion != null) return suggestion
            val firstTitle = queryObj.optJSONArray("search")?.optJSONObject(0)?.optString("title")
            firstTitle?.let { cleanWikipediaTitle(it) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchTvMazeDetails(
        tvMazeId: Int?,
        imdbId: String?,
        fallbackTitle: String?,
    ): TvMazeRichDetails? {
        val resolvedId = tvMazeId ?: run {
            if (!imdbId.isNullOrBlank()) {
                val lookup = getString("https://api.tvmaze.com/lookup/shows?imdb=${URLEncoder.encode(imdbId, "UTF-8")}")
                lookup?.let { runCatching { JSONObject(it).optInt("id", -1) }.getOrNull()?.takeIf { id -> id > 0 } }
            } else null
        } ?: run {
            if (!fallbackTitle.isNullOrBlank()) {
                val search = getString("https://api.tvmaze.com/singlesearch/shows?q=${URLEncoder.encode(fallbackTitle, "UTF-8")}")
                search?.let { runCatching { JSONObject(it).optInt("id", -1) }.getOrNull()?.takeIf { id -> id > 0 } }
            } else null
        } ?: return null

        val body = getString("https://api.tvmaze.com/shows/$resolvedId?embed[]=cast&embed[]=episodes") ?: return null
        return parseTvMazeFullShowJson(body)
    }

    fun parseTvMazeFullShowJson(rawJson: String): TvMazeRichDetails? {
        return try {
            val show = JSONObject(rawJson)
            val id = show.optInt("id", -1).takeIf { it > 0 } ?: return null
            val name = show.optString("name")
            val premiered = show.optString("premiered").takeIf { it.isNotBlank() }
            val ended = show.optString("ended").takeIf { it.isNotBlank() }
            val status = show.optString("status").takeIf { it.isNotBlank() }
            val runtime = show.optInt("averageRuntime", 0).takeIf { it > 0 }
                ?: show.optInt("runtime", 0).takeIf { it > 0 }
            val language = show.optString("language").takeIf { it.isNotBlank() }
            val country = show.optJSONObject("network")?.optJSONObject("country")?.optString("name")
                ?: show.optJSONObject("webChannel")?.optJSONObject("country")?.optString("name")
            val networkName = show.optJSONObject("network")?.optString("name")
                ?: show.optJSONObject("webChannel")?.optString("name")
            val rating = show.optJSONObject("rating")?.optDouble("average")?.takeIf { !it.isNaN() && it > 0.0 }
            val genres = show.optJSONArray("genres")?.toStringList().orEmpty()
            val poster = show.optJSONObject("image")?.let { img ->
                img.optString("original").ifBlank { img.optString("medium") }
            }?.takeIf { it.startsWith("http") }
            val imdbId = show.optJSONObject("externals")?.optString("imdb")?.takeIf { it.startsWith("tt") }
            val summary = stripHtmlTags(show.optString("summary")).takeIf { it.isNotBlank() }

            val embedded = show.optJSONObject("_embedded")
            val castArr = embedded?.optJSONArray("cast") ?: JSONArray()
            val cast = buildList {
                for (i in 0 until castArr.length()) {
                    if (size >= 15) break
                    val entry = castArr.optJSONObject(i) ?: continue
                    val personName = entry.optJSONObject("person")?.optString("name")
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val character = entry.optJSONObject("character")?.optString("name")
                        ?.takeIf { it.isNotBlank() }
                    add(CastMember(personName, character))
                }
            }

            val epsArr = embedded?.optJSONArray("episodes") ?: JSONArray()
            val episodesBySeason = linkedMapOf<Int, MutableList<EpisodeInfo>>()
            for (i in 0 until epsArr.length()) {
                val ep = epsArr.optJSONObject(i) ?: continue
                val seasonNum = ep.optInt("season", 0)
                if (seasonNum <= 0) continue
                val epNum = ep.optInt("number", 0)
                val epTitle = ep.optString("name", "Episode $epNum")
                val epAir = ep.optString("airdate").takeIf { it.isNotBlank() }
                val epRun = ep.optInt("runtime", 0).takeIf { it > 0 }
                val epOverview = stripHtmlTags(ep.optString("summary")).takeIf { it.isNotBlank() }
                episodesBySeason.getOrPut(seasonNum) { mutableListOf() }.add(
                    EpisodeInfo(
                        episodeNumber = epNum,
                        seasonNumber = seasonNum,
                        title = epTitle,
                        airDate = epAir,
                        runtimeMinutes = epRun,
                        overview = epOverview,
                    )
                )
            }

            val seasons = episodesBySeason.entries.sortedBy { it.key }.map { (sNum, eps) ->
                SeasonInfo(
                    seasonNumber = sNum,
                    title = "Season $sNum",
                    episodeCount = eps.size,
                    airDate = eps.firstOrNull()?.airDate,
                    episodes = eps,
                )
            }

            TvMazeRichDetails(
                tvMazeId = id,
                title = name,
                premiered = premiered,
                startYear = premiered?.take(4)?.toIntOrNull(),
                endYear = ended?.take(4)?.toIntOrNull(),
                status = status,
                episodeRuntimeMinutes = runtime,
                genres = genres,
                creators = listOfNotNull(networkName),
                cast = cast,
                rating = rating,
                overview = summary,
                country = country,
                originalLanguage = language,
                posterUrl = poster,
                imdbId = imdbId,
                seasons = seasons,
                totalEpisodes = epsArr.length().takeIf { it > 0 },
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches zero-key movie/TV metadata from Wikidata + Wikipedia (Director, Cast, Runtime, Date, Country, Genres, Persian title/overview).
     * Strictly anchors Persian Wikipedia lookup to a validated Wikidata screen-work entity (via IMDb ID or verified film/TV EN match + sitelink)
     * so unrelated Persian Wikipedia articles are never surfaced.
     */
    suspend fun fetchWikidataAndWikipediaEnrichment(
        title: String,
        imdbId: String?,
        knownWikidataId: String? = null,
        mediaType: MediaType = MediaType.MOVIE,
        releaseYear: Int? = null,
        originalTitle: String? = null,
    ): WikidataEnrichment? = coroutineScope {
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt") }
        val verifiedEnCandidate = if (knownWikidataId == null && cleanImdb == null) {
            findVerifiedEnglishWikipediaMatch(
                title = title,
                originalTitle = originalTitle,
                mediaType = mediaType,
                releaseYear = releaseYear,
            )
        } else {
            null
        }

        val qid = knownWikidataId?.takeIf { it.startsWith("Q") }
            ?: cleanImdb?.let { findWikidataIdByImdb(it) }
            ?: verifiedEnCandidate?.wikidataId
            ?: findVerifiedWikidataEntityBySearch(
                title = title,
                originalTitle = originalTitle,
                mediaType = mediaType,
                releaseYear = releaseYear,
            )

        val entityClaims = qid?.let {
            fetchWikidataEntityDetails(
                qid = it,
                expectedImdbId = cleanImdb,
                mediaType = mediaType,
                expectedYear = releaseYear,
            )
        }

        if (entityClaims == null) {
            // If entity validation failed or no verified entity matched, still allow verified English extract if matched
            val safeEn = verifiedEnCandidate?.extract
                ?.let { cleanWikipediaExtract(it, maxChars = 650) }
                ?.takeIf { isValidMediaOverviewExtract(it, isPersian = false) }
            if (safeEn == null) return@coroutineScope null
            return@coroutineScope WikidataEnrichment(
                wikidataId = null,
                englishOverview = safeEn,
                persianTitle = null,
                persianOverview = null,
                directors = emptyList(),
                cast = emptyList(),
                genres = emptyList(),
                runtimeMinutes = null,
                releaseDate = null,
                country = null,
                originalLanguage = null,
                imdbId = cleanImdb,
            )
        }

        val faSummaryDef = async {
            val faSiteTitle = entityClaims.faWikiTitle ?: return@async null
            val summary = fetchExactWikipediaSummary(faSiteTitle, "fa") ?: return@async null
            summary.extract
                ?.let { cleanWikipediaExtract(it, maxChars = 700) }
                ?.takeIf {
                    isValidPersianMediaExtract(
                        extract = it,
                        releaseYear = releaseYear,
                        requireMediaKeywords = (entityClaims.imdbId == null),
                    )
                }
        }
        val enSummaryDef = async {
            if (verifiedEnCandidate != null && verifiedEnCandidate.wikidataId == qid && !verifiedEnCandidate.extract.isNullOrBlank()) {
                return@async cleanWikipediaExtract(verifiedEnCandidate.extract, maxChars = 650)
            }
            val enSiteTitle = entityClaims.enWikiTitle ?: return@async null
            fetchExactWikipediaSummary(enSiteTitle, "en")?.extract
                ?.let { cleanWikipediaExtract(it, maxChars = 650) }
        }

        val faOverview = faSummaryDef.await()
        val enOverview = enSummaryDef.await()

        WikidataEnrichment(
            wikidataId = qid,
            englishOverview = enOverview,
            persianTitle = entityClaims.persianLabel ?: entityClaims.faWikiTitle?.let { cleanWikipediaTitle(it) },
            persianOverview = faOverview,
            directors = entityClaims.directors,
            cast = entityClaims.cast,
            genres = entityClaims.genres,
            runtimeMinutes = entityClaims.runtimeMinutes,
            releaseDate = entityClaims.releaseDate,
            country = entityClaims.country,
            originalLanguage = entityClaims.originalLanguage,
            imdbId = entityClaims.imdbId ?: cleanImdb,
        )
    }

    private data class WikiSummary(
        val title: String,
        val extract: String?,
        val wikidataId: String?,
    )

    private fun fetchExactWikipediaSummary(pageTitle: String, lang: String): WikiSummary? {
        val cleanTitle = pageTitle.trim()
        if (cleanTitle.isEmpty()) return null
        val detailJson = getString(
            "https://$lang.wikipedia.org/w/api.php?action=query&titles=${URLEncoder.encode(cleanTitle, "UTF-8")}&prop=extracts|pageprops&ppprop=wikibase_item|disambiguation&exintro=1&explaintext=1&redirects=1&format=json"
        ) ?: return null

        return try {
            val pages = JSONObject(detailJson).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val key = pages.keys().asSequence().firstOrNull() ?: return null
            if (key == "-1") return null
            val page = pages.optJSONObject(key) ?: return null
            val pageProps = page.optJSONObject("pageprops")
            if (pageProps?.has("disambiguation") == true) return null
            WikiSummary(
                title = page.optString("title", cleanTitle),
                extract = page.optString("extract").takeIf { it.isNotBlank() },
                wikidataId = pageProps?.optString("wikibase_item")?.takeIf { it.startsWith("Q") },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun findVerifiedEnglishWikipediaMatch(
        title: String,
        originalTitle: String?,
        mediaType: MediaType,
        releaseYear: Int?,
    ): WikiSummary? {
        val typeHint = if (mediaType == MediaType.TV) "TV series" else "film"
        val queries = linkedSetOf<String>()
        val cleanPrimary = title.trim()
        val cleanOrig = originalTitle?.trim()?.takeIf { it.isNotBlank() && !it.equals(cleanPrimary, ignoreCase = true) }
        if (cleanPrimary.isNotEmpty()) {
            if (releaseYear != null) queries.add("$cleanPrimary $releaseYear $typeHint")
            queries.add("$cleanPrimary $typeHint")
        }
        if (cleanOrig != null) {
            if (releaseYear != null) queries.add("$cleanOrig $releaseYear $typeHint")
            queries.add("$cleanOrig $typeHint")
        }

        val candidateTitles = linkedSetOf<String>()
        for (q in queries.take(3)) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val searchJson = getString(
                "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&utf8=1&srlimit=4"
            ) ?: continue
            val arr = runCatching {
                JSONObject(searchJson).optJSONObject("query")?.optJSONArray("search")
            }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() } ?: continue
                candidateTitles.add(t)
            }
            if (candidateTitles.size >= 4) break
        }
        if (candidateTitles.isEmpty()) return null

        val joined = URLEncoder.encode(candidateTitles.take(5).joinToString("|"), "UTF-8")
        val detailJson = getString(
            "https://en.wikipedia.org/w/api.php?action=query&titles=$joined&prop=extracts|pageprops&ppprop=wikibase_item|disambiguation&exintro=1&explaintext=1&exlimit=max&redirects=1&format=json"
        ) ?: return null

        return try {
            val pages = JSONObject(detailJson).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val normPrimary = FuzzyTitleMatcher.normalizeText(cleanPrimary)
            val normOrig = cleanOrig?.let { FuzzyTitleMatcher.normalizeText(it) }

            var bestCandidate: Pair<Int, WikiSummary>? = null
            val keys = pages.keys()
            while (keys.hasNext()) {
                val page = pages.optJSONObject(keys.next()) ?: continue
                val pageProps = page.optJSONObject("pageprops")
                if (pageProps?.has("disambiguation") == true) continue
                val pageTitle = page.optString("title").takeIf { it.isNotBlank() } ?: continue
                val extract = page.optString("extract").takeIf { it.isNotBlank() } ?: continue
                val qid = pageProps?.optString("wikibase_item")?.takeIf { it.startsWith("Q") }
                if (!isValidMediaOverviewExtract(extract, isPersian = false)) continue

                val cleanPageTitle = cleanWikipediaTitle(pageTitle)
                val normPageTitle = FuzzyTitleMatcher.normalizeText(cleanPageTitle)
                val titleSim = maxOf(
                    FuzzyTitleMatcher.stringSimilarity(normPageTitle, normPrimary),
                    normOrig?.let { FuzzyTitleMatcher.stringSimilarity(normPageTitle, it) } ?: 0.0,
                )
                val exactMatch = normPageTitle == normPrimary || (normOrig != null && normPageTitle == normOrig)
                if (!exactMatch && titleSim < 0.72) continue

                val lowerTitle = pageTitle.lowercase()
                val lowerLead = extract.take(280).lowercase()
                val isFilmPage = lowerTitle.contains("film") || lowerLead.contains(" film") || lowerLead.contains("movie")
                val isTvPage = lowerTitle.contains("tv series") || lowerTitle.contains("television") ||
                    lowerLead.contains("television series") || lowerLead.contains("tv series") || lowerLead.contains("miniseries")
                if (mediaType == MediaType.MOVIE && !isFilmPage) continue
                if (mediaType == MediaType.TV && !isTvPage) continue

                if (releaseYear != null) {
                    val mentionedYears = YEAR_REGEX.findAll(extract.take(260))
                        .mapNotNull { it.value.toIntOrNull() }
                        .toList()
                    if (mentionedYears.isNotEmpty() && mentionedYears.none { kotlin.math.abs(it - releaseYear) <= 1 }) {
                        continue
                    }
                }

                var score = (titleSim * 100).toInt()
                if (exactMatch) score += 40
                if (releaseYear != null && extract.take(260).contains(releaseYear.toString())) score += 30
                if (qid != null) score += 10
                val summary = WikiSummary(pageTitle, extract, qid)
                if (bestCandidate == null || score > bestCandidate.first) {
                    bestCandidate = score to summary
                }
            }
            bestCandidate?.second
        } catch (_: Exception) {
            null
        }
    }

    private fun findWikidataIdByImdb(imdbId: String): String? {
        val clean = imdbId.trim().takeIf { it.startsWith("tt") } ?: return null
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val body = getString(
            "https://www.wikidata.org/w/api.php?action=query&list=search&srsearch=haswbstatement:P345=$encoded&format=json&srlimit=2"
        ) ?: return null
        return try {
            JSONObject(body)
                .optJSONObject("query")
                ?.optJSONArray("search")
                ?.optJSONObject(0)
                ?.optString("title")
                ?.takeIf { it.startsWith("Q") }
        } catch (_: Exception) {
            null
        }
    }

    private fun findVerifiedWikidataEntityBySearch(
        title: String,
        originalTitle: String?,
        mediaType: MediaType,
        releaseYear: Int?,
    ): String? {
        val candidates = listOfNotNull(
            title.trim().takeIf { it.isNotBlank() },
            originalTitle?.trim()?.takeIf { it.isNotBlank() && !it.equals(title.trim(), ignoreCase = true) },
        )
        for (term in candidates) {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val body = getString(
                "https://www.wikidata.org/w/api.php?action=wbsearchentities&search=$encoded&language=en&uselang=en&type=item&limit=5&format=json"
            ) ?: continue
            val arr = runCatching { JSONObject(body).optJSONArray("search") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.startsWith("Q") } ?: continue
                val desc = item.optString("description", "").lowercase()
                if (desc.contains("disambiguation")) continue
                val looksLikeRightType = if (mediaType == MediaType.TV) {
                    desc.contains("television") || desc.contains("tv series") || desc.contains("miniseries") || desc.contains("anime")
                } else {
                    desc.contains("film") || desc.contains("movie")
                }
                if (!looksLikeRightType) continue
                if (releaseYear != null) {
                    val years = YEAR_REGEX.findAll(desc).mapNotNull { it.value.toIntOrNull() }.toList()
                    if (years.isNotEmpty() && years.none { kotlin.math.abs(it - releaseYear) <= 1 }) {
                        continue
                    }
                }
                return id
            }
        }
        return null
    }

    data class EntityClaims(
        val persianLabel: String?,
        val faWikiTitle: String?,
        val enWikiTitle: String?,
        val directors: List<String>,
        val cast: List<String>,
        val genres: List<String>,
        val runtimeMinutes: Int?,
        val releaseDate: String?,
        val country: String?,
        val originalLanguage: String?,
        val imdbId: String?,
    )

    private fun fetchWikidataEntityDetails(
        qid: String,
        expectedImdbId: String?,
        mediaType: MediaType,
        expectedYear: Int?,
    ): EntityClaims? {
        val body = getString(
            "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${URLEncoder.encode(qid, "UTF-8")}&props=claims|labels|descriptions|sitelinks&languages=en|fa&sitefilter=enwiki|fawiki&format=json"
        ) ?: return null
        return parseVerifiedWikidataEntityJson(
            rawJson = body,
            qid = qid,
            expectedImdbId = expectedImdbId,
            mediaType = mediaType,
            expectedYear = expectedYear,
        )
    }

    fun parseVerifiedWikidataEntityJson(
        rawJson: String,
        qid: String,
        expectedImdbId: String?,
        mediaType: MediaType,
        expectedYear: Int?,
        resolveClaimLabels: Boolean = true,
    ): EntityClaims? {
        return try {
            val entity = JSONObject(rawJson).optJSONObject("entities")?.optJSONObject(qid) ?: return null
            val labels = entity.optJSONObject("labels")
            val descriptions = entity.optJSONObject("descriptions")
            val sitelinks = entity.optJSONObject("sitelinks")
            val claims = entity.optJSONObject("claims") ?: return null

            val enDesc = descriptions?.optJSONObject("en")?.optString("value").orEmpty()
            val faDesc = descriptions?.optJSONObject("fa")?.optString("value").orEmpty()
            if (enDesc.contains("disambiguation", ignoreCase = true) ||
                faDesc.contains("ابهام‌زدایی")
            ) {
                return null
            }

            val instanceOfIds = extractClaimItemIds(claims, "P31", limit = 12).toSet()
            if ((instanceOfIds intersect DISAMBIGUATION_QIDS).isNotEmpty()) {
                return null
            }

            val imdbId = claims.optJSONArray("P345")?.optJSONObject(0)
                ?.optJSONObject("mainsnak")?.optJSONObject("datavalue")
                ?.optString("value")?.takeIf { it.startsWith("tt") }

            val imdbLocked = expectedImdbId != null && imdbId != null
            if (imdbLocked && !expectedImdbId.equals(imdbId, ignoreCase = true)) {
                return null
            }

            val directorIds = extractClaimItemIds(claims, "P57", limit = 3)
            val castIds = extractClaimItemIds(claims, "P161", limit = 8)
            val genreIds = extractClaimItemIds(claims, "P136", limit = 4)
            val countryIds = extractClaimItemIds(claims, "P495", limit = 1)
            val langIds = extractClaimItemIds(claims, "P364", limit = 1)

            val hasScreenWorkType = (instanceOfIds intersect SCREEN_WORK_QIDS).isNotEmpty()
            val hasMediaDesc = hasMediaDescription(enDesc, faDesc)
            val hasMediaClaims = directorIds.isNotEmpty() || castIds.isNotEmpty() || imdbId != null
            if (!imdbLocked && !hasScreenWorkType && !hasMediaDesc && !hasMediaClaims) {
                return null
            }
            if (!imdbLocked && (instanceOfIds intersect NON_MEDIA_QIDS).isNotEmpty() && !hasScreenWorkType) {
                return null
            }

            // Guard against matching a film entity when searching for a TV series with the same title (or vice versa)
            if (!imdbLocked) {
                val isFilmEntity = (instanceOfIds intersect FILM_ONLY_QIDS).isNotEmpty()
                val isTvEntity = (instanceOfIds intersect TV_SERIES_QIDS).isNotEmpty()
                if (mediaType == MediaType.MOVIE && isTvEntity && !isFilmEntity) return null
                if (mediaType == MediaType.TV && isFilmEntity && !isTvEntity) return null
            }

            val releaseDate = extractWikidataDateClaim(claims, "P577")
                ?: extractWikidataDateClaim(claims, "P580")
            val entityYear = releaseDate?.take(4)?.toIntOrNull()
            if (!imdbLocked && expectedYear != null && entityYear != null &&
                kotlin.math.abs(entityYear - expectedYear) > 2
            ) {
                return null
            }

            val allReferencedIds = if (resolveClaimLabels) {
                (directorIds + castIds + genreIds + countryIds + langIds).distinct()
            } else {
                emptyList()
            }
            val resolvedLabels = if (allReferencedIds.isNotEmpty()) {
                fetchWikidataLabels(allReferencedIds.take(25))
            } else {
                emptyMap()
            }

            val runtime = claims.optJSONArray("P2047")?.optJSONObject(0)
                ?.optJSONObject("mainsnak")?.optJSONObject("datavalue")
                ?.optJSONObject("value")?.optString("amount")
                ?.removePrefix("+")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }

            val persianLabel = labels?.optJSONObject("fa")?.optString("value")?.takeIf { it.isNotBlank() }
            val faWikiTitle = sitelinks?.optJSONObject("fawiki")?.optString("title")?.takeIf { it.isNotBlank() }
            val enWikiTitle = sitelinks?.optJSONObject("enwiki")?.optString("title")?.takeIf { it.isNotBlank() }

            EntityClaims(
                persianLabel = persianLabel,
                faWikiTitle = faWikiTitle,
                enWikiTitle = enWikiTitle,
                directors = directorIds.mapNotNull { resolvedLabels[it] },
                cast = castIds.mapNotNull { resolvedLabels[it] },
                genres = genreIds.mapNotNull { resolvedLabels[it] },
                runtimeMinutes = runtime,
                releaseDate = releaseDate,
                country = countryIds.firstOrNull()?.let { resolvedLabels[it] },
                originalLanguage = langIds.firstOrNull()?.let { resolvedLabels[it] },
                imdbId = imdbId,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractWikidataDateClaim(claims: JSONObject, property: String): String? =
        claims.optJSONArray(property)?.optJSONObject(0)
            ?.optJSONObject("mainsnak")?.optJSONObject("datavalue")
            ?.optJSONObject("value")?.optString("time")
            ?.removePrefix("+")?.substringBefore("T")
            ?.takeIf { it.length >= 4 }

    private fun extractClaimItemIds(claims: JSONObject, property: String, limit: Int): List<String> {
        val arr = claims.optJSONArray(property) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                if (size >= limit) break
                val id = arr.optJSONObject(i)
                    ?.optJSONObject("mainsnak")
                    ?.optJSONObject("datavalue")
                    ?.optJSONObject("value")
                    ?.optString("id")
                    ?.takeIf { it.startsWith("Q") }
                if (id != null) add(id)
            }
        }
    }

    private fun fetchWikidataLabels(qids: List<String>): Map<String, String> {
        if (qids.isEmpty()) return emptyMap()
        val idsParam = URLEncoder.encode(qids.joinToString("|"), "UTF-8")
        val body = getString(
            "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$idsParam&props=labels&languages=en&format=json"
        ) ?: return emptyMap()
        return try {
            val entities = JSONObject(body).optJSONObject("entities") ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            val keys = entities.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val label = entities.optJSONObject(id)?.optJSONObject("labels")?.optJSONObject("en")?.optString("value")
                if (!label.isNullOrBlank()) result[id] = label
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun getString(url: String): String? {
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return resp.body?.string()
                    }
                    if (attempt == 0 && (resp.code == 429 || resp.code >= 500)) {
                        Thread.sleep(250)
                    } else {
                        return null
                    }
                }
            } catch (_: Exception) {
                if (attempt == 0) {
                    runCatching { Thread.sleep(200) }
                } else {
                    return null
                }
            }
        }
        return null
    }

    internal fun cleanWikipediaTitle(rawTitle: String): String {
        return rawTitle
            .replace(Regex("\\s*\\((?:\\d{4}\\s+)?(?:film|TV series|miniseries|television series|فیلم[^)]*|مجموعه تلویزیونی[^)]*|سریال[^)]*)\\)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun shrinkImdbPoster(url: String): String {
        // Resize Amazon/IMDb giant 3000px posters to crisp mobile-friendly 380px thumbnails to save RAM/bandwidth
        return url.replace("._V1_.jpg", "._V1_UX380_.jpg")
    }

    private fun stripHtmlTags(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

    companion object {
        private const val USER_AGENT = "MediaXAndroid/1.1 (https://github.com/alonedroid-maker/mixmedia)"

        private val PERSIAN_CHAR_REGEX = Regex("[\\u0600-\\u06FF]")
        private val YEAR_REGEX = Regex("\\b(19\\d{2}|20\\d{2})\\b")
        private val WIKI_CITATIONS_REGEX = Regex("\\[[0-9\\u06F0-\\u06F9a-zA-Z\\u0600-\\u06FF\\s]{1,18}\\]")

        private val DISAMBIGUATION_QIDS = setOf("Q4167410", "Q22808320", "Q13406463")
        private val FILM_ONLY_QIDS = setOf(
            "Q11424", "Q24862", "Q202866", "Q93204", "Q24856", "Q229390", "Q29168811", "Q20650540"
        )
        private val TV_SERIES_QIDS = setOf(
            "Q5398426", "Q1259759", "Q506240", "Q15416", "Q21191270", "Q63952888", "Q581714", "Q526877"
        )
        private val SCREEN_WORK_QIDS = FILM_ONLY_QIDS + TV_SERIES_QIDS + setOf("Q1107")
        private val NON_MEDIA_QIDS = setOf(
            "Q5", "Q515", "Q6256", "Q7889", "Q482994", "Q7366", "Q16521", "Q7725634", "Q571"
        )

        private fun hasMediaDescription(enDesc: String, faDesc: String): Boolean {
            val en = enDesc.lowercase()
            val fa = faDesc.lowercase()
            return en.contains("film") || en.contains("movie") || en.contains("television") ||
                en.contains("tv series") || en.contains("miniseries") || en.contains("anime") ||
                en.contains("documentary") ||
                fa.contains("فیلم") || fa.contains("سریال") || fa.contains("مجموعه تلویزیونی") ||
                fa.contains("انیمیشن") || fa.contains("مستند")
        }

        fun cleanWikipediaExtract(raw: String, maxChars: Int = 700): String {
            val noCitations = raw
                .replace(WIKI_CITATIONS_REGEX, "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (noCitations.length <= maxChars) return noCitations
            val slice = noCitations.take(maxChars)
            val lastStop = maxOf(
                slice.lastIndexOf('.'),
                slice.lastIndexOf('۔'),
                slice.lastIndexOf('!'),
                slice.lastIndexOf('؟'),
            )
            return if (lastStop >= maxChars / 2) {
                slice.substring(0, lastStop + 1).trim()
            } else {
                slice.substringBeforeLast(' ', slice).trimEnd('.', '،', ' ') + "…"
            }
        }

        fun isValidMediaOverviewExtract(raw: String?, isPersian: Boolean): Boolean {
            val text = raw?.trim().orEmpty()
            if (text.length < 25) return false
            val lower = text.lowercase()
            if (lower.contains("may refer to:") ||
                lower.contains("may also refer to") ||
                text.contains("ممکن است به یکی از موارد زیر") ||
                text.contains("به یکی از موارد زیر اشاره داشته باشد") ||
                text.contains("ابهام‌زدایی")
            ) {
                return false
            }
            if (isPersian) {
                val faCount = PERSIAN_CHAR_REGEX.findAll(text).count()
                if (faCount < 15) return false
            }
            return true
        }

        fun isValidPersianMediaExtract(
            extract: String?,
            releaseYear: Int? = null,
            requireMediaKeywords: Boolean = false,
        ): Boolean {
            val text = extract?.trim().orEmpty()
            if (!isValidMediaOverviewExtract(text, isPersian = true)) return false
            if (releaseYear != null) {
                val lead = text.take(260)
                val yearsInLead = YEAR_REGEX.findAll(lead).mapNotNull { it.value.toIntOrNull() }.toList()
                if (yearsInLead.isNotEmpty() && yearsInLead.none { kotlin.math.abs(it - releaseYear) <= 2 }) {
                    return false
                }
            }
            if (requireMediaKeywords) {
                val lead = text.take(360)
                val hasMediaHint = lead.contains("فیلم") || lead.contains("سینما") ||
                    lead.contains("سریال") || lead.contains("مجموعه تلویزیونی") ||
                    lead.contains("کارگردان") || lead.contains("نویسند") ||
                    lead.contains("بازیگر") || lead.contains("انیمیشن") ||
                    lead.contains("مستند") || lead.contains("پخش") ||
                    lead.contains("اکران")
                if (!hasMediaHint) return false
            }
            return true
        }
    }
}

data class TvMazeRichDetails(
    val tvMazeId: Int,
    val title: String,
    val premiered: String?,
    val startYear: Int?,
    val endYear: Int?,
    val status: String?,
    val episodeRuntimeMinutes: Int?,
    val genres: List<String>,
    val creators: List<String>,
    val cast: List<CastMember>,
    val rating: Double?,
    val overview: String?,
    val country: String?,
    val originalLanguage: String?,
    val posterUrl: String?,
    val imdbId: String?,
    val seasons: List<SeasonInfo>,
    val totalEpisodes: Int?,
)

data class WikidataEnrichment(
    val wikidataId: String?,
    val englishOverview: String?,
    val persianTitle: String?,
    val persianOverview: String?,
    val directors: List<String>,
    val cast: List<String>,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val releaseDate: String?,
    val country: String?,
    val originalLanguage: String?,
    val imdbId: String?,
)
