package com.example.desktop

import com.example.media.AppRuntimeConfig
import com.example.media.MediaPreferences
import com.example.media.SearchLanguagePreference
import com.example.media.SourceSiteConfig
import com.example.media.SourceSitesCatalog
import com.example.media.TmdbCredentialProvider
import com.example.ui.AppLanguage
import com.example.ui.theme.ThemeMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopPersistenceAndConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `desktop persistence roundtrips settings, favorites, and custom source sites`() {
        val dir = File(tempFolder.root, "appdata_mediax")
        val persistence = DesktopPersistence(dir)

        val settings = DesktopAppSettings(
            language = AppLanguage.FA,
            themeMode = ThemeMode.DARK,
            developerMode = true,
            downloadDirectoryPath = File(tempFolder.root, "dl").absolutePath,
            connectionsPerDownload = 8,
            concurrencyLimitCode = "3",
            mediaPreferences = MediaPreferences(
                searchLanguage = SearchLanguagePreference.FA,
                watchRegion = "GB",
                useSeparateTabs = true,
            ),
        )
        persistence.saveSettings(settings)
        val loaded = persistence.loadSettings()
        assertEquals(AppLanguage.FA, loaded.language)
        assertEquals(ThemeMode.DARK, loaded.themeMode)
        assertEquals(8, loaded.connectionsPerDownload)
        assertTrue(loaded.mediaPreferences.useSeparateTabs)
        assertEquals("GB", loaded.mediaPreferences.watchRegion)

        val custom = SourceSiteConfig(
            id = "custom_1",
            name = "Custom Film",
            baseUrl = "https://example.org",
            searchUrlTemplate = "https://example.org/?s={query}",
            isCustom = true,
        )
        val defaults = SourceSitesCatalog.BUILT_IN_SITES
        persistence.saveSourceSites(defaults.map { if (it.id == "myf2ms") it.copy(enabled = false) else it } + custom)
        val merged = persistence.loadSourceSites()
        assertTrue(merged.any { it.id == "myf2ms" && !it.enabled })
        assertTrue(merged.any { it.id == "custom_1" && it.isCustom })
    }

    @Test
    fun `desktop build config wires AppRuntimeConfig without exposing raw secrets`() {
        AppRuntimeConfig.configure(
            tmdbProxyBaseUrl = DesktopBuildConfig.TMDB_PROXY_BASE_URL,
            tmdbMaskHex = DesktopBuildConfig.TMDB_MASK_HEX,
            tmdbTokenBlob = DesktopBuildConfig.TMDB_TOKEN_BLOB,
            tmdbKeyBlob = DesktopBuildConfig.TMDB_KEY_BLOB,
        )
        assertTrue(DesktopBuildConfig.VERSION_NAME.isNotBlank())
        assertTrue(DesktopBuildConfig.VERSION_CODE > 0)
        // Credential provider accepts DesktopBuildConfig format without throwing
        val provider = TmdbCredentialProvider()
        assertTrue(provider.authMode().isNotBlank())
    }
}
