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
internal class ZeroKeyMetadataEnricher(private val client: OkHttpClient) {

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

    internal fun parseImdbSuggestionJson(rawJson: String, limit: Int = 10): List<MediaSearchResult> {
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
            "https://fa.wikipedia.org/w/api.php?action=query&titles=$joined&prop=langlinks|extracts|pageprops&lllang=en&exintro=1&explaintext=1&exlimit=max&redirects=1&format=json"
        ) ?: return emptyList()

        return try {
            val pages = JSONObject(detailBody).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
            buildList {
                val keys = pages.keys()
                while (keys.hasNext()) {
                    val page = pages.optJSONObject(keys.next()) ?: continue
                    val faTitle = page.optString("title").takeIf { it.isNotBlank() }
                    val enTitleRaw = page.optJSONArray("langlinks")?.optJSONObject(0)?.optString("*")
                    val enClean = enTitleRaw?.let { cleanWikipediaTitle(it) }
                    val extract = page.optString("extract").takeIf { it.isNotBlank() }?.take(500)
                    val wikidataId = page.optJSONObject("pageprops")?.optString("wikibase_item")
                        ?.takeIf { it.startsWith("Q") }
                    if (enClean != null || faTitle != null) {
                        add(WikipediaBridgeHit(faTitle, enClean, extract, wikidataId))
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

    internal fun parseTvMazeFullShowJson(rawJson: String): TvMazeRichDetails? {
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
     */
    suspend fun fetchWikidataAndWikipediaEnrichment(
        title: String,
        imdbId: String?,
        knownWikidataId: String? = null,
    ): WikidataEnrichment? = coroutineScope {
        val enWikiSearchTitleDeferred = async { findWikipediaTitleAndSummary(title, "en") }
        val faWikiSearchTitleDeferred = async { findWikipediaTitleAndSummary(title, "fa") }

        val enWiki = enWikiSearchTitleDeferred.await()
        val faWiki = faWikiSearchTitleDeferred.await()

        val qid = knownWikidataId
            ?: enWiki?.wikidataId
            ?: faWiki?.wikidataId
            ?: findWikidataIdByImdbOrTitle(imdbId, title)

        val entityClaims = qid?.let { fetchWikidataEntityDetails(it) }

        if (enWiki == null && faWiki == null && entityClaims == null) return@coroutineScope null
        WikidataEnrichment(
            wikidataId = qid,
            englishOverview = enWiki?.extract,
            persianTitle = entityClaims?.persianLabel ?: faWiki?.title?.let { cleanWikipediaTitle(it) },
            persianOverview = faWiki?.extract,
            directors = entityClaims?.directors.orEmpty(),
            cast = entityClaims?.cast.orEmpty(),
            genres = entityClaims?.genres.orEmpty(),
            runtimeMinutes = entityClaims?.runtimeMinutes,
            releaseDate = entityClaims?.releaseDate,
            country = entityClaims?.country,
            originalLanguage = entityClaims?.originalLanguage,
            imdbId = entityClaims?.imdbId ?: imdbId,
        )
    }

    private data class WikiSummary(
        val title: String,
        val extract: String?,
        val wikidataId: String?,
    )

    private fun findWikipediaTitleAndSummary(title: String, lang: String): WikiSummary? {
        val encoded = URLEncoder.encode(title.trim(), "UTF-8")
        val searchJson = getString(
            "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&utf8=1&srlimit=3"
        ) ?: return null
        val pageTitle = try {
            val arr = JSONObject(searchJson).optJSONObject("query")?.optJSONArray("search") ?: return null
            val titles = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("title") }
            titles.firstOrNull {
                it.contains("film", ignoreCase = true) || it.contains("series", ignoreCase = true) ||
                    it.contains("فیلم") || it.contains("مجموعه") || it.contains("سریال")
            } ?: titles.firstOrNull()
        } catch (_: Exception) {
            null
        } ?: return null

        val detailJson = getString(
            "https://$lang.wikipedia.org/w/api.php?action=query&titles=${URLEncoder.encode(pageTitle, "UTF-8")}&prop=extracts|pageprops&exintro=1&explaintext=1&redirects=1&format=json"
        ) ?: return null

        return try {
            val pages = JSONObject(detailJson).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val key = pages.keys().asSequence().firstOrNull() ?: return null
            val page = pages.optJSONObject(key) ?: return null
            WikiSummary(
                title = page.optString("title", pageTitle),
                extract = page.optString("extract").takeIf { it.isNotBlank() }?.take(600),
                wikidataId = page.optJSONObject("pageprops")?.optString("wikibase_item")?.takeIf { it.startsWith("Q") },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun findWikidataIdByImdbOrTitle(imdbId: String?, title: String): String? {
        val search = imdbId?.takeIf { it.startsWith("tt") } ?: title
        val encoded = URLEncoder.encode(search, "UTF-8")
        val url = if (imdbId != null) {
            "https://www.wikidata.org/w/api.php?action=query&list=search&srsearch=haswbstatement:P345=$encoded&format=json&srlimit=1"
        } else {
            "https://www.wikidata.org/w/api.php?action=wbsearchentities&search=$encoded&language=en&format=json&limit=2"
        }
        val body = getString(url) ?: return null
        return try {
            val root = JSONObject(body)
            if (imdbId != null) {
                root.optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title")
                    ?.takeIf { it.startsWith("Q") }
            } else {
                root.optJSONArray("search")?.optJSONObject(0)?.optString("id")
                    ?.takeIf { it.startsWith("Q") }
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class EntityClaims(
        val persianLabel: String?,
        val directors: List<String>,
        val cast: List<String>,
        val genres: List<String>,
        val runtimeMinutes: Int?,
        val releaseDate: String?,
        val country: String?,
        val originalLanguage: String?,
        val imdbId: String?,
    )

    private fun fetchWikidataEntityDetails(qid: String): EntityClaims? {
        val body = getString(
            "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${URLEncoder.encode(qid, "UTF-8")}&props=claims|labels&languages=en|fa&format=json"
        ) ?: return null
        return try {
            val entity = JSONObject(body).optJSONObject("entities")?.optJSONObject(qid) ?: return null
            val labels = entity.optJSONObject("labels")
            val persianLabel = labels?.optJSONObject("fa")?.optString("value")?.takeIf { it.isNotBlank() }
            val claims = entity.optJSONObject("claims") ?: return null

            val directorIds = extractClaimItemIds(claims, "P57", limit = 3)
            val castIds = extractClaimItemIds(claims, "P161", limit = 8)
            val genreIds = extractClaimItemIds(claims, "P136", limit = 4)
            val countryIds = extractClaimItemIds(claims, "P495", limit = 1)
            val langIds = extractClaimItemIds(claims, "P364", limit = 1)

            val allReferencedIds = (directorIds + castIds + genreIds + countryIds + langIds).distinct()
            val resolvedLabels = if (allReferencedIds.isNotEmpty()) {
                fetchWikidataLabels(allReferencedIds.take(25))
            } else {
                emptyMap()
            }

            val runtime = claims.optJSONArray("P2047")?.optJSONObject(0)
                ?.optJSONObject("mainsnak")?.optJSONObject("datavalue")
                ?.optJSONObject("value")?.optString("amount")
                ?.removePrefix("+")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }

            val releaseDate = claims.optJSONArray("P577")?.optJSONObject(0)
                ?.optJSONObject("mainsnak")?.optJSONObject("datavalue")
                ?.optJSONObject("value")?.optString("time")
                ?.removePrefix("+")?.substringBefore("T")
                ?.takeIf { it.length >= 4 }

            val imdbId = claims.optJSONArray("P345")?.optJSONObject(0)
                ?.optJSONObject("mainsnak")?.optJSONObject("datavalue")
                ?.optString("value")?.takeIf { it.startsWith("tt") }

            EntityClaims(
                persianLabel = persianLabel,
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
    }
}

internal data class TvMazeRichDetails(
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

internal data class WikidataEnrichment(
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
