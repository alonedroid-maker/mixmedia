package com.example.desktop

import com.example.media.MediaDetails
import com.example.media.MediaPreferences
import com.example.media.MediaSearchResult
import com.example.media.MediaType
import com.example.media.MetadataRepository
import com.example.media.QueryModifiers
import com.example.media.SourceDiscoveryBundle
import com.example.media.SourceDiscoveryEngine
import com.example.media.SourceLinkStatus
import com.example.media.SourceSearchTarget
import com.example.media.SourceSiteConfig
import com.example.media.WebSourceResult
import com.example.ui.AppLanguage
import com.example.ui.stringsFor
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Windows Desktop dialog showing full Movie / TV details (including bilingual EN/FA summaries),
 * watch providers, seasons/episodes, favorites toggle, and live Source Discovery across configured sites.
 */
class DesktopMediaDetailsDialog(
    owner: JFrame,
    private val summary: MediaSearchResult,
    private val language: AppLanguage,
    private val preferences: MediaPreferences,
    private val sourceSites: List<SourceSiteConfig>,
    private val isFavoriteInitial: Boolean,
    private val metadataRepo: MetadataRepository,
    private val sourceEngine: SourceDiscoveryEngine,
    private val imageCacheDir: File,
    private val uiScope: CoroutineScope,
    private val onToggleFavorite: (MediaSearchResult) -> Unit,
    private val onSendUrlToResolver: (String) -> Unit,
    private val onEnqueueDirectUrl: (String, String) -> Unit,
) : JDialog(owner, summary.title, true) {

    private val strings = stringsFor(language)
    private var isFavorite = isFavoriteInitial
    private var currentDetails: MediaDetails? = null
    private var modifiers = QueryModifiers()

    private val headerMetaLabel = DesktopTheme.mutedLabel(strings.statusProcessing)
    private val posterPanel = RoundedPosterPanel(
        posterWidth = 148,
        posterHeight = 214,
        mediaTypeBadge = if (summary.mediaType == MediaType.TV) strings.discover.badgeTvShow else strings.discover.badgeMovie,
        ratingBadge = summary.imdbRating?.let { "★ %.1f".format(it) },
    )
    private val overviewArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        foreground = DesktopTheme.palette.textPrimary
        font = DesktopTheme.pickBestUiFont(language, 13)
    }
    private val secondaryOverviewLabel = DesktopTheme.mutedLabel("")
    private val extraInfoLabel = DesktopTheme.mutedLabel("")
    private val providersLabel = DesktopTheme.mutedLabel("")
    private val seasonsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val sourcesStatusLabel = DesktopTheme.mutedLabel("")
    private val sourcesListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val customQueryField = DesktopTheme.styledField(strings.discover.sourcesCustomQueryLabel)
    private val favButton = DesktopTheme.secondaryButton(favButtonText())

    private fun favButtonText(): String = if (isFavorite) "★ ${strings.discover.favoritesTitle}" else "☆ ${strings.discover.favoritesTitle}"

    init {
        setSize(900, 700)
        setLocationRelativeTo(owner)
        contentPane.background = DesktopTheme.palette.bg
        layout = BorderLayout(12, 12)

        val rootContainer = JPanel(BorderLayout(12, 12)).apply {
            isOpaque = false
            border = EmptyBorder(14, 16, 14, 16)
        }
        rootContainer.add(buildTopSummaryCard(), BorderLayout.NORTH)
        rootContainer.add(buildScrollableBody(), BorderLayout.CENTER)
        add(rootContainer, BorderLayout.CENTER)

        DesktopTheme.applyOrientation(this, language)
        loadDetailsAsync()
        loadPosterAsync()
    }

    private fun buildTopSummaryCard(): JPanel {
        val card = DesktopTheme.heroCard(paddingV = 16, paddingH = 18)
        card.layout = BorderLayout(16, 10)
        card.add(posterPanel, BorderLayout.LINE_START)

        val rightCol = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val titleText = buildString {
            append(summary.title)
            summary.persianTitle?.takeIf { it.isNotBlank() && !it.equals(summary.title, true) }?.let {
                append("  •  ").append(it)
            }
        }
        val titleLbl = JLabel(titleText).apply {
            foreground = DesktopTheme.palette.textPrimary
            font = font.deriveFont(Font.BOLD, 19f)
        }
        rightCol.add(titleLbl)
        rightCol.add(Box.createVerticalStrut(4))
        rightCol.add(headerMetaLabel)
        rightCol.add(Box.createVerticalStrut(8))
        rightCol.add(overviewArea)
        rightCol.add(Box.createVerticalStrut(4))
        rightCol.add(secondaryOverviewLabel)
        rightCol.add(Box.createVerticalStrut(6))
        rightCol.add(extraInfoLabel)
        rightCol.add(Box.createVerticalStrut(4))
        rightCol.add(providersLabel)
        rightCol.add(Box.createVerticalStrut(10))

        val actionsRow = JPanel(FlowLayout(FlowLayout.LEADING, 8, 4)).apply { isOpaque = false }
        val findSourcesBtn = DesktopTheme.primaryButton(strings.discover.detailsSearchSources).apply {
            addActionListener { runSourceDiscovery() }
        }
        favButton.addActionListener {
            isFavorite = !isFavorite
            favButton.text = favButtonText()
            onToggleFavorite(summary)
        }
        val closeBtn = DesktopTheme.secondaryButton(strings.ui.unifiedDismissResult).apply {
            addActionListener { dispose() }
        }
        actionsRow.add(findSourcesBtn)
        actionsRow.add(favButton)
        actionsRow.add(closeBtn)
        rightCol.add(actionsRow)

        card.add(rightCol, BorderLayout.CENTER)
        return card
    }

    private fun buildScrollableBody(): JScrollPane {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        body.add(seasonsPanel)
        body.add(Box.createVerticalStrut(10))
        body.add(buildSourceDiscoveryCard())

        return JScrollPane(body).apply {
            border = null
            viewport.background = DesktopTheme.palette.bg
            verticalScrollBar.unitIncrement = 16
        }
    }

    private fun buildSourceDiscoveryCard(): JPanel {
        val card = DesktopTheme.styledCard(elevated = false)
        card.layout = BorderLayout(10, 10)

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        topPanel.add(DesktopTheme.sectionTitle(strings.discover.sourcesScreenTitle))
        topPanel.add(Box.createVerticalStrut(6))

        val modifiersRow = JPanel(FlowLayout(FlowLayout.LEADING, 10, 4)).apply { isOpaque = false }
        val cbDl = JCheckBox(strings.discover.sourcesModDownload, modifiers.includeDownload).apply {
            isOpaque = false
            foreground = DesktopTheme.palette.textPrimary
            addActionListener { modifiers = modifiers.copy(includeDownload = isSelected) }
        }
        val cbOnline = JCheckBox(strings.discover.sourcesModOnline, modifiers.includeWatchOnline).apply {
            isOpaque = false
            foreground = DesktopTheme.palette.textPrimary
            addActionListener { modifiers = modifiers.copy(includeWatchOnline = isSelected) }
        }
        val cbSub = JCheckBox(strings.discover.sourcesModSubtitle, modifiers.includeSubtitle).apply {
            isOpaque = false
            foreground = DesktopTheme.palette.textPrimary
            addActionListener { modifiers = modifiers.copy(includeSubtitle = isSelected) }
        }
        val cbDub = JCheckBox(strings.discover.sourcesModDubbed, modifiers.includeDubbed).apply {
            isOpaque = false
            foreground = DesktopTheme.palette.textPrimary
            addActionListener { modifiers = modifiers.copy(includeDubbed = isSelected) }
        }
        modifiersRow.add(cbDl)
        modifiersRow.add(cbOnline)
        modifiersRow.add(cbSub)
        modifiersRow.add(cbDub)
        topPanel.add(modifiersRow)
        topPanel.add(Box.createVerticalStrut(6))

        val queryRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        queryRow.add(customQueryField, BorderLayout.CENTER)
        val searchSourcesBtn = DesktopTheme.primaryButton(strings.discover.sourcesRunSearch).apply {
            addActionListener { runSourceDiscovery() }
        }
        queryRow.add(searchSourcesBtn, BorderLayout.LINE_END)
        topPanel.add(queryRow)
        topPanel.add(Box.createVerticalStrut(6))
        topPanel.add(sourcesStatusLabel)

        card.add(topPanel, BorderLayout.NORTH)
        card.add(sourcesListPanel, BorderLayout.CENTER)
        return card
    }

    private fun loadPosterAsync() {
        uiScope.launch {
            val icon = DesktopPlatformBridge.loadScaledImage(summary.posterUrl, imageCacheDir, 160, 230)
            if (icon != null) {
                posterPanel.setPosterImage(icon.image)
            }
        }
    }

    private fun loadDetailsAsync() {
        uiScope.launch {
            val details = runCatching { metadataRepo.getMediaDetails(summary, preferences).getOrNull() }.getOrNull()
            currentDetails = details
            if (details == null) {
                headerMetaLabel.text = listOfNotNull(
                    summary.releaseYear?.toString(),
                    summary.imdbRating?.let { "★ %.1f".format(it) },
                    if (summary.mediaType == MediaType.TV) strings.discover.badgeTvShow else strings.discover.badgeMovie,
                ).joinToString("  •  ")
                overviewArea.text = summary.persianOverview?.takeIf { language == AppLanguage.FA && it.isNotBlank() }
                    ?: summary.overview?.takeIf { it.isNotBlank() }
                    ?: summary.persianOverview
                    ?: ""
                runSourceDiscovery()
                return@launch
            }

            val metaParts = buildList {
                add(if (details.mediaType == MediaType.TV) strings.discover.badgeTvShow else strings.discover.badgeMovie)
                details.releaseYear?.let { add(it.toString()) }
                details.runtimeMinutes?.let { add(strings.discover.labelRuntimeMinutes(it)) }
                details.imdbRating?.let { add("★ %.1f".format(it)) }
                if (details.genres.isNotEmpty()) add(details.genres.take(4).joinToString(", "))
            }
            headerMetaLabel.text = metaParts.joinToString("  •  ")

            val faOverview = details.persianOverview?.takeIf { it.isNotBlank() }
            val enOverview = details.overview?.takeIf { it.isNotBlank() }
            if (language == AppLanguage.FA) {
                overviewArea.text = faOverview ?: enOverview ?: ""
                secondaryOverviewLabel.text = if (faOverview != null && enOverview != null) {
                    "<html><body style='width:600px'><b>${strings.discover.detailsEnglishOverview}:</b> ${enOverview.take(260)}</body></html>"
                } else ""
            } else {
                overviewArea.text = enOverview ?: faOverview ?: ""
                secondaryOverviewLabel.text = if (enOverview != null && faOverview != null) {
                    "<html><body style='width:600px'><b>${strings.discover.detailsPersianOverview}:</b> ${faOverview.take(260)}</body></html>"
                } else ""
            }

            val castNames = details.cast.take(6).joinToString(", ") { it.name }
            extraInfoLabel.text = if (castNames.isNotBlank()) "${strings.discover.labelCast}: $castNames" else ""

            if (details.watchProviders.isNotEmpty()) {
                val names = details.watchProviders.map { it.name }.distinct().take(6).joinToString(", ")
                providersLabel.text = "${strings.discover.whereToWatchTitle}: $names"
            }

            renderSeasons(details)
            runSourceDiscovery()
        }
    }

    private fun renderSeasons(details: MediaDetails) {
        seasonsPanel.removeAll()
        if (details.mediaType == MediaType.TV && details.seasons.isNotEmpty()) {
            val card = DesktopTheme.styledCard(elevated = false)
            card.layout = BorderLayout(8, 8)
            card.add(DesktopTheme.sectionTitle(strings.discover.labelSeasonsCount(details.seasons.size)), BorderLayout.NORTH)
            val rows = JPanel(FlowLayout(FlowLayout.LEADING, 8, 6)).apply { isOpaque = false }
            details.seasons.take(12).forEach { season ->
                val btn = DesktopTheme.chipButton(strings.discover.detailsSearchSeason(season.seasonNumber) + " (${season.episodeCount})").apply {
                    addActionListener {
                        runSourceDiscovery(seasonNumber = season.seasonNumber)
                    }
                }
                rows.add(btn)
            }
            card.add(rows, BorderLayout.CENTER)
            seasonsPanel.add(card)
        }
        seasonsPanel.revalidate()
        seasonsPanel.repaint()
    }

    private fun runSourceDiscovery(seasonNumber: Int? = null) {
        val details = currentDetails
        val target = SourceSearchTarget(
            mediaId = summary.id,
            mediaType = summary.mediaType,
            title = details?.title ?: summary.title,
            persianTitle = details?.persianTitle ?: summary.persianTitle,
            releaseYear = details?.releaseYear ?: summary.releaseYear,
            seasonNumber = seasonNumber,
        )
        val customQuery = customQueryField.text.trim().takeIf { it.isNotEmpty() }
        sourcesStatusLabel.text = strings.statusProcessing
        sourcesListPanel.removeAll()
        sourcesListPanel.revalidate()
        sourcesListPanel.repaint()

        uiScope.launch {
            val bundle = runCatching {
                sourceEngine.discoverSources(
                    target = target,
                    sites = sourceSites,
                    preferences = preferences,
                    modifiers = modifiers,
                    customQueryOverride = customQuery,
                )
            }.getOrNull()

            if (bundle == null) {
                sourcesStatusLabel.text = strings.discover.sourcesEmptySiteNotice
                return@launch
            }
            renderSourceBundle(bundle)
        }
    }

    private fun renderSourceBundle(bundle: SourceDiscoveryBundle) {
        sourcesListPanel.removeAll()
        val combined = (bundle.siteResults + bundle.webSearchResults).distinctBy { it.url }
        sourcesStatusLabel.text = buildString {
            append("${combined.size} ${strings.discover.sourcesWebResultsTitle}")
            if (bundle.siteNotices.isNotEmpty()) {
                append("  •  ").append(bundle.siteNotices.take(2).joinToString(" | "))
            }
        }

        val enginesBar = JPanel(FlowLayout(FlowLayout.LEADING, 6, 4)).apply { isOpaque = false }
        bundle.searchEngineLaunchUrls.forEach { (engine, url) ->
            enginesBar.add(
                DesktopTheme.chipButton("${engine.displayName} ↗").apply {
                    addActionListener { DesktopPlatformBridge.openInDefaultBrowser(url) }
                }
            )
        }
        sourcesListPanel.add(enginesBar)
        sourcesListPanel.add(Box.createVerticalStrut(6))

        if (combined.isEmpty()) {
            sourcesListPanel.add(DesktopTheme.mutedLabel(strings.discover.sourcesEmptySiteNotice))
        } else {
            combined.take(35).forEach { result ->
                sourcesListPanel.add(buildSourceResultRow(result))
                sourcesListPanel.add(Box.createVerticalStrut(6))
            }
        }
        sourcesListPanel.revalidate()
        sourcesListPanel.repaint()
    }

    private fun buildSourceResultRow(result: WebSourceResult): JPanel {
        val row = DesktopTheme.styledCard(elevated = true, paddingV = 10, paddingH = 12, hoverable = true)
        row.layout = GridBagLayout()
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0, 0, 2, 8)
        }

        val titleLbl = JLabel(result.title.take(90)).apply {
            foreground = DesktopTheme.palette.textPrimary
            font = font.deriveFont(Font.BOLD, 13f)
        }
        row.add(titleLbl, gbc)

        gbc.gridy = 1
        val statusText = DesktopTheme.mutedLabel(
            "${result.siteName} (${result.domain})${result.detectedExtension?.let { " • $it" } ?: ""}"
        )
        row.add(statusText, gbc)

        val actionsPanel = JPanel(FlowLayout(FlowLayout.TRAILING, 6, 0)).apply { isOpaque = false }
        val verifyBtn = DesktopTheme.secondaryButton(strings.discover.actionCheckLink)
        verifyBtn.addActionListener {
            verifyBtn.isEnabled = false
            uiScope.launch {
                val (status, note) = sourceEngine.verifyUrlStatus(result.url)
                statusText.text = "${result.siteName} • ${status.name}${note?.let { " ($it)" } ?: ""}"
                if (status == SourceLinkStatus.VERIFIED_ACCESSIBLE) {
                    statusText.foreground = DesktopTheme.palette.secondary
                } else if (status == SourceLinkStatus.UNAVAILABLE) {
                    statusText.foreground = DesktopTheme.palette.error
                }
                verifyBtn.isEnabled = true
            }
        }
        val openBtn = DesktopTheme.secondaryButton(strings.discover.actionOpenExternalBrowser).apply {
            addActionListener { DesktopPlatformBridge.openInDefaultBrowser(result.url) }
        }
        val resolveOrDlBtn = if (result.isDirectDownloadCandidate) {
            DesktopTheme.primaryButton(strings.actionDownloadHere).apply {
                addActionListener {
                    onEnqueueDirectUrl(result.url, result.title)
                    dispose()
                }
            }
        } else {
            DesktopTheme.primaryButton(strings.ui.unifiedActionResolve).apply {
                addActionListener {
                    onSendUrlToResolver(result.url)
                    dispose()
                }
            }
        }
        val copyBtn = DesktopTheme.secondaryButton(strings.actionCopyLink).apply {
            addActionListener { DesktopPlatformBridge.copyToClipboard(result.url) }
        }

        actionsPanel.add(verifyBtn)
        actionsPanel.add(openBtn)
        actionsPanel.add(copyBtn)
        actionsPanel.add(resolveOrDlBtn)

        gbc.gridx = 1
        gbc.gridy = 0
        gbc.gridheight = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        row.add(actionsPanel, gbc)

        DesktopTheme.applyOrientation(row, language)
        return row
    }
}
