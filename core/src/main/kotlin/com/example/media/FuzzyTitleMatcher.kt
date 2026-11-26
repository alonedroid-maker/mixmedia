package com.example.media

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ParsedSearchQuery(
    val raw: String,
    val cleanTitle: String,
    val normalizedTitle: String,
    val year: Int? = null,
    val isPersian: Boolean = false,
)

data class QueryModifiers(
    val includeSubtitle: Boolean = true,
    val includeDubbed: Boolean = false,
    val includeWatchOnline: Boolean = false,
    val includeDownload: Boolean = true,
)

object FuzzyTitleMatcher {

    private val persianScriptRegex = Regex("[\\u0600-\\u06FF]")
    private val trailingYearRegex = Regex("(?:^|\\s)[(\\[]?((?:19|20)\\d{2})[)\\]]?(?:\\s|$)")
    private val punctuationRegex = Regex("[^\\p{L}\\p{N}\\s]")
    private val multiSpaceRegex = Regex("\\s+")

    fun containsPersian(text: String): Boolean = persianScriptRegex.containsMatchIn(text)

    fun normalizeDigits(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun normalizeText(text: String): String {
        if (text.isBlank()) return ""
        val digitsNormalized = normalizeDigits(text)
        val sb = StringBuilder(digitsNormalized.length)
        for (ch in digitsNormalized) {
            when (ch) {
                'ي', 'ى', 'ئ' -> sb.append('ی')
                'ك' -> sb.append('ک')
                'ة' -> sb.append('ه')
                'أ', 'إ', 'آ' -> sb.append('ا')
                '\u200C', '\u200D', '\u200E', '\u200F' -> sb.append(' ')
                in '\u064B'..'\u065F', '\u0670' -> Unit
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return punctuationRegex.replace(sb.toString(), " ")
            .let { multiSpaceRegex.replace(it, " ") }
            .trim()
    }

    fun parseQuery(rawQuery: String): ParsedSearchQuery {
        val trimmed = rawQuery.trim()
        val asciiDigits = normalizeDigits(trimmed)
        var extractedYear: Int? = null
        val yearMatch = trailingYearRegex.findAll(asciiDigits).lastOrNull()
        var titlePart = asciiDigits
        if (yearMatch != null) {
            val candidateYear = yearMatch.groupValues[1].toIntOrNull()
            val stripped = asciiDigits.removeRange(yearMatch.range).trim()
            // Avoid stripping "2001" if that is the entire title like "2001: A Space Odyssey" unless followed/preceded by real words
            if (candidateYear != null && stripped.isNotBlank() && stripped.length >= 2) {
                extractedYear = candidateYear
                titlePart = stripped
            }
        }
        return ParsedSearchQuery(
            raw = trimmed,
            cleanTitle = titlePart,
            normalizedTitle = normalizeText(titlePart),
            year = extractedYear,
            isPersian = containsPersian(trimmed),
        )
    }

    fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = min(
                    min(curr[j] + 1, prev[j + 1] + 1),
                    prev[j] + cost,
                )
            }
            System.arraycopy(curr, 0, prev, 0, prev.size)
        }
        return prev[b.length]
    }

    fun stringSimilarity(aRaw: String, bRaw: String): Double {
        val a = normalizeText(aRaw)
        val b = normalizeText(bRaw)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val maxLen = max(a.length, b.length)
        val editSim = 1.0 - (levenshteinDistance(a, b).toDouble() / maxLen.toDouble())

        val tokensA = a.split(' ').filter { it.isNotEmpty() }
        val tokensB = b.split(' ').filter { it.isNotEmpty() }
        val overlap = if (tokensA.isNotEmpty() && tokensB.isNotEmpty()) {
            val matches = tokensA.count { tokA ->
                tokensB.any { tokB ->
                    tokB == tokA || tokB.startsWith(tokA) || tokA.startsWith(tokB) ||
                        (tokA.length >= 4 && tokB.length >= 4 && levenshteinDistance(tokA, tokB) <= 1)
                }
            }
            matches.toDouble() / max(tokensA.size, tokensB.size).toDouble()
        } else {
            0.0
        }

        val containsBonus = when {
            b.startsWith(a) -> 0.25
            b.contains(a) || a.contains(b) -> 0.16
            else -> 0.0
        }

        // Collapse spaces for compound/spaced Persian or English titles (e.g. "میان ستاره ای" vs "میان‌ستاره‌ای")
        val compactA = a.replace(" ", "")
        val compactB = b.replace(" ", "")
        val compactSim = if (compactA.isNotEmpty() && compactB.isNotEmpty()) {
            1.0 - (levenshteinDistance(compactA, compactB).toDouble() / max(compactA.length, compactB.length).toDouble())
        } else {
            0.0
        }

        return (max(editSim, compactSim) * 0.55 + overlap * 0.35 + containsBonus).coerceIn(0.0, 1.0)
    }

    fun scoreCandidate(
        query: ParsedSearchQuery,
        candidateTitle: String,
        candidateAltTitle: String? = null,
        candidateYear: Int? = null,
        basePopularityBoost: Double = 0.0,
    ): Double {
        val primarySim = stringSimilarity(query.cleanTitle, candidateTitle)
        val altSim = candidateAltTitle?.let { stringSimilarity(query.cleanTitle, it) } ?: 0.0
        var score = max(primarySim, altSim) * 100.0

        if (query.year != null && candidateYear != null) {
            score += when (abs(query.year - candidateYear)) {
                0 -> 35.0
                1 -> 12.0
                else -> -12.0
            }
        }
        return score + basePopularityBoost.coerceIn(0.0, 10.0)
    }

    /**
     * Generates lightweight spelling repair candidates for typos like "Interstelar" -> "Interstellar".
     */
    fun generateLightweightLookupFallbacks(cleanTitle: String): List<String> {
        val trimmed = cleanTitle.trim()
        if (trimmed.length < 4 || containsPersian(trimmed)) return emptyList()
        val variants = LinkedHashSet<String>()

        // Collapse duplicate letters ("interstelllar" -> "interstelar") and expand single trailing consonants
        val collapsed = trimmed.replace(Regex("([a-zA-Z])\\1+"), "$1")
        if (!collapsed.equals(trimmed, ignoreCase = true)) variants.add(collapsed)

        // Common English cinema/TV typo repairs (e.g. single 'l' -> 'll', 's' -> 'ss', missing vowel before 'r')
        val tokens = trimmed.split(' ').filter { it.isNotBlank() }
        if (tokens.isNotEmpty()) {
            val first = tokens.first()
            if (first.length >= 5) {
                variants.add(first.take(4)) // Prefix fallback for IMDb suggestion endpoint
            }
        }
        return variants.take(2)
    }

    fun formatSeasonEpisodeTag(seasonNumber: Int?, episodeNumber: Int?): String? {
        if (seasonNumber == null) return null
        val sStr = seasonNumber.toString().padStart(2, '0')
        return if (episodeNumber != null) {
            val eStr = episodeNumber.toString().padStart(2, '0')
            "S${sStr}E${eStr}"
        } else {
            "S$sStr"
        }
    }

    fun buildWebSearchQueries(
        target: SourceSearchTarget,
        searchLanguage: SearchLanguagePreference = SearchLanguagePreference.BOTH,
        modifiers: QueryModifiers = QueryModifiers(),
    ): List<String> {
        val queries = LinkedHashSet<String>()
        val title = target.title.trim()
        val persianTitle = target.persianTitle?.trim()?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        val yearPart = target.releaseYear?.takeIf { target.seasonNumber == null }?.let { " $it" }.orEmpty()
        val seTag = formatSeasonEpisodeTag(target.seasonNumber, target.episodeNumber)

        val includeEnglish = searchLanguage == SearchLanguagePreference.EN || searchLanguage == SearchLanguagePreference.BOTH
        val includePersian = searchLanguage == SearchLanguagePreference.FA || searchLanguage == SearchLanguagePreference.BOTH

        if (includeEnglish) {
            val baseEn = buildString {
                append(title)
                if (yearPart.isNotEmpty()) append(yearPart)
                if (seTag != null) append(" $seTag")
            }
            if (modifiers.includeDownload) {
                queries.add("$baseEn download")
            }
            if (modifiers.includeWatchOnline) {
                queries.add("$baseEn watch online")
            }
            if (modifiers.includeSubtitle) {
                queries.add("$baseEn Persian subtitle")
            }
            if (target.seasonNumber != null && target.episodeNumber != null) {
                queries.add("$title season ${target.seasonNumber} episode ${target.episodeNumber} download")
            }
            if (queries.isEmpty()) queries.add(baseEn)
        }

        if (includePersian) {
            val mediaPrefixFa = if (target.mediaType == MediaType.TV) "دانلود سریال" else "دانلود فیلم"
            val seFa = buildString {
                if (target.seasonNumber != null) append(" فصل ${target.seasonNumber}")
                if (target.episodeNumber != null) append(" قسمت ${target.episodeNumber}")
            }
            val subFa = when {
                modifiers.includeDubbed -> " دوبله فارسی"
                modifiers.includeSubtitle -> " زیرنویس فارسی"
                else -> ""
            }
            queries.add("$mediaPrefixFa $title$yearPart$seFa$subFa".trim())
            if (persianTitle != null) {
                queries.add("$mediaPrefixFa $persianTitle$yearPart$seFa$subFa".trim())
            }
            if (modifiers.includeWatchOnline) {
                queries.add("تماشای آنلاین $title$seFa".trim())
            }
        }

        return queries.toList().take(6)
    }

    fun buildSearchEngineUrl(engine: SearchEngineOption, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return when (engine) {
            SearchEngineOption.GOOGLE -> "https://www.google.com/search?q=$encoded"
            SearchEngineOption.BING -> "https://www.bing.com/search?q=$encoded"
            SearchEngineOption.DUCKDUCKGO -> "https://duckduckgo.com/?q=$encoded"
        }
    }

    fun isSafeHttpUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim().lowercase(Locale.US)
        return trimmed.startsWith("https://") || trimmed.startsWith("http://")
    }
}
