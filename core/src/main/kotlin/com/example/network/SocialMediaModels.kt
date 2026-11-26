package com.example.network

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

data class YouTubeMetadataInfo(
    val videoId: String,
    val canonicalUrl: String,
    val title: String,
    val channelTitle: String? = null,
    val channelUrl: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    val publishedAt: String? = null,
    val isAvailable: Boolean = true,
    val unavailableReason: String? = null,
    val hops: List<RedirectHop> = emptyList(),
    val verboseLog: String = "",
)

class UnsupportedPlatformException(
    val platform: SocialPlatform,
    val verboseLog: String = "",
) : Exception("${platform.name} links are currently not supported")

class YouTubeUnavailableException(
    val reason: String? = null,
    val verboseLog: String = "",
) : Exception(reason ?: "YouTube video is unavailable or private")

class XExtractionException(
    val error: XExtractionError,
    val verboseLog: String = "",
) : Exception("X extraction failed: ${error.name}")
