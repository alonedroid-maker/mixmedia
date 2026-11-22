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
    val popularFeed: List<MediaSearchResult> = emptyList(),
    val isPopularLoading: Boolean = false,
    val favorites: List<MediaSearchResult> = emptyList(),
    val recentlyViewed: List<MediaSearchResult> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val savedSourcePages: List<SavedSourcePage> = emptyList(),
    val sourceSites: List<SourceSiteConfig> = MediaPersistence.defaultSourceSites(),
    val preferences: MediaPreferences = MediaPreferences(),
    val cacheSizeBytes: Long = 0L,
    val routeStack: List<DiscoveryRoute> = listOf(DiscoveryRoute.HomeSearch),
    val detailsState: DetailsUiState = DetailsUiState.Idle,
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
}

class MovieDiscoveryViewModel(
    private val metadataRepo: MetadataRepository = MetadataRepository(),
    private var sourceEngine: SourceDiscoveryEngine = SourceDiscoveryEngine(),
) : ViewModel() {

    private val _state = MutableStateFlow(MovieDiscoveryState())
    val state: StateFlow<MovieDiscoveryState> = _state.asStateFlow()

    private var persistence: MediaPersistence? = null
    private var searchJob: Job? = null
    private var detailsJob: Job? = null
    private var sourceJob: Job? = null

    fun initialize(context: Context) {
        if (persistence != null) return
        val store = MediaPersistence(context.applicationContext)
        persistence = store
        sourceEngine = SourceDiscoveryEngine(store)

        val prefs = store.loadPreferences()
        _state.update {
            it.copy(
                preferences = prefs,
                favorites = store.loadFavorites(),
                recentlyViewed = store.loadRecentlyViewed(),
                recentSearches = store.loadRecentSearches(),
                savedSourcePages = store.loadSavedSourcePages(),
                sourceSites = store.loadSourceSites(),
                cacheSizeBytes = store.getCacheSizeBytes(),
            )
        }
        loadPopularFeedIfEmpty()
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
        val trimmed = newQuery.trim()
        if (trimmed.length < 2) {
            _state.update {
                it.copy(
                    isSearching = false,
                    hasSearched = false,
                    searchResults = emptyList(),
                    searchError = null,
                )
            }
            return
        }

        searchJob = viewModelScope.launch {
            if (!triggerImmediately) {
                delay(420)
            }
            performSearch(trimmed, saveToHistory = triggerImmediately)
        }
    }

    fun submitSearch() {
        val trimmed = _state.value.searchQuery.trim()
        if (trimmed.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            performSearch(trimmed, saveToHistory = true)
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
        _state.update {
            it.copy(
                searchQuery = "",
                isSearching = false,
                hasSearched = false,
                searchError = null,
                searchResults = emptyList(),
            )
        }
    }

    private suspend fun performSearch(query: String, saveToHistory: Boolean) {
        _state.update { it.copy(isSearching = true, searchError = null) }
        if (saveToHistory) {
            recordRecentSearch(query)
        }
        val result = metadataRepo.searchMedia(query, _state.value.preferences)
        result.fold(
            onSuccess = { list ->
                _state.update {
                    it.copy(
                        isSearching = false,
                        hasSearched = true,
                        searchResults = list,
                        searchError = null,
                    )
                }
                if (!saveToHistory && list.isNotEmpty()) {
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
            }
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
                }
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

    fun updatePreferences(transform: (MediaPreferences) -> MediaPreferences) {
        val updated = transform(_state.value.preferences)
        _state.update { it.copy(preferences = updated) }
        persistence?.savePreferences(updated)
        metadataRepo.clearMemoryCaches()
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
