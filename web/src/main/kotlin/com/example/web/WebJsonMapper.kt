package com.example.web

import com.example.media.CastMember
import com.example.media.EpisodeInfo
import com.example.media.MediaDetails
import com.example.media.MediaGenre
import com.example.media.MediaPreferences
import com.example.media.MediaSearchPage
import com.example.media.MediaSearchResult
import com.example.media.MediaType
import com.example.media.QueryModifiers
import com.example.media.SearchEngineOption
import com.example.media.SearchLanguagePreference
import com.example.media.SeasonInfo
import com.example.media.SourceDiscoveryBundle
import com.example.media.SourceSearchTarget
import com.example.media.SourceSiteConfig
import com.example.media.TrendingFeedBundle
import com.example.media.TrendingTimeWindow
import com.example.media.WatchProviderInfo
import com.example.media.WatchRegions
import com.example.media.WebSourceResult
import com.example.network.CurlResolutionResult
import com.example.network.RedirectHop
import com.example.network.XVideoVariant
import com.example.network.YouTubeMetadataInfo
import org.json.JSONArray
import org.json.JSONObject

object WebJsonMapper {

    fun parsePreferences(
        langCode: String?,
        regionCode: String?,
        trendingWindow: String?,
        searchEngineCode: String? = null,
    ): MediaPreferences {
        return MediaPreferences(
            searchLanguage = SearchLanguagePreference.fromCode(langCode),
            searchEngine = SearchEngineOption.fromCode(searchEngineCode),
            watchRegion = WatchRegions.normalize(regionCode),
            trendingWindow = TrendingTimeWindow.fromApi(trendingWindow),
        )
    }

    fun searchPageToJson(page: MediaSearchPage): JSONObject = JSONObject().apply {
        put("items", JSONArray().apply { page.items.forEach { put(mediaResultToJson(it)) } })
        put("page", page.page)
        put("totalPages", page.totalPages)
        put("totalResults", page.totalResults)
        put("hasMorePages", page.hasMorePages)
        put("usedTmdbBackend", page.usedTmdbBackend)
    }

    fun trendingBundleToJson(bundle: TrendingFeedBundle): JSONObject = JSONObject().apply {
        put("movies", JSONArray().apply { bundle.movies.forEach { put(mediaResultToJson(it)) } })
        put("tvShows", JSONArray().apply { bundle.tvShows.forEach { put(mediaResultToJson(it)) } })
        put("lastUpdatedMillis", bundle.lastUpdatedMillis)
        put("isUsingFallbackSources", bundle.isUsingFallbackSources)
        put("attribution", bundle.attribution)
        put("window", bundle.window.apiValue)
        put("region", bundle.region)
    }

    fun mediaResultToJson(item: MediaSearchResult): JSONObject = JSONObject().apply {
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
        put("digitalReleaseStatus", item.digitalReleaseStatus.name)
        put("digitalReleaseDate", item.digitalReleaseDate ?: JSONObject.NULL)
        put("tvReleaseStatus", item.tvReleaseStatus.name)
        put("seasonCount", item.seasonCount ?: JSONObject.NULL)
        put("watchProviders", JSONArray().apply { item.watchProviders.forEach { put(watchProviderToJson(it)) } })
        put("watchProvidersPageUrl", item.watchProvidersPageUrl ?: JSONObject.NULL)
        put("authorizedDirectDownloadUrl", item.authorizedDirectDownloadUrl ?: JSONObject.NULL)
    }

    fun mediaDetailsToJson(d: MediaDetails): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("mediaType", d.mediaType.name)
        put("title", d.title)
        put("persianTitle", d.persianTitle ?: JSONObject.NULL)
        put("originalTitle", d.originalTitle ?: JSONObject.NULL)
        put("releaseYear", d.releaseYear ?: JSONObject.NULL)
        put("releaseDate", d.releaseDate ?: JSONObject.NULL)
        put("posterUrl", d.posterUrl ?: JSONObject.NULL)
        put("backdropUrl", d.backdropUrl ?: JSONObject.NULL)
        put("runtimeMinutes", d.runtimeMinutes ?: JSONObject.NULL)
        put("episodeRuntimeMinutes", d.episodeRuntimeMinutes ?: JSONObject.NULL)
        put("genres", JSONArray(d.genres))
        put("directors", JSONArray(d.directors))
        put("creators", JSONArray(d.creators))
        put("cast", JSONArray().apply { d.cast.forEach { put(castMemberToJson(it)) } })
        put("imdbRating", d.imdbRating ?: JSONObject.NULL)
        put("overview", d.overview ?: JSONObject.NULL)
        put("persianOverview", d.persianOverview ?: JSONObject.NULL)
        put("country", d.country ?: JSONObject.NULL)
        put("originalLanguage", d.originalLanguage ?: JSONObject.NULL)
        put("status", d.status ?: JSONObject.NULL)
        put("startYear", d.startYear ?: JSONObject.NULL)
        put("endYear", d.endYear ?: JSONObject.NULL)
        put("totalSeasons", d.totalSeasons ?: JSONObject.NULL)
        put("totalEpisodes", d.totalEpisodes ?: JSONObject.NULL)
        put("seasons", JSONArray().apply { d.seasons.forEach { put(seasonToJson(it)) } })
        put("imdbId", d.imdbId ?: JSONObject.NULL)
        put("tmdbId", d.tmdbId ?: JSONObject.NULL)
        put("tvMazeId", d.tvMazeId ?: JSONObject.NULL)
        put("wikidataId", d.wikidataId ?: JSONObject.NULL)
        put("imdbUrl", d.imdbUrl ?: JSONObject.NULL)
        put("tmdbUrl", d.tmdbUrl ?: JSONObject.NULL)
        put("wikipediaUrl", d.wikipediaUrl ?: JSONObject.NULL)
        put("sourceAttribution", d.sourceAttribution)
        put("digitalReleaseStatus", d.digitalReleaseStatus.name)
        put("digitalReleaseDate", d.digitalReleaseDate ?: JSONObject.NULL)
        put("tvReleaseStatus", d.tvReleaseStatus.name)
        put("watchProviders", JSONArray().apply { d.watchProviders.forEach { put(watchProviderToJson(it)) } })
        put("watchProvidersPageUrl", d.watchProvidersPageUrl ?: JSONObject.NULL)
        put("authorizedDirectDownloadUrl", d.authorizedDirectDownloadUrl ?: JSONObject.NULL)
    }

    private fun castMemberToJson(c: CastMember): JSONObject = JSONObject().apply {
        put("name", c.name)
        put("character", c.character ?: JSONObject.NULL)
    }

    private fun seasonToJson(s: SeasonInfo): JSONObject = JSONObject().apply {
        put("seasonNumber", s.seasonNumber)
        put("title", s.title)
        put("episodeCount", s.episodeCount)
        put("airDate", s.airDate ?: JSONObject.NULL)
        put("episodes", JSONArray().apply { s.episodes.forEach { put(episodeToJson(it)) } })
    }

    private fun episodeToJson(e: EpisodeInfo): JSONObject = JSONObject().apply {
        put("episodeNumber", e.episodeNumber)
        put("seasonNumber", e.seasonNumber)
        put("title", e.title)
        put("airDate", e.airDate ?: JSONObject.NULL)
        put("runtimeMinutes", e.runtimeMinutes ?: JSONObject.NULL)
        put("overview", e.overview ?: JSONObject.NULL)
    }

    private fun watchProviderToJson(p: WatchProviderInfo): JSONObject = JSONObject().apply {
        put("name", p.name)
        put("category", p.category.name)
        put("logoUrl", p.logoUrl ?: JSONObject.NULL)
        put("url", p.url ?: JSONObject.NULL)
        put("region", p.region)
    }

    fun genreToJson(g: MediaGenre): JSONObject = JSONObject().apply {
        put("id", g.id)
        put("name", g.name)
    }

    fun siteConfigToJson(s: SourceSiteConfig): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("baseUrl", s.baseUrl)
        put("searchUrlTemplate", s.searchUrlTemplate)
        put("isArchive", s.isArchive)
        put("enabled", s.enabled)
        put("isCustom", s.isCustom)
        put("requiresDomesticIpHint", s.requiresDomesticIpHint)
    }

    fun parseSiteConfigList(arr: JSONArray?): List<SourceSiteConfig> {
        if (arr == null) return emptyList()
        val out = mutableListOf<SourceSiteConfig>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val baseUrl = o.optString("baseUrl", "").trim()
            val template = o.optString("searchUrlTemplate", "").trim()
            if (UrlSecurityGuard.validateExternalHttpUrl(baseUrl) == null) continue
            val probeTemplate = template.replace("{query}", "test")
            if (UrlSecurityGuard.validateExternalHttpUrl(probeTemplate) == null) continue
            out.add(
                SourceSiteConfig(
                    id = o.optString("id", "custom_$i").take(64).ifBlank { "custom_$i" },
                    name = o.optString("name", baseUrl).take(80).ifBlank { baseUrl },
                    baseUrl = baseUrl,
                    searchUrlTemplate = template,
                    isArchive = o.optBoolean("isArchive", false),
                    enabled = o.optBoolean("enabled", true),
                    isCustom = o.optBoolean("isCustom", true),
                    requiresDomesticIpHint = o.optBoolean("requiresDomesticIpHint", false),
                )
            )
        }
        return out
    }

    fun parseSourceSearchTarget(obj: JSONObject): SourceSearchTarget? {
        val title = obj.optString("title", "").trim().take(200)
        if (title.isBlank()) return null
        val typeStr = obj.optString("mediaType", "MOVIE").trim().uppercase()
        val mediaType = if (typeStr == "TV") MediaType.TV else MediaType.MOVIE
        return SourceSearchTarget(
            mediaId = obj.optString("mediaId", title).take(120).ifBlank { title },
            mediaType = mediaType,
            title = title,
            persianTitle = obj.optString("persianTitle", "").trim().takeIf { it.isNotEmpty() },
            releaseYear = obj.optInt("releaseYear", 0).takeIf { it in 1880..2100 },
            seasonNumber = obj.optInt("seasonNumber", 0).takeIf { it > 0 },
            episodeNumber = obj.optInt("episodeNumber", 0).takeIf { it > 0 },
            episodeTitle = obj.optString("episodeTitle", "").trim().takeIf { it.isNotEmpty() },
        )
    }

    fun parseQueryModifiers(obj: JSONObject?): QueryModifiers {
        if (obj == null) return QueryModifiers()
        return QueryModifiers(
            includeSubtitle = obj.optBoolean("includeSubtitle", true),
            includeDubbed = obj.optBoolean("includeDubbed", false),
            includeWatchOnline = obj.optBoolean("includeWatchOnline", false),
            includeDownload = obj.optBoolean("includeDownload", true),
        )
    }

    fun sourceBundleToJson(b: SourceDiscoveryBundle): JSONObject = JSONObject().apply {
        put("generatedQueries", JSONArray(b.generatedQueries))
        put("searchEngineLaunchUrls", JSONArray().apply {
            b.searchEngineLaunchUrls.forEach { (engine, url) ->
                put(JSONObject().apply {
                    put("engine", engine.code)
                    put("displayName", engine.displayName)
                    put("url", url)
                })
            }
        })
        put("siteResults", JSONArray().apply { b.siteResults.forEach { put(webSourceResultToJson(it)) } })
        put("webSearchResults", JSONArray().apply { b.webSearchResults.forEach { put(webSourceResultToJson(it)) } })
        put("siteNotices", JSONArray(b.siteNotices))
    }

    private fun webSourceResultToJson(r: WebSourceResult): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("title", r.title)
        put("url", r.url)
        put("domain", r.domain)
        put("siteName", r.siteName)
        put("snippet", r.snippet)
        put("status", r.status.name)
        put("statusNote", r.statusNote ?: JSONObject.NULL)
        put("isDirectDownloadCandidate", r.isDirectDownloadCandidate)
        put("detectedExtension", r.detectedExtension ?: JSONObject.NULL)
    }

    fun resolutionResultToJson(res: CurlResolutionResult): JSONObject = JSONObject().apply {
        put("sourceUrl", res.sourceUrl)
        put("directVideoUrl", res.directVideoUrl ?: JSONObject.NULL)
        put("candidateVideoUrls", JSONArray(res.candidateVideoUrls))
        put("filename", res.filename ?: JSONObject.NULL)
        put("extension", res.extension ?: JSONObject.NULL)
        put("fileSizeFormatted", res.fileSizeFormatted ?: JSONObject.NULL)
        put("finalStatusCode", res.finalStatusCode)
        put("hops", JSONArray().apply { res.hops.forEach { put(hopToJson(it)) } })
        put("verboseLog", res.verboseLog)
        put("youtubeInfo", res.youtubeInfo?.let { youtubeToJson(it) } ?: JSONObject.NULL)
        put("mediaTitle", res.mediaTitle ?: JSONObject.NULL)
        put("mediaAuthor", res.mediaAuthor ?: JSONObject.NULL)
        put("mediaDurationSeconds", res.mediaDurationSeconds ?: JSONObject.NULL)
        put("xVideoVariants", JSONArray().apply { res.xVideoVariants.forEach { put(xVariantToJson(it)) } })
        put("thumbnailUrl", res.thumbnailUrl ?: JSONObject.NULL)
    }

    private fun hopToJson(h: RedirectHop): JSONObject = JSONObject().apply {
        put("step", h.step)
        put("requestUrl", h.requestUrl)
        put("statusCode", h.statusCode)
        put("statusMessage", h.statusMessage)
        put("locationHeader", h.locationHeader ?: JSONObject.NULL)
        put("contentType", h.contentType ?: JSONObject.NULL)
        put("contentLength", h.contentLength ?: JSONObject.NULL)
    }

    private fun youtubeToJson(y: YouTubeMetadataInfo): JSONObject = JSONObject().apply {
        put("videoId", y.videoId)
        put("canonicalUrl", y.canonicalUrl)
        put("title", y.title)
        put("channelTitle", y.channelTitle ?: JSONObject.NULL)
        put("channelUrl", y.channelUrl ?: JSONObject.NULL)
        put("thumbnailUrl", y.thumbnailUrl ?: JSONObject.NULL)
        put("durationSeconds", y.durationSeconds ?: JSONObject.NULL)
        put("publishedAt", y.publishedAt ?: JSONObject.NULL)
        put("isAvailable", y.isAvailable)
        put("unavailableReason", y.unavailableReason ?: JSONObject.NULL)
    }

    private fun xVariantToJson(v: XVideoVariant): JSONObject = JSONObject().apply {
        put("url", v.url)
        put("qualityLabel", v.qualityLabel)
        put("width", v.width ?: JSONObject.NULL)
        put("height", v.height ?: JSONObject.NULL)
        put("bitrateKbps", v.bitrateKbps ?: JSONObject.NULL)
        put("container", v.container)
        put("contentLengthBytes", v.contentLengthBytes ?: JSONObject.NULL)
        put("fileSizeFormatted", v.fileSizeFormatted ?: JSONObject.NULL)
        put("suggestedFileName", v.suggestedFileName)
    }
}
