package com.example.desktop

import com.example.media.MediaGenre
import com.example.media.MediaPreferences
import com.example.media.MediaSearchPage
import com.example.media.MediaSearchResult
import com.example.media.MediaType
import com.example.media.MetadataRepository
import com.example.media.SourceDiscoveryEngine
import com.example.media.SourceSiteConfig
import com.example.media.TmdbDiscoverFilters
import com.example.media.TrendingFeedBundle
import com.example.media.TrendingTimeWindow
import com.example.network.CurlRedirectResolver
import com.example.network.CurlResolutionResult
import com.example.network.UnsupportedPlatformException
import com.example.network.VideoLinkExtractor
import com.example.network.XExtractionException
import com.example.network.XVideoVariant
import com.example.network.YouTubeMetadataInfo
import com.example.network.YouTubeUnavailableException
import com.example.ui.AppLanguage
import com.example.ui.stringsFor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class MoviesPanelMode {
    UNIFIED,
    MOVIES_ONLY,
    RESOLVER_ONLY,
}

/**
 * Windows Desktop Smart Hub combining Unified / Separate Movies & TV Discovery,
 * Trending Now shelf, Filters, Favorites, Recently Viewed, and Direct/Social/curl Link Resolver.
 */
class DesktopMoviesAndResolverPanel(
    private val ownerFrame: JFrame,
    private val mode: MoviesPanelMode,
    private val persistence: DesktopPersistence,
    private val metadataRepo: MetadataRepository,
    private val sourceEngine: SourceDiscoveryEngine,
    private val resolver: CurlRedirectResolver,
    private val uiScope: CoroutineScope,
    private val getSettings: () -> DesktopAppSettings,
    private val getSourceSites: () -> List<SourceSiteConfig>,
    private val onEnqueueDownload: (url: String, fileName: String, customUa: String?) -> Unit,
    private val onSwitchToDownloads: () -> Unit,
) : JPanel(BorderLayout(12, 12)) {

    private var strings = stringsFor(getSettings().language)
    private var resolveJob: Job? = null
    private var searchJob: Job? = null
    private var currentResolution: CurlResolutionResult? = null
    private var selectedXVariant: XVideoVariant? = null

    private var mediaTypeFilter: MediaType? = null
    private var currentFilters = TmdbDiscoverFilters()
    private var availableGenres: List<MediaGenre> = emptyList()
    private var searchResultsPage: MediaSearchPage? = null
    private var currentQuery: String = ""
    private var showInlineToolsDrawer: Boolean = (mode == MoviesPanelMode.RESOLVER_ONLY)

    private val omniboxField = DesktopTheme.styledField(strings.ui.unifiedSmartPlaceholder)
    private val modeHintLabel = DesktopTheme.mutedLabel(strings.ui.smartHomeHelper)
    private val primaryGoButton = DesktopTheme.primaryButton(strings.discover.actionSearchMovies)
    private val cancelResolveButton = DesktopTheme.secondaryButton(strings.actionCancel).apply { isVisible = false }
    private val toggleDrawerButton = DesktopTheme.chipButton(strings.ui.unifiedQuickResolverTools)

    private val resolverStatusLabel = DesktopTheme.mutedLabel(strings.statusReady)
    private val resolverResultCard = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
    }

    private val genreCombo = JComboBox<String>()
    private val ratingCombo = JComboBox<String>()
    private val yearCombo = JComboBox<String>()
    private val filterTypeAllBtn = DesktopTheme.chipButton(strings.ui.filterAllTypes, selected = true)
    private val filterTypeMovieBtn = DesktopTheme.chipButton(strings.ui.filterMoviesOnly, selected = false)
    private val filterTypeTvBtn = DesktopTheme.chipButton(strings.ui.filterTvOnly, selected = false)

    private val trendingPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val searchResultsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val personalPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        isOpaque = false
        border = EmptyBorder(12, 16, 12, 16)
        buildUi()
        refreshTranslationsAndData()
    }

    private fun buildUi() {
        val northStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        northStack.add(buildSmartHeroCard())
        northStack.add(Box.createVerticalStrut(10))
        northStack.add(resolverResultCard)
        add(northStack, BorderLayout.NORTH)

        val scrollBody = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        if (mode != MoviesPanelMode.RESOLVER_ONLY) {
            scrollBody.add(buildFilterBar())
            scrollBody.add(Box.createVerticalStrut(10))
            scrollBody.add(searchResultsPanel)
            scrollBody.add(Box.createVerticalStrut(12))
            scrollBody.add(trendingPanel)
            scrollBody.add(Box.createVerticalStrut(12))
            scrollBody.add(personalPanel)
        }

        val scrollPane = JScrollPane(scrollBody).apply {
            border = null
            viewport.background = DesktopTheme.palette.bg
            verticalScrollBar.unitIncrement = 18
        }
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun buildSmartHeroCard(): JPanel {
        val card = DesktopTheme.styledCard(elevated = false)
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)

        val heroTitle = when (mode) {
            MoviesPanelMode.UNIFIED -> strings.ui.unifiedHeroTitle
            MoviesPanelMode.MOVIES_ONLY -> strings.discover.discoverTitle
            MoviesPanelMode.RESOLVER_ONLY -> strings.inputCardTitle
        }
        val heroSub = when (mode) {
            MoviesPanelMode.UNIFIED -> strings.ui.unifiedHeroSubtitle
            MoviesPanelMode.MOVIES_ONLY -> strings.discover.discoverSubtitle
            MoviesPanelMode.RESOLVER_ONLY -> strings.inputHelper
        }

        val titleLbl = DesktopTheme.sectionTitle(heroTitle).apply { font = font.deriveFont(Font.BOLD, 17f) }
        val subLbl = DesktopTheme.mutedLabel(heroSub)
        card.add(titleLbl)
        card.add(Box.createVerticalStrut(4))
        card.add(subLbl)
        card.add(Box.createVerticalStrut(10))

        val inputRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        omniboxField.addActionListener { submitSmartInput() }
        omniboxField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = updateSmartHint()
                override fun removeUpdate(e: DocumentEvent?) = updateSmartHint()
                override fun changedUpdate(e: DocumentEvent?) = updateSmartHint()
            }
        )
        inputRow.add(omniboxField, BorderLayout.CENTER)

        val rightBtns = JPanel(FlowLayout(FlowLayout.TRAILING, 6, 0)).apply { isOpaque = false }
        val pasteBtn = DesktopTheme.secondaryButton(strings.actionPaste).apply {
            addActionListener {
                val clip = DesktopPlatformBridge.readClipboardText()?.trim().orEmpty()
                if (clip.isNotEmpty()) {
                    omniboxField.text = clip
                    updateSmartHint()
                } else {
                    JOptionPane.showMessageDialog(ownerFrame, strings.msgNoClipboard)
                }
            }
        }
        val clearBtn = DesktopTheme.secondaryButton(strings.actionClear).apply {
            addActionListener {
                omniboxField.text = ""
                currentResolution = null
                resolverResultCard.isVisible = false
                if (mode != MoviesPanelMode.RESOLVER_ONLY) {
                    currentQuery = ""
                    searchResultsPage = null
                    renderSearchResults()
                }
                updateSmartHint()
            }
        }
        primaryGoButton.addActionListener { submitSmartInput() }
        cancelResolveButton.addActionListener {
            resolveJob?.cancel()
            cancelResolveButton.isVisible = false
            resolverStatusLabel.text = strings.errorCancelled
        }
        rightBtns.add(pasteBtn)
        rightBtns.add(clearBtn)
        rightBtns.add(primaryGoButton)
        rightBtns.add(cancelResolveButton)
        inputRow.add(rightBtns, BorderLayout.LINE_END)
        card.add(inputRow)
        card.add(Box.createVerticalStrut(6))

        val bottomMetaRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        bottomMetaRow.add(modeHintLabel, BorderLayout.CENTER)
        val quickRow = JPanel(FlowLayout(FlowLayout.TRAILING, 6, 0)).apply { isOpaque = false }
        if (mode == MoviesPanelMode.UNIFIED) {
            toggleDrawerButton.addActionListener {
                showInlineToolsDrawer = !showInlineToolsDrawer
                toggleDrawerButton.text = if (showInlineToolsDrawer) {
                    strings.ui.unifiedHideResolverTools
                } else {
                    strings.ui.unifiedQuickResolverTools
                }
                resolverResultCard.isVisible = showInlineToolsDrawer || currentResolution != null
            }
            quickRow.add(toggleDrawerButton)
        }
        quickRow.add(resolverStatusLabel)
        bottomMetaRow.add(quickRow, BorderLayout.LINE_END)
        card.add(bottomMetaRow)
        return card
    }

    private fun buildFilterBar(): JPanel {
        val bar = DesktopTheme.styledCard(elevated = false, paddingV = 10, paddingH = 12)
        bar.layout = FlowLayout(FlowLayout.LEADING, 8, 4)

        filterTypeAllBtn.addActionListener {
            mediaTypeFilter = null
            refreshFilterButtons()
            if (currentQuery.isNotBlank() || currentFilters.hasActiveFilters()) runMediaSearch(page = 1)
        }
        filterTypeMovieBtn.addActionListener {
            mediaTypeFilter = MediaType.MOVIE
            refreshFilterButtons()
            if (currentQuery.isNotBlank() || currentFilters.hasActiveFilters()) runMediaSearch(page = 1)
        }
        filterTypeTvBtn.addActionListener {
            mediaTypeFilter = MediaType.TV
            refreshFilterButtons()
            if (currentQuery.isNotBlank() || currentFilters.hasActiveFilters()) runMediaSearch(page = 1)
        }

        bar.add(filterTypeAllBtn)
        bar.add(filterTypeMovieBtn)
        bar.add(filterTypeTvBtn)

        ratingCombo.removeAllItems()
        ratingCombo.addItem(strings.discover.filterAnyRating)
        listOf("6.0", "7.0", "7.5", "8.0", "8.5").forEach { ratingCombo.addItem("★ $it+") }
        ratingCombo.addActionListener {
            val idx = ratingCombo.selectedIndex
            val chosen = listOf(null, 6.0, 7.0, 7.5, 8.0, 8.5).getOrNull(idx)
            if (chosen != currentFilters.minRating) {
                currentFilters = currentFilters.copy(minRating = chosen)
                if (currentQuery.isNotBlank() || currentFilters.hasActiveFilters()) runMediaSearch(page = 1)
            }
        }

        yearCombo.removeAllItems()
        yearCombo.addItem(strings.discover.filterAllYears)
        (2026 downTo 1985).forEach { yearCombo.addItem(it.toString()) }
        yearCombo.addActionListener {
            val sel = yearCombo.selectedItem as? String
            val yr = sel?.toIntOrNull()
            if (yr != currentFilters.releaseYear) {
                currentFilters = currentFilters.copy(releaseYear = yr)
                if (currentQuery.isNotBlank() || currentFilters.hasActiveFilters()) runMediaSearch(page = 1)
            }
        }

        genreCombo.addActionListener {
            val idx = genreCombo.selectedIndex
            val gId = if (idx <= 0) null else availableGenres.getOrNull(idx - 1)?.id
            if (gId != currentFilters.genreId) {
                currentFilters = currentFilters.copy(genreId = gId)
                if (currentQuery.isNotBlank() || currentFilters.hasActiveFilters()) runMediaSearch(page = 1)
            }
        }

        val clearFiltersBtn = DesktopTheme.secondaryButton(strings.discover.filterClearFilters).apply {
            addActionListener {
                mediaTypeFilter = null
                currentFilters = TmdbDiscoverFilters()
                if (genreCombo.itemCount > 0) genreCombo.selectedIndex = 0
                if (ratingCombo.itemCount > 0) ratingCombo.selectedIndex = 0
                if (yearCombo.itemCount > 0) yearCombo.selectedIndex = 0
                refreshFilterButtons()
                if (currentQuery.isNotBlank()) runMediaSearch(page = 1) else {
                    searchResultsPage = null
                    renderSearchResults()
                }
            }
        }

        bar.add(genreCombo)
        bar.add(ratingCombo)
        bar.add(yearCombo)
        bar.add(clearFiltersBtn)
        return bar
    }

    private fun refreshFilterButtons() {
        filterTypeAllBtn.background = if (mediaTypeFilter == null) DesktopTheme.palette.primary else DesktopTheme.palette.badgeBg
        filterTypeAllBtn.foreground = if (mediaTypeFilter == null) DesktopTheme.palette.onPrimary else DesktopTheme.palette.textPrimary
        filterTypeMovieBtn.background = if (mediaTypeFilter == MediaType.MOVIE) DesktopTheme.palette.primary else DesktopTheme.palette.badgeBg
        filterTypeMovieBtn.foreground = if (mediaTypeFilter == MediaType.MOVIE) DesktopTheme.palette.onPrimary else DesktopTheme.palette.textPrimary
        filterTypeTvBtn.background = if (mediaTypeFilter == MediaType.TV) DesktopTheme.palette.primary else DesktopTheme.palette.badgeBg
        filterTypeTvBtn.foreground = if (mediaTypeFilter == MediaType.TV) DesktopTheme.palette.onPrimary else DesktopTheme.palette.textPrimary
    }

    private fun looksLikeLinkOrTrace(raw: String): Boolean {
        if (mode == MoviesPanelMode.RESOLVER_ONLY) return true
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        return VideoLinkExtractor.looksLikeRawCurlTrace(trimmed) ||
            trimmed.startsWith("curl ", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("vlc://", ignoreCase = true) ||
            VideoLinkExtractor.extractInputUrl(trimmed) != null
    }

    private fun updateSmartHint() {
        val text = omniboxField.text
        if (mode == MoviesPanelMode.MOVIES_ONLY) {
            primaryGoButton.text = strings.discover.actionSearchMovies
            modeHintLabel.text = strings.discover.discoverSubtitle
            return
        }
        if (mode == MoviesPanelMode.RESOLVER_ONLY || looksLikeLinkOrTrace(text)) {
            primaryGoButton.text = if (mode == MoviesPanelMode.UNIFIED) strings.ui.unifiedActionResolve else strings.actionExtract
            modeHintLabel.text = strings.ui.smartHomeLinkDetected
        } else {
            primaryGoButton.text = strings.discover.actionSearchMovies
            modeHintLabel.text = strings.ui.smartHomeMediaDetected
        }
    }

    fun sendUrlToResolver(url: String) {
        omniboxField.text = url
        updateSmartHint()
        resolveUrlInput(url)
    }

    private fun submitSmartInput() {
        val raw = omniboxField.text.trim()
        if (raw.isEmpty()) {
            if (mode == MoviesPanelMode.RESOLVER_ONLY) {
                resolverStatusLabel.text = strings.errorEmptyUrl
            } else if (currentFilters.hasActiveFilters()) {
                currentQuery = ""
                runMediaSearch(page = 1)
            }
            return
        }

        if (mode != MoviesPanelMode.MOVIES_ONLY && looksLikeLinkOrTrace(raw)) {
            resolveUrlInput(raw)
        } else {
            currentQuery = raw
            saveRecentSearch(raw)
            runMediaSearch(page = 1)
        }
    }

    private fun resolveUrlInput(rawInput: String) {
        resolveJob?.cancel()
        currentResolution = null
        selectedXVariant = null
        resolverStatusLabel.text = strings.loadingNormalMessage
        cancelResolveButton.isVisible = true
        resolverResultCard.isVisible = true
        resolverResultCard.removeAll()
        resolverResultCard.revalidate()
        resolverResultCard.repaint()

        val settings = getSettings()
        resolveJob = uiScope.launch {
            try {
                if (VideoLinkExtractor.looksLikeRawCurlTrace(rawInput)) {
                    val parsed = resolver.resolveFromRawTrace(rawInput)
                    currentResolution = parsed
                    resolverStatusLabel.text = if (parsed.directVideoUrl != null) strings.statusDone else strings.statusError
                    renderResolutionResult(parsed)
                    return@launch
                }

                val cleanUrl = VideoLinkExtractor.extractInputUrl(rawInput)
                if (cleanUrl == null) {
                    resolverStatusLabel.text = strings.errorInvalidUrl
                    return@launch
                }

                val result = resolver.resolve(
                    inputUrl = cleanUrl,
                    youtubeApiKey = settings.mediaPreferences.youtubeApiKey.ifBlank { null },
                    onLogUpdate = { _, hop ->
                        resolverStatusLabel.text = strings.statusProcessingStep(hop)
                    },
                )
                currentResolution = result
                selectedXVariant = result.xVideoVariants.firstOrNull()
                resolverStatusLabel.text = strings.statusDone
                renderResolutionResult(result)
            } catch (e: UnsupportedPlatformException) {
                resolverStatusLabel.text = when (e.platform.name) {
                    "INSTAGRAM" -> strings.errorInstagramUnsupported
                    else -> strings.errorYouTubeUnsupported
                }
            } catch (e: YouTubeUnavailableException) {
                resolverStatusLabel.text = e.reason ?: strings.errorYouTubeUnavailable
            } catch (e: XExtractionException) {
                resolverStatusLabel.text = e.error.name
            } catch (e: Exception) {
                resolverStatusLabel.text = e.localizedMessage ?: strings.errorNetworkGeneric
            } finally {
                cancelResolveButton.isVisible = false
            }
        }
    }

    private fun renderResolutionResult(result: CurlResolutionResult) {
        resolverResultCard.removeAll()
        val card = DesktopTheme.styledCard(elevated = true)
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)

        val yt = result.youtubeInfo
        if (yt != null) {
            renderYouTubeCard(card, yt)
        } else {
            val activeVariant = selectedXVariant
            val effectiveUrl = activeVariant?.url ?: result.directVideoUrl
            val effectiveName = activeVariant?.suggestedFileName
                ?: result.filename
                ?: "mediax_video.mp4"

            val titleLine = result.mediaTitle?.let { "$it — $effectiveName" } ?: effectiveName
            card.add(DesktopTheme.sectionTitle("${strings.resultCardTitle}: $titleLine"))
            card.add(Box.createVerticalStrut(4))

            val detailsMeta = listOfNotNull(
                result.extension,
                activeVariant?.fileSizeFormatted ?: result.fileSizeFormatted?.let { strings.resultSizeLabel(it) },
                result.hops.takeIf { it.isNotEmpty() }?.let { strings.resultHopsLabel(it.size) },
                result.mediaAuthor,
            ).joinToString("  •  ")
            if (detailsMeta.isNotBlank()) {
                card.add(DesktopTheme.mutedLabel(detailsMeta))
                card.add(Box.createVerticalStrut(6))
            }

            if (result.xVideoVariants.size > 1) {
                val variantBar = JPanel(FlowLayout(FlowLayout.LEADING, 6, 4)).apply { isOpaque = false }
                result.xVideoVariants.forEach { variant ->
                    val label = "${variant.qualityLabel}${variant.fileSizeFormatted?.let { " ($it)" } ?: ""}"
                    val chip = DesktopTheme.chipButton(label, selected = variant == activeVariant)
                    chip.addActionListener {
                        selectedXVariant = variant
                        renderResolutionResult(result)
                    }
                    variantBar.add(chip)
                }
                card.add(variantBar)
                card.add(Box.createVerticalStrut(6))
            }

            if (effectiveUrl != null) {
                val urlBox = DesktopTheme.styledField("").apply {
                    text = effectiveUrl
                    isEditable = false
                }
                card.add(urlBox)
                card.add(Box.createVerticalStrut(8))

                val actionsRow = JPanel(FlowLayout(FlowLayout.LEADING, 8, 2)).apply { isOpaque = false }
                actionsRow.add(
                    DesktopTheme.primaryButton(strings.actionDownloadHere).apply {
                        addActionListener {
                            onEnqueueDownload(effectiveUrl, effectiveName, result.customUserAgent)
                            onSwitchToDownloads()
                        }
                    }
                )
                actionsRow.add(
                    DesktopTheme.secondaryButton("▶ VLC / Play").apply {
                        addActionListener { DesktopPlatformBridge.openInVlcOrSystemPlayer(effectiveUrl) }
                    }
                )
                actionsRow.add(
                    DesktopTheme.secondaryButton(strings.actionCopyLink).apply {
                        addActionListener { DesktopPlatformBridge.copyToClipboard(effectiveUrl) }
                    }
                )
                actionsRow.add(
                    DesktopTheme.secondaryButton(strings.actionOpenLink).apply {
                        addActionListener { DesktopPlatformBridge.openInDefaultBrowser(effectiveUrl) }
                    }
                )
                card.add(actionsRow)
            } else {
                card.add(DesktopTheme.mutedLabel(strings.errorNoVideoFound))
            }
        }

        if (getSettings().developerMode && result.verboseLog.isNotBlank()) {
            card.add(Box.createVerticalStrut(8))
            val traceArea = JTextArea(result.verboseLog.take(3500)).apply {
                isEditable = false
                rows = 7
                background = DesktopTheme.palette.bg
                foreground = DesktopTheme.palette.secondary
                font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            }
            card.add(JScrollPane(traceArea).apply { preferredSize = Dimension(800, 140) })
        }

        resolverResultCard.add(card)
        resolverResultCard.isVisible = true
        resolverResultCard.revalidate()
        resolverResultCard.repaint()
    }

    private fun renderYouTubeCard(card: JPanel, yt: YouTubeMetadataInfo) {
        card.add(DesktopTheme.sectionTitle("${strings.ytCardBadge}: ${yt.title}"))
        card.add(Box.createVerticalStrut(4))
        val infoParts = listOfNotNull(
            yt.channelTitle,
            yt.durationSeconds?.let { dur ->
                val m = dur / 60
                val s = dur % 60
                strings.ytDurationLabel("%d:%02d".format(m, s))
            },
            yt.publishedAt,
        ).joinToString("  •  ")
        if (infoParts.isNotBlank()) card.add(DesktopTheme.mutedLabel(infoParts))
        card.add(Box.createVerticalStrut(6))
        card.add(DesktopTheme.mutedLabel(strings.ytPremiumOfflineNote))
        card.add(Box.createVerticalStrut(8))

        val actions = JPanel(FlowLayout(FlowLayout.LEADING, 8, 2)).apply { isOpaque = false }
        actions.add(
            DesktopTheme.primaryButton(strings.ytOpenOfficialApp).apply {
                addActionListener { DesktopPlatformBridge.openInDefaultBrowser(yt.canonicalUrl) }
            }
        )
        actions.add(
            DesktopTheme.secondaryButton(strings.ytOpenStudio).apply {
                addActionListener { DesktopPlatformBridge.openInDefaultBrowser("https://studio.youtube.com") }
            }
        )
        actions.add(
            DesktopTheme.secondaryButton(strings.ytOpenTakeout).apply {
                addActionListener { DesktopPlatformBridge.openInDefaultBrowser("https://takeout.google.com") }
            }
        )
        card.add(actions)
    }

    private fun runMediaSearch(page: Int) {
        searchJob?.cancel()
        val settings = getSettings()
        searchJob = uiScope.launch {
            val res = metadataRepo.searchAndDiscoverMedia(
                rawQuery = currentQuery,
                mediaTypeFilter = mediaTypeFilter,
                filters = currentFilters,
                page = page,
                preferences = settings.mediaPreferences,
            )
            res.onSuccess { pageData ->
                searchResultsPage = pageData
                renderSearchResults()
            }.onFailure {
                searchResultsPage = MediaSearchPage(emptyList())
                renderSearchResults()
            }
        }
    }

    private fun renderSearchResults() {
        searchResultsPanel.removeAll()
        val pageData = searchResultsPage ?: run {
            searchResultsPanel.revalidate()
            searchResultsPanel.repaint()
            return
        }

        val headerRow = JPanel(BorderLayout()).apply { isOpaque = false }
        headerRow.add(
            DesktopTheme.sectionTitle(
                if (currentQuery.isNotBlank()) "${strings.discover.actionSearchMovies}: $currentQuery"
                else strings.discover.popularTitle
            ),
            BorderLayout.LINE_START,
        )
        headerRow.add(
            DesktopTheme.mutedLabel(strings.discover.pageIndicator(pageData.page, pageData.totalPages)),
            BorderLayout.LINE_END,
        )
        searchResultsPanel.add(headerRow)
        searchResultsPanel.add(Box.createVerticalStrut(8))

        if (pageData.items.isEmpty()) {
            val emptyCard = DesktopTheme.styledCard(elevated = false)
            emptyCard.layout = BoxLayout(emptyCard, BoxLayout.Y_AXIS)
            emptyCard.add(DesktopTheme.sectionTitle(strings.discover.searchNoResultsTitle))
            emptyCard.add(Box.createVerticalStrut(4))
            emptyCard.add(DesktopTheme.mutedLabel(strings.discover.searchNoResultsDesc))
            searchResultsPanel.add(emptyCard)
        } else {
            searchResultsPanel.add(buildMediaCardGrid(pageData.items))
            if (pageData.hasMorePages) {
                searchResultsPanel.add(Box.createVerticalStrut(8))
                val nextBtn = DesktopTheme.secondaryButton(strings.discover.actionLoadMore).apply {
                    addActionListener { runMediaSearch(pageData.page + 1) }
                }
                searchResultsPanel.add(nextBtn)
            }
        }
        searchResultsPanel.revalidate()
        searchResultsPanel.repaint()
    }

    private fun loadTrendingAsync(forceRefresh: Boolean = false) {
        if (mode == MoviesPanelMode.RESOLVER_ONLY) return
        val prefs = getSettings().mediaPreferences
        uiScope.launch {
            val bundle = runCatching {
                metadataRepo.getTrendingFeed(
                    preferences = prefs,
                    persistence = persistence,
                    forceRefresh = forceRefresh,
                )
            }.getOrNull() ?: return@launch
            renderTrending(bundle)
        }
    }

    private fun renderTrending(bundle: TrendingFeedBundle) {
        trendingPanel.removeAll()
        val card = DesktopTheme.styledCard(elevated = false)
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)

        val topBar = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        val left = JPanel(FlowLayout(FlowLayout.LEADING, 8, 0)).apply { isOpaque = false }
        left.add(DesktopTheme.sectionTitle(strings.discover.trendingSectionTitle))
        if (bundle.attribution.isNotBlank()) {
            left.add(DesktopTheme.mutedLabel(strings.discover.trendingAttributedBy(bundle.attribution)))
        }
        topBar.add(left, BorderLayout.LINE_START)

        val right = JPanel(FlowLayout(FlowLayout.TRAILING, 6, 0)).apply { isOpaque = false }
        val activeWindow = getSettings().mediaPreferences.trendingWindow
        val dayBtn = DesktopTheme.chipButton(strings.discover.trendingWindowDay, selected = activeWindow == TrendingTimeWindow.DAY).apply {
            addActionListener {
                val cur = getSettings()
                persistence.saveSettings(cur.copy(mediaPreferences = cur.mediaPreferences.copy(trendingWindow = TrendingTimeWindow.DAY)))
                loadTrendingAsync(forceRefresh = true)
            }
        }
        val weekBtn = DesktopTheme.chipButton(strings.discover.trendingWindowWeek, selected = activeWindow == TrendingTimeWindow.WEEK).apply {
            addActionListener {
                val cur = getSettings()
                persistence.saveSettings(cur.copy(mediaPreferences = cur.mediaPreferences.copy(trendingWindow = TrendingTimeWindow.WEEK)))
                loadTrendingAsync(forceRefresh = true)
            }
        }
        val refreshBtn = DesktopTheme.secondaryButton(strings.discover.trendingRefreshAction).apply {
            addActionListener { loadTrendingAsync(forceRefresh = true) }
        }
        right.add(dayBtn)
        right.add(weekBtn)
        right.add(refreshBtn)
        topBar.add(right, BorderLayout.LINE_END)
        card.add(topBar)

        if (bundle.lastUpdatedMillis > 0L) {
            val fmt = SimpleDateFormat("HH:mm", Locale.US)
            card.add(DesktopTheme.mutedLabel(strings.discover.trendingLastUpdated(fmt.format(Date(bundle.lastUpdatedMillis)))))
        }
        if (bundle.isUsingFallbackSources) {
            card.add(DesktopTheme.mutedLabel(strings.discover.trendingFallbackNotice))
        }

        if (bundle.movies.isNotEmpty()) {
            card.add(Box.createVerticalStrut(8))
            card.add(DesktopTheme.mutedLabel(strings.discover.trendingMoviesTitle).apply { font = font.deriveFont(Font.BOLD, 13f) })
            card.add(Box.createVerticalStrut(6))
            card.add(buildMediaCardGrid(bundle.movies))
        }
        if (bundle.tvShows.isNotEmpty()) {
            card.add(Box.createVerticalStrut(10))
            card.add(DesktopTheme.mutedLabel(strings.discover.trendingTvTitle).apply { font = font.deriveFont(Font.BOLD, 13f) })
            card.add(Box.createVerticalStrut(6))
            card.add(buildMediaCardGrid(bundle.tvShows))
        }

        trendingPanel.add(card)
        trendingPanel.revalidate()
        trendingPanel.repaint()
    }

    private fun renderPersonalSections() {
        if (mode == MoviesPanelMode.RESOLVER_ONLY) return
        personalPanel.removeAll()

        val recentSearches = persistence.loadRecentSearches()
        if (recentSearches.isNotEmpty()) {
            val searchChips = JPanel(FlowLayout(FlowLayout.LEADING, 6, 4)).apply { isOpaque = false }
            searchChips.add(DesktopTheme.mutedLabel("${strings.discover.recentSearchesTitle}:"))
            recentSearches.take(10).forEach { q ->
                searchChips.add(
                    DesktopTheme.chipButton(q).apply {
                        addActionListener {
                            omniboxField.text = q
                            currentQuery = q
                            runMediaSearch(page = 1)
                        }
                    }
                )
            }
            personalPanel.add(searchChips)
            personalPanel.add(Box.createVerticalStrut(8))
        }

        val favorites = persistence.loadFavorites()
        if (favorites.isNotEmpty()) {
            personalPanel.add(DesktopTheme.sectionTitle(strings.discover.favoritesTitle))
            personalPanel.add(Box.createVerticalStrut(6))
            personalPanel.add(buildMediaCardGrid(favorites.take(10)))
            personalPanel.add(Box.createVerticalStrut(10))
        }

        val viewed = persistence.loadRecentlyViewed()
        if (viewed.isNotEmpty()) {
            personalPanel.add(DesktopTheme.sectionTitle(strings.discover.recentlyViewedTitle))
            personalPanel.add(Box.createVerticalStrut(6))
            personalPanel.add(buildMediaCardGrid(viewed.take(10)))
        }

        personalPanel.revalidate()
        personalPanel.repaint()
    }

    private fun buildMediaCardGrid(items: List<MediaSearchResult>): JPanel {
        val grid = JPanel(GridLayout(0, 5, 10, 10)).apply { isOpaque = false }
        val lang = getSettings().language
        items.forEach { item ->
            val tile = DesktopTheme.styledCard(elevated = true, paddingV = 8, paddingH = 8)
            tile.layout = BorderLayout(6, 6)
            tile.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            tile.preferredSize = Dimension(165, 255)

            val posterLbl = JLabel(
                if (item.mediaType == MediaType.TV) "TV" else "MOVIE",
                SwingConstants.CENTER,
            ).apply {
                preferredSize = Dimension(145, 185)
                isOpaque = true
                background = DesktopTheme.palette.surface
                foreground = DesktopTheme.palette.textSecondary
            }
            tile.add(posterLbl, BorderLayout.CENTER)

            uiScope.launch {
                val icon = DesktopPlatformBridge.loadScaledImage(item.posterUrl, persistence.imageCacheDir, 145, 185)
                if (icon != null) {
                    posterLbl.icon = icon
                    posterLbl.text = ""
                }
            }

            val metaBox = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            val displayTitle = if (lang == AppLanguage.FA && !item.persianTitle.isNullOrBlank()) {
                item.persianTitle!!
            } else {
                item.title
            }
            val titleLbl = JLabel(displayTitle.take(26)).apply {
                foreground = DesktopTheme.palette.textPrimary
                font = font.deriveFont(Font.BOLD, 12f)
            }
            val subText = listOfNotNull(
                item.releaseYear?.toString(),
                item.imdbRating?.let { "★ %.1f".format(it) },
                if (item.mediaType == MediaType.TV) strings.discover.badgeTvShow else strings.discover.badgeMovie,
            ).joinToString(" • ")
            metaBox.add(titleLbl)
            metaBox.add(DesktopTheme.mutedLabel(subText))
            tile.add(metaBox, BorderLayout.SOUTH)

            tile.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        openDetails(item)
                    }
                }
            )
            grid.add(tile)
        }
        return grid
    }

    private fun openDetails(item: MediaSearchResult) {
        val updatedViewed = (listOf(item) + persistence.loadRecentlyViewed().filterNot { it.id == item.id }).take(20)
        persistence.saveRecentlyViewed(updatedViewed)

        val favorites = persistence.loadFavorites()
        val isFav = favorites.any { it.id == item.id }
        val settings = getSettings()
        val dlg = DesktopMediaDetailsDialog(
            owner = ownerFrame,
            summary = item,
            language = settings.language,
            preferences = settings.mediaPreferences,
            sourceSites = getSourceSites(),
            isFavoriteInitial = isFav,
            metadataRepo = metadataRepo,
            sourceEngine = sourceEngine,
            imageCacheDir = persistence.imageCacheDir,
            uiScope = uiScope,
            onToggleFavorite = { target ->
                val cur = persistence.loadFavorites()
                val next = if (cur.any { it.id == target.id }) cur.filterNot { it.id == target.id } else listOf(target) + cur
                persistence.saveFavorites(next)
                renderPersonalSections()
            },
            onSendUrlToResolver = { url -> sendUrlToResolver(url) },
            onEnqueueDirectUrl = { url, name ->
                onEnqueueDownload(url, name, null)
                onSwitchToDownloads()
            },
        )
        dlg.isVisible = true
        renderPersonalSections()
    }

    private fun saveRecentSearch(query: String) {
        val current = persistence.loadRecentSearches()
        val next = (listOf(query) + current.filterNot { it.equals(query, ignoreCase = true) }).take(15)
        persistence.saveRecentSearches(next)
        renderPersonalSections()
    }

    fun refreshTranslationsAndData() {
        val settings = getSettings()
        strings = stringsFor(settings.language)
        DesktopTheme.applyOrientation(this, settings.language)
        updateSmartHint()

        uiScope.launch {
            val genres = runCatching { metadataRepo.getAvailableGenres(settings.mediaPreferences) }.getOrDefault(emptyList())
            availableGenres = genres
            genreCombo.removeAllItems()
            genreCombo.addItem(strings.discover.filterAllGenres)
            genres.forEach { genreCombo.addItem(it.name) }
        }

        loadTrendingAsync(forceRefresh = false)
        renderPersonalSections()
    }
}
