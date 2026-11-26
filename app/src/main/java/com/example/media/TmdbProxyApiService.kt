package com.example.media

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit service talking to an optional Cloudflare Worker proxy backend.
 */
interface TmdbProxyApiService {

    @GET("health")
    suspend fun getHealth(): Response<TmdbHealthResponse>

    @GET("v1/trending/movie/{window}")
    suspend fun getTrendingMovies(
        @Path("window") window: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("region") region: String? = null,
    ): Response<TmdbPagedResponse>

    @GET("v1/trending/tv/{window}")
    suspend fun getTrendingTv(
        @Path("window") window: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("region") region: String? = null,
    ): Response<TmdbPagedResponse>

    @GET("v1/movie/{movieId}")
    suspend fun getMovieDetails(
        @Path("movieId") movieId: Int,
        @Query("language") language: String = "en-US",
        @Query("append_to_response") appendToResponse: String? = "credits,external_ids,release_dates,watch/providers",
    ): Response<TmdbMovieDetailsDto>

    @GET("v1/tv/{tvId}")
    suspend fun getTvDetails(
        @Path("tvId") tvId: Int,
        @Query("language") language: String = "en-US",
        @Query("append_to_response") appendToResponse: String? = "credits,external_ids,watch/providers",
    ): Response<TmdbTvDetailsDto>

    @GET("v1/tv/{tvId}/season/{seasonNumber}")
    suspend fun getTvSeasonDetails(
        @Path("tvId") tvId: Int,
        @Path("seasonNumber") seasonNumber: Int,
        @Query("language") language: String = "en-US",
    ): Response<TmdbSeasonDetailsDto>

    @GET("v1/movie/{movieId}/watch/providers")
    suspend fun getMovieWatchProviders(
        @Path("movieId") movieId: Int,
        @Query("region") region: String? = null,
    ): Response<TmdbWatchProvidersResponseDto>

    @GET("v1/tv/{tvId}/watch/providers")
    suspend fun getTvWatchProviders(
        @Path("tvId") tvId: Int,
        @Query("region") region: String? = null,
    ): Response<TmdbWatchProvidersResponseDto>

    @GET("v1/search/movie")
    suspend fun searchMovies(
        @Query("query") query: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("year") year: Int? = null,
    ): Response<TmdbPagedResponse>

    @GET("v1/search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("year") year: Int? = null,
    ): Response<TmdbPagedResponse>

    @GET("v1/search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
    ): Response<TmdbPagedResponse>

    @GET("v1/discover/movie")
    suspend fun discoverMovies(
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("with_genres") withGenres: String? = null,
        @Query("vote_average_gte") minRating: Double? = null,
        @Query("year") year: Int? = null,
        @Query("region") region: String? = null,
        @Query("with_release_type") releaseType: String? = null,
    ): Response<TmdbPagedResponse>

    @GET("v1/discover/tv")
    suspend fun discoverTv(
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("with_genres") withGenres: String? = null,
        @Query("vote_average_gte") minRating: Double? = null,
        @Query("year") year: Int? = null,
        @Query("region") region: String? = null,
    ): Response<TmdbPagedResponse>

    @GET("v1/genres/movie")
    suspend fun getMovieGenres(
        @Query("language") language: String = "en-US",
    ): Response<TmdbGenreListResponse>

    @GET("v1/genres/tv")
    suspend fun getTvGenres(
        @Query("language") language: String = "en-US",
    ): Response<TmdbGenreListResponse>

    @GET("v1/find/{imdbId}")
    suspend fun findByImdbId(
        @Path("imdbId") imdbId: String,
        @Query("language") language: String = "en-US",
    ): Response<TmdbFindResponseDto>
}

/**
 * Direct TMDB REST API v3 Retrofit service (https://api.themoviedb.org/3/).
 * Auth (Bearer token or api_key query) is attached exclusively by an isolated OkHttp interceptor
 * from build-time GitHub Secrets via [TmdbCredentialProvider].
 */
interface TmdbDirectApiService {

    @GET("configuration")
    suspend fun getConfiguration(): Response<ResponseBody>

    @GET("trending/movie/{window}")
    suspend fun getTrendingMovies(
        @Path("window") window: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("region") region: String? = null,
    ): Response<TmdbPagedResponse>

    @GET("trending/tv/{window}")
    suspend fun getTrendingTv(
        @Path("window") window: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("region") region: String? = null,
    ): Response<TmdbPagedResponse>

    @GET("movie/{movieId}")
    suspend fun getMovieDetails(
        @Path("movieId") movieId: Int,
        @Query("language") language: String = "en-US",
        @Query("append_to_response") appendToResponse: String? = "credits,external_ids,release_dates,watch/providers",
    ): Response<TmdbMovieDetailsDto>

    @GET("tv/{tvId}")
    suspend fun getTvDetails(
        @Path("tvId") tvId: Int,
        @Query("language") language: String = "en-US",
        @Query("append_to_response") appendToResponse: String? = "credits,external_ids,watch/providers",
    ): Response<TmdbTvDetailsDto>

    @GET("tv/{tvId}/season/{seasonNumber}")
    suspend fun getTvSeasonDetails(
        @Path("tvId") tvId: Int,
        @Path("seasonNumber") seasonNumber: Int,
        @Query("language") language: String = "en-US",
    ): Response<TmdbSeasonDetailsDto>

    @GET("movie/{movieId}/watch/providers")
    suspend fun getMovieWatchProviders(
        @Path("movieId") movieId: Int,
    ): Response<TmdbWatchProvidersResponseDto>

    @GET("tv/{tvId}/watch/providers")
    suspend fun getTvWatchProviders(
        @Path("tvId") tvId: Int,
    ): Response<TmdbWatchProvidersResponseDto>

    @GET("search/movie")
    suspend fun searchMovies(
        @Query("query") query: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("year") year: Int? = null,
    ): Response<TmdbPagedResponse>

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("year") year: Int? = null,
    ): Response<TmdbPagedResponse>

    @GET("search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
    ): Response<TmdbPagedResponse>

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("with_genres") withGenres: String? = null,
        @Query("vote_average.gte") minRating: Double? = null,
        @Query("year") year: Int? = null,
        @Query("region") region: String? = null,
        @Query("with_release_type") releaseType: String? = null,
    ): Response<TmdbPagedResponse>

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("with_genres") withGenres: String? = null,
        @Query("vote_average.gte") minRating: Double? = null,
        @Query("first_air_date_year") year: Int? = null,
        @Query("watch_region") region: String? = null,
    ): Response<TmdbPagedResponse>

    @GET("genre/movie/list")
    suspend fun getMovieGenres(
        @Query("language") language: String = "en-US",
    ): Response<TmdbGenreListResponse>

    @GET("genre/tv/list")
    suspend fun getTvGenres(
        @Query("language") language: String = "en-US",
    ): Response<TmdbGenreListResponse>

    @GET("find/{imdbId}")
    suspend fun findByImdbId(
        @Path("imdbId") imdbId: String,
        @Query("external_source") externalSource: String = "imdb_id",
        @Query("language") language: String = "en-US",
    ): Response<TmdbFindResponseDto>
}

internal interface TmdbBackendClient {
    val isDirectTmdb: Boolean
    suspend fun getTrendingMovies(window: String, language: String = "en-US", page: Int = 1, region: String? = null): Response<TmdbPagedResponse>
    suspend fun getTrendingTv(window: String, language: String = "en-US", page: Int = 1, region: String? = null): Response<TmdbPagedResponse>
    suspend fun getMovieDetails(movieId: Int, language: String = "en-US", appendToResponse: String? = "credits,external_ids,release_dates,watch/providers"): Response<TmdbMovieDetailsDto>
    suspend fun getTvDetails(tvId: Int, language: String = "en-US", appendToResponse: String? = "credits,external_ids,watch/providers"): Response<TmdbTvDetailsDto>
    suspend fun getTvSeasonDetails(tvId: Int, seasonNumber: Int, language: String = "en-US"): Response<TmdbSeasonDetailsDto>
    suspend fun getMovieWatchProviders(movieId: Int, region: String? = null): Response<TmdbWatchProvidersResponseDto>
    suspend fun getTvWatchProviders(tvId: Int, region: String? = null): Response<TmdbWatchProvidersResponseDto>
    suspend fun searchMovies(query: String, language: String = "en-US", page: Int = 1, year: Int? = null): Response<TmdbPagedResponse>
    suspend fun searchTv(query: String, language: String = "en-US", page: Int = 1, year: Int? = null): Response<TmdbPagedResponse>
    suspend fun searchMulti(query: String, language: String = "en-US", page: Int = 1): Response<TmdbPagedResponse>
    suspend fun discoverMovies(language: String = "en-US", page: Int = 1, sortBy: String = "popularity.desc", withGenres: String? = null, minRating: Double? = null, year: Int? = null, region: String? = null, releaseType: String? = null): Response<TmdbPagedResponse>
    suspend fun discoverTv(language: String = "en-US", page: Int = 1, sortBy: String = "popularity.desc", withGenres: String? = null, minRating: Double? = null, year: Int? = null, region: String? = null): Response<TmdbPagedResponse>
    suspend fun getMovieGenres(language: String = "en-US"): Response<TmdbGenreListResponse>
    suspend fun getTvGenres(language: String = "en-US"): Response<TmdbGenreListResponse>
    suspend fun findByImdbId(imdbId: String, language: String = "en-US"): Response<TmdbFindResponseDto>
}

internal class WorkerTmdbBackendClient(
    private val service: TmdbProxyApiService,
) : TmdbBackendClient {
    override val isDirectTmdb: Boolean = false
    override suspend fun getTrendingMovies(window: String, language: String, page: Int, region: String?) =
        service.getTrendingMovies(window, language, page, region)
    override suspend fun getTrendingTv(window: String, language: String, page: Int, region: String?) =
        service.getTrendingTv(window, language, page, region)
    override suspend fun getMovieDetails(movieId: Int, language: String, appendToResponse: String?) =
        service.getMovieDetails(movieId, language, appendToResponse)
    override suspend fun getTvDetails(tvId: Int, language: String, appendToResponse: String?) =
        service.getTvDetails(tvId, language, appendToResponse)
    override suspend fun getTvSeasonDetails(tvId: Int, seasonNumber: Int, language: String) =
        service.getTvSeasonDetails(tvId, seasonNumber, language)
    override suspend fun getMovieWatchProviders(movieId: Int, region: String?) =
        service.getMovieWatchProviders(movieId, region)
    override suspend fun getTvWatchProviders(tvId: Int, region: String?) =
        service.getTvWatchProviders(tvId, region)
    override suspend fun searchMovies(query: String, language: String, page: Int, year: Int?) =
        service.searchMovies(query, language, page, year)
    override suspend fun searchTv(query: String, language: String, page: Int, year: Int?) =
        service.searchTv(query, language, page, year)
    override suspend fun searchMulti(query: String, language: String, page: Int) =
        service.searchMulti(query, language, page)
    override suspend fun discoverMovies(language: String, page: Int, sortBy: String, withGenres: String?, minRating: Double?, year: Int?, region: String?, releaseType: String?) =
        service.discoverMovies(language, page, sortBy, withGenres, minRating, year, region, releaseType)
    override suspend fun discoverTv(language: String, page: Int, sortBy: String, withGenres: String?, minRating: Double?, year: Int?, region: String?) =
        service.discoverTv(language, page, sortBy, withGenres, minRating, year, region)
    override suspend fun getMovieGenres(language: String) = service.getMovieGenres(language)
    override suspend fun getTvGenres(language: String) = service.getTvGenres(language)
    override suspend fun findByImdbId(imdbId: String, language: String) = service.findByImdbId(imdbId, language)
}

internal class DirectTmdbBackendClient(
    private val service: TmdbDirectApiService,
) : TmdbBackendClient {
    override val isDirectTmdb: Boolean = true
    override suspend fun getTrendingMovies(window: String, language: String, page: Int, region: String?) =
        service.getTrendingMovies(window, language, page, region)
    override suspend fun getTrendingTv(window: String, language: String, page: Int, region: String?) =
        service.getTrendingTv(window, language, page, region)
    override suspend fun getMovieDetails(movieId: Int, language: String, appendToResponse: String?) =
        service.getMovieDetails(movieId, language, appendToResponse)
    override suspend fun getTvDetails(tvId: Int, language: String, appendToResponse: String?) =
        service.getTvDetails(tvId, language, appendToResponse)
    override suspend fun getTvSeasonDetails(tvId: Int, seasonNumber: Int, language: String) =
        service.getTvSeasonDetails(tvId, seasonNumber, language)
    override suspend fun getMovieWatchProviders(movieId: Int, region: String?) =
        service.getMovieWatchProviders(movieId)
    override suspend fun getTvWatchProviders(tvId: Int, region: String?) =
        service.getTvWatchProviders(tvId)
    override suspend fun searchMovies(query: String, language: String, page: Int, year: Int?) =
        service.searchMovies(query, language, page, year)
    override suspend fun searchTv(query: String, language: String, page: Int, year: Int?) =
        service.searchTv(query, language, page, year)
    override suspend fun searchMulti(query: String, language: String, page: Int) =
        service.searchMulti(query, language, page)
    override suspend fun discoverMovies(language: String, page: Int, sortBy: String, withGenres: String?, minRating: Double?, year: Int?, region: String?, releaseType: String?) =
        service.discoverMovies(language, page, sortBy, withGenres, minRating, year, region, releaseType)
    override suspend fun discoverTv(language: String, page: Int, sortBy: String, withGenres: String?, minRating: Double?, year: Int?, region: String?) =
        service.discoverTv(language, page, sortBy, withGenres, minRating, year, region)
    override suspend fun getMovieGenres(language: String) = service.getMovieGenres(language)
    override suspend fun getTvGenres(language: String) = service.getTvGenres(language)
    override suspend fun findByImdbId(imdbId: String, language: String) =
        service.findByImdbId(imdbId, externalSource = "imdb_id", language = language)
}

object TmdbProxyClientFactory {

    const val TMDB_V3_BASE_URL = "https://api.themoviedb.org/3/"

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    fun normalizeProxyBaseUrl(rawUrl: String?, allowHttpForLocalhost: Boolean = false): String? {
        val trimmed = rawUrl?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed.contains('?') || trimmed.contains('#') || trimmed.contains('@')) return null
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return null
        if (httpUrl.querySize > 0 || httpUrl.fragment != null) return null
        if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return null
        val hostLower = httpUrl.host.lowercase()
        if (hostLower == "themoviedb.org" || hostLower.endsWith(".themoviedb.org")) return null
        val isHttps = httpUrl.scheme.equals("https", ignoreCase = true)
        val isLocalTestHttp = allowHttpForLocalhost &&
            httpUrl.scheme.equals("http", ignoreCase = true) &&
            (hostLower == "127.0.0.1" || hostLower == "localhost")
        if (!isHttps && !isLocalTestHttp) return null
        val cleanBase = httpUrl.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
        return "$cleanBase/"
    }

    fun create(
        baseUrl: String,
        client: OkHttpClient,
        allowHttpForLocalhost: Boolean = false,
    ): TmdbProxyApiService? {
        val normalized = normalizeProxyBaseUrl(baseUrl, allowHttpForLocalhost) ?: return null
        return try {
            Retrofit.Builder()
                .baseUrl(normalized)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(TmdbProxyApiService::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun createDirect(
        baseClient: OkHttpClient,
        credentialProvider: TmdbCredentialProvider,
    ): TmdbDirectApiService? {
        if (!credentialProvider.hasDirectCredentials()) return null
        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            if (!original.url.host.equals("api.themoviedb.org", ignoreCase = true)) {
                return@Interceptor chain.proceed(original)
            }
            val token = credentialProvider.readAccessToken()
            val apiKey = credentialProvider.apiKey()
            val updatedRequest = if (!token.isNullOrBlank()) {
                original.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()
            } else if (!apiKey.isNullOrBlank()) {
                val urlWithKey = original.url.newBuilder()
                    .setQueryParameter("api_key", apiKey)
                    .build()
                original.newBuilder()
                    .url(urlWithKey)
                    .header("Accept", "application/json")
                    .build()
            } else {
                original
            }
            chain.proceed(updatedRequest)
        }
        val directClient = baseClient.newBuilder().apply {
            interceptors().add(0, authInterceptor)
        }.build()

        return try {
            Retrofit.Builder()
                .baseUrl(TMDB_V3_BASE_URL)
                .client(directClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(TmdbDirectApiService::class.java)
        } catch (_: Exception) {
            null
        }
    }
}
