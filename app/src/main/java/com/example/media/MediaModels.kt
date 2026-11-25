package com.example.media

enum class MediaType {
    MOVIE,
    TV,
}

enum class SearchLanguagePreference(val code: String) {
    EN("en"),
    FA("fa"),
    BOTH("both");

    companion object {
        fun fromCode(code: String?): SearchLanguagePreference =
            entries.firstOrNull { it.code == code } ?: BOTH
    }
}

enum class SearchEngineOption(val code: String, val displayName: String) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo"),
    GOOGLE("google", "Google"),
    BING("bing", "Bing");

    companion object {
        fun fromCode(code: String?): SearchEngineOption =
            entries.firstOrNull { it.code == code } ?: DUCKDUCKGO
    }
}

enum class SourceLinkStatus {
    VERIFIED_ACCESSIBLE,
    SEARCH_RESULT,
    WEBSITE_PAGE,
    NOT_CHECKED,
    UNAVAILABLE,
    UNKNOWN,
}

enum class DigitalReleaseStatus {
    CONFIRMED_DIGITAL,
    THEATRICAL_ONLY_OR_UNCONFIRMED,
    UNKNOWN,
}

enum class TvReleaseStatus {
    CURRENTLY_AIRING,
    SEASON_COMPLETED,
    UPCOMING,
    ENDED,
    UNKNOWN,
}

enum class ProviderCategory {
    STREAMING,
    RENTAL_PURCHASE,
    FREE_AD_SUPPORTED,
    OFFICIAL_PAGE,
}

data class WatchProviderInfo(
    val name: String,
    val category: ProviderCategory,
    val logoUrl: String? = null,
    val url: String? = null,
    val region: String = "US",
)

data class MediaSearchResult(
    val id: String,
    val mediaType: MediaType,
    val title: String,
    val persianTitle: String? = null,
    val originalTitle: String? = null,
    val releaseYear: Int? = null,
    val posterUrl: String? = null,
    val runtimeMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val topCast: List<String> = emptyList(),
    val imdbRating: Double? = null,
    val overview: String? = null,
    val persianOverview: String? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val tvMazeId: Int? = null,
    val wikidataId: String? = null,
    val relevanceScore: Double = 0.0,
    val digitalReleaseStatus: DigitalReleaseStatus = DigitalReleaseStatus.UNKNOWN,
    val digitalReleaseDate: String? = null,
    val tvReleaseStatus: TvReleaseStatus = TvReleaseStatus.UNKNOWN,
    val seasonCount: Int? = null,
    val watchProviders: List<WatchProviderInfo> = emptyList(),
    val watchProvidersPageUrl: String? = null,
    val authorizedDirectDownloadUrl: String? = null,
)

data class TrendingFeedBundle(
    val movies: List<MediaSearchResult> = emptyList(),
    val tvShows: List<MediaSearchResult> = emptyList(),
    val lastUpdatedMillis: Long = 0L,
    val isUsingFallbackSources: Boolean = false,
    val attribution: String = "",
)

data class CastMember(
    val name: String,
    val character: String? = null,
)

data class EpisodeInfo(
    val episodeNumber: Int,
    val seasonNumber: Int,
    val title: String,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null,
    val overview: String? = null,
)

data class SeasonInfo(
    val seasonNumber: Int,
    val title: String,
    val episodeCount: Int,
    val airDate: String? = null,
    val episodes: List<EpisodeInfo> = emptyList(),
)

data class MediaDetails(
    val id: String,
    val mediaType: MediaType,
    val title: String,
    val persianTitle: String? = null,
    val originalTitle: String? = null,
    val releaseYear: Int? = null,
    val releaseDate: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val runtimeMinutes: Int? = null,
    val episodeRuntimeMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val creators: List<String> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val imdbRating: Double? = null,
    val overview: String? = null,
    val persianOverview: String? = null,
    val country: String? = null,
    val originalLanguage: String? = null,
    val status: String? = null,
    val startYear: Int? = null,
    val endYear: Int? = null,
    val totalSeasons: Int? = null,
    val totalEpisodes: Int? = null,
    val seasons: List<SeasonInfo> = emptyList(),
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val tvMazeId: Int? = null,
    val wikidataId: String? = null,
    val imdbUrl: String? = null,
    val tmdbUrl: String? = null,
    val wikipediaUrl: String? = null,
    val sourceAttribution: String = "",
    val digitalReleaseStatus: DigitalReleaseStatus = DigitalReleaseStatus.UNKNOWN,
    val digitalReleaseDate: String? = null,
    val tvReleaseStatus: TvReleaseStatus = TvReleaseStatus.UNKNOWN,
    val watchProviders: List<WatchProviderInfo> = emptyList(),
    val watchProvidersPageUrl: String? = null,
    val authorizedDirectDownloadUrl: String? = null,
) {
    fun toSummary(): MediaSearchResult = MediaSearchResult(
        id = id,
        mediaType = mediaType,
        title = title,
        persianTitle = persianTitle,
        originalTitle = originalTitle,
        releaseYear = releaseYear ?: startYear,
        posterUrl = posterUrl,
        runtimeMinutes = runtimeMinutes ?: episodeRuntimeMinutes,
        genres = genres,
        directors = if (directors.isNotEmpty()) directors else creators,
        topCast = cast.take(3).map { it.name },
        imdbRating = imdbRating,
        overview = overview,
        persianOverview = persianOverview,
        imdbId = imdbId,
        tmdbId = tmdbId,
        tvMazeId = tvMazeId,
        wikidataId = wikidataId,
        digitalReleaseStatus = digitalReleaseStatus,
        digitalReleaseDate = digitalReleaseDate,
        tvReleaseStatus = tvReleaseStatus,
        seasonCount = totalSeasons ?: seasons.size.takeIf { it > 0 },
        watchProviders = watchProviders,
        watchProvidersPageUrl = watchProvidersPageUrl,
        authorizedDirectDownloadUrl = authorizedDirectDownloadUrl,
    )
}

data class SourceSearchTarget(
    val mediaId: String,
    val mediaType: MediaType,
    val title: String,
    val persianTitle: String? = null,
    val releaseYear: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
)

data class SourceSiteConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val searchUrlTemplate: String,
    val isArchive: Boolean = false,
    val enabled: Boolean = true,
    val isCustom: Boolean = false,
    val requiresDomesticIpHint: Boolean = false,
)

data class WebSourceResult(
    val id: String,
    val title: String,
    val url: String,
    val domain: String,
    val siteName: String,
    val snippet: String = "",
    val status: SourceLinkStatus = SourceLinkStatus.SEARCH_RESULT,
    val statusNote: String? = null,
    val isDirectDownloadCandidate: Boolean = false,
    val detectedExtension: String? = null,
)

data class SourceDiscoveryBundle(
    val target: SourceSearchTarget,
    val generatedQueries: List<String>,
    val searchEngineLaunchUrls: List<Pair<SearchEngineOption, String>>,
    val siteResults: List<WebSourceResult>,
    val webSearchResults: List<WebSourceResult>,
    val siteNotices: List<String> = emptyList(),
)

data class SavedSourcePage(
    val url: String,
    val title: String,
    val domain: String,
    val mediaTitle: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

data class MediaPreferences(
    val tmdbApiKey: String = "",
    val omdbApiKey: String = "",
    val youtubeApiKey: String = "",
    val searchLanguage: SearchLanguagePreference = SearchLanguagePreference.BOTH,
    val searchEngine: SearchEngineOption = SearchEngineOption.DUCKDUCKGO,
    val preferInternalBrowser: Boolean = true,
    val downloadNotificationsEnabled: Boolean = true,
)

data class ArchiveEntry(
    val title: String,
    val normalizedTitle: String,
    val url: String,
)
