package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.media.DigitalReleaseStatus
import com.example.media.MediaSearchResult
import com.example.media.MediaType
import com.example.media.MovieDiscoveryState
import com.example.media.ProviderCategory
import com.example.media.SavedSourcePage
import com.example.media.SourceSiteConfig
import com.example.media.TvReleaseStatus
import com.example.media.WatchProviderInfo
import java.text.DateFormat
import java.util.Date

enum class SearchMediaTypeFilter {
    ALL,
    MOVIE,
    TV,
}

@Composable
fun MovieSearchScreen(
    state: MovieDiscoveryState,
    strings: AppStrings,
    onQueryChanged: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onSelectRecentSearch: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onOpenDetails: (MediaSearchResult) -> Unit,
    onToggleFavorite: (MediaSearchResult) -> Unit,
    onOpenSavedPage: (SavedSourcePage) -> Unit,
    onOpenQuickSite: (SourceSiteConfig) -> Unit,
    onRefreshTrending: () -> Unit = {},
    onOpenProviderUrl: (url: String, title: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var typeFilter by remember { mutableStateOf(SearchMediaTypeFilter.ALL) }
    val filteredSearchResults = remember(state.searchResults, typeFilter) {
        when (typeFilter) {
            SearchMediaTypeFilter.ALL -> state.searchResults
            SearchMediaTypeFilter.MOVIE -> state.searchResults.filter { it.mediaType == MediaType.MOVIE }
            SearchMediaTypeFilter.TV -> state.searchResults.filter { it.mediaType == MediaType.TV }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "discover_header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = strings.discover.discoverTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = strings.discover.discoverSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "search_box") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = onQueryChanged,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("movie_search_input"),
                            placeholder = {
                                Text(
                                    text = strings.discover.movieSearchPlaceholder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = onClearSearch,
                                        modifier = Modifier.testTag("movie_search_clear"),
                                    ) {
                                        Icon(Icons.Rounded.Clear, contentDescription = strings.cdClearInput)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    focusManager.clearFocus()
                                    onSubmitSearch()
                                }
                            ),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    onSubmitSearch()
                                },
                                enabled = state.searchQuery.trim().length >= 2 && !state.isSearching,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("movie_search_submit"),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                if (state.isSearching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.discover.actionSearchMovies)
                            }
                        }

                        if (state.recentSearches.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(state.recentSearches, key = { "rec_$it" }) { query ->
                                    InputChip(
                                        selected = false,
                                        onClick = {
                                            focusManager.clearFocus()
                                            onSelectRecentSearch(query)
                                        },
                                        label = {
                                            Text(
                                                text = query,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.History,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Rounded.Close,
                                                contentDescription = strings.actionDelete,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable { onRemoveRecentSearch(query) },
                                            )
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.searchQuery.trim().length >= 2) {
                when {
                    state.isSearching && state.searchResults.isEmpty() -> {
                        item(key = "search_loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    state.searchError != null -> {
                        item(key = "search_error") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = state.searchError,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    OutlinedButton(
                                        onClick = onSubmitSearch,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text(strings.discover.actionRetrySearch)
                                    }
                                }
                            }
                        }
                    }

                    state.hasSearched && state.searchResults.isEmpty() -> {
                        item(key = "search_empty") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = strings.discover.searchNoResultsTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = strings.discover.searchNoResultsDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        item(key = "search_type_filters") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val options = listOf(
                                    SearchMediaTypeFilter.ALL to strings.ui.filterAllTypes,
                                    SearchMediaTypeFilter.MOVIE to strings.ui.filterMoviesOnly,
                                    SearchMediaTypeFilter.TV to strings.ui.filterTvOnly,
                                )
                                items(options) { (option, label) ->
                                    val count = when (option) {
                                        SearchMediaTypeFilter.ALL -> state.searchResults.size
                                        SearchMediaTypeFilter.MOVIE -> state.searchResults.count { it.mediaType == MediaType.MOVIE }
                                        SearchMediaTypeFilter.TV -> state.searchResults.count { it.mediaType == MediaType.TV }
                                    }
                                    FilterChip(
                                        selected = typeFilter == option,
                                        onClick = { typeFilter = option },
                                        label = { Text("$label ($count)") },
                                        modifier = Modifier
                                            .heightIn(min = 40.dp)
                                            .testTag("filter_type_${option.name.lowercase()}"),
                                    )
                                }
                            }
                        }

                        if (filteredSearchResults.isEmpty()) {
                            item(key = "filtered_empty") {
                                Text(
                                    text = strings.discover.searchNoResultsTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                        } else {
                            items(filteredSearchResults, key = { it.id }) { item ->
                                MediaResultCard(
                                    item = item,
                                    isFavorite = state.favorites.any { it.id == item.id },
                                    strings = strings,
                                    onClick = { onOpenDetails(item) },
                                    onToggleFavorite = { onToggleFavorite(item) },
                                )
                            }
                        }
                    }
                }
            } else {
                // Home Discover View: Trending Movies & TV, Favorites, Recently Viewed, Quick Sites, Saved Sites, Popular
                item(key = "trending_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.TrendingUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = strings.discover.trendingSectionTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                            if (state.trendingFeed.lastUpdatedMillis > 0L) {
                                val formattedTs = remember(state.trendingFeed.lastUpdatedMillis) {
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(Date(state.trendingFeed.lastUpdatedMillis))
                                }
                                Text(
                                    text = strings.discover.trendingLastUpdated(formattedTs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(
                            onClick = onRefreshTrending,
                            enabled = !state.isTrendingLoading,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("trending_refresh_button"),
                        ) {
                            if (state.isTrendingLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.discover.trendingRefreshAction)
                        }
                    }
                }

                if (state.trendingFeed.isUsingFallbackSources) {
                    item(key = "trending_fallback_notice") {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("trending_fallback_notice"),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = strings.discover.trendingFallbackNotice,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }

                if (state.isTrendingLoading &&
                    state.trendingFeed.movies.isEmpty() &&
                    state.trendingFeed.tvShows.isEmpty()
                ) {
                    item(key = "trending_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                }

                if (state.trendingFeed.movies.isNotEmpty()) {
                    item(key = "trending_movies_title") {
                        SectionTitle(strings.discover.trendingMoviesTitle)
                    }
                    items(state.trendingFeed.movies, key = { "tr_mov_${it.id}" }) { item ->
                        TrendingMediaCard(
                            item = item,
                            isFavorite = state.favorites.any { it.id == item.id },
                            strings = strings,
                            onClick = { onOpenDetails(item) },
                            onToggleFavorite = { onToggleFavorite(item) },
                            onOpenProviderUrl = onOpenProviderUrl,
                        )
                    }
                }

                if (state.trendingFeed.tvShows.isNotEmpty()) {
                    item(key = "trending_tv_title") {
                        SectionTitle(strings.discover.trendingTvTitle)
                    }
                    items(state.trendingFeed.tvShows, key = { "tr_tv_${it.id}" }) { item ->
                        TrendingMediaCard(
                            item = item,
                            isFavorite = state.favorites.any { it.id == item.id },
                            strings = strings,
                            onClick = { onOpenDetails(item) },
                            onToggleFavorite = { onToggleFavorite(item) },
                            onOpenProviderUrl = onOpenProviderUrl,
                        )
                    }
                }

                if (state.favorites.isNotEmpty()) {
                    item(key = "favorites_section") {
                        SectionTitle(strings.discover.favoritesTitle)
                    }
                    item(key = "favorites_row") {
                        HorizontalMediaCarousel(
                            items = state.favorites,
                            strings = strings,
                            onItemClick = onOpenDetails,
                        )
                    }
                }

                if (state.recentlyViewed.isNotEmpty()) {
                    item(key = "recent_viewed_section") {
                        SectionTitle(strings.discover.recentlyViewedTitle)
                    }
                    item(key = "recent_viewed_row") {
                        HorizontalMediaCarousel(
                            items = state.recentlyViewed,
                            strings = strings,
                            onItemClick = onOpenDetails,
                        )
                    }
                }

                item(key = "quick_sites_section") {
                    SectionTitle(strings.discover.quickSitesTitle)
                }
                item(key = "quick_sites_row") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.sourceSites.filter { it.enabled }, key = { "qs_${it.id}" }) { site ->
                            FilterChip(
                                selected = false,
                                onClick = { onOpenQuickSite(site) },
                                label = { Text(site.name) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }

                if (state.savedSourcePages.isNotEmpty()) {
                    item(key = "saved_sources_section") {
                        SectionTitle(strings.discover.savedSourcesTitle)
                    }
                    items(state.savedSourcePages.take(6), key = { "saved_${it.url}" }) { page ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenSavedPage(page) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Bookmark,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = page.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = page.domain.ifBlank { page.url },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.popularFeed.isNotEmpty()) {
                    item(key = "popular_section") {
                        SectionTitle(strings.discover.popularTitle)
                    }
                    items(state.popularFeed, key = { "pop_${it.id}" }) { item ->
                        MediaResultCard(
                            item = item,
                            isFavorite = state.favorites.any { it.id == item.id },
                            strings = strings,
                            onClick = { onOpenDetails(item) },
                            onToggleFavorite = { onToggleFavorite(item) },
                        )
                    }
                } else if (state.isPopularLoading) {
                    item(key = "popular_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

@Composable
private fun HorizontalMediaCarousel(
    items: List<MediaSearchResult>,
    strings: AppStrings,
    onItemClick: (MediaSearchResult) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { "car_${it.id}" }) { item ->
            Card(
                modifier = Modifier
                    .width(136.dp)
                    .clickable { onItemClick(item) },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            ) {
                Column {
                    PosterImage(
                        posterUrl = item.posterUrl,
                        mediaType = item.mediaType,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.persianTitle ?: item.title,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.releaseYear?.toString() ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (item.mediaType == MediaType.TV) strings.discover.badgeTvShow else strings.discover.badgeMovie,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MediaResultCard(
    item: MediaSearchResult,
    isFavorite: Boolean,
    strings: AppStrings,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("media_card_${item.id}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PosterImage(
                posterUrl = item.posterUrl,
                mediaType = item.mediaType,
                contentDescription = item.title,
                modifier = Modifier
                    .width(88.dp)
                    .height(132.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val faTitle = item.persianTitle?.takeIf { !it.equals(item.title, ignoreCase = true) }
                        if (faTitle != null) {
                            Text(
                                text = faTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = strings.discover.favoritesTitle,
                            tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Badges Row: Type, Year, Runtime, Rating
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (item.mediaType == MediaType.TV) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    ) {
                        Text(
                            text = if (item.mediaType == MediaType.TV) strings.discover.badgeTvShow else strings.discover.badgeMovie,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (item.mediaType == MediaType.TV) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    item.releaseYear?.let { year ->
                        Text(
                            text = year.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item.runtimeMinutes?.let { min ->
                        Text(
                            text = "• ${strings.discover.labelRuntimeMinutes(min)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item.imdbRating?.let { rating ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = String.format(java.util.Locale.US, "%.1f", rating),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }

                if (item.genres.isNotEmpty()) {
                    Text(
                        text = item.genres.take(3).joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (item.directors.isNotEmpty()) {
                    Text(
                        text = "${strings.discover.labelDirector}: ${item.directors.take(2).joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (item.topCast.isNotEmpty()) {
                    Text(
                        text = "${strings.discover.labelCast}: ${item.topCast.take(3).joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val synopsis = item.persianOverview ?: item.overview
                if (!synopsis.isNullOrBlank()) {
                    Text(
                        text = synopsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrendingMediaCard(
    item: MediaSearchResult,
    isFavorite: Boolean,
    strings: AppStrings,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenProviderUrl: (url: String, title: String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("trending_card_${item.id}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PosterImage(
                    posterUrl = item.posterUrl,
                    mediaType = item.mediaType,
                    contentDescription = item.title,
                    modifier = Modifier
                        .width(84.dp)
                        .height(126.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val faTitle = item.persianTitle?.takeIf { !it.equals(item.title, ignoreCase = true) }
                            if (faTitle != null) {
                                Text(
                                    text = faTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = strings.discover.favoritesTitle,
                                tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item.releaseYear?.let { year ->
                            Text(
                                text = year.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item.imdbRating?.let { rating ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = String.format(java.util.Locale.US, "%.1f", rating),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        if (item.mediaType == MediaType.TV && item.seasonCount != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = strings.discover.labelSeasonsCount(item.seasonCount),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    val statusBadge = resolveTrendingStatusBadge(item, strings)
                    if (statusBadge != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = statusBadge,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }

                    if (item.genres.isNotEmpty()) {
                        Text(
                            text = item.genres.take(3).joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (item.watchProviders.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item.watchProviders.take(4).forEach { provider ->
                        val catLabel = when (provider.category) {
                            ProviderCategory.STREAMING -> strings.discover.providerCatStreaming
                            ProviderCategory.RENTAL_PURCHASE -> strings.discover.providerCatRentBuy
                            ProviderCategory.FREE_AD_SUPPORTED -> strings.discover.providerCatFree
                            ProviderCategory.OFFICIAL_PAGE -> strings.discover.providerCatOfficial
                        }
                        val targetUrl = provider.url ?: item.watchProvidersPageUrl
                        AssistChip(
                            onClick = {
                                if (!targetUrl.isNullOrBlank()) {
                                    onOpenProviderUrl(targetUrl, "${item.title} - ${provider.name}")
                                } else {
                                    onClick()
                                }
                            },
                            label = {
                                Text(
                                    text = "${provider.name} • $catLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = if (!targetUrl.isNullOrBlank()) {
                                {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

private fun resolveTrendingStatusBadge(item: MediaSearchResult, strings: AppStrings): String? {
    return if (item.mediaType == MediaType.MOVIE) {
        when (item.digitalReleaseStatus) {
            DigitalReleaseStatus.CONFIRMED_DIGITAL ->
                item.digitalReleaseDate?.let { strings.discover.statusDigitalReleasedOn(it) }
                    ?: strings.discover.statusDigitalReleased
            DigitalReleaseStatus.THEATRICAL_ONLY_OR_UNCONFIRMED -> strings.discover.statusTheatricalOnly
            DigitalReleaseStatus.UNKNOWN -> null
        }
    } else {
        when (item.tvReleaseStatus) {
            TvReleaseStatus.CURRENTLY_AIRING -> strings.discover.statusTvAiring
            TvReleaseStatus.SEASON_COMPLETED -> strings.discover.statusTvSeasonCompleted
            TvReleaseStatus.UPCOMING -> strings.discover.statusTvUpcoming
            TvReleaseStatus.ENDED -> strings.discover.statusTvEnded
            TvReleaseStatus.UNKNOWN -> null
        }
    }
}

@Composable
fun PosterImage(
    posterUrl: String?,
    mediaType: MediaType,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (mediaType == MediaType.TV) Icons.Rounded.Tv else Icons.Rounded.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(32.dp),
        )
        if (!posterUrl.isNullOrBlank()) {
            val imageRequest = remember(posterUrl, context) {
                ImageRequest.Builder(context)
                    .data(posterUrl)
                    .size(300, 450)
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
