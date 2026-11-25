package com.example.network

enum class MediaFormatCategory {
    VIDEO_WITH_AUDIO,
    VIDEO_ONLY,
    AUDIO_ONLY,
}

enum class SocialPlatform {
    YOUTUBE,
    INSTAGRAM,
    X_TWITTER,
}

enum class XExtractionError {
    INVALID_URL,
    NO_VIDEO_IN_POST,
    UNAVAILABLE_OR_RESTRICTED,
    EXTRACTION_FAILED,
}

data class XVideoVariant(
    val url: String,
    val qualityLabel: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrateKbps: Int? = null,
    val container: String = "MP4",
    val contentLengthBytes: Long? = null,
    val fileSizeFormatted: String? = null,
    val suggestedFileName: String,
)

data class XExtractionResult(
    val tweetId: String,
    val canonicalUrl: String,
    val authorName: String? = null,
    val authorHandle: String? = null,
    val postText: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    val variants: List<XVideoVariant> = emptyList(),
    val error: XExtractionError? = null,
    val hops: List<RedirectHop> = emptyList(),
    val verboseLog: String = "",
)

data class YouTubeStreamOption(
    val itag: Int,
    val url: String,
    val qualityLabel: String,
    val container: String,
    val category: MediaFormatCategory,
    val bitrateKbps: Int? = null,
    val contentLengthBytes: Long? = null,
    val fileSizeFormatted: String? = null,
    val suggestedFileName: String,
    val userAgent: String? = null,
) {
    val isAudioOnly: Boolean
        get() = category == MediaFormatCategory.AUDIO_ONLY

    val hasAudio: Boolean
        get() = category != MediaFormatCategory.VIDEO_ONLY
}

class UnsupportedPlatformException(
    val platform: SocialPlatform,
    val verboseLog: String = "",
) : Exception("${platform.name} links are currently not supported")

class XExtractionException(
    val error: XExtractionError,
    val verboseLog: String = "",
) : Exception("X extraction failed: ${error.name}")
