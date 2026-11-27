package com.example.desktop

import com.example.download.DownloadStatus
import com.example.download.DownloadTask
import com.example.download.QueueConcurrency
import com.example.media.FuzzyTitleMatcher
import com.example.media.MediaPreferences
import com.example.media.MetadataRepository
import com.example.media.SearchEngineOption
import com.example.media.SearchLanguagePreference
import com.example.media.SourceSiteConfig
import com.example.media.SourceSitesCatalog
import com.example.media.WatchRegions
import com.example.network.GitHubUpdateChecker
import com.example.network.GitHubUpdateResult
import com.example.network.VideoLinkExtractor
import com.example.security.DeveloperAccessVerifier
import com.example.ui.AppLanguage
import com.example.ui.stringsFor
import com.example.ui.theme.ThemeMode
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.border.EmptyBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Multi-connection 1-8 parallel Download Manager panel for Windows 7..11+ Desktop.
 */
class DesktopDownloadsPanel(
    private val ownerFrame: JFrame,
    private val downloadEngine: DesktopDownloadEngine,
    private val uiScope: CoroutineScope,
    private val getSettings: () -> DesktopAppSettings,
) : JPanel(BorderLayout(12, 12)) {

    private var strings = stringsFor(getSettings().language)
    private var selectedFilter: DownloadFilter = DownloadFilter.ALL

    private enum class DownloadFilter { ALL, ACTIVE, QUEUED, COMPLETED }

    private val subtitleLabel = DesktopTheme.mutedLabel("")
    private val savePathLabel = DesktopTheme.mutedLabel("")
    private val listPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        isOpaque = false
        border = EmptyBorder(12, 16, 12, 16)
        add(buildHeaderCard(), BorderLayout.NORTH)
        add(
            JScrollPane(listPanel).apply {
                border = null
                viewport.background = DesktopTheme.palette.bg
                verticalScrollBar.unitIncrement = 18
            },
            BorderLayout.CENTER,
        )

        uiScope.launch {
            downloadEngine.tasks.collectLatest { tasks ->
                renderTasks(tasks)
            }
        }
        uiScope.launch {
            downloadEngine.saveLocation.collectLatest { loc ->
                savePathLabel.text = "${strings.saveLocationTitle}: ${loc.displayPath}"
            }
        }
    }

    fun promptEnqueueUrl(url: String, suggestedName: String? = null, customUa: String? = null) {
        val fileName = suggestedName?.takeIf { it.isNotBlank() }
            ?: VideoLinkExtractor.extractFilename(url)
        when (val res = downloadEngine.enqueueDownload(url, fileName, customUserAgent = customUa)) {
            is DesktopEnqueueResult.Started -> Unit
            is DesktopEnqueueResult.Conflict -> {
                val msg = strings.conflictDialogMessage(res.existing.fileName, downloadEngine.saveLocation.value.displayPath)
                val choice = JOptionPane.showOptionDialog(
                    ownerFrame,
                    msg,
                    strings.conflictDialogTitle,
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    arrayOf(strings.actionConflictRename, strings.actionConflictOverwrite, strings.actionCancel),
                    strings.actionConflictRename,
                )
                when (choice) {
                    0 -> downloadEngine.enqueueDownload(url, fileName, customUa, allowDuplicateRename = true)
                    1 -> downloadEngine.enqueueDownload(url, fileName, customUa, allowDuplicateRename = true, replaceExistingId = res.existing.id)
                }
            }
        }
    }

    private fun buildHeaderCard(): JPanel {
        val card = DesktopTheme.heroCard(paddingV = 16, paddingH = 18)
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)

        val topRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        val titleStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        titleStack.add(DesktopTheme.sectionTitle(strings.downloadsTitle))
        titleStack.add(subtitleLabel)
        titleStack.add(savePathLabel)
        topRow.add(titleStack, BorderLayout.LINE_START)

        val btnsRow = JPanel(FlowLayout(FlowLayout.TRAILING, 6, 4)).apply { isOpaque = false }
        btnsRow.add(
            DesktopTheme.primaryButton("+ ${strings.actionAddDownload}").apply {
                addActionListener {
                    val url = JOptionPane.showInputDialog(ownerFrame, strings.addDownloadUrlLabel, strings.addDownloadTitle, JOptionPane.PLAIN_MESSAGE)
                    if (!url.isNullOrBlank()) {
                        promptEnqueueUrl(url.trim())
                    }
                }
            }
        )
        btnsRow.add(DesktopTheme.secondaryButton(strings.actionPauseAll).apply { addActionListener { downloadEngine.pauseAll() } })
        btnsRow.add(DesktopTheme.secondaryButton(strings.actionResumeAll).apply { addActionListener { downloadEngine.resumeAll() } })
        btnsRow.add(DesktopTheme.secondaryButton(strings.actionClearDone).apply { addActionListener { downloadEngine.clearFinished() } })
        btnsRow.add(
            DesktopTheme.secondaryButton(strings.actionChangeFolder).apply {
                addActionListener {
                    val chooser = JFileChooser(File(downloadEngine.saveLocation.value.displayPath)).apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    }
                    if (chooser.showOpenDialog(ownerFrame) == JFileChooser.APPROVE_OPTION) {
                        downloadEngine.setCustomSaveDirectory(chooser.selectedFile)
                    }
                }
            }
        )
        topRow.add(btnsRow, BorderLayout.LINE_END)
        card.add(topRow)
        card.add(Box.createVerticalStrut(8))

        val bottomRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        val filterRow = JPanel(FlowLayout(FlowLayout.LEADING, 6, 2)).apply { isOpaque = false }
        val fAll = DesktopTheme.chipButton(strings.filterAll, selected = true)
        val fActive = DesktopTheme.chipButton(strings.filterActive)
        val fQueued = DesktopTheme.chipButton(strings.filterQueued)
        val fDone = DesktopTheme.chipButton(strings.filterCompleted)
        fun updateFilter(filter: DownloadFilter) {
            selectedFilter = filter
            listOf(
                DownloadFilter.ALL to fAll,
                DownloadFilter.ACTIVE to fActive,
                DownloadFilter.QUEUED to fQueued,
                DownloadFilter.COMPLETED to fDone,
            ).forEach { (f, btn) ->
                btn.background = if (f == filter) DesktopTheme.palette.primary else DesktopTheme.palette.badgeBg
                btn.foreground = if (f == filter) DesktopTheme.palette.onPrimary else DesktopTheme.palette.textPrimary
            }
            renderTasks(downloadEngine.tasks.value)
        }
        fAll.addActionListener { updateFilter(DownloadFilter.ALL) }
        fActive.addActionListener { updateFilter(DownloadFilter.ACTIVE) }
        fQueued.addActionListener { updateFilter(DownloadFilter.QUEUED) }
        fDone.addActionListener { updateFilter(DownloadFilter.COMPLETED) }
        filterRow.add(fAll)
        filterRow.add(fActive)
        filterRow.add(fQueued)
        filterRow.add(fDone)
        bottomRow.add(filterRow, BorderLayout.LINE_START)

        val engineRow = JPanel(FlowLayout(FlowLayout.TRAILING, 8, 2)).apply { isOpaque = false }
        engineRow.add(DesktopTheme.mutedLabel(strings.connectionsTitle))
        val connSpinner = JSpinner(SpinnerNumberModel(downloadEngine.connectionsPerDownload.value, 1, 8, 1)).apply {
            preferredSize = Dimension(60, 28)
            addChangeListener { downloadEngine.setConnectionsPerDownload((value as Int).coerceIn(1, 8)) }
        }
        engineRow.add(connSpinner)
        engineRow.add(DesktopTheme.mutedLabel(strings.concurrencyTitle))
        val concCombo = JComboBox(
            arrayOf(strings.concurrencyOne, strings.concurrencyTwo, strings.concurrencyThree, strings.concurrencyAll)
        ).apply {
            selectedIndex = QueueConcurrency.entries.indexOf(downloadEngine.concurrencyLimit.value).coerceAtLeast(1)
            addActionListener {
                val chosen = QueueConcurrency.entries.getOrElse(selectedIndex) { QueueConcurrency.TWO }
                downloadEngine.setConcurrencyLimit(chosen)
            }
        }
        engineRow.add(concCombo)
        bottomRow.add(engineRow, BorderLayout.LINE_END)
        card.add(bottomRow)
        return card
    }

    private fun renderTasks(allTasks: List<DownloadTask>) {
        strings = stringsFor(getSettings().language)
        val active = allTasks.count { it.isActive }
        val queued = allTasks.count { it.status == DownloadStatus.QUEUED }
        val totalSpeed = allTasks.filter { it.status == DownloadStatus.DOWNLOADING }.sumOf { it.speedBytesPerSec }
        val speedStr = (VideoLinkExtractor.formatByteSize(totalSpeed) ?: "0 B") + "/s"
        subtitleLabel.text = strings.downloadsSubtitle(active, queued, speedStr)

        val visible = when (selectedFilter) {
            DownloadFilter.ALL -> allTasks
            DownloadFilter.ACTIVE -> allTasks.filter { it.isActive || it.status == DownloadStatus.PAUSED }
            DownloadFilter.QUEUED -> allTasks.filter { it.status == DownloadStatus.QUEUED }
            DownloadFilter.COMPLETED -> allTasks.filter { it.status == DownloadStatus.COMPLETED }
        }

        listPanel.removeAll()
        if (visible.isEmpty()) {
            val emptyCard = DesktopTheme.styledCard(elevated = false)
            emptyCard.layout = BoxLayout(emptyCard, BoxLayout.Y_AXIS)
            emptyCard.add(DesktopTheme.sectionTitle(strings.downloadsEmptyTitle))
            emptyCard.add(Box.createVerticalStrut(4))
            emptyCard.add(DesktopTheme.mutedLabel(strings.downloadsEmptyDesc))
            listPanel.add(emptyCard)
        } else {
            visible.forEach { task ->
                listPanel.add(buildTaskCard(task))
                listPanel.add(Box.createVerticalStrut(8))
            }
        }
        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun buildTaskCard(task: DownloadTask): JPanel {
        val card = DesktopTheme.styledCard(elevated = true, paddingV = 12, paddingH = 14, hoverable = true)
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)

        val top = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        val titleLbl = JLabel("${task.fileName}  (${task.connectionCount}x)").apply {
            foreground = DesktopTheme.palette.textPrimary
            font = font.deriveFont(Font.BOLD, 14f)
        }
        val statusText = when (task.status) {
            DownloadStatus.QUEUED -> strings.statusQueued
            DownloadStatus.CONNECTING -> strings.statusConnecting
            DownloadStatus.DOWNLOADING -> strings.statusDownloading
            DownloadStatus.PAUSED -> strings.statusPaused
            DownloadStatus.COMPLETED -> strings.statusCompleted
            DownloadStatus.FAILED -> strings.statusFailed
        }
        val badgeColor = when (task.status) {
            DownloadStatus.COMPLETED -> DesktopTheme.palette.secondary
            DownloadStatus.FAILED -> DesktopTheme.palette.error
            DownloadStatus.DOWNLOADING -> DesktopTheme.palette.primary
            else -> DesktopTheme.palette.badgeBg
        }
        top.add(titleLbl, BorderLayout.LINE_START)
        top.add(DesktopTheme.pillLabel(statusText, badgeColor, DesktopTheme.palette.onPrimary), BorderLayout.LINE_END)
        card.add(top)
        card.add(Box.createVerticalStrut(6))

        val progressBar = JProgressBar(0, 100).apply {
            value = task.progressPercent
            isStringPainted = true
            isIndeterminate = task.status == DownloadStatus.CONNECTING || (task.isActive && task.totalBytes <= 0L)
        }
        card.add(progressBar)
        card.add(Box.createVerticalStrut(4))

        val infoStr = buildString {
            append(task.formattedDownloaded())
            task.formattedTotal()?.let { append(" / ").append(it) }
            if (task.status == DownloadStatus.DOWNLOADING) {
                append("  •  ").append(task.formattedSpeed())
                task.formattedEta()?.let { append("  •  ETA ").append(it) }
            }
            if (!task.errorMessage.isNullOrBlank()) {
                append("  •  ").append(task.errorMessage)
            }
        }
        card.add(DesktopTheme.mutedLabel(infoStr))
        card.add(Box.createVerticalStrut(6))

        val actions = JPanel(FlowLayout(FlowLayout.LEADING, 6, 0)).apply { isOpaque = false }
        when (task.status) {
            DownloadStatus.DOWNLOADING, DownloadStatus.CONNECTING -> {
                actions.add(DesktopTheme.secondaryButton(strings.actionPause).apply { addActionListener { downloadEngine.pauseTask(task.id) } })
            }
            DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                actions.add(DesktopTheme.primaryButton(if (task.status == DownloadStatus.FAILED) strings.actionRetry else strings.actionResume).apply {
                    addActionListener { downloadEngine.resumeTask(task.id) }
                })
                actions.add(DesktopTheme.secondaryButton(strings.actionPrioritize).apply { addActionListener { downloadEngine.prioritizeTask(task.id) } })
            }
            DownloadStatus.QUEUED -> {
                actions.add(DesktopTheme.secondaryButton(strings.actionPrioritize).apply { addActionListener { downloadEngine.prioritizeTask(task.id) } })
                actions.add(DesktopTheme.secondaryButton(strings.actionPause).apply { addActionListener { downloadEngine.pauseTask(task.id) } })
            }
            DownloadStatus.COMPLETED -> {
                val file = task.localFilePath?.let { File(it) }
                if (file != null && file.exists()) {
                    actions.add(DesktopTheme.primaryButton(strings.actionPlayOpen).apply { addActionListener { DesktopPlatformBridge.openLocalFile(file) } })
                    actions.add(DesktopTheme.secondaryButton("▶ VLC").apply { addActionListener { DesktopPlatformBridge.openInVlcOrSystemPlayer(file.absolutePath) } })
                    actions.add(DesktopTheme.secondaryButton("Folder").apply { addActionListener { DesktopPlatformBridge.revealInFileExplorer(file) } })
                }
            }
        }
        actions.add(DesktopTheme.secondaryButton(strings.actionCopyUrl).apply { addActionListener { DesktopPlatformBridge.copyToClipboard(task.url) } })
        actions.add(DesktopTheme.secondaryButton(strings.actionDelete).apply { addActionListener { downloadEngine.removeTask(task.id) } })
        card.add(actions)
        return card
    }

    fun refreshTranslations() {
        strings = stringsFor(getSettings().language)
        DesktopTheme.applyOrientation(this, getSettings().language)
        renderTasks(downloadEngine.tasks.value)
    }
}

/**
 * Windows Desktop Settings panel: Language, Theme, Unified vs Separate tabs, Movie Sources Manager
 * (including MyF2Ms & custom site addition), Developer PIN / TMDB diagnostics, and GitHub Windows Update Checker.
 */
class DesktopSettingsPanel(
    private val ownerFrame: JFrame,
    private val persistence: DesktopPersistence,
    private val metadataRepo: MetadataRepository,
    private val uiScope: CoroutineScope,
    private val getSettings: () -> DesktopAppSettings,
    private val onSettingsChanged: (DesktopAppSettings, rebuildTheme: Boolean) -> Unit,
    private val onEnqueueInstallerDownload: (url: String, fileName: String) -> Unit,
) : JPanel(BorderLayout()) {

    private var strings = stringsFor(getSettings().language)
    private val bodyPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = EmptyBorder(12, 16, 16, 16)
    }
    private val updateStatusLabel = DesktopTheme.mutedLabel("")
    private var latestUpdate: GitHubUpdateResult.UpdateAvailable? = null
    private val updateActionBtn = DesktopTheme.primaryButton(strings.actionOpenReleasePage).apply { isVisible = false }

    init {
        isOpaque = false
        add(
            JScrollPane(bodyPanel).apply {
                border = null
                viewport.background = DesktopTheme.palette.bg
                verticalScrollBar.unitIncrement = 18
            },
            BorderLayout.CENTER,
        )
        rebuildContent()
    }

    fun rebuildContent() {
        val settings = getSettings()
        strings = stringsFor(settings.language)
        DesktopTheme.applyOrientation(this, settings.language)
        bodyPanel.removeAll()

        val headerBanner = DesktopTheme.heroCard(paddingV = 14, paddingH = 18).apply {
            layout = BorderLayout()
            add(DesktopTheme.sectionTitle("⚙  ${strings.settingsTitle}").apply { font = font.deriveFont(Font.BOLD, 18f) }, BorderLayout.LINE_START)
            add(DesktopTheme.pillLabel("v${DesktopBuildConfig.VERSION_NAME}", DesktopTheme.palette.primary, DesktopTheme.palette.onPrimary), BorderLayout.LINE_END)
        }
        bodyPanel.add(headerBanner)
        bodyPanel.add(Box.createVerticalStrut(12))

        // 1. Language & Appearance & Layout
        val generalCard = DesktopTheme.styledCard(elevated = false)
        generalCard.layout = BoxLayout(generalCard, BoxLayout.Y_AXIS)
        generalCard.add(DesktopTheme.sectionTitle("${strings.sectionLanguage} & ${strings.ui.sectionAppearance}"))
        generalCard.add(Box.createVerticalStrut(6))

        val langRow = JPanel(FlowLayout(FlowLayout.LEADING, 8, 4)).apply { isOpaque = false }
        langRow.add(DesktopTheme.mutedLabel(strings.languageDesc))
        AppLanguage.entries.forEach { lang ->
            langRow.add(
                DesktopTheme.chipButton(lang.displayName, selected = settings.language == lang).apply {
                    addActionListener {
                        val updated = settings.copy(language = lang)
                        persistence.saveSettings(updated)
                        onSettingsChanged(updated, true)
                    }
                }
            )
        }
        generalCard.add(langRow)

        val themeRow = JPanel(FlowLayout(FlowLayout.LEADING, 8, 4)).apply { isOpaque = false }
        themeRow.add(DesktopTheme.mutedLabel(strings.ui.appearanceDesc))
        listOf(
            ThemeMode.SYSTEM to strings.ui.themeSystem,
            ThemeMode.DARK to strings.ui.themeDark,
            ThemeMode.LIGHT to strings.ui.themeLight,
        ).forEach { (mode, label) ->
            themeRow.add(
                DesktopTheme.chipButton(label, selected = settings.themeMode == mode).apply {
                    addActionListener {
                        val updated = settings.copy(themeMode = mode)
                        persistence.saveSettings(updated)
                        onSettingsChanged(updated, true)
                    }
                }
            )
        }
        generalCard.add(themeRow)

        val tabsCheck = JCheckBox(strings.ui.separateTabsTitle, settings.mediaPreferences.useSeparateTabs).apply {
            isOpaque = false
            foreground = DesktopTheme.palette.textPrimary
            addActionListener {
                val updated = settings.copy(mediaPreferences = settings.mediaPreferences.copy(useSeparateTabs = isSelected))
                persistence.saveSettings(updated)
                onSettingsChanged(updated, false)
            }
        }
        generalCard.add(tabsCheck)
        generalCard.add(DesktopTheme.mutedLabel(strings.ui.separateTabsDesc))
        bodyPanel.add(generalCard)
        bodyPanel.add(Box.createVerticalStrut(10))

        // 2. Movie Discovery Preferences
        val discoveryCard = DesktopTheme.styledCard(elevated = false)
        discoveryCard.layout = BoxLayout(discoveryCard, BoxLayout.Y_AXIS)
        discoveryCard.add(DesktopTheme.sectionTitle(strings.discover.sectionMovieDiscovery))
        discoveryCard.add(Box.createVerticalStrut(6))

        val searchLangRow = JPanel(FlowLayout(FlowLayout.LEADING, 8, 4)).apply { isOpaque = false }
        searchLangRow.add(DesktopTheme.mutedLabel(strings.discover.searchLangLabel))
        listOf(
            SearchLanguagePreference.EN to strings.discover.searchLangEn,
            SearchLanguagePreference.FA to strings.discover.searchLangFa,
            SearchLanguagePreference.BOTH to strings.discover.searchLangBoth,
        ).forEach { (pref, lbl) ->
            searchLangRow.add(
                DesktopTheme.chipButton(lbl, selected = settings.mediaPreferences.searchLanguage == pref).apply {
                    addActionListener {
                        val updated = settings.copy(mediaPreferences = settings.mediaPreferences.copy(searchLanguage = pref))
                        persistence.saveSettings(updated)
                        onSettingsChanged(updated, false)
                    }
                }
            )
        }
        discoveryCard.add(searchLangRow)

        val regionEngineRow = JPanel(FlowLayout(FlowLayout.LEADING, 8, 4)).apply { isOpaque = false }
        regionEngineRow.add(DesktopTheme.mutedLabel(strings.discover.searchEngineLabel))
        SearchEngineOption.entries.forEach { engine ->
            regionEngineRow.add(
                DesktopTheme.chipButton(engine.displayName, selected = settings.mediaPreferences.searchEngine == engine).apply {
                    addActionListener {
                        val updated = settings.copy(mediaPreferences = settings.mediaPreferences.copy(searchEngine = engine))
                        persistence.saveSettings(updated)
                        onSettingsChanged(updated, false)
                    }
                }
            )
        }
        regionEngineRow.add(DesktopTheme.mutedLabel("  |  ${strings.discover.watchRegionLabel}:"))
        val regionLabels = WatchRegions.SUPPORTED.map {
            if (settings.language == AppLanguage.FA) it.labelFa else it.labelEn
        }.toTypedArray()
        val regionCombo = JComboBox(regionLabels).apply {
            val curIdx = WatchRegions.SUPPORTED.indexOfFirst { it.code.equals(settings.mediaPreferences.watchRegion, true) }
                .coerceAtLeast(0)
            selectedIndex = curIdx
            addActionListener {
                val chosen = WatchRegions.SUPPORTED.getOrNull(selectedIndex)?.code ?: "US"
                if (chosen != settings.mediaPreferences.watchRegion) {
                    val updated = settings.copy(mediaPreferences = settings.mediaPreferences.copy(watchRegion = chosen))
                    persistence.saveSettings(updated)
                    onSettingsChanged(updated, false)
                }
            }
        }
        regionEngineRow.add(regionCombo)
        discoveryCard.add(regionEngineRow)

        val cacheBtns = JPanel(FlowLayout(FlowLayout.LEADING, 8, 4)).apply { isOpaque = false }
        cacheBtns.add(
            DesktopTheme.secondaryButton(strings.discover.actionClearCache).apply {
                addActionListener {
                    metadataRepo.clearMemoryCaches()
                    JOptionPane.showMessageDialog(ownerFrame, strings.discover.msgCacheCleared)
                }
            }
        )
        cacheBtns.add(
            DesktopTheme.secondaryButton(strings.discover.actionClearHistory).apply {
                addActionListener {
                    persistence.clearSearchHistory()
                    JOptionPane.showMessageDialog(ownerFrame, strings.discover.msgHistoryCleared)
                }
            }
        )
        discoveryCard.add(cacheBtns)
        bodyPanel.add(discoveryCard)
        bodyPanel.add(Box.createVerticalStrut(10))

        // 3. Source Websites Manager (editable + MyF2Ms + Custom Sites)
        val sitesCard = DesktopTheme.styledCard(elevated = false)
        sitesCard.layout = BoxLayout(sitesCard, BoxLayout.Y_AXIS)
        sitesCard.add(DesktopTheme.sectionTitle(strings.discover.sourceSitesManagerTitle))
        sitesCard.add(Box.createVerticalStrut(6))

        val currentSites = persistence.loadSourceSites().toMutableList()
        currentSites.forEach { site ->
            val row = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
            val cb = JCheckBox("${site.name}  —  ${site.baseUrl}", site.enabled).apply {
                isOpaque = false
                foreground = DesktopTheme.palette.textPrimary
                addActionListener {
                    val idx = currentSites.indexOfFirst { it.id == site.id }
                    if (idx >= 0) {
                        currentSites[idx] = currentSites[idx].copy(enabled = isSelected)
                        persistence.saveSourceSites(currentSites)
                    }
                }
            }
            row.add(cb, BorderLayout.CENTER)
            if (site.isCustom) {
                row.add(
                    DesktopTheme.secondaryButton(strings.actionDelete).apply {
                        addActionListener {
                            persistence.saveSourceSites(currentSites.filterNot { it.id == site.id })
                            rebuildContent()
                        }
                    },
                    BorderLayout.LINE_END,
                )
            }
            sitesCard.add(row)
        }

        sitesCard.add(Box.createVerticalStrut(8))
        val addSiteRow = JPanel(FlowLayout(FlowLayout.LEADING, 6, 4)).apply { isOpaque = false }
        val nameField = DesktopTheme.styledField(strings.discover.customSiteNameLabel).apply { columns = 14 }
        val urlField = DesktopTheme.styledField(strings.discover.customSiteUrlLabel).apply { columns = 24 }
        val addBtn = DesktopTheme.primaryButton(strings.discover.actionAddSite).apply {
            addActionListener {
                val siteName = nameField.text.trim()
                val rawUrl = urlField.text.trim()
                if (siteName.isEmpty() || !FuzzyTitleMatcher.isSafeHttpUrl(rawUrl)) {
                    JOptionPane.showMessageDialog(ownerFrame, strings.discover.msgInvalidSiteUrl)
                    return@addActionListener
                }
                val base = rawUrl.substringBefore("?").trimEnd('/')
                val template = if (rawUrl.contains("{query}")) rawUrl else "$base/?s={query}"
                val newSite = SourceSiteConfig(
                    id = "custom_${System.currentTimeMillis()}",
                    name = siteName,
                    baseUrl = base,
                    searchUrlTemplate = template,
                    isArchive = false,
                    enabled = true,
                    isCustom = true,
                )
                persistence.saveSourceSites(currentSites + newSite)
                rebuildContent()
            }
        }
        addSiteRow.add(nameField)
        addSiteRow.add(urlField)
        addSiteRow.add(addBtn)
        sitesCard.add(addSiteRow)
        bodyPanel.add(sitesCard)
        bodyPanel.add(Box.createVerticalStrut(10))

        // 4. GitHub Updates (Windows .exe installer)
        val updatesCard = DesktopTheme.styledCard(elevated = false)
        updatesCard.layout = BoxLayout(updatesCard, BoxLayout.Y_AXIS)
        updatesCard.add(DesktopTheme.sectionTitle(strings.sectionUpdates))
        updatesCard.add(DesktopTheme.mutedLabel("${strings.updatesCurrentVersion(DesktopBuildConfig.VERSION_NAME)}  •  ${DesktopPlatformBridge.windowsVersionLabel()}"))
        updatesCard.add(Box.createVerticalStrut(6))
        val updateRow = JPanel(FlowLayout(FlowLayout.LEADING, 8, 4)).apply { isOpaque = false }
        updateRow.add(
            DesktopTheme.primaryButton(strings.actionCheckUpdates).apply {
                addActionListener { checkUpdates() }
            }
        )
        updateRow.add(
            DesktopTheme.secondaryButton(strings.actionOpenGitHub).apply {
                addActionListener { DesktopPlatformBridge.openInDefaultBrowser(GitHubUpdateChecker.REPO_URL) }
            }
        )
        updateActionBtn.addActionListener {
            val upd = latestUpdate ?: return@addActionListener
            val winUrl = upd.windowsDownloadUrl
            if (winUrl != null) {
                onEnqueueInstallerDownload(winUrl, upd.windowsFileName ?: "MediaX-${upd.latestVersion}-Windows-Setup.exe")
            } else {
                DesktopPlatformBridge.openInDefaultBrowser(upd.releaseUrl)
            }
        }
        updateRow.add(updateActionBtn)
        updatesCard.add(updateRow)
        updatesCard.add(updateStatusLabel)
        bodyPanel.add(updatesCard)
        bodyPanel.add(Box.createVerticalStrut(10))

        // 5. Developer Mode & TMDB Backend Verification
        val devCard = DesktopTheme.styledCard(elevated = false)
        devCard.layout = BoxLayout(devCard, BoxLayout.Y_AXIS)
        devCard.add(DesktopTheme.sectionTitle(strings.sectionDeveloper))
        devCard.add(
            DesktopTheme.mutedLabel(if (settings.developerMode) strings.devModeDescUnlocked else strings.devModeDescLocked)
        )
        devCard.add(Box.createVerticalStrut(6))

        val devToggleBtn = DesktopTheme.secondaryButton(
            if (settings.developerMode) strings.devModeBadgeOn else strings.actionUnlock
        ).apply {
            addActionListener {
                if (settings.developerMode) {
                    val updated = settings.copy(developerMode = false)
                    persistence.saveSettings(updated)
                    onSettingsChanged(updated, false)
                } else {
                    val pwdField = JPasswordField()
                    val ok = JOptionPane.showConfirmDialog(
                        ownerFrame,
                        pwdField,
                        strings.devDialogTitle,
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE,
                    )
                    if (ok == JOptionPane.OK_OPTION) {
                        val candidate = String(pwdField.password)
                        if (DeveloperAccessVerifier.verify(candidate)) {
                            val updated = settings.copy(developerMode = true)
                            persistence.saveSettings(updated)
                            onSettingsChanged(updated, false)
                        } else {
                            JOptionPane.showMessageDialog(ownerFrame, strings.devPasswordError)
                        }
                    }
                }
            }
        }
        devCard.add(devToggleBtn)

        if (settings.developerMode) {
            devCard.add(Box.createVerticalStrut(8))
            val tmdbDiagLabel = DesktopTheme.mutedLabel(strings.discover.devTmdbBackendDesc)
            val proxyField = DesktopTheme.styledField(strings.discover.devTmdbProxyUrlLabel).apply {
                text = settings.mediaPreferences.tmdbProxyUrl
                columns = 34
            }
            val tmdbRow = JPanel(FlowLayout(FlowLayout.LEADING, 6, 4)).apply { isOpaque = false }
            tmdbRow.add(proxyField)
            tmdbRow.add(
                DesktopTheme.secondaryButton(strings.discover.devTmdbSaveUrl).apply {
                    addActionListener {
                        val updated = settings.copy(mediaPreferences = settings.mediaPreferences.copy(tmdbProxyUrl = proxyField.text.trim()))
                        persistence.saveSettings(updated)
                        onSettingsChanged(updated, false)
                    }
                }
            )
            tmdbRow.add(
                DesktopTheme.primaryButton(strings.discover.devTmdbTestConnection).apply {
                    addActionListener {
                        tmdbDiagLabel.text = strings.discover.devTmdbTesting
                        uiScope.launch {
                            val diag = metadataRepo.testBackendConnection(proxyField.text.trim())
                            tmdbDiagLabel.text = "${diag.endpointHost} • reachable=${diag.isReachable} • ${diag.latencyMs ?: 0}ms ${diag.errorMessage.orEmpty()}"
                        }
                    }
                }
            )
            devCard.add(tmdbRow)
            devCard.add(tmdbDiagLabel)
        }
        bodyPanel.add(devCard)
        bodyPanel.add(Box.createVerticalStrut(8))
        bodyPanel.add(DesktopTheme.mutedLabel(strings.discover.tmdbAttributionText))

        bodyPanel.revalidate()
        bodyPanel.repaint()
    }

    private fun checkUpdates() {
        updateStatusLabel.text = strings.updatesChecking
        updateActionBtn.isVisible = false
        uiScope.launch {
            val checker = GitHubUpdateChecker()
            when (val res = checker.checkForUpdates(DesktopBuildConfig.VERSION_NAME)) {
                is GitHubUpdateResult.UpdateAvailable -> {
                    latestUpdate = res
                    updateStatusLabel.text = strings.updatesAvailableTitle(res.latestVersion)
                    updateActionBtn.text = if (res.windowsDownloadUrl != null) {
                        "Download ${res.windowsFileName ?: "Windows Setup"}"
                    } else {
                        strings.actionOpenReleasePage
                    }
                    updateActionBtn.isVisible = true
                }
                is GitHubUpdateResult.UpToDate -> {
                    updateStatusLabel.text = strings.updatesUpToDate(res.latestVersion)
                }
                is GitHubUpdateResult.NoReleasesYet -> {
                    updateStatusLabel.text = strings.updatesNoReleases
                }
                is GitHubUpdateResult.Error -> {
                    updateStatusLabel.text = res.message
                }
            }
        }
    }
}
