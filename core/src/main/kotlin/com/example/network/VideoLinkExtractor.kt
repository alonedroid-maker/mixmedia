package com.example.network

import java.net.URLDecoder
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ExtractedLinkCandidate(
    val url: String,
    val anchorText: String? = null,
    val qualityLabel: String? = null,
    val sizeHint: String? = null,
    val isVideo: Boolean = false,
)

object VideoLinkExtractor {

    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "avi", "mov", "webm", "flv", "m4v", "ts", "m3u8", "wmv", "3gp"
    )

    val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus"
    )

    val ARCHIVE_AND_APP_EXTENSIONS = setOf(
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "apk", "apks", "xapk", "exe", "msi", "dmg", "iso", "img", "bin"
    )

    val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "srt", "ass"
    )

    val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp"
    )

    // Extensions safe to extract when scanning an HTML/JSON response body (excludes webpage image/css noise)
    val BODY_EXTRACTABLE_EXTENSIONS = VIDEO_EXTENSIONS + AUDIO_EXTENSIONS + ARCHIVE_AND_APP_EXTENSIONS + DOCUMENT_EXTENSIONS

    // All direct downloadable extensions when given directly as a URL or in Location headers
    val DOWNLOADABLE_EXTENSIONS = BODY_EXTRACTABLE_EXTENSIONS + IMAGE_EXTENSIONS

    private val URL_REGEX = Regex(
        """https?://[^\s"'<>\]\)]+""",
        RegexOption.IGNORE_CASE
    )

    private val ANCHOR_TAG_REGEX = Regex(
        """<a\s+([^>]*?)href\s*=\s*["']([^"']+)["']([^>]*)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val MEDIA_SRC_REGEX = Regex(
        """<(?:source|video)\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val OG_VIDEO_REGEX = Regex(
        """<meta[^>]+(?:property|name)\s*=\s*["']og:video(?::url|:secure_url)?["'][^>]+content\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val SIZE_HINT_REGEX = Regex(
        """\b(\d+(?:[.,]\d+)?\s*(?:GB|MB|KB|GiB|MiB))\b""",
        RegexOption.IGNORE_CASE
    )

    private val REFRESH_URL_REGEX = Regex(
        """url\s*=\s*['"]?([^'";\s]+)['"]?""",
        RegexOption.IGNORE_CASE
    )

    private val FILENAME_HEADER_REGEX = Regex(
        """filename\*?=(?:UTF-8''|")?([^";\r\n]+)"?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Extracts the target HTTP/HTTPS URL from a raw URL, `vlc://` deep link,
     * or a pasted curl command such as `curl -L -v "http://..."`.
     */
    fun extractInputUrl(rawInput: String): String? {
        val trimmed = normalizeVlcScheme(rawInput.trim())
        if (trimmed.isEmpty()) return null

        val match = URL_REGEX.find(trimmed)?.value ?: return null
        return cleanTrailingPunctuation(match)
    }

    /**
     * Converts `vlc://http://...` or `vlc://example.com/...` into standard http/https URL.
     */
    fun normalizeVlcScheme(raw: String): String {
        if (!raw.startsWith("vlc://", ignoreCase = true)) return raw
        val stripped = raw.substring(6).trimStart('/')
        return if (stripped.startsWith("http://", ignoreCase = true) ||
            stripped.startsWith("https://", ignoreCase = true)
        ) {
            stripped
        } else if (stripped.isNotBlank()) {
            "http://$stripped"
        } else {
            raw
        }
    }

    /**
     * Returns true if the user pasted raw multi-line `curl -L -v` verbose output
     * rather than a single URL or curl command.
     */
    fun looksLikeRawCurlTrace(rawInput: String): Boolean {
        val trimmed = rawInput.trim()
        return trimmed.contains("\n") && (
            trimmed.contains("< HTTP/") ||
                trimmed.contains("* Issue another request") ||
                trimmed.contains("< location:", ignoreCase = true) ||
                trimmed.contains("location:", ignoreCase = true)
            )
    }

    /**
     * Checks whether a URL starts with http:// or https:// and has a video file extension
     * (e.g., .mkv, .mp4, .avi) before any query string or fragment.
     */
    fun isVideoDownloadUrl(url: String): Boolean {
        return extractUrlExtensionLower(url) in VIDEO_EXTENSIONS
    }

    /**
     * Checks whether a URL points to any supported direct downloadable file extension
     * (video, audio, archive, apk, document, image) before query/fragment.
     */
    fun isDirectDownloadableUrl(url: String): Boolean {
        return extractUrlExtensionLower(url) in DOWNLOADABLE_EXTENSIONS
    }

    /**
     * Checks whether a URL is safe to extract from an HTML/text page body (excludes webpage image/icon assets).
     */
    fun isBodyExtractableDownloadUrl(url: String): Boolean {
        return extractUrlExtensionLower(url) in BODY_EXTRACTABLE_EXTENSIONS
    }

    private fun extractUrlExtensionLower(url: String): String? {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val withoutFragment = url.substringBefore('#')
        val withoutQuery = withoutFragment.substringBefore('?')
        val lastSegment = withoutQuery.substringAfterLast('/', "")
        if ('.' !in lastSegment) return null
        val extension = lastSegment.substringAfterLast('.', "").lowercase(Locale.US)
        return extension.takeIf { it.isNotBlank() }
    }

    /**
     * Scans arbitrary response text, JSON/HTML, or raw `curl -L -v` trace output and returns
     * all distinct video download URLs in order of appearance.
     */
    fun extractVideoUrlsFromText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val results = linkedSetOf<String>()
        for (match in URL_REGEX.findAll(normalized)) {
            val cleaned = cleanTrailingPunctuation(match.value)
            if (isVideoDownloadUrl(cleaned)) {
                results.add(cleaned)
            }
        }
        return results.toList()
    }

    /**
     * Scans text/headers for any downloadable file URL (video, audio, archives, APKs, documents).
     * When [fromHtmlBody] is true, ignores common webpage image formats to avoid false positives.
     */
    fun extractDownloadableUrlsFromText(text: String, fromHtmlBody: Boolean = false): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val results = linkedSetOf<String>()
        for (match in URL_REGEX.findAll(normalized)) {
            val cleaned = cleanTrailingPunctuation(match.value)
            val matched = if (fromHtmlBody) {
                isBodyExtractableDownloadUrl(cleaned)
            } else {
                isDirectDownloadableUrl(cleaned)
            }
            if (matched) {
                results.add(cleaned)
            }
        }
        return results.toList()
    }

    /**
     * Extracts all downloadable link options from an HTML or text payload, resolving relative
     * `href`/`src` links against [baseUrl] and preserving anchor labels, quality tags, and size hints.
     */
    fun extractRichDownloadCandidates(
        text: String,
        baseUrl: String? = null,
        fromHtmlBody: Boolean = true,
    ): List<ExtractedLinkCandidate> {
        if (text.isBlank()) return emptyList()
        val baseHttp = baseUrl?.toHttpUrlOrNull()
        val byUrl = linkedMapOf<String, ExtractedLinkCandidate>()

        fun considerCandidate(rawUrl: String, anchorText: String? = null, forceInclude: Boolean = false) {
            val decodedHref = rawUrl.trim().replace("&amp;", "&").replace("\\/", "/")
            if (decodedHref.isEmpty() || decodedHref.startsWith("#") || decodedHref.startsWith("javascript:", true)) {
                return
            }
            val resolved = if (decodedHref.startsWith("http://", true) || decodedHref.startsWith("https://", true)) {
                cleanTrailingPunctuation(decodedHref)
            } else {
                baseHttp?.resolve(decodedHref)?.toString()?.let(::cleanTrailingPunctuation) ?: return
            }

            val isVid = isVideoDownloadUrl(resolved)
            val isFile = if (fromHtmlBody) isBodyExtractableDownloadUrl(resolved) else isDirectDownloadableUrl(resolved)
            if (!forceInclude && !isVid && !isFile) return

            val cleanLabel = anchorText
                ?.replace(Regex("<[^>]+>"), " ")
                ?.replace("&nbsp;", " ")
                ?.replace("&amp;", "&")
                ?.replace("&quot;", "\"")
                ?.replace("&#039;", "'")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(110)
                ?.takeIf { it.isNotBlank() }

            val quality = detectQualityBadge(resolved, cleanLabel)
            val sizeHint = extractSizeHint(cleanLabel) ?: extractSizeHint(resolved)

            val existing = byUrl[resolved]
            if (existing == null || (existing.anchorText.isNullOrBlank() && !cleanLabel.isNullOrBlank())) {
                byUrl[resolved] = ExtractedLinkCandidate(
                    url = resolved,
                    anchorText = cleanLabel ?: existing?.anchorText,
                    qualityLabel = quality ?: existing?.qualityLabel,
                    sizeHint = sizeHint ?: existing?.sizeHint,
                    isVideo = isVid || (existing?.isVideo == true),
                )
            }
        }

        if (fromHtmlBody) {
            for (m in ANCHOR_TAG_REGEX.findAll(text)) {
                val attrsBefore = m.groupValues[1]
                val href = m.groupValues[2]
                val attrsAfter = m.groupValues[3]
                val inner = m.groupValues[4]
                val hasDownloadAttr = Regex("""\bdownload\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn("$attrsBefore $attrsAfter")
                considerCandidate(href, anchorText = inner, forceInclude = hasDownloadAttr)
            }
            for (m in MEDIA_SRC_REGEX.findAll(text)) {
                considerCandidate(m.groupValues[1], forceInclude = true)
            }
            for (m in OG_VIDEO_REGEX.findAll(text)) {
                considerCandidate(m.groupValues[1], forceInclude = true)
            }
        }

        val normalized = text
            .replace("\\/", "/")
            .replace("&amp;", "&")
        for (match in URL_REGEX.findAll(normalized)) {
            considerCandidate(match.value, anchorText = null, forceInclude = false)
        }

        return byUrl.values.toList()
    }

    fun detectQualityBadge(url: String, label: String? = null): String? {
        val combined = listOfNotNull(label, url).joinToString(" ").lowercase(Locale.US)
        val res = when {
            Regex("""\b(2160p|4k|uhd)\b""").containsMatchIn(combined) -> "4K 2160p"
            Regex("""\b1080p\b""").containsMatchIn(combined) -> "1080p"
            Regex("""\b720p\b""").containsMatchIn(combined) -> "720p"
            Regex("""\b480p\b""").containsMatchIn(combined) -> "480p"
            Regex("""\b360p\b""").containsMatchIn(combined) -> "360p"
            else -> null
        }
        val extra = buildList {
            if (Regex("""\bx265\b|\bhevc\b""").containsMatchIn(combined)) add("x265")
            if (Regex("""\b10bit\b""").containsMatchIn(combined)) add("10bit")
            if (Regex("""\bbluray\b|\bbdrip\b""").containsMatchIn(combined)) add("BluRay")
            else if (Regex("""\bweb-?dl\b|\bwebrip\b""").containsMatchIn(combined)) add("WEB-DL")
            if (combined.contains("دوبله") || Regex("""\bdubbed\b|\bpersian\.?dub\b""").containsMatchIn(combined)) add("دوبله")
            if (combined.contains("زیرنویس") || Regex("""\bsoftsub\b|\bhardsub\b""").containsMatchIn(combined)) add("زیرنویس")
        }
        return listOfNotNull(res, extra.take(2).joinToString(" ").takeIf { it.isNotEmpty() })
            .joinToString(" • ")
            .takeIf { it.isNotBlank() }
    }

    fun extractSizeHint(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return SIZE_HINT_REGEX.find(text)?.groupValues?.getOrNull(1)?.replace(',', '.')
    }

    fun qualitySortRank(qualityLabel: String?, url: String): Int {
        val s = (qualityLabel ?: url).lowercase(Locale.US)
        return when {
            s.contains("2160p") || s.contains("4k") -> 500
            s.contains("1080p") -> 400
            s.contains("720p") -> 300
            s.contains("480p") -> 200
            s.contains("360p") -> 100
            isVideoDownloadUrl(url) -> 50
            else -> 0
        }
    }

    fun extractRefreshRedirectUrl(refreshHeader: String?): String? {
        if (refreshHeader.isNullOrBlank()) return null
        val match = REFRESH_URL_REGEX.find(refreshHeader)?.groupValues?.getOrNull(1)
        return match?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Given a list of candidate download URLs (for example across 302 redirect hops),
     * returns the best direct download URL (last candidate in the redirect chain).
     */
    fun selectBestVideoUrl(candidates: List<String>): String? {
        return candidates.lastOrNull()
    }

    fun extractFilename(url: String, contentDisposition: String? = null): String {
        if (!contentDisposition.isNullOrBlank()) {
            val headerMatch = FILENAME_HEADER_REGEX.find(contentDisposition)
            val rawName = headerMatch?.groupValues?.getOrNull(1)?.trim()
            if (!rawName.isNullOrBlank()) {
                return decodeSafely(rawName)
            }
        }
        val pathOnly = url.substringBefore('#').substringBefore('?')
        val segment = pathOnly.substringAfterLast('/', "").trim()
        return if (segment.isNotEmpty()) decodeSafely(segment) else "downloaded_file"
    }

    fun extractExtension(url: String, filename: String? = null): String? {
        val target = filename?.takeIf { '.' in it }
            ?: url.substringBefore('#').substringBefore('?').substringAfterLast('/', "")
        val ext = target.substringAfterLast('.', "").uppercase(Locale.US)
        return ext.takeIf { it.lowercase(Locale.US) in DOWNLOADABLE_EXTENSIONS }
    }

    fun formatByteSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0L) return null
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private fun cleanTrailingPunctuation(url: String): String {
        return url.trimEnd('\'', '"', ',', ';', '.', '!', ')', ']')
    }

    private fun decodeSafely(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
