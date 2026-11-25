package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.media.MediaPreferences
import com.example.media.SearchEngineOption
import com.example.media.SearchLanguagePreference
import com.example.media.SourceSiteConfig
import com.example.media.WatchRegions
import com.example.network.VideoLinkExtractor

@Composable
fun MovieDiscoverySettingsSection(
    preferences: MediaPreferences,
    sourceSites: List<SourceSiteConfig>,
    cacheSizeBytes: Long,
    strings: AppStrings,
    onUpdatePreferences: ((MediaPreferences) -> MediaPreferences) -> Unit,
    onToggleSite: (String, Boolean) -> Unit,
    onAddCustomSite: (name: String, url: String) -> Boolean,
    onRemoveCustomSite: (String) -> Unit,
    onClearCache: () -> Unit,
    onClearSearchHistory: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    var omdbDraft by remember(preferences.omdbApiKey) { mutableStateOf(preferences.omdbApiKey) }
    var youtubeDraft by remember(preferences.youtubeApiKey) { mutableStateOf(preferences.youtubeApiKey) }
    var showOmdbKey by remember { mutableStateOf(false) }
    var showYoutubeKey by remember { mutableStateOf(false) }
    var showAddSiteDialog by remember { mutableStateOf(false) }

    Text(
        text = strings.discover.sectionMovieDiscovery,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    // Optional Third-Party Keys Card (OMDb & YouTube Data API only — TMDB requires no user key)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(strings.discover.omdbKeyLabel, style = MaterialTheme.typography.titleSmall)
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = omdbDraft,
                    onValueChange = {
                        omdbDraft = it
                        onUpdatePreferences { p -> p.copy(omdbApiKey = it) }
                    },
                    label = { Text(strings.discover.omdbKeyLabel) },
                    singleLine = true,
                    visualTransformation = if (showOmdbKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showOmdbKey = !showOmdbKey }) {
                            Icon(
                                imageVector = if (showOmdbKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_omdb_key_input"),
                )
            }
            Text(
                text = strings.discover.omdbKeyHelper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = youtubeDraft,
                    onValueChange = {
                        youtubeDraft = it
                        onUpdatePreferences { p -> p.copy(youtubeApiKey = it) }
                    },
                    label = { Text(strings.discover.youtubeKeyLabel) },
                    singleLine = true,
                    visualTransformation = if (showYoutubeKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showYoutubeKey = !showYoutubeKey }) {
                            Icon(
                                imageVector = if (showYoutubeKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_youtube_key_input"),
                )
            }
            Text(
                text = strings.discover.youtubeKeyHelper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Search Language, Engine, Browser & Notifications Preferences
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.discover.searchLangLabel, style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    SearchLanguagePreference.EN to strings.discover.searchLangEn,
                    SearchLanguagePreference.FA to strings.discover.searchLangFa,
                    SearchLanguagePreference.BOTH to strings.discover.searchLangBoth,
                )
                options.forEachIndexed { idx, (pref, label) ->
                    SegmentedButton(
                        selected = preferences.searchLanguage == pref,
                        onClick = { onUpdatePreferences { it.copy(searchLanguage = pref) } },
                        shape = SegmentedButtonDefaults.itemShape(idx, options.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }

            Text(strings.discover.searchEngineLabel, style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SearchEngineOption.entries.forEachIndexed { idx, engine ->
                    SegmentedButton(
                        selected = preferences.searchEngine == engine,
                        onClick = { onUpdatePreferences { it.copy(searchEngine = engine) } },
                        shape = SegmentedButtonDefaults.itemShape(idx, SearchEngineOption.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(engine.displayName, maxLines = 1)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            Text(strings.discover.watchRegionLabel, style = MaterialTheme.typography.titleSmall)
            val isPersianUi = strings.settingsTitle == "تنظیمات"
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(WatchRegions.SUPPORTED, key = { it.code }) { region ->
                    val selected = preferences.watchRegion.equals(region.code, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = { onUpdatePreferences { it.copy(watchRegion = region.code) } },
                        label = {
                            Text(
                                text = if (isPersianUi) region.labelFa else region.labelEn,
                                maxLines = 1,
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("settings_region_${region.code}"),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(strings.discover.preferInternalBrowserTitle, style = MaterialTheme.typography.titleSmall)
                        Text(
                            strings.discover.preferInternalBrowserDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = preferences.preferInternalBrowser,
                    onCheckedChange = { enabled ->
                        onUpdatePreferences { it.copy(preferInternalBrowser = enabled) }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(strings.discover.downloadNotificationsTitle, style = MaterialTheme.typography.titleSmall)
                        Text(
                            strings.discover.downloadNotificationsDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = preferences.downloadNotificationsEnabled,
                    onCheckedChange = { enabled ->
                        onUpdatePreferences { it.copy(downloadNotificationsEnabled = enabled) }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
            }
        }
    }

    // Source Sites Configuration Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.MovieFilter, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(strings.discover.sourceSitesManagerTitle, style = MaterialTheme.typography.titleSmall)
                }
                OutlinedButton(
                    onClick = { showAddSiteDialog = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(strings.discover.actionAddSite, style = MaterialTheme.typography.labelMedium)
                }
            }

            sourceSites.forEach { site ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(site.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = site.baseUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (site.isCustom) {
                            IconButton(
                                onClick = { onRemoveCustomSite(site.id) },
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = strings.actionDelete)
                            }
                        }
                        Switch(
                            checked = site.enabled,
                            onCheckedChange = { checked -> onToggleSite(site.id, checked) },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        )
                    }
                }
            }
        }
    }

    // Cache, Search History & Attribution Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val formattedCache = VideoLinkExtractor.formatByteSize(cacheSizeBytes) ?: "0 B"
            Text(
                text = strings.discover.cacheSizeLabel(formattedCache),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onClearCache()
                        onShowMessage(strings.discover.msgCacheCleared)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.discover.actionClearCache, style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = {
                        onClearSearchHistory()
                        onShowMessage(strings.discover.msgHistoryCleared)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.discover.actionClearHistory, style = MaterialTheme.typography.labelMedium)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = strings.discover.aboutSectionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = strings.discover.tmdbAttributionText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                    Text(
                        text = strings.discover.justWatchAttributionText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }

    if (showAddSiteDialog) {
        var siteName by remember { mutableStateOf("") }
        var siteUrl by remember { mutableStateOf("") }
        var urlError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddSiteDialog = false },
            title = { Text(strings.discover.addCustomSiteTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = siteName,
                        onValueChange = { siteName = it },
                        label = { Text(strings.discover.customSiteNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = siteUrl,
                            onValueChange = {
                                siteUrl = it
                                urlError = false
                            },
                            label = { Text(strings.discover.customSiteUrlLabel) },
                            isError = urlError,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (urlError) {
                        Text(
                            text = strings.discover.msgInvalidSiteUrl,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (onAddCustomSite(siteName, siteUrl)) {
                            showAddSiteDialog = false
                            onShowMessage(strings.discover.msgSiteAdded)
                        } else {
                            urlError = true
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(strings.discover.actionAddSite)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddSiteDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(strings.actionDialogCancel)
                }
            },
        )
    }
}
