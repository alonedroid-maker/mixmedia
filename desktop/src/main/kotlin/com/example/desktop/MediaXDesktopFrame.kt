package com.example.desktop

import com.example.media.MetadataRepository
import com.example.media.SourceDiscoveryEngine
import com.example.network.CurlRedirectResolver
import com.example.ui.stringsFor
import java.awt.BorderLayout
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JTabbedPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Main top-level JFrame for MediaX Windows Desktop (Windows 7 SP1 through Windows 11+).
 */
class MediaXDesktopFrame(
    initialUrlArg: String? = null,
) : JFrame("MediaX v${DesktopBuildConfig.VERSION_NAME}") {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val persistence = DesktopPersistence()
    private val metadataRepo = MetadataRepository()
    private val sourceEngine = SourceDiscoveryEngine(persistence = persistence)
    private val resolver = CurlRedirectResolver()
    private val downloadEngine = DesktopDownloadEngine(persistenceStore = persistence)

    @Volatile
    private var settings: DesktopAppSettings = persistence.loadSettings()

    private val tabs = JTabbedPane()
    private val downloadsPanel: DesktopDownloadsPanel
    private val settingsPanel: DesktopSettingsPanel
    private var primaryHubPanel: DesktopMoviesAndResolverPanel? = null
    private var classicResolverPanel: DesktopMoviesAndResolverPanel? = null

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1160, 780)
        setMinimumSize(java.awt.Dimension(860, 600))
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
                    javax.swing.SwingUtilities.updateComponentTreeUI(this)
                }
                if (tabsChanged) {
                    rebuildTabs()
                } else {
                    refreshAllPanels()
                }
            },
            onEnqueueInstallerDownload = { url, fileName ->
                downloadsPanel.promptEnqueueUrl(url, fileName)
                selectDownloadsTab()
            },
        )

        add(tabs, BorderLayout.CENTER)
        rebuildTabs()

        if (!initialUrlArg.isNullOrBlank()) {
            val targetPanel = classicResolverPanel ?: primaryHubPanel
            targetPanel?.sendUrlToResolver(initialUrlArg)
        }
    }

    private fun loadAppIcon() {
        runCatching {
            val stream = javaClass.getResourceAsStream("/mediax-icon.png") ?: return
            stream.use { iconImage = ImageIO.read(it) }
        }
    }

    private fun rebuildTabs() {
        val selectedIndex = tabs.selectedIndex
        val strings = stringsFor(settings.language)
        tabs.removeAll()
        DesktopTheme.applyOrientation(tabs, settings.language)

        if (settings.mediaPreferences.useSeparateTabs) {
            val moviesPanel = createMoviesPanel(MoviesPanelMode.MOVIES_ONLY)
            val resolverPanel = createMoviesPanel(MoviesPanelMode.RESOLVER_ONLY)
            primaryHubPanel = moviesPanel
            classicResolverPanel = resolverPanel

            tabs.addTab(strings.navDiscover, moviesPanel)
            tabs.addTab(strings.navResolver, resolverPanel)
            tabs.addTab(strings.navDownloads, downloadsPanel)
            tabs.addTab(strings.navSettings, settingsPanel)
        } else {
            val unifiedPanel = createMoviesPanel(MoviesPanelMode.UNIFIED)
            primaryHubPanel = unifiedPanel
            classicResolverPanel = null

            tabs.addTab(strings.navDiscover, unifiedPanel)
            tabs.addTab(strings.navDownloads, downloadsPanel)
            tabs.addTab(strings.navSettings, settingsPanel)
        }

        if (selectedIndex in 0 until tabs.tabCount) {
            tabs.selectedIndex = selectedIndex
        }
        tabs.revalidate()
        tabs.repaint()
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
        val idx = tabs.indexOfComponent(downloadsPanel)
        if (idx >= 0) tabs.selectedIndex = idx
    }

    private fun refreshAllPanels() {
        val strings = stringsFor(settings.language)
        if (tabs.tabCount >= 3) {
            if (settings.mediaPreferences.useSeparateTabs && tabs.tabCount == 4) {
                tabs.setTitleAt(0, strings.navDiscover)
                tabs.setTitleAt(1, strings.navResolver)
                tabs.setTitleAt(2, strings.navDownloads)
                tabs.setTitleAt(3, strings.navSettings)
            } else if (!settings.mediaPreferences.useSeparateTabs && tabs.tabCount == 3) {
                tabs.setTitleAt(0, strings.navDiscover)
                tabs.setTitleAt(1, strings.navDownloads)
                tabs.setTitleAt(2, strings.navSettings)
            }
        }
        primaryHubPanel?.refreshTranslationsAndData()
        classicResolverPanel?.refreshTranslationsAndData()
        downloadsPanel.refreshTranslations()
        settingsPanel.rebuildContent()
    }

    override fun dispose() {
        uiScope.cancel()
        super.dispose()
    }
}
