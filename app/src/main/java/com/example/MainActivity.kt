package com.example

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.download.DownloadForegroundService
import com.example.download.DownloadManagerHolder
import com.example.intent.VlcIntentHelper
import com.example.media.MovieDiscoveryViewModel
import com.example.ui.AppDestination
import com.example.ui.AppLanguage
import com.example.ui.LinkExtractorViewModel
import com.example.ui.MediaXApp
import com.example.ui.theme.MediaXTheme
import com.example.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    private val viewModel: LinkExtractorViewModel by viewModels()
    private val discoveryViewModel: MovieDiscoveryViewModel by viewModels()

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val downloadEngine = DownloadManagerHolder.get(applicationContext)
        discoveryViewModel.initialize(applicationContext)

        if (savedInstanceState == null) {
            val savedLangCode = prefs.getString(KEY_LANGUAGE, AppLanguage.EN.code)
            val savedLanguage = AppLanguage.entries.firstOrNull { it.code == savedLangCode } ?: AppLanguage.EN
            val savedDevMode = prefs.getBoolean(KEY_DEV_MODE, false)
            val savedThemeMode = ThemeMode.fromCode(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.code))

            viewModel.initializePreferences(
                language = savedLanguage,
                vlcEnabled = VlcIntentHelper.isVlcInterceptorEnabled(this),
                vlcInstalled = VlcIntentHelper.isVlcInstalled(this),
                developerMode = savedDevMode,
                themeMode = savedThemeMode,
                useSeparateTabs = discoveryViewModel.state.value.preferences.useSeparateTabs,
            )
            handleIncomingIntent(intent)
        }

        setContent {
            val screenState by viewModel.screenState.collectAsStateWithLifecycle()
            MediaXTheme(themeMode = screenState.themeMode) {
                MediaXApp(
                    viewModel = viewModel,
                    discoveryViewModel = discoveryViewModel,
                    downloadEngine = downloadEngine,
                    onLanguagePersisted = { lang ->
                        prefs.edit().putString(KEY_LANGUAGE, lang.code).apply()
                    },
                    onThemeModePersisted = { mode ->
                        prefs.edit().putString(KEY_THEME_MODE, mode.code).apply()
                    },
                    onDeveloperModePersisted = { enabled ->
                        prefs.edit().putBoolean(KEY_DEV_MODE, enabled).apply()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkClipboardForLink()
        }
    }

    private fun checkClipboardForLink() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clipText = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
            viewModel.onClipboardTextObserved(clipText)
        } catch (_: Exception) {
            // Ignore restricted clipboard states
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(DownloadForegroundService.EXTRA_OPEN_DOWNLOADS, false) == true) {
            viewModel.navigateTo(AppDestination.DOWNLOADS)
            return
        }
        val incomingUrl = VlcIntentHelper.extractUrlFromIntent(intent) ?: return
        viewModel.handleIncomingExternalUrl(incomingUrl)
    }

    companion object {
        private const val PREFS_NAME = "fixlink_prefs"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DEV_MODE = "developer_mode"
    }
}
