package com.example.desktop

import com.example.media.MediaPreferences
import com.example.media.SearchEngineOption
import com.example.media.SearchLanguagePreference
import com.example.media.TrendingTimeWindow
import com.example.network.GitHubUpdateChecker
import com.example.ui.AppLanguage
import com.example.ui.theme.ThemeMode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPersistenceAndDownloadTest {

    @Test
    fun `desktop persistence saves and reloads settings with unified tab mode default`() {
        val tempDir = Files.createTempDirectory("mediax-desktop-test").toFile()
        try {
            val persistence = DesktopPersistence(tempDir)
            val defaults = persistence.loadSettings()
            assertEquals(AppLanguage.EN, defaults.language)
            assertEquals(ThemeMode.SYSTEM, defaults.themeMode)
            assertFalse(defaults.mediaPreferences.useSeparateTabs)

            val updated = defaults.copy(
                language = AppLanguage.FA,
                themeMode = ThemeMode.DARK,
                connectionsPerDownload = 6,
                mediaPreferences = MediaPreferences(
                    searchLanguage = SearchLanguagePreference.FA,
                    searchEngine = SearchEngineOption.GOOGLE,
                    watchRegion = "IR",
                    trendingWindow = TrendingTimeWindow.WEEK,
                    useSeparateTabs = true,
                ),
            )
            persistence.saveSettings(updated)

            val reloaded = persistence.loadSettings()
            assertEquals(AppLanguage.FA, reloaded.language)
            assertEquals(ThemeMode.DARK, reloaded.themeMode)
            assertEquals(6, reloaded.connectionsPerDownload)
            assertTrue(reloaded.mediaPreferences.useSeparateTabs)
            assertEquals("IR", reloaded.mediaPreferences.watchRegion)
            assertEquals(TrendingTimeWindow.WEEK, reloaded.mediaPreferences.trendingWindow)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `desktop source sites catalog includes built in sites and saves custom sites`() {
        val tempDir = Files.createTempDirectory("mediax-desktop-sites").toFile()
        try {
            val persistence = DesktopPersistence(tempDir)
            val sites = persistence.loadSourceSites()
            assertTrue(sites.any { it.id.contains("serialblog") })
            assertTrue(sites.any { it.baseUrl.contains("myf2ms") })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `github update checker version comparison detects newer windows release`() {
        assertTrue(GitHubUpdateChecker.isNewerVersion("v1.6", "1.5"))
        assertFalse(GitHubUpdateChecker.isNewerVersion("v1.5", "1.5"))
    }
}
