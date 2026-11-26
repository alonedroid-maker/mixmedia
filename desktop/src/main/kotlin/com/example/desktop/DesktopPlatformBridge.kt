package com.example.desktop

import java.awt.Desktop
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Windows 7 / 8 / 8.1 / 10 / 11+ and cross-platform Desktop integrations
 * (default browser launch, Windows Explorer reveal, VLC detection & launch, clipboard, image cache).
 */
object DesktopPlatformBridge {

    private val imageMemoryCache = ConcurrentHashMap<String, ImageIcon>()
    private val imageHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isWindowsOs(): Boolean =
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

    fun windowsVersionLabel(): String = buildString {
        append(System.getProperty("os.name").orEmpty())
        val ver = System.getProperty("os.version").orEmpty()
        if (ver.isNotBlank()) append(" (").append(ver).append(')')
    }

    /**
     * Queries Windows 10/11 Personalize registry key for system light/dark mode.
     * Safely falls back to dark mode on Windows 7/8 where the key does not exist.
     */
    fun detectSystemDarkMode(): Boolean {
        if (!isWindowsOs()) return true
        return try {
            val proc = ProcessBuilder(
                "reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme",
            ).redirectErrorStream(true).start()
            val finished = proc.waitFor(800, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return true
            }
            val out = proc.inputStream.bufferedReader().readText()
            // 0x0 = Dark mode enabled, 0x1 = Light mode enabled
            !out.contains("0x1")
        } catch (_: Exception) {
            true
        }
    }

    fun copyToClipboard(text: String) {
        runCatching {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }
    }

    fun readClipboardText(): String? = runCatching {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            cb.getData(DataFlavor.stringFlavor) as? String
        } else null
    }.getOrNull()

    fun openInDefaultBrowser(url: String): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("http://", ignoreCase = true) &&
            !clean.startsWith("https://", ignoreCase = true)
        ) return false

        return runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(clean))
                true
            } else if (isWindowsOs()) {
                ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", clean).start()
                true
            } else {
                false
            }
        }.getOrElse {
            if (isWindowsOs()) {
                runCatching {
                    ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", clean).start()
                    true
                }.getOrDefault(false)
            } else false
        }
    }

    fun openLocalFile(file: File): Boolean {
        if (!file.exists()) return false
        return runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file)
                true
            } else if (isWindowsOs()) {
                ProcessBuilder("cmd", "/c", "start", "", file.absolutePath).start()
                true
            } else false
        }.getOrDefault(false)
    }

    fun revealInFileExplorer(file: File): Boolean {
        val target = if (file.exists()) file else file.parentFile ?: return false
        return runCatching {
            if (isWindowsOs()) {
                if (file.exists() && file.isFile) {
                    ProcessBuilder("explorer.exe", "/select,${file.absolutePath}").start()
                } else {
                    ProcessBuilder("explorer.exe", target.absolutePath).start()
                }
                true
            } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(if (target.isDirectory) target else target.parentFile)
                true
            } else false
        }.getOrDefault(false)
    }

    fun findVlcExecutable(): String? {
        if (isWindowsOs()) {
            val candidates = listOfNotNull(
                System.getenv("ProgramFiles")?.let { "$it\\VideoLAN\\VLC\\vlc.exe" },
                System.getenv("ProgramFiles(x86)")?.let { "$it\\VideoLAN\\VLC\\vlc.exe" },
                "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
                "C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe",
            )
            candidates.firstOrNull { File(it).isFile }?.let { return it }
        } else {
            listOf("/usr/bin/vlc", "/usr/local/bin/vlc").firstOrNull { File(it).isFile }?.let { return it }
        }
        return null
    }

    fun openInVlcOrSystemPlayer(mediaTarget: String): Boolean {
        val vlc = findVlcExecutable()
        if (vlc != null) {
            return runCatching {
                ProcessBuilder(vlc, mediaTarget).start()
                true
            }.getOrDefault(false)
        }
        val local = File(mediaTarget)
        if (local.exists()) return openLocalFile(local)
        return openInDefaultBrowser(mediaTarget)
    }

    suspend fun loadScaledImage(
        url: String?,
        cacheDir: File,
        width: Int,
        height: Int,
    ): ImageIcon? = withContext(Dispatchers.IO) {
        val clean = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return@withContext null
        val key = "$clean|${width}x${height}"
        imageMemoryCache[key]?.let { return@withContext it }

        val hash = sha256Hex(clean)
        val diskFile = File(cacheDir, "$hash.img")
        val bytes: ByteArray? = if (diskFile.exists() && diskFile.length() > 64L) {
            runCatching { diskFile.readBytes() }.getOrNull()
        } else {
            runCatching {
                val req = Request.Builder().url(clean).header("User-Agent", "MediaX-Desktop/1.5").build()
                imageHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val raw = resp.body?.bytes()
                    if (raw != null && raw.size > 64) {
                        runCatching { diskFile.writeBytes(raw) }
                    }
                    raw
                }
            }.getOrNull()
        }
        if (bytes == null) return@withContext null

        val img: BufferedImage = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return@withContext null
        val scaled = img.getScaledInstance(width, height, Image.SCALE_SMOOTH)
        val icon = ImageIcon(scaled)
        if (imageMemoryCache.size > 240) imageMemoryCache.clear()
        imageMemoryCache[key] = icon
        icon
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}
