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
        return try {
            val arr = JSONArray(body)
            val candidates = ArrayList<Pair<Double, MediaSearchResult>>(60)
            for (i in 0 until arr.length()) {
                val show = arr.optJSONObject(i) ?: continue
                val rating = show.optJSONObject("rating")?.optDouble("average")?.takeIf { !it.isNaN() } ?: 0.0
                val weight = show.optDouble("weight", 0.0).takeIf { !it.isNaN() } ?: 0.0
                if (rating < 8.2 && weight < 92.0) continue
                val tvMazeId = show.optInt("id", -1).takeIf { it > 0 } ?: continue
                val name = show.optString("name").takeIf { it.isNotBlank() } ?: continue
                val year = show.optString("premiered").take(4).toIntOrNull()
                val genres = show.optJSONArray("genres")?.toStringList().orEmpty()
                val runtime = show.optInt("averageRuntime", 0).takeIf { it > 0 }
                    ?: show.optInt("runtime", 0).takeIf { it > 0 }
                val poster = show.optJSONObject("image")?.let { img ->
                    img.optString("medium").ifBlank { img.optString("original") }
                }?.takeIf { it.startsWith("http") }
                val imdbId = show.optJSONObject("externals")?.optString("imdb")?.takeIf { it.startsWith("tt") }
                val summary = stripHtmlTags(show.optString("summary"))
                candidates.add(
                    (weight + rating * 5.0) to MediaSearchResult(
                        id = imdbId?.let { "imdb:$it" } ?: "tvmaze:$tvMazeId",
                        mediaType = MediaType.TV,
                        title = name,
                        originalTitle = name,
                        releaseYear = year,
                        posterUrl = poster,
                        runtimeMinutes = runtime,
                        genres = genres,
                        imdbRating = rating.takeIf { it > 0.0 },
                        overview = summary.takeIf { it.isNotBlank() },
                        imdbId = imdbId,
                        tvMazeId = tvMazeId,
                    )
                )
            }
            candidates.sortedByDescending { it.first }.map { it.second }.take(limit)
        } catch (_: Exception) {
            emptyList()
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
     * and Persian synopses via fa.wikipedia.org without any API key.
     */
    fun resolvePersianQueryViaWikipedia(query: String): List<WikipediaBridgeHit> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val searchBody = getString(
            "https://fa.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&utf8=1&srlimit=6"
        ) ?: return emptyList()

        val pageTitles = try {
            val arr = JSONObject(searchBody).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
            val rawTitles = (0 until arr.length()).mapNotNull { idx ->
                val item = arr.optJSONObject(idx) ?: return@mapNotNull null
                val title = item.optString("title")
                val snippet = item.optString("snippet")
                val isMediaLikely = title.contains("فیلم") || title.contains("مجموعه") || title.contains("سریال") ||
                    snippet.contains("فیلم") || snippet.contains("سریال") || snippet.contains("مجموعه تلویزیونی") ||
                    snippet.contains("کارگردان")
                (title to isMediaLikely)
            }
            rawTitles.sortedByDescending { it.second }.map { it.first }.take(4)
        } catch (_: Exception) {
            emptyList()
        }
        if (pageTitles.isEmpty()) return emptyList()

        val joined = URLEncoder.encode(pageTitles.joinToString("|"), "UTF-8")
        val detailBody = getString(
            "https://fa.wikipedia.org/w/api.php?action=query&titles=$joined&prop=langlinks|extracts|pageprops&lllang=en&exintro=1&explaintext=1&redirects=1&format=json"
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
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
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
