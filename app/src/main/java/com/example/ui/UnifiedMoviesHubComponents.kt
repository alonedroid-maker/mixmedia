package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.media.FuzzyTitleMatcher
import com.example.media.MovieDiscoveryState
import com.example.media.TmdbDiscoverFilters
import com.example.network.CurlResolutionResult
import com.example.network.DownloadCandidateOption
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun UnifiedMoviesHeroAndResolverCard(
    discoveryState: MovieDiscoveryState,
    extractorState: LinkExtractorScreenState,
    activeDownloadCount: Int,
    defaultBrowserUrl: String,
    strings: AppStrings,
    onQueryChanged: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onSelectRecentSearch: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onUpdateDiscoverFilters: ((TmdbDiscoverFilters) -> TmdbDiscoverFilters) -> Unit,
    onClearDiscoverFilters: () -> Unit,
    onExtractorInputChanged: (String) -> Unit,
    onResolveLink: (String) -> Unit,
    onClearExtractor: () -> Unit,
    onCancelExtraction: () -> Unit,
    onDismissInlineResult: () -> Unit,
    onToggleLogExpanded: () -> Unit,
    onSelectHistoryItem: (CurlResolutionResult) -> Unit,
    onAcceptClipboardUrl: () -> Unit,
    onDismissClipboardUrl: () -> Unit,
    onDownloadInApp: (CurlResolutionResult) -> Unit,
    onDownloadAuthorizedUrl: (url: String, suggestedName: String?) -> Unit,
    onOpenXQualityPicker: () -> Unit,
    onSelectDownloadOption: (DownloadCandidateOption) -> Unit = {},
    onOpenDownloadOptionsPicker: () -> Unit = {},
    onOpenDownloads: () -> Unit,
    onOpenInAppBrowser: (url: String, title: String) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var smartText by rememberSaveable {
        mutableStateOf(
            discoveryState.searchQuery.ifBlank { extractorState.inputText }
        )
    }
    var showBrowserDialog by rememberSaveable { mutableStateOf(false) }
    var showLinkToolsDrawer by rememberSaveable { mutableStateOf(false) }

    // Keep smart bar synchronized when an external intent, clipboard accept, or history click updates extractor input
    LaunchedEffect(extractorState.inputText, extractorState.uiState) {
        val extInput = extractorState.inputText.trim()
        if (extInput.isNotEmpty() &&
            classifyHomeInput(extInput) is HomeInputAction.ResolveLink &&
            extInput != smartText.trim() &&
            (extractorState.uiState !is ExtractorUiState.Idle || extractorState.capturedFromExternalIntent)
        ) {
            smartText = extInput
        }
    }

    // Keep smart bar synchronized when recent movie search or clear triggers externally
    LaunchedEffect(discoveryState.searchQuery) {
        val mediaQuery = discoveryState.searchQuery
        if (mediaQuery.isNotBlank() && mediaQuery != smartText) {
            smartText = mediaQuery
        } else if (mediaQuery.isEmpty() && extractorState.inputText.isEmpty() && smartText.isNotEmpty()) {
            smartText = ""
        }
    }

    val detectedAction = remember(smartText) { classifyHomeInput(smartText) }
    val isLinkDetected = detectedAction is HomeInputAction.ResolveLink
    val isResolving = extractorState.uiState is ExtractorUiState.Loading

    val borderAccent by animateColorAsState(
        targetValue = when {
            isLinkDetected -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        },
        label = "unifiedHeroBorderColor",
    )

    fun handleSmartTextChange(newText: String) {
        smartText = newText
        when (classifyHomeInput(newText)) {
            is HomeInputAction.ResolveLink -> {
                if (discoveryState.searchQuery.isNotEmpty()) {
                    onQueryChanged("")
                }
                onExtractorInputChanged(newText)
            }
            is HomeInputAction.SearchMedia -> {
                onQueryChanged(newText)
            }
            HomeInputAction.OpenDiscover -> {
                onQueryChanged("")
                onExtractorInputChanged("")
            }
        }
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        if (!clipText.isNullOrBlank()) {
            handleSmartTextChange(clipText)
        } else {
            onShowMessage(strings.msgNoClipboard)
        }
    }

    fun handlePrimaryAction() {
        focusManager.clearFocus()
        if (isResolving) {
            onCancelExtraction()
            return
        }
        when (val action = classifyHomeInput(smartText)) {
            is HomeInputAction.ResolveLink -> {
                onResolveLink(action.normalizedInput)
            }
            is HomeInputAction.SearchMedia -> {
                onQueryChanged(action.query)
                onSubmitSearch()
            }
            HomeInputAction.OpenDiscover -> {
                if (discoveryState.discoverFilters.hasActiveFilters()) {
                    onSubmitSearch()
                }
            }
        }
    }

    fun copyToClipboard(label: String, text: String, toastMessage: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        onShowMessage(toastMessage)
    }

    fun openExternalUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            onShowMessage(strings.msgNoBrowser)
        }
    }

    fun shareText(url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, strings.actionShareLink))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("unified_movies_hero_card"),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, borderAccent),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Header row: Brand Mark + Title + Resolver Status Pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
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
                                    modifier = Modifier.size(21.dp),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = strings.appName,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (extractorState.isDeveloperMode) {
                                        BadgePill(
                                            text = strings.devBadge,
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                                Text(
                                    text = strings.ui.unifiedHeroTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        UnifiedStatusChip(
                            uiState = extractorState.uiState,
                            isDeveloperMode = extractorState.isDeveloperMode,
                            strings = strings,
                        )
                    }

                    Text(
                        text = strings.ui.unifiedHeroSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Smart Omnibox Input + Action Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = smartText,
                            onValueChange = ::handleSmartTextChange,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("movie_search_input"),
                            placeholder = {
                                Text(
                                    text = strings.ui.unifiedSmartPlaceholder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isLinkDetected) Icons.Rounded.Link else Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = if (isLinkDetected) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (smartText.isEmpty()) {
                                        IconButton(
                                            onClick = ::pasteFromClipboard,
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("unified_smart_paste_button"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.ContentPaste,
                                                contentDescription = strings.cdPasteInput,
                                            )
                                        }
                                    } else {
                                        IconButton(
                                            onClick = {
                                                smartText = ""
                                                onClearSearch()
                                                onClearExtractor()
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("movie_search_clear"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Clear,
                                                contentDescription = strings.cdClearInput,
                                            )
                                        }
                                    }
                                }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (isLinkDetected) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            ),
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (isLinkDetected) ImeAction.Go else ImeAction.Search,
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = { handlePrimaryAction() },
                                onGo = { handlePrimaryAction() },
                            ),
                        )

                        Button(
                            onClick = ::handlePrimaryAction,
                            enabled = isResolving ||
                                isLinkDetected ||
                                smartText.trim().length >= 2 ||
                                discoveryState.discoverFilters.hasActiveFilters(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = when {
                                    isResolving -> MaterialTheme.colorScheme.errorContainer
                                    isLinkDetected -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                contentColor = when {
                                    isResolving -> MaterialTheme.colorScheme.onErrorContainer
                                    isLinkDetected -> MaterialTheme.colorScheme.onSecondary
                                    else -> MaterialTheme.colorScheme.onPrimary
                                },
                            ),
                            modifier = Modifier
                                .heightIn(min = 54.dp)
                                .testTag("movie_search_submit"),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            when {
                                isResolving -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                                discoveryState.isSearching -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                                isLinkDetected -> {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = strings.ui.unifiedActionResolve,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = strings.ui.unifiedActionResolve,
                                        maxLines = 1,
                                    )
                                }
                                else -> {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = strings.discover.actionSearchMovies,
                                        modifier = Modifier.size(19.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = strings.discover.actionSearchMovies,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    // Live smart auto-detect feedback
                    val hintText = when (detectedAction) {
                        is HomeInputAction.ResolveLink -> strings.ui.smartHomeLinkDetected
                        is HomeInputAction.SearchMedia -> strings.ui.smartHomeMediaDetected
                        HomeInputAction.OpenDiscover -> strings.ui.smartHomeHelper
                    }
                    val hintColor = when (detectedAction) {
                        is HomeInputAction.ResolveLink -> MaterialTheme.colorScheme.secondary
                        is HomeInputAction.SearchMedia -> MaterialTheme.colorScheme.primary
                        HomeInputAction.OpenDiscover -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.labelSmall,
                        color = hintColor,
                        modifier = Modifier.testTag("unified_smart_hint"),
                    )

                    // Quick Action Strip: Browser, Link Tools / Recent Links, Downloads
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item(key = "quick_browser_chip") {
                            FilterChip(
                                selected = false,
                                onClick = { showBrowserDialog = true },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.TravelExplore,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = { Text(strings.ui.unifiedQuickBrowser, maxLines = 1) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("unified_quick_browser"),
                            )
                        }

                        item(key = "quick_resolver_tools_chip") {
                            val isActiveDrawer = showLinkToolsDrawer || extractorState.sessionHistory.isNotEmpty()
                            FilterChip(
                                selected = showLinkToolsDrawer,
                                onClick = { showLinkToolsDrawer = !showLinkToolsDrawer },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = {
                                    val suffix = if (extractorState.sessionHistory.isNotEmpty() && !showLinkToolsDrawer) {
                                        " (${extractorState.sessionHistory.size})"
                                    } else {
                                        ""
                                    }
                                    Text(
                                        text = if (showLinkToolsDrawer) {
                                            strings.ui.unifiedHideResolverTools
                                        } else {
                                            "${strings.ui.unifiedQuickResolverTools}$suffix"
                                        },
                                        maxLines = 1,
                                    )
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("unified_toggle_link_tools"),
                            )
                        }

                        item(key = "quick_downloads_chip") {
                            FilterChip(
                                selected = activeDownloadCount > 0,
                                onClick = onOpenDownloads,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = { Text(strings.navDownloads, maxLines = 1) },
                                trailingIcon = if (activeDownloadCount > 0) {
                                    {
                                        Badge { Text(activeDownloadCount.toString()) }
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("unified_quick_downloads"),
                            )
                        }
                    }

                    // Discover Filters (Genre, Rating, Year)
                    DiscoverFilterChipsRow(
                        state = discoveryState,
                        strings = strings,
                        onUpdateDiscoverFilters = onUpdateDiscoverFilters,
                        onClearDiscoverFilters = onClearDiscoverFilters,
                    )

                    // Recent Searches
                    if (discoveryState.recentSearches.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(discoveryState.recentSearches, key = { "rec_$it" }) { query ->
                                InputChip(
                                    selected = false,
                                    onClick = {
                                        focusManager.clearFocus()
                                        smartText = query
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

        // Clipboard Quick-Detect Banner
        AnimatedVisibility(
            visible = extractorState.detectedClipboardUrl != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            extractorState.detectedClipboardUrl?.let { clipUrl ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = strings.clipboardDetectedTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = clipUrl,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.5.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onAcceptClipboardUrl,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("clipboard_extract_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary,
                                ),
                            ) {
                                Text(strings.clipboardExtractAction)
                            }
                            OutlinedButton(
                                onClick = onDismissClipboardUrl,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("clipboard_dismiss_button"),
                            ) {
                                Text(strings.clipboardDismissAction)
                            }
                        }
                    }
                }
            }
        }

        // Inline Resolver Result & Link Tools Panel
        val showResolverArea = extractorState.uiState !is ExtractorUiState.Idle ||
            extractorState.capturedFromExternalIntent ||
            showLinkToolsDrawer

        AnimatedVisibility(
            visible = showResolverArea,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("unified_inline_resolver_panel"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (extractorState.capturedFromExternalIntent) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CastConnected,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = strings.vlcCapturedBanner,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                when (val uiState = extractorState.uiState) {
                    is ExtractorUiState.Idle -> {
                        if (showLinkToolsDrawer && extractorState.sessionHistory.isEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Link,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = strings.ui.unifiedInlineResolverTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = if (extractorState.isDeveloperMode) {
                                                strings.inputHelperDev
                                            } else {
                                                strings.emptyDesc
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is ExtractorUiState.Loading -> {
                        if (extractorState.isDeveloperMode) {
                            TerminalTraceCard(
                                verboseLog = uiState.liveLog,
                                strings = strings,
                                isExpanded = true,
                                onToggleExpand = {},
                                onCopyLog = { copyToClipboard("dev_log", it, strings.msgLogCopied) },
                            )
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.5.dp,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                        Text(
                                            text = strings.loadingNormalMessage,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    TextButton(
                                        onClick = onCancelExtraction,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text(strings.actionCancel)
                                    }
                                }
                            }
                        }
                    }

                    is ExtractorUiState.Success -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = strings.ui.unifiedInlineResolverTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            TextButton(
                                onClick = onDismissInlineResult,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("unified_dismiss_resolver_result"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = strings.ui.unifiedDismissResult,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(strings.ui.unifiedDismissResult)
                            }
                        }

                        val ytInfo = uiState.result.youtubeInfo
                        if (ytInfo != null) {
                            YouTubeCompliantCard(
                                yt = ytInfo,
                                strings = strings,
                                onOpenOfficialUrl = ::openExternalUrl,
                                onCopyLink = { copyToClipboard("youtube_url", it, strings.msgCopied) },
                                onShareLink = ::shareText,
                                onDownloadAuthorizedUrl = onDownloadAuthorizedUrl,
                            )
                        } else {
                            DirectLinkResultCard(
                                result = uiState.result,
                                strings = strings,
                                isDeveloperMode = extractorState.isDeveloperMode,
                                onDownloadInApp = onDownloadInApp,
                                onCopyLink = { copyToClipboard("direct_video_url", it, strings.msgCopied) },
                                onOpenLink = ::openExternalUrl,
                                onShareLink = ::shareText,
                                onOpenXQualityPicker = if (uiState.result.xVideoVariants.size > 1) {
                                    onOpenXQualityPicker
                                } else {
                                    null
                                },
                                onSelectDownloadOption = onSelectDownloadOption,
                                onOpenDownloadOptionsPicker = if (uiState.result.downloadOptions.size > 1) {
                                    onOpenDownloadOptionsPicker
                                } else {
                                    null
                                },
                            )
                        }

                        if (extractorState.isDeveloperMode) {
                            RedirectChainCard(
                                hops = uiState.result.hops,
                                strings = strings,
                            )
                            TerminalTraceCard(
                                verboseLog = uiState.result.verboseLog,
                                strings = strings,
                                isExpanded = extractorState.isLogExpanded,
                                onToggleExpand = onToggleLogExpanded,
                                onCopyLog = { copyToClipboard("dev_log", it, strings.msgLogCopied) },
                            )
                        }
                    }

                    is ExtractorUiState.Error -> {
                        val errorText = when (uiState.errorType) {
                            ErrorType.EMPTY_URL -> strings.errorEmptyUrl
                            ErrorType.INVALID_URL -> strings.errorInvalidUrl
                            ErrorType.NO_VIDEO_FOUND -> strings.errorNoVideoFound
                            ErrorType.YOUTUBE_UNSUPPORTED -> strings.errorYouTubeUnsupported
                            ErrorType.YOUTUBE_UNAVAILABLE -> strings.errorYouTubeUnavailable
                            ErrorType.INSTAGRAM_UNSUPPORTED -> strings.errorInstagramUnsupported
                            ErrorType.X_INVALID_URL -> strings.xDownloader.errorInvalidUrl
                            ErrorType.X_NO_VIDEO -> strings.xDownloader.errorNoVideo
                            ErrorType.X_UNAVAILABLE -> strings.xDownloader.errorUnavailable
                            ErrorType.X_EXTRACTION_FAILED -> strings.xDownloader.errorExtractionFailed
                            ErrorType.HOST_UNREACHABLE -> strings.errorHostUnreachable
                            ErrorType.NETWORK_GENERIC -> strings.errorNetworkGeneric
                            ErrorType.CANCELLED -> strings.errorCancelled
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    text = errorText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = onDismissInlineResult,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("unified_dismiss_resolver_error"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = strings.ui.unifiedDismissResult,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }

                        if (extractorState.isDeveloperMode && uiState.verboseLog.isNotBlank()) {
                            TerminalTraceCard(
                                verboseLog = uiState.verboseLog,
                                strings = strings,
                                isExpanded = extractorState.isLogExpanded,
                                onToggleExpand = onToggleLogExpanded,
                                onCopyLog = { copyToClipboard("dev_log", it, strings.msgLogCopied) },
                            )
                        }
                    }
                }

                if ((showLinkToolsDrawer || extractorState.uiState !is ExtractorUiState.Idle) &&
                    extractorState.sessionHistory.isNotEmpty()
                ) {
                    SessionHistorySection(
                        history = extractorState.sessionHistory,
                        strings = strings,
                        onSelectItem = onSelectHistoryItem,
                        onCopyLink = { copyToClipboard("direct_video_url", it, strings.msgCopied) },
                    )
                }
            }
        }
    }

    if (showBrowserDialog) {
        QuickOpenBrowserDialog(
            defaultUrl = defaultBrowserUrl,
            strings = strings,
            onDismiss = { showBrowserDialog = false },
            onOpenUrl = { url ->
                showBrowserDialog = false
                onOpenInAppBrowser(url, "MediaX Browser")
            },
        )
    }
}

@Composable
private fun UnifiedStatusChip(
    uiState: ExtractorUiState,
    isDeveloperMode: Boolean,
    strings: AppStrings,
) {
    val (label, bg, fg) = when (uiState) {
        is ExtractorUiState.Idle -> Triple(
            strings.statusReady,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is ExtractorUiState.Loading -> Triple(
            if (isDeveloperMode) strings.statusProcessingStep(uiState.currentHop) else strings.statusProcessing,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        is ExtractorUiState.Success -> Triple(
            strings.statusDone,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        is ExtractorUiState.Error -> Triple(
            strings.statusError,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Surface(
        shape = CircleShape,
        color = bg,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun QuickOpenBrowserDialog(
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
                        .testTag("unified_browser_url_input"),
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
