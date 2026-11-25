package com.example.media

import java.util.Locale

internal object TmdbDtoMappers {

    private const val IMAGE_POSTER_SMALL = "https://image.tmdb.org/t/p/w342"
    private const val IMAGE_POSTER_LARGE = "https://image.tmdb.org/t/p/w500"
    private const val IMAGE_BACKDROP = "https://image.tmdb.org/t/p/w780"
    private const val IMAGE_LOGO = "https://image.tmdb.org/t/p/w92"

    fun mapSearchResponse(
        dto: TmdbPagedResponse,
        forcedType: MediaType? = null,
    ): List<MediaSearchResult> {
        val out = mutableListOf<MediaSearchResult>()
        for (item in dto.results) {
            if (item.id <= 0) continue
            val resolvedType = when (forcedType ?: when (item.mediaType?.lowercase(Locale.US)) {
                "movie" -> MediaType.MOVIE
                "tv" -> MediaType.TV
                "person" -> null
                else -> null
            }) {
                MediaType.MOVIE -> MediaType.MOVIE
                MediaType.TV -> MediaType.TV
                null -> {
                    val personWorks = item.knownFor.orEmpty()
                    val personName = item.name?.takeIf { it.isNotBlank() }
                    for (work in personWorks) {
                        if (work.id <= 0) continue
                        val workType = when (work.mediaType?.lowercase(Locale.US)) {
                            "movie" -> MediaType.MOVIE
                            "tv" -> MediaType.TV
                            else -> continue
                        }
                        mapItem(work, workType, extraCast = listOfNotNull(personName))?.let { out.add(it) }
                    }
                    continue
                }
            }
            mapItem(item, resolvedType)?.let { out.add(it) }
        }
        return out
    }

    fun mapItem(
        item: TmdbMediaItemDto,
        type: MediaType,
        extraCast: List<String> = emptyList(),
    ): MediaSearchResult? {
        if (item.id <= 0) return null
        val rawTitle = (if (type == MediaType.TV) item.name else item.title)?.takeIf { it.isNotBlank() }
            ?: (if (type == MediaType.TV) item.title else item.name)?.takeIf { it.isNotBlank() }
            ?: return null
        val rawOrig = (if (type == MediaType.TV) item.originalName else item.originalTitle)?.takeIf { it.isNotBlank() }
        val dateStr = if (type == MediaType.TV) item.firstAirDate else item.releaseDate
        val year = dateStr?.take(4)?.toIntOrNull()
        val poster = item.posterPath?.takeIf { it.startsWith("/") }?.let { "$IMAGE_POSTER_SMALL$it" }
        val rating = item.voteAverage?.takeIf { !it.isNaN() && it > 0.0 }
        val overview = item.overview?.takeIf { it.isNotBlank() }
        val isFa = FuzzyTitleMatcher.containsPersian(rawTitle)

        return MediaSearchResult(
            id = "tmdb:${type.name.lowercase(Locale.US)}:${item.id}",
            mediaType = type,
            title = if (isFa && !rawOrig.isNullOrBlank()) rawOrig else rawTitle,
            persianTitle = if (isFa) rawTitle else null,
            originalTitle = rawOrig,
            releaseYear = year,
            posterUrl = poster,
            topCast = extraCast,
            imdbRating = rating,
            overview = if (!isFa) overview else null,
            persianOverview = if (isFa) overview else null,
            tmdbId = item.id,
        )
    }

    fun mapTrendingMovieDetail(
        en: TmdbMovieDetailsDto,
        fa: TmdbMovieDetailsDto?,
        todayIso: String,
        preferredRegion: String,
    ): MediaSearchResult? {
        if (en.id <= 0) return null
        val title = en.title?.takeIf { it.isNotBlank() } ?: return null
        val origTitle = en.originalTitle?.takeIf { it.isNotBlank() }
        val faTitle = fa?.title?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        val (digitalDate, hasConfirmedDigitalDate) = extractConfirmedDigitalRelease(en.releaseDates, todayIso)
        val (providers, providersPage) = mapWatchProviders(en.watchProviders, preferredRegion)

        if (!hasConfirmedDigitalDate && providers.isEmpty()) {
            return null
        }

        val releaseDate = en.releaseDate.orEmpty()
        val poster = en.posterPath?.takeIf { it.startsWith("/") }?.let { "$IMAGE_POSTER_SMALL$it" }
        val genres = en.genres.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }
        val directors = en.credits?.crew.orEmpty()
            .filter { it.job.equals("Director", ignoreCase = true) }
            .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
            .distinct()

        return MediaSearchResult(
            id = "tmdb:movie:${en.id}",
            mediaType = MediaType.MOVIE,
            title = title,
            persianTitle = faTitle,
            originalTitle = origTitle,
            releaseYear = releaseDate.take(4).toIntOrNull(),
            posterUrl = poster,
            runtimeMinutes = en.runtime?.takeIf { it > 0 },
            genres = genres,
            directors = directors,
            imdbRating = en.voteAverage?.takeIf { !it.isNaN() && it > 0.0 },
            overview = en.overview?.takeIf { it.isNotBlank() },
            persianOverview = fa?.overview?.takeIf { it.isNotBlank() },
            imdbId = en.externalIds?.imdbId?.takeIf { it.startsWith("tt") },
            tmdbId = en.id,
            digitalReleaseStatus = DigitalReleaseStatus.CONFIRMED_DIGITAL,
            digitalReleaseDate = digitalDate,
            watchProviders = providers,
            watchProvidersPageUrl = providersPage,
        )
    }

    fun mapTrendingTvDetail(
        en: TmdbTvDetailsDto,
        fa: TmdbTvDetailsDto?,
        preferredRegion: String,
    ): MediaSearchResult? {
        if (en.id <= 0) return null
        val title = en.name?.takeIf { it.isNotBlank() } ?: return null
        val origTitle = en.originalName?.takeIf { it.isNotBlank() }
        val faTitle = fa?.name?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        val (providers, providersPage) = mapWatchProviders(en.watchProviders, preferredRegion)
        val firstAir = en.firstAirDate.orEmpty()
        val poster = en.posterPath?.takeIf { it.startsWith("/") }?.let { "$IMAGE_POSTER_SMALL$it" }
        val genres = en.genres.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }
        val creators = en.createdBy.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }

        return MediaSearchResult(
            id = "tmdb:tv:${en.id}",
            mediaType = MediaType.TV,
            title = title,
            persianTitle = faTitle,
            originalTitle = origTitle,
            releaseYear = firstAir.take(4).toIntOrNull(),
            posterUrl = poster,
            runtimeMinutes = en.episodeRunTime.firstOrNull()?.takeIf { it > 0 },
            genres = genres,
            directors = creators,
            imdbRating = en.voteAverage?.takeIf { !it.isNaN() && it > 0.0 },
            overview = en.overview?.takeIf { it.isNotBlank() },
            persianOverview = fa?.overview?.takeIf { it.isNotBlank() },
            imdbId = en.externalIds?.imdbId?.takeIf { it.startsWith("tt") },
            tmdbId = en.id,
            tvReleaseStatus = mapTvStatus(en),
            seasonCount = en.numberOfSeasons?.takeIf { it > 0 },
            watchProviders = providers,
            watchProvidersPageUrl = providersPage,
        )
    }

    fun mapMovieDetails(
        en: TmdbMovieDetailsDto,
        fa: TmdbMovieDetailsDto?,
        todayIso: String,
        preferredRegion: String,
    ): MediaDetails? {
        val title = en.title?.takeIf { it.isNotBlank() } ?: return null
        val faTitle = fa?.title?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        val releaseDate = en.releaseDate.orEmpty()
        val (providers, providersPage) = mapWatchProviders(en.watchProviders, preferredRegion)
        val (digitalDate, hasDigitalDate) = extractConfirmedDigitalRelease(en.releaseDates, todayIso)
        val digitalStatus = when {
            hasDigitalDate || providers.isNotEmpty() -> DigitalReleaseStatus.CONFIRMED_DIGITAL
            releaseDate.isNotBlank() -> DigitalReleaseStatus.THEATRICAL_ONLY_OR_UNCONFIRMED
            else -> DigitalReleaseStatus.UNKNOWN
        }

        return MediaDetails(
            id = "tmdb:movie:${en.id}",
            mediaType = MediaType.MOVIE,
            title = title,
            persianTitle = faTitle,
            originalTitle = en.originalTitle?.takeIf { it.isNotBlank() },
            releaseYear = releaseDate.take(4).toIntOrNull(),
            releaseDate = releaseDate.takeIf { it.isNotBlank() },
            posterUrl = en.posterPath?.takeIf { it.startsWith("/") }?.let { "$IMAGE_POSTER_LARGE$it" },
            backdropUrl = en.backdropPath?.takeIf { it.startsWith("/") }?.let { "$IMAGE_BACKDROP$it" },
            runtimeMinutes = en.runtime?.takeIf { it > 0 },
            genres = en.genres.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } },
            directors = en.credits?.crew.orEmpty()
                .filter { it.job.equals("Director", ignoreCase = true) }
                .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                .distinct(),
            cast = en.credits?.cast.orEmpty().take(15).mapNotNull { c ->
                val name = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CastMember(name, c.character?.takeIf { it.isNotBlank() })
            },
            imdbRating = en.voteAverage?.takeIf { !it.isNaN() && it > 0.0 },
            overview = en.overview?.takeIf { it.isNotBlank() },
            persianOverview = fa?.overview?.takeIf { it.isNotBlank() },
            country = en.productionCountries.firstOrNull()?.name?.takeIf { it.isNotBlank() },
            originalLanguage = en.originalLanguage?.takeIf { it.isNotBlank() },
            status = en.status?.takeIf { it.isNotBlank() },
            startYear = releaseDate.take(4).toIntOrNull(),
            imdbId = en.externalIds?.imdbId?.takeIf { it.startsWith("tt") },
            tmdbId = en.id,
            digitalReleaseStatus = digitalStatus,
            digitalReleaseDate = digitalDate,
            watchProviders = providers,
            watchProvidersPageUrl = providersPage,
        )
    }

    fun mapTvDetails(
        en: TmdbTvDetailsDto,
        fa: TmdbTvDetailsDto?,
        preferredRegion: String,
    ): MediaDetails? {
        val title = en.name?.takeIf { it.isNotBlank() } ?: return null
        val faTitle = fa?.name?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        val firstAir = en.firstAirDate.orEmpty()
        val lastAir = en.lastAirDate.orEmpty()
        val (providers, providersPage) = mapWatchProviders(en.watchProviders, preferredRegion)

        val seasons = en.seasons.filter { it.seasonNumber > 0 }.map { s ->
            SeasonInfo(
                seasonNumber = s.seasonNumber,
                title = s.name?.takeIf { it.isNotBlank() } ?: "Season ${s.seasonNumber}",
                episodeCount = s.episodeCount,
                airDate = s.airDate?.takeIf { it.isNotBlank() },
            )
        }

        return MediaDetails(
            id = "tmdb:tv:${en.id}",
            mediaType = MediaType.TV,
            title = title,
            persianTitle = faTitle,
            originalTitle = en.originalName?.takeIf { it.isNotBlank() },
            releaseYear = firstAir.take(4).toIntOrNull(),
            releaseDate = firstAir.takeIf { it.isNotBlank() },
            posterUrl = en.posterPath?.takeIf { it.startsWith("/") }?.let { "$IMAGE_POSTER_LARGE$it" },
            backdropUrl = en.backdropPath?.takeIf { it.startsWith("/") }?.let { "$IMAGE_BACKDROP$it" },
            episodeRuntimeMinutes = en.episodeRunTime.firstOrNull()?.takeIf { it > 0 },
            genres = en.genres.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } },
            creators = en.createdBy.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } },
            cast = en.credits?.cast.orEmpty().take(15).mapNotNull { c ->
                val name = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CastMember(name, c.character?.takeIf { it.isNotBlank() })
            },
            imdbRating = en.voteAverage?.takeIf { !it.isNaN() && it > 0.0 },
            overview = en.overview?.takeIf { it.isNotBlank() },
            persianOverview = fa?.overview?.takeIf { it.isNotBlank() },
            country = en.productionCountries.firstOrNull()?.name?.takeIf { it.isNotBlank() },
            originalLanguage = en.originalLanguage?.takeIf { it.isNotBlank() },
            status = en.status?.takeIf { it.isNotBlank() },
            startYear = firstAir.take(4).toIntOrNull(),
            endYear = lastAir.take(4).toIntOrNull(),
            totalSeasons = en.numberOfSeasons?.takeIf { it > 0 },
            totalEpisodes = en.numberOfEpisodes?.takeIf { it > 0 },
            seasons = seasons,
            imdbId = en.externalIds?.imdbId?.takeIf { it.startsWith("tt") },
            tmdbId = en.id,
            tvReleaseStatus = mapTvStatus(en),
            watchProviders = providers,
            watchProvidersPageUrl = providersPage,
        )
    }

    fun mapWatchProviders(
        dto: TmdbWatchProvidersResponseDto?,
        preferredRegion: String,
        strictRegion: Boolean = false,
    ): Pair<List<WatchProviderInfo>, String?> {
        val results = dto?.results ?: return emptyList<WatchProviderInfo>() to null
        val normalizedPref = WatchRegions.normalize(preferredRegion)
        val regionOrder = if (strictRegion) {
            listOf(normalizedPref)
        } else {
            (listOf(normalizedPref, "US", "GB", "CA", "DE", "FR") + results.keys).distinct()
        }

        var chosenCode: String? = null
        var chosenRegion: TmdbWatchProviderRegionDto? = null
        for (code in regionOrder) {
            val candidate = results[code]
            if (candidate != null && hasAnyProvider(candidate)) {
                chosenCode = code
                chosenRegion = candidate
                break
            }
        }
        if (chosenCode == null || chosenRegion == null) {
            return emptyList<WatchProviderInfo>() to null
        }

        val pageUrl = chosenRegion.link?.takeIf { FuzzyTitleMatcher.isSafeHttpUrl(it) }
        val collected = mutableListOf<WatchProviderInfo>()
        val seen = HashSet<String>()

        fun collect(items: List<TmdbWatchProviderItemDto>, category: ProviderCategory) {
            for (item in items) {
                val name = item.providerName?.takeIf { it.isNotBlank() } ?: continue
                val dedup = "${name.lowercase(Locale.US)}:${category.name}"
                if (!seen.add(dedup)) continue
                val logo = item.logoPath?.takeIf { it.startsWith("/") }?.let { "$IMAGE_LOGO$it" }
                collected.add(
                    WatchProviderInfo(
                        name = name,
                        category = category,
                        logoUrl = logo,
                        url = pageUrl,
                        region = chosenCode,
                    )
                )
            }
        }

        collect(chosenRegion.flatrate, ProviderCategory.STREAMING)
        collect(chosenRegion.free, ProviderCategory.FREE_AD_SUPPORTED)
        collect(chosenRegion.ads, ProviderCategory.FREE_AD_SUPPORTED)
        collect(chosenRegion.rent, ProviderCategory.RENTAL_PURCHASE)
        collect(chosenRegion.buy, ProviderCategory.RENTAL_PURCHASE)

        return collected.take(10) to pageUrl
    }

    private fun hasAnyProvider(region: TmdbWatchProviderRegionDto): Boolean =
        region.flatrate.isNotEmpty() ||
            region.free.isNotEmpty() ||
            region.ads.isNotEmpty() ||
            region.rent.isNotEmpty() ||
            region.buy.isNotEmpty()

    private fun extractConfirmedDigitalRelease(
        container: TmdbReleaseDatesContainerDto?,
        todayIso: String,
    ): Pair<String?, Boolean> {
        var earliest: String? = null
        for (country in container?.results.orEmpty()) {
            for (entry in country.releaseDates) {
                if (entry.type !in 4..6) continue
                val dateIso = entry.releaseDate?.take(10).orEmpty()
                if (dateIso.length != 10 || dateIso > todayIso) continue
                if (earliest == null || dateIso < earliest) {
                    earliest = dateIso
                }
            }
        }
        return earliest to (earliest != null)
    }

    private fun mapTvStatus(dto: TmdbTvDetailsDto): TvReleaseStatus {
        val status = dto.status.orEmpty()
        return when {
            status.equals("Ended", ignoreCase = true) ||
                status.equals("Canceled", ignoreCase = true) -> TvReleaseStatus.ENDED
            status.equals("In Production", ignoreCase = true) ||
                status.equals("Planned", ignoreCase = true) -> TvReleaseStatus.UPCOMING
            status.equals("Returning Series", ignoreCase = true) -> when {
                dto.nextEpisodeToAir != null -> TvReleaseStatus.CURRENTLY_AIRING
                dto.lastEpisodeToAir != null -> TvReleaseStatus.SEASON_COMPLETED
                dto.inProduction -> TvReleaseStatus.CURRENTLY_AIRING
                else -> TvReleaseStatus.SEASON_COMPLETED
            }
            else -> TvReleaseStatus.UNKNOWN
        }
    }
}
