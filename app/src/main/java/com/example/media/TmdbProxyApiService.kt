package com.example.media

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit service talking strictly to the MixMedia Cloudflare Worker backend proxy.
 * Zero TMDB API keys or Bearer tokens are ever stored or transmitted by the Android client.
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

object TmdbProxyClientFactory {

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
}
