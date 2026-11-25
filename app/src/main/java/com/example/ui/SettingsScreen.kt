package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.download.DownloadSaveLocation
import com.example.download.QueueConcurrency
import com.example.media.MediaPreferences
import com.example.media.MovieDiscoveryState
import com.example.media.TmdbBackendDiagnostic
import com.example.network.GitHubUpdateChecker
import com.example.network.GitHubUpdateResult
import com.example.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    state: LinkExtractorScreenState,
    concurrencyLimit: QueueConcurrency,
    connectionsPerDownload: Int,
    saveLocation: DownloadSaveLocation,
    currentVersion: String,
    strings: AppStrings,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit = {},
    onConcurrencyChanged: (QueueConcurrency) -> Unit,
    onConnectionsChanged: (Int) -> Unit,
    onPickSaveFolder: () -> Unit,
    onResetSaveFolder: () -> Unit,
    onToggleVlcInterceptor: (Boolean) -> Unit,
    onOpenVlcSettings: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenGitHubUrl: (String) -> Unit,
    onDownloadReleaseApk: (url: String, fileName: String?) -> Unit,
    onAttemptUnlockDeveloper: (String) -> Boolean,
    onDisableDeveloperMode: () -> Unit,
    discoveryState: MovieDiscoveryState? = null,
    onUpdateMediaPreferences: (((MediaPreferences) -> MediaPreferences) -> Unit)? = null,
    onToggleSourceSite: ((String, Boolean) -> Unit)? = null,
    onAddCustomSourceSite: ((String, String) -> Boolean)? = null,
    onRemoveCustomSourceSite: ((String) -> Unit)? = null,
    onClearMediaCache: (() -> Unit)? = null,
    onClearSearchHistory: (() -> Unit)? = null,
    onShowMessage: ((String) -> Unit)? = null,
    onUpdateTmdbProxyUrl: ((String) -> Unit)? = null,
    onTestTmdbBackend: ((String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showPasswordDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = strings.settingsTitle,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            // 1. Language Switcher (English default / Persian)
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                text = strings.sectionLanguage,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = strings.languageDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AppLanguage.entries.forEachIndexed { index, lang ->
                            SegmentedButton(
                                selected = state.language == lang,
                                onClick = { onSelectLanguage(lang) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = AppLanguage.entries.size,
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("lang_option_${lang.code}"),
                            ) {
                                Text(text = lang.displayName)
                            }
                        }
                    }
                }
            }

            // 1B. Appearance (System / Dark / Light Theme Switcher)
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                text = strings.ui.sectionAppearance,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = strings.ui.appearanceDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val themeOptions = listOf(
                            ThemeMode.SYSTEM to strings.ui.themeSystem,
                            ThemeMode.DARK to strings.ui.themeDark,
                            ThemeMode.LIGHT to strings.ui.themeLight,
                        )
                        themeOptions.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = state.themeMode == mode,
                                onClick = { onSelectThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = themeOptions.size,
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("theme_option_${mode.code}"),
                            ) {
                                Text(text = label, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // 2. Download Queue & Multi-Segment Settings
            Text(
                text = strings.sectionQueueSettings,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            QueueSettingsCard(
                concurrencyLimit = concurrencyLimit,
                connectionsPerDownload = connectionsPerDownload,
                saveLocation = saveLocation,
                strings = strings,
                onConcurrencyChanged = onConcurrencyChanged,
                onConnectionsChanged = onConnectionsChanged,
                onPickSaveFolder = onPickSaveFolder,
                onResetSaveFolder = onResetSaveFolder,
            )

            // 3. External Player Receiver (moved completely to Settings)
            Text(
                text = strings.sectionPlayerCapture,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = if (state.isVlcInterceptorEnabled) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (state.isVlcInterceptorEnabled) {
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                    },
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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
                            Icon(
                                imageVector = Icons.Rounded.CastConnected,
                                contentDescription = null,
                                tint = if (state.isVlcInterceptorEnabled) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = strings.vlcCardTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = if (state.isVlcInterceptorEnabled) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ) {
                                    Text(
                                        text = if (state.isVlcInterceptorEnabled) strings.vlcBadgeOn else strings.vlcBadgeOff,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (state.isVlcInterceptorEnabled) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        Switch(
                            checked = state.isVlcInterceptorEnabled,
                            onCheckedChange = onToggleVlcInterceptor,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .semantics { contentDescription = strings.cdVlcSwitch }
                                .testTag("settings_vlc_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                                checkedTrackColor = MaterialTheme.colorScheme.secondary,
                            ),
                        )
                    }

                    Text(
                        text = strings.vlcCardDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        text = if (state.isVlcInstalled) strings.vlcInstalledNote else strings.vlcNotInstalledNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (state.isVlcInstalled) {
                        OutlinedButton(
                            onClick = onOpenVlcSettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("open_vlc_settings_button"),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = strings.vlcClearDefaultsButton)
                        }
                    }

                    Text(
                        text = strings.vlcHelpTip,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
            }

            // 4. App Updates (GitHub)
            Text(
                text = strings.sectionUpdates,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            GitHubUpdateCard(
                currentVersion = currentVersion,
                updateState = state.updateCheckState,
                strings = strings,
                onCheckUpdates = onCheckUpdates,
                onOpenGitHubUrl = onOpenGitHubUrl,
                onDownloadReleaseApk = onDownloadReleaseApk,
            )

            // 5. Developer Mode Section
            Text(
                text = strings.sectionDeveloper,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = if (state.isDeveloperMode) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (state.isDeveloperMode) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                    },
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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
                            Icon(
                                imageVector = if (state.isDeveloperMode) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = if (state.isDeveloperMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = strings.devModeTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = if (state.isDeveloperMode) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ) {
                                    Text(
                                        text = if (state.isDeveloperMode) strings.devModeBadgeOn else strings.devModeBadgeOff,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (state.isDeveloperMode) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        Switch(
                            checked = state.isDeveloperMode,
                            onCheckedChange = { wantEnable ->
                                if (wantEnable) {
                                    showPasswordDialog = true
                                } else {
                                    onDisableDeveloperMode()
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .semantics { contentDescription = strings.cdDevSwitch }
                                .testTag("developer_mode_switch"),
                        )
                    }

                    Text(
                        text = if (state.isDeveloperMode) strings.devModeDescUnlocked else strings.devModeDescLocked,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if ((state.isDeveloperMode || BuildConfig.DEBUG) && discoveryState != null && onTestTmdbBackend != null) {
                DeveloperTmdbBackendCard(
                    proxyUrl = discoveryState.preferences.tmdbProxyUrl,
                    diagnostic = discoveryState.tmdbBackendDiagnostic,
                    strings = strings,
                    onSaveProxyUrl = { url -> onUpdateTmdbProxyUrl?.invoke(url) },
                    onTestConnection = onTestTmdbBackend,
                )
            }

            if (discoveryState != null && onUpdateMediaPreferences != null &&
                onToggleSourceSite != null && onAddCustomSourceSite != null &&
                onRemoveCustomSourceSite != null && onClearMediaCache != null &&
                onClearSearchHistory != null && onShowMessage != null
            ) {
                MovieDiscoverySettingsSection(
                    preferences = discoveryState.preferences,
                    sourceSites = discoveryState.sourceSites,
                    cacheSizeBytes = discoveryState.cacheSizeBytes,
                    strings = strings,
                    onUpdatePreferences = onUpdateMediaPreferences,
                    onToggleSite = onToggleSourceSite,
                    onAddCustomSite = onAddCustomSourceSite,
                    onRemoveCustomSite = onRemoveCustomSourceSite,
                    onClearCache = onClearMediaCache,
                    onClearSearchHistory = onClearSearchHistory,
                    onShowMessage = onShowMessage,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showPasswordDialog) {
        DeveloperPasswordDialog(
            strings = strings,
            onDismiss = { showPasswordDialog = false },
            onAttemptUnlock = { entered ->
                val ok = onAttemptUnlockDeveloper(entered)
                if (ok) {
                    showPasswordDialog = false
                }
                ok
            },
        )
    }
}

@Composable
private fun GitHubUpdateCard(
    currentVersion: String,
    updateState: UpdateCheckState,
    strings: AppStrings,
    onCheckUpdates: () -> Unit,
    onOpenGitHubUrl: (String) -> Unit,
    onDownloadReleaseApk: (url: String, fileName: String?) -> Unit,
) {
    val isChecking = updateState is UpdateCheckState.Checking

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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(
                        text = strings.updatesTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = strings.updatesCurrentVersion("$currentVersion (${BuildConfig.VERSION_CODE})"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onCheckUpdates,
                    enabled = !isChecking,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("check_updates_button"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isChecking) strings.updatesChecking else strings.actionCheckUpdates)
                }

                OutlinedButton(
                    onClick = { onOpenGitHubUrl(GitHubUpdateChecker.REPO_URL) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("open_github_repo_button"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.actionOpenGitHub)
                }
            }

            if (updateState is UpdateCheckState.Completed) {
                when (val result = updateState.result) {
                    is GitHubUpdateResult.UpdateAvailable -> {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = strings.updatesAvailableTitle(result.latestVersion),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                if (result.releaseNotes.isNotBlank()) {
                                    Text(
                                        text = result.releaseNotes,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 4,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (!result.apkDownloadUrl.isNullOrBlank()) {
                                        Button(
                                            onClick = {
                                                onDownloadReleaseApk(result.apkDownloadUrl, result.apkFileName)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("download_release_apk_button"),
                                        ) {
                                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(strings.actionDownloadApk)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { onOpenGitHubUrl(result.releaseUrl) },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("open_release_page_button"),
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(strings.actionOpenReleasePage)
                                    }
                                }
                            }
                        }
                    }

                    is GitHubUpdateResult.UpToDate -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = strings.updatesUpToDate(result.latestVersion),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }

                    is GitHubUpdateResult.NoReleasesYet -> {
                        Text(
                            text = strings.updatesNoReleases,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is GitHubUpdateResult.Error -> {
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeveloperPasswordDialog(
    strings: AppStrings,
    onDismiss: () -> Unit,
    onAttemptUnlock: (String) -> Boolean,
) {
    var password by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    fun submit() {
        val success = onAttemptUnlock(password.trim())
        if (!success) {
            isError = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(text = strings.devDialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = strings.devDialogDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            isError = false
                        },
                        label = { Text(strings.devPasswordLabel) },
                        isError = isError,
                        singleLine = true,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("developer_password_input"),
                    )
                }

                if (isError) {
                    Text(
                        text = strings.devPasswordError,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("developer_password_error"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::submit,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("developer_unlock_button"),
            ) {
                Text(strings.actionUnlock)
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

@Composable
private fun DeveloperTmdbBackendCard(
    proxyUrl: String,
    diagnostic: TmdbBackendDiagnostic,
    strings: AppStrings,
    onSaveProxyUrl: (String) -> Unit,
    onTestConnection: (String?) -> Unit,
) {
    var draftUrl by remember(proxyUrl) { mutableStateOf(proxyUrl) }

    val statusText = when {
        diagnostic.isTesting -> strings.discover.devTmdbTesting
        !diagnostic.isConfigured -> strings.discover.devTmdbStatusUnconfigured
        diagnostic.isReachable == true -> strings.discover.devTmdbStatusConnected
        diagnostic.isReachable == false || !diagnostic.errorMessage.isNullOrBlank() -> strings.discover.devTmdbStatusError
        else -> strings.discover.devTmdbStatusUnconfigured
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dev_tmdb_backend_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = strings.discover.devTmdbBackendTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = when (diagnostic.isReachable) {
                        true -> MaterialTheme.colorScheme.secondaryContainer
                        false -> MaterialTheme.colorScheme.errorContainer
                        null -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = when (diagnostic.isReachable) {
                            true -> MaterialTheme.colorScheme.onSecondaryContainer
                            false -> MaterialTheme.colorScheme.onErrorContainer
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Text(
                text = strings.discover.devTmdbBackendDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = draftUrl,
                    onValueChange = { draftUrl = it },
                    label = { Text(strings.discover.devTmdbProxyUrlLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dev_tmdb_proxy_url_input"),
                )
            }

            Text(
                text = strings.discover.devTmdbProxyUrlHelper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onSaveProxyUrl(draftUrl) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("dev_save_tmdb_proxy_button"),
                ) {
                    Text(strings.discover.devTmdbSaveUrl)
                }

                Button(
                    onClick = { onTestConnection(draftUrl.trim().ifBlank { null }) },
                    enabled = !diagnostic.isTesting,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("dev_test_tmdb_backend_button"),
                ) {
                    if (diagnostic.isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(if (diagnostic.isTesting) strings.discover.devTmdbTesting else strings.discover.devTmdbTestConnection)
                }
            }

            if (diagnostic.endpointHost.isNotBlank() ||
                diagnostic.httpStatus != null ||
                diagnostic.tmdbSecretConfigured != null ||
                !diagnostic.errorMessage.isNullOrBlank()
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (diagnostic.endpointHost.isNotBlank()) {
                            Text(
                                text = "Host: ${diagnostic.endpointHost}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        diagnostic.tmdbSecretConfigured?.let { configured ->
                            Text(
                                text = if (configured) {
                                    strings.discover.devTmdbSecretConfiguredYes
                                } else {
                                    strings.discover.devTmdbSecretConfiguredNo
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        val metrics = buildList {
                            diagnostic.httpStatus?.let { add("HTTP $it") }
                            diagnostic.latencyMs?.let { add("${it}ms") }
                            diagnostic.authMode?.let { add("auth=$it") }
                        }.joinToString(" • ")
                        if (metrics.isNotBlank()) {
                            Text(
                                text = metrics,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!diagnostic.errorMessage.isNullOrBlank()) {
                            Text(
                                text = diagnostic.errorMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

