package com.example.media

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface DiscoveryRoute {
    data object HomeSearch : DiscoveryRoute
    data class Details(val summary: MediaSearchResult) : DiscoveryRoute
    data class Sources(val target: SourceSearchTarget) : DiscoveryRoute
    data class Browser(
        val url: String,
        val title: String,
        val mediaTitle: String? = null,
    ) : DiscoveryRoute
}

sealed interface DetailsUiState {
    data object Idle : DetailsUiState
    data object Loading : DetailsUiState
    data class Success(val details: MediaDetails) : DetailsUiState
    data class Error(val message: String) : DetailsUiState
}

sealed interface SourceUiState {
    data object Idle : SourceUiState
    data object Loading : SourceUiState
    data class Success(val bundle: SourceDiscoveryBundle) : SourceUiState
    data class Error(val message: String) : SourceUiState
}

data class PendingBrowserDownload(
    val url: String,
    val fileName: String,
    val fileTypeLabel: String,
    val sourcePageUrl: String? = null,
)

data class MovieDiscoveryState(
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val searchError: String? = null,
    val searchResults: List<MediaSearchResult> = emptyList(),
    val selectedTypeFilter: MediaType? = null,
    val discoverFilters: TmdbDiscoverFilters = TmdbDiscoverFilters(),
    val availableGenres: List<MediaGenre> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val isLoadingMore: Boolean = false,
    val popularFeed: List<MediaSearchResult> = emptyList(),
    val isPopularLoading: Boolean = false,
    val trendingFeed: TrendingFeedBundle = TrendingFeedBundle(),
    val isTrendingLoading: Boolean = false,
    val trendingError: String? = null,
    val isTmdbBackendActive: Boolean = false,
    val tmdbBackendDiagnostic: TmdbBackendDiagnostic = TmdbBackendDiagnostic(),
    val favorites: List<MediaSearchResult> = emptyList(),
    val recentlyViewed: List<MediaSearchResult> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val savedSourcePages: List<SavedSourcePage> = emptyList(),
    val sourceSites: List<SourceSiteConfig> = MediaPersistence.defaultSourceSites(),
    val preferences: MediaPreferences = MediaPreferences(),
    val cacheSizeBytes: Long = 0L,
    val routeStack: List<DiscoveryRoute> = listOf(DiscoveryRoute.HomeSearch),
    val detailsState: DetailsUiState = DetailsUiState.Idle,
    val isWatchProvidersRefreshing: Boolean = false,
    val sourceState: SourceUiState = SourceUiState.Idle,
    val queryModifiers: QueryModifiers = QueryModifiers(),
    val customSourceQuery: String = "",
    val verifyingUrlId: String? = null,
    val pendingBrowserDownload: PendingBrowserDownload? = null,
) {
    val currentRoute: DiscoveryRoute
        get() = routeStack.lastOrNull() ?: DiscoveryRoute.HomeSearch

    val canPopRoute: Boolean
        get() = routeStack.size > 1

    val hasMorePages: Boolean
        get() = currentPage < totalPages

    val isDiscoverOrSearchActive: Boolean
        get() = searchQuery.trim().length >= 2 || discoverFilters.hasActiveFilters()
}

class MovieDiscoveryViewModel(
    private val metadataRepo: MetadataRepository = MetadataRepository(),
    private var sourceEngine: SourceDiscoveryEngine = SourceDiscoveryEngine(),
) : ViewModel() {

    private val _state = MutableStateFlow(MovieDiscoveryState())
    val state: StateFlow<MovieDiscoveryState> = _state.asStateFlow()

    private var persistence: MediaPersistence? = null
    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var detailsJob: Job? = null
    private var providersJob: Job? = null
    private var sourceJob: Job? = null
    private var trendingJob: Job? = null

    fun initialize(context: Context) {
        if (persistence != null) return
        val store = MediaPersistence(context.applicationContext)
        persistence = store
        sourceEngine = SourceDiscoveryEngine(store)

        val prefs = store.loadPreferences()
        val cachedTrending = store.loadTrendingCache()
        val backendConfigured = metadataRepo.isTmdbBackendConfigured(prefs)
        _state.update {
            it.copy(
                preferences = prefs,
                isTmdbBackendActive = backendConfigured,
                tmdbBackendDiagnostic = buildInitialDiagnostic(prefs),
                trendingFeed = cachedTrending ?: TrendingFeedBundle(),
                favorites = store.loadFavorites(),
                recentlyViewed = store.loadRecentlyViewed(),
                recentSearches = store.loadRecentSearches(),
                savedSourcePages = store.loadSavedSourcePages(),
                sourceSites = store.loadSourceSites(),
                cacheSizeBytes = store.getCacheSizeBytes(),
            )
        }
        loadTrendingIfNeeded()
        loadPopularFeedIfEmpty()
        loadGenresIfNeeded()
    }

    private fun buildInitialDiagnostic(prefs: MediaPreferences): TmdbBackendDiagnostic {
        val raw = prefs.effectiveProxyBaseUrl()
        val normalized = TmdbProxyClientFactory.normalizeProxyBaseUrl(raw)
        val host = normalized?.toHttpUrlOrNull()?.host ?: ""
        return TmdbBackendDiagnostic(
            endpointHost = host,
            isConfigured = normalized != null,
        )
    }

    fun loadGenresIfNeeded() {
        val prefs = _state.value.preferences
        if (!metadataRepo.isTmdbBackendConfigured(prefs)) return
        viewModelScope.launch {
            val genres = metadataRepo.getAvailableGenres(prefs)
            if (genres.isNotEmpty()) {
                _state.update { it.copy(availableGenres = genres) }
            }
        }
    }

    fun loadTrendingIfNeeded(forceRefresh: Boolean = false) {
        if (_state.value.isTrendingLoading && !forceRefresh) return
        trendingJob?.cancel()
        trendingJob = viewModelScope.launch {
            _state.update { it.copy(isTrendingLoading = true, trendingError = null) }
            try {
                val bundle = metadataRepo.getTrendingFeed(
                    preferences = _state.value.preferences,
                    persistence = persistence,
                    forceRefresh = forceRefresh,
                )
                _state.update {
                    it.copy(
                        trendingFeed = if (bundle.movies.isNotEmpty() || bundle.tvShows.isNotEmpty()) bundle else it.trendingFeed,
                        isTrendingLoading = false,
                        trendingError = null,
                        cacheSizeBytes = persistence?.getCacheSizeBytes() ?: it.cacheSizeBytes,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isTrendingLoading = false,
                        trendingError = e.message ?: "Unable to load trending titles",
                    )
                }
            }
        }
    }

    fun refreshTrending() {
        loadTrendingIfNeeded(forceRefresh = true)
    }

    fun setTrendingWindow(window: TrendingTimeWindow) {
        if (_state.value.preferences.trendingWindow == window) return
        updatePreferences { it.copy(trendingWindow = window) }
    }

    fun setWatchRegion(regionCode: String) {
        val normalized = WatchRegions.normalize(regionCode)
        val changed = !_state.value.preferences.watchRegion.equals(normalized, ignoreCase = true)
        if (changed) {
            updatePreferences { it.copy(watchRegion = normalized) }
        }
        val currentDetails = _state.value.currentRoute as? DiscoveryRoute.Details
        if (currentDetails != null) {
            refreshDetailsWatchProviders(currentDetails.summary, normalized)
        }
    }

    private fun refreshDetailsWatchProviders(summary: MediaSearchResult, region: String) {
        providersJob?.cancel()
        providersJob = viewModelScope.launch {
            _state.update { it.copy(isWatchProvidersRefreshing = true) }
            val (providers, pageUrl) = metadataRepo.getWatchProvidersForRegion(
                summary = summary,
                region = region,
                preferences = _state.value.preferences,
            )
            _state.update { current ->
                val updatedDetails = when (val ds = current.detailsState) {
                    is DetailsUiState.Success -> DetailsUiState.Success(
                        ds.details.copy(
                            watchProviders = providers,
                            watchProvidersPageUrl = pageUrl ?: ds.details.watchProvidersPageUrl,
                        )
                    )
                    else -> ds
                }
                current.copy(
                    detailsState = updatedDetails,
                    isWatchProvidersRefreshing = false,
                )
            }
        }
    }

    fun loadPopularFeedIfEmpty() {
        val current = _state.value
        if (current.popularFeed.isNotEmpty() || current.isPopularLoading) return
        viewModelScope.launch {
            _state.update { it.copy(isPopularLoading = true) }
            val popular = metadataRepo.getPopularDiscoverFeed(_state.value.preferences)
            _state.update { it.copy(popularFeed = popular, isPopularLoading = false) }
        }
    }

    fun onSearchQueryChanged(newQuery: String, triggerImmediately: Boolean = false) {
        _state.update { it.copy(searchQuery = newQuery, searchError = null) }
        searchJob?.cancel()
        loadMoreJob?.cancel()
        val trimmed = newQuery.trim()
        if (trimmed.length < 2 && !_state.value.discoverFilters.hasActiveFilters()) {
            _state.update {
                it.copy(
                    isSearching = false,
                    hasSearched = false,
                    searchResults = emptyList(),
                    searchError = null,
                    currentPage = 1,
                    totalPages = 1,
                )
            }
            return
        }

        searchJob = viewModelScope.launch {
            if (!triggerImmediately) {
                delay(420)
            }
            performSearchOrDiscover(query = trimmed, page = 1, saveToHistory = triggerImmediately)
        }
    }

    fun submitSearch() {
        val trimmed = _state.value.searchQuery.trim()
        if (trimmed.length < 2 && !_state.value.discoverFilters.hasActiveFilters()) return
        searchJob?.cancel()
        loadMoreJob?.cancel()
        searchJob = viewModelScope.launch {
            performSearchOrDiscover(query = trimmed, page = 1, saveToHistory = trimmed.length >= 2)
        }
    }

    fun setTypeFilter(typeFilter: MediaType?) {
        if (_state.value.selectedTypeFilter == typeFilter) return
        _state.update { it.copy(selectedTypeFilter = typeFilter) }
        if (_state.value.isDiscoverOrSearchActive) {
            submitSearch()
        }
    }

    fun updateDiscoverFilters(transform: (TmdbDiscoverFilters) -> TmdbDiscoverFilters) {
        val updated = transform(_state.value.discoverFilters)
        if (updated == _state.value.discoverFilters) return
        _state.update { it.copy(discoverFilters = updated) }
        if (_state.value.searchQuery.trim().length >= 2 || updated.hasActiveFilters()) {
            submitSearch()
        } else {
            _state.update {
                it.copy(
                    isSearching = false,
                    hasSearched = false,
                    searchResults = emptyList(),
                    searchError = null,
                    currentPage = 1,
                    totalPages = 1,
                )
            }
        }
    }

    fun clearDiscoverFilters() {
        updateDiscoverFilters { TmdbDiscoverFilters() }
    }

    fun loadNextSearchPage() {
        val current = _state.value
        if (current.isSearching || current.isLoadingMore || !current.hasMorePages) return
        if (!current.isDiscoverOrSearchActive) return
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            val nextPage = current.currentPage + 1
            val res = metadataRepo.searchAndDiscoverMedia(
                rawQuery = current.searchQuery.trim(),
                mediaTypeFilter = current.selectedTypeFilter,
                filters = current.discoverFilters,
                page = nextPage,
                preferences = current.preferences,
            )
            res.fold(
                onSuccess = { pageData ->
                    _state.update { st ->
                        val existingIds = st.searchResults.mapTo(HashSet()) { it.id }
                        val appended = pageData.items.filter { existingIds.add(it.id) }
                        st.copy(
                            searchResults = st.searchResults + appended,
                            currentPage = pageData.page,
                            totalPages = pageData.totalPages,
                            isLoadingMore = false,
                        )
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) throw err
                    _state.update { it.copy(isLoadingMore = false) }
                },
            )
        }
    }

    fun selectRecentSearch(query: String) {
        _state.update { it.copy(searchQuery = query) }
        submitSearch()
    }

    fun removeRecentSearch(query: String) {
        val updated = _state.value.recentSearches.filterNot { it == query }
        _state.update { it.copy(recentSearches = updated) }
        persistence?.saveRecentSearches(updated)
    }

    fun clearSearchInput() {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        _state.update {
            it.copy(
                searchQuery = "",
                discoverFilters = TmdbDiscoverFilters(),
                selectedTypeFilter = null,
                isSearching = false,
                isLoadingMore = false,
                hasSearched = false,
                searchError = null,
                searchResults = emptyList(),
                currentPage = 1,
                totalPages = 1,
            )
        }
    }

    private suspend fun performSearchOrDiscover(
        query: String,
        page: Int,
        saveToHistory: Boolean,
    ) {
        _state.update { it.copy(isSearching = true, searchError = null) }
        if (saveToHistory && query.length >= 2) {
            recordRecentSearch(query)
        }
        val current = _state.value
        val result = metadataRepo.searchAndDiscoverMedia(
            rawQuery = query,
            mediaTypeFilter = current.selectedTypeFilter,
            filters = current.discoverFilters,
            page = page,
            preferences = current.preferences,
        )
        result.fold(
            onSuccess = { pageData ->
                _state.update {
                    it.copy(
                        isSearching = false,
                        hasSearched = true,
                        searchResults = pageData.items,
                        currentPage = pageData.page,
                        totalPages = pageData.totalPages,
                        searchError = null,
                    )
                }
                if (!saveToHistory && query.length >= 2 && pageData.items.isNotEmpty()) {
                    recordRecentSearch(query)
                }
            },
            onFailure = { err ->
                if (err is CancellationException) throw err
                _state.update {
                    it.copy(
                        isSearching = false,
                        hasSearched = true,
                        searchError = err.message ?: "Search failed",
                    )
                }
            },
        )
    }

    private fun recordRecentSearch(query: String) {
        val updated = (listOf(query) + _state.value.recentSearches.filterNot {
            it.equals(query, ignoreCase = true)
        }).take(12)
        _state.update { it.copy(recentSearches = updated) }
        persistence?.saveRecentSearches(updated)
    }

    fun openMediaDetails(summary: MediaSearchResult) {
        recordRecentlyViewed(summary)
        _state.update {
            it.copy(
                routeStack = it.routeStack.filterNot { r -> r is DiscoveryRoute.Details } + DiscoveryRoute.Details(summary),
                detailsState = DetailsUiState.Loading,
            )
        }
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            val res = metadataRepo.getMediaDetails(summary, _state.value.preferences)
            res.fold(
                onSuccess = { details ->
                    _state.update { it.copy(detailsState = DetailsUiState.Success(details)) }
                },
                onFailure = { err ->
                    if (err is CancellationException) throw err
                    _state.update {
                        it.copy(detailsState = DetailsUiState.Error(err.message ?: "Unable to load details"))
                    }
                },
            )
        }
    }

    fun retryMediaDetails() {
        val currentDetails = _state.value.currentRoute as? DiscoveryRoute.Details ?: return
        openMediaDetails(currentDetails.summary)
    }

    fun openSourceDiscovery(target: SourceSearchTarget) {
        _state.update {
            it.copy(
                customSourceQuery = "",
                routeStack = it.routeStack + DiscoveryRoute.Sources(target),
                sourceState = SourceUiState.Loading,
            )
        }
        runSourceDiscovery(target, customQuery = null)
    }

    fun updateQueryModifiers(modifiers: QueryModifiers) {
        _state.update { it.copy(queryModifiers = modifiers) }
        val route = _state.value.currentRoute as? DiscoveryRoute.Sources ?: return
        runSourceDiscovery(route.target, customQuery = _state.value.customSourceQuery.takeIf { it.isNotBlank() })
    }

    fun runCustomSourceQuery(query: String) {
        _state.update { it.copy(customSourceQuery = query) }
        val route = _state.value.currentRoute as? DiscoveryRoute.Sources ?: return
        runSourceDiscovery(route.target, customQuery = query.takeIf { it.isNotBlank() })
    }

    private fun runSourceDiscovery(target: SourceSearchTarget, customQuery: String?) {
        sourceJob?.cancel()
        sourceJob = viewModelScope.launch {
            _state.update { it.copy(sourceState = SourceUiState.Loading) }
            try {
                val bundle = sourceEngine.discoverSources(
                    target = target,
                    sites = _state.value.sourceSites,
                    preferences = _state.value.preferences,
                    modifiers = _state.value.queryModifiers,
                    customQueryOverride = customQuery,
                )
                _state.update {
                    it.copy(
                        sourceState = SourceUiState.Success(bundle),
                        cacheSizeBytes = persistence?.getCacheSizeBytes() ?: it.cacheSizeBytes,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(sourceState = SourceUiState.Error(e.message ?: "Source search failed"))
                }
            }
        }
    }

    fun verifySourceLinkStatus(item: WebSourceResult) {
        if (_state.value.verifyingUrlId != null) return
        viewModelScope.launch {
            _state.update { it.copy(verifyingUrlId = item.id) }
            val (newStatus, note) = sourceEngine.verifyUrlStatus(item.url)
            _state.update { current ->
                val bundle = (current.sourceState as? SourceUiState.Success)?.bundle
                val updatedBundle = bundle?.copy(
                    siteResults = bundle.siteResults.map {
                        if (it.id == item.id) it.copy(status = newStatus, statusNote = note) else it
                    },
                    webSearchResults = bundle.webSearchResults.map {
                        if (it.id == item.id) it.copy(status = newStatus, statusNote = note) else it
                    },
                )
                current.copy(
                    verifyingUrlId = null,
                    sourceState = if (updatedBundle != null) SourceUiState.Success(updatedBundle) else current.sourceState,
                )
            }
        }
    }

    fun openInternalBrowser(url: String, title: String, mediaTitle: String? = null) {
        if (!FuzzyTitleMatcher.isSafeHttpUrl(url)) return
        _state.update {
            it.copy(
                routeStack = it.routeStack + DiscoveryRoute.Browser(
                    url = url,
                    title = title,
                    mediaTitle = mediaTitle,
                )
            )
        }
    }

    fun popRoute(): Boolean {
        val current = _state.value.routeStack
        if (current.size <= 1) return false
        _state.update { it.copy(routeStack = current.dropLast(1)) }
        return true
    }

    fun resetToHome() {
        _state.update { it.copy(routeStack = listOf(DiscoveryRoute.HomeSearch)) }
    }

    fun toggleFavorite(item: MediaSearchResult) {
        val current = _state.value.favorites
        val exists = current.any { it.id == item.id }
        val updated = if (exists) {
            current.filterNot { it.id == item.id }
        } else {
            listOf(item) + current
        }
        _state.update { it.copy(favorites = updated) }
        persistence?.saveFavorites(updated)
    }

    fun isFavorite(mediaId: String): Boolean = _state.value.favorites.any { it.id == mediaId }

    fun toggleSavedSourcePage(url: String, title: String, mediaTitle: String? = null) {
        if (!FuzzyTitleMatcher.isSafeHttpUrl(url)) return
        val current = _state.value.savedSourcePages
        val exists = current.any { it.url == url }
        val domain = url.toHttpUrlOrNull()?.host ?: ""
        val updated = if (exists) {
            current.filterNot { it.url == url }
        } else {
            listOf(SavedSourcePage(url = url, title = title.ifBlank { domain }, domain = domain, mediaTitle = mediaTitle)) + current
        }
        _state.update { it.copy(savedSourcePages = updated) }
        persistence?.saveSavedSourcePages(updated)
    }

    fun isSourceSaved(url: String): Boolean = _state.value.savedSourcePages.any { it.url == url }

    fun requestBrowserDownloadConfirmation(url: String, fileName: String, fileTypeLabel: String, pageUrl: String? = null) {
        if (!FuzzyTitleMatcher.isSafeHttpUrl(url)) return
        _state.update {
            it.copy(
                pendingBrowserDownload = PendingBrowserDownload(
                    url = url,
                    fileName = fileName,
                    fileTypeLabel = fileTypeLabel,
                    sourcePageUrl = pageUrl,
                )
            )
        }
    }

    fun dismissBrowserDownloadConfirmation() {
        _state.update { it.copy(pendingBrowserDownload = null) }
    }

    fun updateTmdbProxyUrl(rawUrl: String) {
        updatePreferences { it.copy(tmdbProxyUrl = rawUrl.trim()) }
    }

    fun testTmdbBackendConnection(overrideUrl: String? = null) {
        val targetUrl = overrideUrl?.trim().orEmpty().ifBlank { _state.value.preferences.effectiveProxyBaseUrl() }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    tmdbBackendDiagnostic = it.tmdbBackendDiagnostic.copy(
                        isTesting = true,
                        errorMessage = null,
                    )
                )
            }
            val result = metadataRepo.testBackendConnection(targetUrl)
            _state.update { it.copy(tmdbBackendDiagnostic = result) }
        }
    }

    fun updatePreferences(transform: (MediaPreferences) -> MediaPreferences) {
        val previous = _state.value.preferences
        val updated = transform(previous)
        val backendConfigured = metadataRepo.isTmdbBackendConfigured(updated)
        _state.update {
            it.copy(
                preferences = updated,
                isTmdbBackendActive = backendConfigured,
                tmdbBackendDiagnostic = if (previous.tmdbProxyUrl != updated.tmdbProxyUrl) {
                    buildInitialDiagnostic(updated)
                } else {
                    it.tmdbBackendDiagnostic
                },
            )
        }
        persistence?.savePreferences(updated)
        metadataRepo.clearMemoryCaches()

        val trendingInputsChanged = previous.searchLanguage != updated.searchLanguage ||
            previous.watchRegion != updated.watchRegion ||
            previous.trendingWindow != updated.trendingWindow ||
            previous.tmdbProxyUrl != updated.tmdbProxyUrl
        if (trendingInputsChanged) {
            loadTrendingIfNeeded(forceRefresh = true)
            if (previous.tmdbProxyUrl != updated.tmdbProxyUrl) {
                loadGenresIfNeeded()
            }
        }
    }

    fun toggleSourceSiteEnabled(siteId: String, enabled: Boolean) {
        val updated = _state.value.sourceSites.map {
            if (it.id == siteId) it.copy(enabled = enabled) else it
        }
        _state.update { it.copy(sourceSites = updated) }
        persistence?.saveSourceSites(updated)
    }

    fun addCustomSourceSite(name: String, rawBaseUrl: String): Boolean {
        val trimmed = rawBaseUrl.trim()
        if (trimmed.isEmpty()) return false
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmed)
        if (hasScheme && !FuzzyTitleMatcher.isSafeHttpUrl(trimmed)) return false
        val normalizedBase = if (hasScheme) trimmed.trimEnd('/') else "https://${trimmed.trimEnd('/')}"
        if (!FuzzyTitleMatcher.isSafeHttpUrl(normalizedBase)) return false
        val parsed = normalizedBase.toHttpUrlOrNull() ?: return false
        if (parsed.host.isBlank() || '.' !in parsed.host) return false
        val template = if (normalizedBase.contains("{query}")) {
            normalizedBase
        } else {
            "$normalizedBase/?s={query}"
        }
        val cleanBase = template.substringBefore("/?s=").ifBlank { normalizedBase }
        val newSite = SourceSiteConfig(
            id = "custom_${System.currentTimeMillis()}",
            name = name.trim().ifBlank { parsed.host },
            baseUrl = cleanBase,
            searchUrlTemplate = template,
            isArchive = false,
            enabled = true,
            isCustom = true,
        )
        val updated = _state.value.sourceSites + newSite
        _state.update { it.copy(sourceSites = updated) }
        persistence?.saveSourceSites(updated)
        return true
    }

    fun removeCustomSourceSite(siteId: String) {
        val updated = _state.value.sourceSites.filterNot { it.id == siteId && it.isCustom }
        _state.update { it.copy(sourceSites = updated) }
        persistence?.saveSourceSites(updated)
    }

    fun clearAllCaches() {
        metadataRepo.clearMemoryCaches()
        persistence?.clearMediaCache()
        _state.update { it.copy(cacheSizeBytes = persistence?.getCacheSizeBytes() ?: 0L) }
    }

    fun clearSearchHistory() {
        persistence?.clearSearchHistory()
        _state.update { it.copy(recentSearches = emptyList()) }
    }

    private fun recordRecentlyViewed(summary: MediaSearchResult) {
        val updated = (listOf(summary) + _state.value.recentlyViewed.filterNot { it.id == summary.id }).take(12)
        _state.update { it.copy(recentlyViewed = updated) }
        persistence?.saveRecentlyViewed(updated)
    }
}
