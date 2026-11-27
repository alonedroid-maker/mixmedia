package com.example.desktop

import com.example.download.DownloadStatus
import com.example.media.MetadataRepository
import com.example.media.SourceDiscoveryEngine
import com.example.network.CurlRedirectResolver
import com.example.network.VideoLinkExtractor
import com.example.ui.AppLanguage
import com.example.ui.stringsFor
import com.example.ui.theme.ThemeMode
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Image
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main top-level JFrame for MediaX Windows Desktop (Windows 7 SP1 through Windows 11+),
 * using a modern Sidebar Navigation Rail + CardLayout workspace.
 */
class MediaXDesktopFrame(
    initialUrlArg: String? = null,
) : JFrame("MediaX v${DesktopBuildConfig.VERSION_NAME}") {

    private companion object {
        const val ROUTE_DISCOVER = "discover"
        const val ROUTE_RESOLVER = "resolver"
        const val ROUTE_DOWNLOADS = "downloads"
        const val ROUTE_SETTINGS = "settings"
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val persistence = DesktopPersistence()
    private val metadataRepo = MetadataRepository()
    private val sourceEngine = SourceDiscoveryEngine(persistence = persistence)
    private val resolver = CurlRedirectResolver()
    private val downloadEngine = DesktopDownloadEngine(persistenceStore = persistence)

    @Volatile
    private var settings: DesktopAppSettings = persistence.loadSettings()

    private var activeRoute: String = ROUTE_DISCOVER
    private var appLogoIcon: ImageIcon? = null

    private val sidebarPanel = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(236, 0)
    }
    private val navListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val sidebarFooterPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val workspaceLayout = CardLayout()
    private val workspacePanel = JPanel(workspaceLayout).apply {
        isOpaque = false
    }

    private val downloadsPanel: DesktopDownloadsPanel
    private val settingsPanel: DesktopSettingsPanel
    private var primaryHubPanel: DesktopMoviesAndResolverPanel? = null
    private var classicResolverPanel: DesktopMoviesAndResolverPanel? = null

    private var activeDownloadsCount = 0
    private var activeDownloadsSpeed = ""

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1220, 810)
        setMinimumSize(Dimension(900, 620))
        setLocationRelativeTo(null)
        loadAppIcon()

        layout = BorderLayout()
        downloadsPanel = DesktopDownloadsPanel(
            ownerFrame = this,
            downloadEngine = downloadEngine,
            uiScope = uiScope,
            getSettings = { settings },
        )
        settingsPanel = DesktopSettingsPanel(
            ownerFrame = this,
            persistence = persistence,
            metadataRepo = metadataRepo,
            uiScope = uiScope,
            getSettings = { settings },
            onSettingsChanged = { updated, rebuildTheme ->
                val tabsChanged = updated.mediaPreferences.useSeparateTabs != settings.mediaPreferences.useSeparateTabs
                settings = updated
                if (rebuildTheme) {
                    DesktopTheme.apply(updated.themeMode, updated.language)
                    SwingUtilities.updateComponentTreeUI(this)
                }
                if (tabsChanged) {
                    rebuildWorkspace()
                } else {
                    refreshAllPanels()
                }
            },
            onEnqueueInstallerDownload = { url, fileName ->
                downloadsPanel.promptEnqueueUrl(url, fileName)
                selectDownloadsTab()
            },
        )

        add(sidebarPanel, BorderLayout.LINE_START)
        add(workspacePanel, BorderLayout.CENTER)
        rebuildWorkspace()

        uiScope.launch {
            downloadEngine.tasks.collectLatest { tasks ->
                val active = tasks.count { it.isActive || it.status == DownloadStatus.QUEUED }
                val totalSpeed = tasks.filter { it.status == DownloadStatus.DOWNLOADING }.sumOf { it.speedBytesPerSec }
                activeDownloadsCount = active
                activeDownloadsSpeed = if (totalSpeed > 0L) {
                    (VideoLinkExtractor.formatByteSize(totalSpeed) ?: "0 B") + "/s"
                } else {
                    ""
                }
                rebuildSidebar()
            }
        }

        if (!initialUrlArg.isNullOrBlank()) {
            if (settings.mediaPreferences.useSeparateTabs && classicResolverPanel != null) {
                navigateTo(ROUTE_RESOLVER)
                classicResolverPanel?.sendUrlToResolver(initialUrlArg)
            } else {
                navigateTo(ROUTE_DISCOVER)
                primaryHubPanel?.sendUrlToResolver(initialUrlArg)
            }
        }
    }

    private fun loadAppIcon() {
        runCatching {
            val stream = javaClass.getResourceAsStream("/mediax-icon.png") ?: return
            stream.use {
                val img = ImageIO.read(it)
                iconImage = img
                appLogoIcon = ImageIcon(img.getScaledInstance(38, 38, Image.SCALE_SMOOTH))
            }
        }
    }

    private fun rebuildWorkspace() {
        workspacePanel.removeAll()
        DesktopTheme.applyOrientation(this, settings.language)

        if (settings.mediaPreferences.useSeparateTabs) {
            val moviesPanel = createMoviesPanel(MoviesPanelMode.MOVIES_ONLY)
            val resolverPanel = createMoviesPanel(MoviesPanelMode.RESOLVER_ONLY)
            primaryHubPanel = moviesPanel
            classicResolverPanel = resolverPanel

            workspacePanel.add(moviesPanel, ROUTE_DISCOVER)
            workspacePanel.add(resolverPanel, ROUTE_RESOLVER)
            workspacePanel.add(downloadsPanel, ROUTE_DOWNLOADS)
            workspacePanel.add(settingsPanel, ROUTE_SETTINGS)
        } else {
            val unifiedPanel = createMoviesPanel(MoviesPanelMode.UNIFIED)
            primaryHubPanel = unifiedPanel
            classicResolverPanel = null

            workspacePanel.add(unifiedPanel, ROUTE_DISCOVER)
            workspacePanel.add(downloadsPanel, ROUTE_DOWNLOADS)
            workspacePanel.add(settingsPanel, ROUTE_SETTINGS)
            if (activeRoute == ROUTE_RESOLVER) {
                activeRoute = ROUTE_DISCOVER
            }
        }

        contentPane.background = DesktopTheme.palette.bg
        rebuildSidebar()
        workspaceLayout.show(workspacePanel, activeRoute)
        workspacePanel.revalidate()
        workspacePanel.repaint()
    }

    private fun rebuildSidebar() {
        val strings = stringsFor(settings.language)
        val p = DesktopTheme.palette

        sidebarPanel.removeAll()
        sidebarPanel.isOpaque = true
        sidebarPanel.background = p.sidebarBg
        sidebarPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(
                0,
                if (settings.language == AppLanguage.FA) 1 else 0,
                0,
                if (settings.language == AppLanguage.FA) 0 else 1,
                p.border,
            ),
            EmptyBorder(16, 12, 14, 12),
        )

        // Brand Lockup Header
        val brandCard = DesktopTheme.heroCard(paddingV = 12, paddingH = 12)
        brandCard.layout = BorderLayout(10, 0)
        if (appLogoIcon != null) {
            brandCard.add(JLabel(appLogoIcon), BorderLayout.LINE_START)
        }
        val brandTextCol = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        val titleRow = JPanel(FlowLayout(FlowLayout.LEADING, 6, 0)).apply { isOpaque = false }
        titleRow.add(
            JLabel("MediaX").apply {
                foreground = p.textPrimary
                font = font.deriveFont(Font.BOLD, 17f)
            }
        )
        titleRow.add(DesktopTheme.pillLabel("v${DesktopBuildConfig.VERSION_NAME}", p.primary, p.onPrimary))
        brandTextCol.add(titleRow)
        brandTextCol.add(Box.createVerticalStrut(3))
        brandTextCol.add(DesktopTheme.mutedLabel(DesktopPlatformBridge.windowsVersionLabel()))
        brandCard.add(brandTextCol, BorderLayout.CENTER)

        val topStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        topStack.add(brandCard)
        topStack.add(Box.createVerticalStrut(16))

        // Navigation Items
        navListPanel.removeAll()
        fun addNavItem(route: String, label: String, glyph: String, badgeText: String? = null) {
            val isSelected = activeRoute == route
            val rowPanel = JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, 44)
            }
            val displayText = if (badgeText != null) "$label  ($badgeText)" else label
            val btn: JButton = DesktopTheme.navButton(displayText, glyph, selected = isSelected).apply {
                addActionListener { navigateTo(route) }
            }
            rowPanel.add(btn, BorderLayout.CENTER)
            navListPanel.add(rowPanel)
            navListPanel.add(Box.createVerticalStrut(6))
        }

        addNavItem(ROUTE_DISCOVER, strings.navDiscover, "✦")
        if (settings.mediaPreferences.useSeparateTabs) {
            addNavItem(ROUTE_RESOLVER, strings.navResolver, "⚡")
        }
        val dlBadge = when {
            activeDownloadsCount > 0 && activeDownloadsSpeed.isNotBlank() -> "$activeDownloadsCount • $activeDownloadsSpeed"
            activeDownloadsCount > 0 -> "$activeDownloadsCount"
            else -> null
        }
        addNavItem(ROUTE_DOWNLOADS, strings.navDownloads, "⬇", dlBadge)
        addNavItem(ROUTE_SETTINGS, strings.navSettings, "⚙")

        topStack.add(navListPanel)
        sidebarPanel.add(topStack, BorderLayout.NORTH)

        // Quick Footer Controls (Theme + Language switchers)
        sidebarFooterPanel.removeAll()
        sidebarFooterPanel.add(Box.createVerticalStrut(8))
        val quickControlsGrid = JPanel(GridLayout(1, 2, 6, 0)).apply { isOpaque = false }
        val nextLang = if (settings.language == AppLanguage.FA) AppLanguage.EN else AppLanguage.FA
        val langBtn = DesktopTheme.chipButton(
            text = if (settings.language == AppLanguage.FA) "EN" else "فارسی",
            selected = false,
        ).apply {
            addActionListener {
                val updated = settings.copy(language = nextLang)
                persistence.saveSettings(updated)
                settings = updated
                DesktopTheme.apply(updated.themeMode, updated.language)
                SwingUtilities.updateComponentTreeUI(this@MediaXDesktopFrame)
                rebuildWorkspace()
                refreshAllPanels()
            }
        }

        val nextTheme = when (settings.themeMode) {
            ThemeMode.SYSTEM -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.SYSTEM
        }
        val themeBtnLabel = when (settings.themeMode) {
            ThemeMode.SYSTEM -> "◐ ${strings.ui.themeSystem}"
            ThemeMode.DARK -> "☾ ${strings.ui.themeDark}"
            ThemeMode.LIGHT -> "☀ ${strings.ui.themeLight}"
        }
        val themeBtn = DesktopTheme.chipButton(themeBtnLabel, selected = false).apply {
            addActionListener {
                val updated = settings.copy(themeMode = nextTheme)
                persistence.saveSettings(updated)
                settings = updated
                DesktopTheme.apply(updated.themeMode, updated.language)
                SwingUtilities.updateComponentTreeUI(this@MediaXDesktopFrame)
                rebuildWorkspace()
                refreshAllPanels()
            }
        }
        quickControlsGrid.add(langBtn)
        quickControlsGrid.add(themeBtn)
        sidebarFooterPanel.add(quickControlsGrid)
        sidebarPanel.add(sidebarFooterPanel, BorderLayout.SOUTH)

        DesktopTheme.applyOrientation(sidebarPanel, settings.language)
        sidebarPanel.revalidate()
        sidebarPanel.repaint()
    }

    private fun navigateTo(route: String) {
        activeRoute = route
        workspaceLayout.show(workspacePanel, route)
        rebuildSidebar()
    }

    private fun createMoviesPanel(panelMode: MoviesPanelMode): DesktopMoviesAndResolverPanel {
        return DesktopMoviesAndResolverPanel(
            ownerFrame = this,
            mode = panelMode,
            persistence = persistence,
            metadataRepo = metadataRepo,
            sourceEngine = sourceEngine,
            resolver = resolver,
            uiScope = uiScope,
            getSettings = { settings },
            getSourceSites = { persistence.loadSourceSites() },
            onEnqueueDownload = { url, name, customUa ->
                downloadsPanel.promptEnqueueUrl(url, name, customUa)
            },
            onSwitchToDownloads = { selectDownloadsTab() },
        )
    }

    private fun selectDownloadsTab() {
        navigateTo(ROUTE_DOWNLOADS)
    }

    private fun refreshAllPanels() {
        contentPane.background = DesktopTheme.palette.bg
        DesktopTheme.applyOrientation(this, settings.language)
        rebuildSidebar()
        primaryHubPanel?.refreshTranslationsAndData()
        classicResolverPanel?.refreshTranslationsAndData()
        downloadsPanel.refreshTranslations()
        settingsPanel.rebuildContent()
        revalidate()
        repaint()
    }

    override fun dispose() {
        uiScope.cancel()
        super.dispose()
    }
}
