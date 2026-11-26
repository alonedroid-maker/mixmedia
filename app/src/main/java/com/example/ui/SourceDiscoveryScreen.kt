package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.media.FuzzyTitleMatcher
import com.example.media.QueryModifiers
import com.example.media.SavedSourcePage
import com.example.media.SourceDiscoveryBundle
import com.example.media.SourceLinkStatus
import com.example.media.SourceSearchTarget
import com.example.media.SourceUiState
import com.example.media.WebSourceResult
import com.example.network.VideoLinkExtractor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceDiscoveryScreen(
    target: SourceSearchTarget,
    sourceState: SourceUiState,
    modifiers: QueryModifiers,
    customQuery: String,
    verifyingUrlId: String?,
    savedPages: List<SavedSourcePage>,
    strings: AppStrings,
    onBack: () -> Unit,
    onUpdateModifiers: (QueryModifiers) -> Unit,
    onRunCustomQuery: (String) -> Unit,
    onVerifyLink: (WebSourceResult) -> Unit,
    onOpenInAppBrowser: (url: String, title: String) -> Unit,
    onOpenExternal: (url: String) -> Unit,
    onCopyUrl: (url: String) -> Unit,
    onToggleBookmark: (url: String, title: String) -> Unit,
    onDownloadCandidate: (url: String, fileName: String, typeLabel: String) -> Unit,
    onSendToResolver: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var queryDraft by remember(customQuery) { mutableStateOf(customQuery) }
    val focusManager = LocalFocusManager.current
    val seTag = FuzzyTitleMatcher.formatSeasonEpisodeTag(target.seasonNumber, target.episodeNumber)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "sources_topbar") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("sources_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.discover.detailsBack)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.discover.detailsBack)
                    }
                    Text(
                        text = strings.discover.sourcesScreenTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            item(key = "sources_target_card") {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = buildString {
                                    append(target.title)
                                    target.releaseYear?.let { append(" ($it)") }
                                    if (seTag != null) append(" • $seTag")
                                },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        val persianTitle = target.persianTitle?.takeIf { it.isNotBlank() }
                        if (persianTitle != null) {
                            Text(
                                text = persianTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(
                                selected = modifiers.includeDownload,
                                onClick = { onUpdateModifiers(modifiers.copy(includeDownload = !modifiers.includeDownload)) },
                                label = { Text(strings.discover.sourcesModDownload) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                            FilterChip(
                                selected = modifiers.includeSubtitle,
                                onClick = { onUpdateModifiers(modifiers.copy(includeSubtitle = !modifiers.includeSubtitle)) },
                                label = { Text(strings.discover.sourcesModSubtitle) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                            FilterChip(
                                selected = modifiers.includeDubbed,
                                onClick = { onUpdateModifiers(modifiers.copy(includeDubbed = !modifiers.includeDubbed)) },
                                label = { Text(strings.discover.sourcesModDubbed) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                            FilterChip(
                                selected = modifiers.includeWatchOnline,
                                onClick = { onUpdateModifiers(modifiers.copy(includeWatchOnline = !modifiers.includeWatchOnline)) },
                                label = { Text(strings.discover.sourcesModOnline) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = queryDraft,
                                onValueChange = { queryDraft = it },
                                label = { Text(strings.discover.sourcesCustomQueryLabel) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sources_custom_query_input"),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        focusManager.clearFocus()
                                        onRunCustomQuery(queryDraft)
                                    }
                                ),
                            )
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    onRunCustomQuery(queryDraft)
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = strings.discover.sourcesRunSearch, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            when (sourceState) {
                is SourceUiState.Loading -> {
                    item(key = "sources_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                is SourceUiState.Error -> {
                    item(key = "sources_error") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        ) {
                            Text(
                                text = sourceState.message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }

                is SourceUiState.Success -> {
                    val bundle: SourceDiscoveryBundle = sourceState.bundle

                    // Generated Queries + Search Engine Launch Chips
                    item(key = "sources_engines") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(strings.discover.sourcesSearchEnginesTitle, style = MaterialTheme.typography.titleSmall)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    bundle.searchEngineLaunchUrls.forEach { (engine, launchUrl) ->
                                        AssistChip(
                                            onClick = { onOpenInAppBrowser(launchUrl, "${engine.displayName}: ${target.title}") },
                                            label = { Text("${engine.displayName} • ${strings.discover.actionOpenInAppBrowser}") },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                                            },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        )
                                    }
                                }

                                bundle.generatedQueries.forEach { q ->
                                    val engine = bundle.searchEngineLaunchUrls.firstOrNull()?.first
                                        ?: com.example.media.SearchEngineOption.GOOGLE
                                    val url = FuzzyTitleMatcher.buildSearchEngineUrl(engine, q)
                                    TextButton(
                                        onClick = { onOpenInAppBrowser(url, q) },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = q,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (bundle.siteNotices.isNotEmpty()) {
                        item(key = "sources_notices") {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    bundle.siteNotices.forEach { notice ->
                                        Text(
                                            text = "• $notice",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "configured_sites_header") {
                        Text(
                            text = strings.discover.sourcesConfiguredSitesTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (bundle.siteResults.isEmpty()) {
                        item(key = "configured_sites_empty") {
                            Text(
                                text = strings.discover.sourcesEmptySiteNotice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(bundle.siteResults, key = { "site_${it.id}" }) { item ->
                            SourceResultCard(
                                item = item,
                                isVerifying = verifyingUrlId == item.id,
                                isBookmarked = savedPages.any { it.url == item.url },
                                strings = strings,
                                onOpenInApp = { onOpenInAppBrowser(item.url, item.title) },
                                onOpenExternal = { onOpenExternal(item.url) },
                                onCheckLink = { onVerifyLink(item) },
                                onCopyUrl = { onCopyUrl(item.url) },
                                onToggleBookmark = { onToggleBookmark(item.url, item.title) },
                                onDownload = {
                                    val name = VideoLinkExtractor.extractFilename(item.url)
                                    onDownloadCandidate(item.url, name, item.detectedExtension ?: "FILE")
                                },
                                onSendToResolver = { onSendToResolver(item.url) },
                            )
                        }
                    }

                    if (bundle.webSearchResults.isNotEmpty()) {
                        item(key = "web_results_header") {
                            Text(
                                text = strings.discover.sourcesWebResultsTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(bundle.webSearchResults, key = { "web_${it.id}" }) { item ->
                            SourceResultCard(
                                item = item,
                                isVerifying = verifyingUrlId == item.id,
                                isBookmarked = savedPages.any { it.url == item.url },
                                strings = strings,
                                onOpenInApp = { onOpenInAppBrowser(item.url, item.title) },
                                onOpenExternal = { onOpenExternal(item.url) },
                                onCheckLink = { onVerifyLink(item) },
                                onCopyUrl = { onCopyUrl(item.url) },
                                onToggleBookmark = { onToggleBookmark(item.url, item.title) },
                                onDownload = {
                                    val name = VideoLinkExtractor.extractFilename(item.url)
                                    onDownloadCandidate(item.url, name, item.detectedExtension ?: "FILE")
                                },
                                onSendToResolver = { onSendToResolver(item.url) },
                            )
                        }
                    }
                }

                SourceUiState.Idle -> Unit
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceResultCard(
    item: WebSourceResult,
    isVerifying: Boolean,
    isBookmarked: Boolean,
    strings: AppStrings,
    onOpenInApp: () -> Unit,
    onOpenExternal: () -> Unit,
    onCheckLink: () -> Unit,
    onCopyUrl: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDownload: () -> Unit,
    onSendToResolver: () -> Unit,
) {
    val statusText = when (item.status) {
        SourceLinkStatus.VERIFIED_ACCESSIBLE -> strings.discover.statusVerifiedAccessible
        SourceLinkStatus.SEARCH_RESULT -> strings.discover.statusSearchResult
        SourceLinkStatus.WEBSITE_PAGE -> strings.discover.statusWebsitePage
        SourceLinkStatus.NOT_CHECKED -> strings.discover.statusNotChecked
        SourceLinkStatus.UNAVAILABLE -> strings.discover.statusUnavailable
        SourceLinkStatus.UNKNOWN -> strings.discover.statusUnknown
    }

    val statusColor = when (item.status) {
        SourceLinkStatus.VERIFIED_ACCESSIBLE -> MaterialTheme.colorScheme.secondaryContainer
        SourceLinkStatus.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.siteName} • ${item.domain}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = strings.discover.actionBookmarkPage,
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (item.snippet.isNotBlank()) {
                Text(
                    text = item.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val honestBadges = remember(item) { parseHonestSourceBadges(item) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor,
                ) {
                    Text(
                        text = item.statusNote ?: statusText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (item.isDirectDownloadCandidate) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = if (item.isDirectDownloadCandidate) {
                            strings.ui.sourceBadgeDirectFile
                        } else {
                            strings.ui.sourceBadgeWebPage
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (item.isDirectDownloadCandidate) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }

                honestBadges.forEach { badge ->
                    val (bg, fg) = when (badge.category) {
                        SourceMetaCategory.QUALITY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                        SourceMetaCategory.FORMAT -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                        SourceMetaCategory.AUDIO_SUB -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = bg,
                    ) {
                        Text(
                            text = badge.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = fg,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = onOpenInApp,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.discover.actionOpenInAppBrowser, style = MaterialTheme.typography.labelMedium)
                }

                if (item.isDirectDownloadCandidate) {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.discover.actionDownloadConfirm, style = MaterialTheme.typography.labelMedium)
                    }
                }

                OutlinedButton(
                    onClick = onCheckLink,
                    enabled = !isVerifying,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    if (isVerifying) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(strings.discover.actionCheckLink, style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = onOpenExternal,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(strings.discover.actionOpenExternalBrowser, style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = onSendToResolver,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(strings.discover.actionResolveInFixLink, style = MaterialTheme.typography.labelSmall)
                }

                IconButton(
                    onClick = onCopyUrl,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = strings.actionCopyUrl)
                }
            }
        }
    }
}

enum class SourceMetaCategory {
    QUALITY,
    FORMAT,
    AUDIO_SUB,
}

data class SourceMetaBadge(
    val label: String,
    val category: SourceMetaCategory,
)

fun parseHonestSourceBadges(item: WebSourceResult): List<SourceMetaBadge> {
    val combined = listOf(item.title, item.snippet, item.url).joinToString(" ")
    val lower = combined.lowercase()
    val badges = mutableListOf<SourceMetaBadge>()

    // Only emit badges for markers explicitly present in the genuine text/URL
    when {
        Regex("""\b(2160p|4k|uhd)\b""").containsMatchIn(lower) -> badges.add(SourceMetaBadge("4K / 2160p", SourceMetaCategory.QUALITY))
        Regex("""\b1080p\b""").containsMatchIn(lower) -> badges.add(SourceMetaBadge("1080p", SourceMetaCategory.QUALITY))
        Regex("""\b720p\b""").containsMatchIn(lower) -> badges.add(SourceMetaBadge("720p", SourceMetaCategory.QUALITY))
        Regex("""\b480p\b""").containsMatchIn(lower) -> badges.add(SourceMetaBadge("480p", SourceMetaCategory.QUALITY))
    }

    val ext = item.detectedExtension?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    if (ext != null) {
        badges.add(SourceMetaBadge(ext, SourceMetaCategory.FORMAT))
    } else {
        when {
            Regex("""\bmkv\b""").containsMatchIn(lower) -> badges.add(SourceMetaBadge("MKV", SourceMetaCategory.FORMAT))
            Regex("""\bmp4\b""").containsMatchIn(lower) -> badges.add(SourceMetaBadge("MP4", SourceMetaCategory.FORMAT))
            Regex("""\bm3u8\b""").containsMatchIn(lower) -> badges.add(SourceMetaBadge("HLS", SourceMetaCategory.FORMAT))
        }
    }

    if (lower.contains("دوبله") || Regex("""\bdubbed\b|\bpersian dub\b""").containsMatchIn(lower)) {
        badges.add(SourceMetaBadge("دوبله فارسی / Dub", SourceMetaCategory.AUDIO_SUB))
    }
    if (lower.contains("زیرنویس") || Regex("""\bsoftsub\b|\bhardsub\b|\bpersian sub\b""").containsMatchIn(lower)) {
        badges.add(SourceMetaBadge("زیرنویس / Sub", SourceMetaCategory.AUDIO_SUB))
    }

    return badges.distinctBy { it.label }
}
