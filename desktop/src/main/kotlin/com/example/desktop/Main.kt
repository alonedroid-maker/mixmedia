package com.example.desktop

import com.example.media.AppRuntimeConfig
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    // Prevent DirectDraw / GPU driver crashes on older Windows 7 machines while remaining fast on Windows 10/11
    System.setProperty("sun.java2d.noddraw", "true")
    System.setProperty("jdk.tls.client.protocols", "TLSv1.2,TLSv1.3")

    AppRuntimeConfig.configure(
        tmdbProxyBaseUrl = DesktopBuildConfig.TMDB_PROXY_BASE_URL,
        tmdbMaskHex = DesktopBuildConfig.TMDB_MASK_HEX,
        tmdbTokenBlob = DesktopBuildConfig.TMDB_TOKEN_BLOB,
        tmdbKeyBlob = DesktopBuildConfig.TMDB_KEY_BLOB,
    )

    val initialSettings = DesktopPersistence().loadSettings()
    val initialUrl = args.firstOrNull {
        it.startsWith("http://", ignoreCase = true) ||
            it.startsWith("https://", ignoreCase = true) ||
            it.startsWith("vlc://", ignoreCase = true)
    }

    SwingUtilities.invokeLater {
        DesktopTheme.apply(initialSettings.themeMode, initialSettings.language)
        val frame = MediaXDesktopFrame(initialUrlArg = initialUrl)
        frame.isVisible = true
    }
}
