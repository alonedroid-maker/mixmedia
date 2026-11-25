package com.example.media

import com.squareup.moshi.Json

data class TmdbHealthResponse(
    val ok: Boolean = false,
    val service: String = "",
    val tmdbConfigured: Boolean = false,
    val authMode: String = "",
    val attribution: String = "",
)

data class TmdbPagedResponse(
    val page: Int = 1,
    val results: List<TmdbMediaItemDto> = emptyList(),
    @Json(name = "total_pages") val totalPages: Int = 1,
    @Json(name = "total_results") val totalResults: Int = 0,
)

data class TmdbMediaItemDto(
    val id: Int = 0,
    @Json(name = "media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    val overview: String? = null,
    @Json(name = "genre_ids") val genreIds: List<Int> = emptyList(),
    @Json(name = "known_for") val knownFor: List<TmdbMediaItemDto>? = null,
)

data class TmdbGenreDto(
    val id: Int = 0,
    val name: String = "",
)

data class TmdbGenreListResponse(
    val genres: List<TmdbGenreDto> = emptyList(),
)

data class TmdbCastDto(
    val name: String? = null,
    val character: String? = null,
)

data class TmdbCrewDto(
    val name: String? = null,
    val job: String? = null,
)

data class TmdbCreditsDto(
    val cast: List<TmdbCastDto> = emptyList(),
    val crew: List<TmdbCrewDto> = emptyList(),
)

data class TmdbExternalIdsDto(
    @Json(name = "imdb_id") val imdbId: String? = null,
)

data class TmdbReleaseDateEntryDto(
    val type: Int = -1,
    @Json(name = "release_date") val releaseDate: String? = null,
)

data class TmdbReleaseDateCountryDto(
    @Json(name = "iso_3166_1") val iso31661: String? = null,
    @Json(name = "release_dates") val releaseDates: List<TmdbReleaseDateEntryDto> = emptyList(),
)

data class TmdbReleaseDatesContainerDto(
    val results: List<TmdbReleaseDateCountryDto> = emptyList(),
)

data class TmdbWatchProviderItemDto(
    @Json(name = "provider_name") val providerName: String? = null,
    @Json(name = "logo_path") val logoPath: String? = null,
)

data class TmdbWatchProviderRegionDto(
    val link: String? = null,
    val flatrate: List<TmdbWatchProviderItemDto> = emptyList(),
    val free: List<TmdbWatchProviderItemDto> = emptyList(),
    val ads: List<TmdbWatchProviderItemDto> = emptyList(),
    val rent: List<TmdbWatchProviderItemDto> = emptyList(),
    val buy: List<TmdbWatchProviderItemDto> = emptyList(),
)

data class TmdbWatchProvidersResponseDto(
    val id: Int? = null,
    val region: String? = null,
    val availableRegions: List<String> = emptyList(),
    val attribution: String? = null,
    val results: Map<String, TmdbWatchProviderRegionDto> = emptyMap(),
)

data class TmdbProductionCountryDto(
    val name: String? = null,
)

data class TmdbCreatorDto(
    val name: String? = null,
)

data class TmdbSeasonSummaryDto(
    @Json(name = "season_number") val seasonNumber: Int = -1,
    val name: String? = null,
    @Json(name = "episode_count") val episodeCount: Int = 0,
    @Json(name = "air_date") val airDate: String? = null,
)

data class TmdbEpisodeMarkerDto(
    @Json(name = "episode_number") val episodeNumber: Int? = null,
)

data class TmdbMovieDetailsDto(
    val id: Int = 0,
    val title: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    val genres: List<TmdbGenreDto> = emptyList(),
    val runtime: Int? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    val overview: String? = null,
    val status: String? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "production_countries") val productionCountries: List<TmdbProductionCountryDto> = emptyList(),
    val credits: TmdbCreditsDto? = null,
    @Json(name = "external_ids") val externalIds: TmdbExternalIdsDto? = null,
    @Json(name = "release_dates") val releaseDates: TmdbReleaseDatesContainerDto? = null,
    @Json(name = "watch/providers") val watchProviders: TmdbWatchProvidersResponseDto? = null,
)

data class TmdbTvDetailsDto(
    val id: Int = 0,
    val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "last_air_date") val lastAirDate: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    val genres: List<TmdbGenreDto> = emptyList(),
    @Json(name = "episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @Json(name = "vote_average") val voteAverage: Double? = null,
    val overview: String? = null,
    val status: String? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "production_countries") val productionCountries: List<TmdbProductionCountryDto> = emptyList(),
    @Json(name = "created_by") val createdBy: List<TmdbCreatorDto> = emptyList(),
    @Json(name = "number_of_seasons") val numberOfSeasons: Int? = null,
    @Json(name = "number_of_episodes") val numberOfEpisodes: Int? = null,
    @Json(name = "in_production") val inProduction: Boolean = false,
    @Json(name = "next_episode_to_air") val nextEpisodeToAir: TmdbEpisodeMarkerDto? = null,
    @Json(name = "last_episode_to_air") val lastEpisodeToAir: TmdbEpisodeMarkerDto? = null,
    val seasons: List<TmdbSeasonSummaryDto> = emptyList(),
    val credits: TmdbCreditsDto? = null,
    @Json(name = "external_ids") val externalIds: TmdbExternalIdsDto? = null,
    @Json(name = "watch/providers") val watchProviders: TmdbWatchProvidersResponseDto? = null,
)

data class TmdbEpisodeDto(
    @Json(name = "episode_number") val episodeNumber: Int = 0,
    val name: String? = null,
    @Json(name = "air_date") val airDate: String? = null,
    val runtime: Int? = null,
    val overview: String? = null,
)

data class TmdbSeasonDetailsDto(
    val episodes: List<TmdbEpisodeDto> = emptyList(),
)

data class TmdbFindResponseDto(
    @Json(name = "movie_results") val movieResults: List<TmdbMediaItemDto> = emptyList(),
    @Json(name = "tv_results") val tvResults: List<TmdbMediaItemDto> = emptyList(),
)
