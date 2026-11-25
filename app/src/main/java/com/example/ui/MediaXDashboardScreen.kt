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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.download.DownloadStatus
import com.example.download.DownloadTask
import com.example.media.FuzzyTitleMatcher
import com.example.media.MediaSearchResult
import com.example.media.MediaType
import com.example.media.MovieDiscoveryState
import com.example.media.SourceSiteConfig
import com.example.network.CurlResolutionResult
import com.example.network.VideoLinkExtractor
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun MediaXDashboardScreen(
    extractorState: LinkExtractorScreenState,
    discoveryState: MovieDiscoveryState,
    downloadTasks: List<DownloadTask>,
    strings: AppStrings,
    onQuickMovieSearch: (String) -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenResolver: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenInAppBrowser: (url: String, title: String) -> Unit,
    onOpenQuickSite: (SourceSiteConfig) -> Unit,
    onOpenMediaDetails: (MediaSearchResult) -> Unit,
    onSelectRecentSearch: (String) -> Unit,
    onSelectResolvedHistory: (CurlResolutionResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var quickQuery by remember { mutableStateOf("") }
    var showBrowserUrlDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val activeDownloads = remember(downloadTasks) { downloadTasks.filter { it.isActive } }
    val queuedCount = remember(downloadTasks) { downloadTasks.count { it.status == DownloadStatus.QUEUED } }
    val totalSpeed = remember(activeDownloads) { activeDownloads.sumOf { it.speedBytesPerSec } }
    val speedLabel = if (totalSpeed > 0L) {
        "${VideoLinkExtractor.formatByteSize(totalSpeed) ?: "0 B"}/s"
    } else {
        "0 KB/s"
    }

    val highlightMedia = remember(discoveryState.favorites, discoveryState.recentlyViewed) {
        (discoveryState.favorites + discoveryState.recentlyViewed)
            .distinctBy { it.id }
            .take(10)
    }
    val enabledSites = remember(discoveryState.sourceSites) {
        discoveryState.sourceSites.filter { it.enabled }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "dash_hero") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                                        Color.Transparent,
                                    )
                                )
                            )
                            .padding(18.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(MaterialTheme.shapes.medium)
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(
                                                        MaterialTheme.colorScheme.primary,
                                                        MaterialTheme.colorScheme.secondary,
                                                    )
                                                )
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                    Text(
                                        text = strings.appName,
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        text = strings.ui.dashboardHeroBadge,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }

                            Text(
                                text = strings.ui.dashboardHeroTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = strings.ui.dashboardHeroSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = quickQuery,
                                    onValueChange = { quickQuery = it },
                                    placeholder = {
                                        Text(
                                            text = strings.discover.movieSearchPlaceholder,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.medium,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(
                                        onSearch = {
                                            focusManager.clearFocus()
                                            val clean = quickQuery.trim()
                                            if (clean.length >= 2) {
                                                onQuickMovieSearch(clean)
                                            } else {
                                                onOpenDiscover()
                                            }
                                        }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("dashboard_quick_search_input"),
                                )

                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        val clean = quickQuery.trim()
                                        if (clean.length >= 2) {
                                            onQuickMovieSearch(clean)
                                        } else {
                                            onOpenDiscover()
                                        }
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 52.dp)
                                        .testTag("dashboard_quick_search_button"),
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Icon(Icons.Rounded.Search, contentDescription = strings.discover.actionSearchMovies)
                                }
                            }
                        }
                    }
                }
            }

            item(key = "dash_tools_header") {
                Text(
                    text = strings.ui.dashboardQuickToolsTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }

            item(key = "dash_tools_grid") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        QuickToolCard(
                            icon = Icons.Rounded.Movie,
                            title = strings.ui.toolMoviesTitle,
                            subtitle = strings.ui.toolMoviesSubtitle,
                            accentColor = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            onAccentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = onOpenDiscover,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dash_tool_movies"),
                        )
                        QuickToolCard(
                            icon = Icons.Rounded.Link,
                            title = strings.ui.toolResolverTitle,
                            subtitle = strings.ui.toolResolverSubtitle,
                            accentColor = MaterialTheme.colorScheme.secondary,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            onAccentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            onClick = onOpenResolver,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dash_tool_resolver"),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        QuickToolCard(
                            icon = Icons.Rounded.Download,
                            title = strings.ui.toolDownloadsTitle,
                            subtitle = strings.ui.toolDownloadsSubtitle(
                                activeDownloads.size,
                                queuedCount,
                                speedLabel,
                            ),
                            accentColor = MaterialTheme.colorScheme.tertiary,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            onAccentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            onClick = onOpenDownloads,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dash_tool_downloads"),
                        )
                        QuickToolCard(
                            icon = Icons.Rounded.TravelExplore,
                            title = strings.ui.toolBrowserTitle,
                            subtitle = strings.ui.toolBrowserSubtitle,
                            accentColor = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            onAccentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { showBrowserUrlDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dash_tool_browser"),
                        )
                    }
                }
            }

            if (enabledSites.isNotEmpty()) {
                item(key = "dash_sites_header") {
                    Text(
                        text = strings.discover.quickSitesTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                item(key = "dash_sites_row") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(enabledSites, key = { "dash_site_${it.id}" }) { site ->
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
            }

            if (discoveryState.recentSearches.isNotEmpty()) {
                item(key = "dash_recent_searches_header") {
                    Text(
                        text = strings.discover.recentSearchesTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                item(key = "dash_recent_searches_row") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(discoveryState.recentSearches.take(8), key = { "dash_rs_$it" }) { q ->
                            InputChip(
                                selected = false,
                                onClick = { onSelectRecentSearch(q) },
                                label = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }

            if (highlightMedia.isNotEmpty()) {
                item(key = "dash_media_header") {
                    val title = if (discoveryState.favorites.isNotEmpty()) {
                        strings.discover.favoritesTitle
                    } else {
                        strings.discover.recentlyViewedTitle
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                item(key = "dash_media_carousel") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(highlightMedia, key = { "dash_media_${it.id}" }) { item ->
                            Card(
                                modifier = Modifier
                                    .width(128.dp)
                                    .clickable { onOpenMediaDetails(item) },
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
                                            .height(172.dp),
                                    )
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = item.persianTitle ?: item.title,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = buildString {
                                                append(if (item.mediaType == MediaType.TV) strings.discover.badgeTvShow else strings.discover.badgeMovie)
                                                item.releaseYear?.let { append(" • $it") }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (extractorState.sessionHistory.isNotEmpty()) {
                item(key = "dash_resolved_header") {
                    Text(
                        text = strings.recentLinksTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                items(extractorState.sessionHistory.take(3), key = { "dash_res_${it.directVideoUrl ?: it.sourceUrl}" }) { item ->
                    val targetUrl = item.directVideoUrl ?: item.sourceUrl
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectResolvedHistory(item) },
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = item.filename ?: targetUrl,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                val meta = listOfNotNull(item.extension, item.fileSizeFormatted).joinToString(" • ")
                                if (meta.isNotEmpty()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }

    if (showBrowserUrlDialog) {
        OpenBrowserUrlDialog(
            defaultUrl = enabledSites.firstOrNull()?.baseUrl ?: "https://www.google.com",
            strings = strings,
            onDismiss = { showBrowserUrlDialog = false },
            onOpenUrl = { url ->
                showBrowserUrlDialog = false
                onOpenInAppBrowser(url, "MediaX Browser")
            },
        )
    }
}

@Composable
private fun QuickToolCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    containerColor: Color,
    onAccentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .heightIn(min = 104.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(containerColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (containerColor == MaterialTheme.colorScheme.surfaceVariant) accentColor else onAccentColor,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OpenBrowserUrlDialog(
    defaultUrl: String,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var urlText by remember { mutableStateOf(defaultUrl) }
    val normalizedCandidate = remember(urlText) {
        val t = urlText.trim()
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    }
    val isValid = FuzzyTitleMatcher.isSafeHttpUrl(normalizedCandidate)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.TravelExplore, contentDescription = null) },
        title = { Text(strings.ui.openBrowserDialogTitle) },
        text = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text(strings.ui.openBrowserUrlLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { if (isValid) onOpenUrl(normalizedCandidate) }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dashboard_browser_url_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onOpenUrl(normalizedCandidate) },
                enabled = isValid,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(strings.ui.actionOpenUrl)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(strings.actionDialogCancel)
            }
        },
    )
}
