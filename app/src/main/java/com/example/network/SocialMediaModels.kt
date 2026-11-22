package com.example.network

enum class MediaFormatCategory {
    VIDEO_WITH_AUDIO,
    VIDEO_ONLY,
    AUDIO_ONLY,
}

enum class SocialPlatform {
    YOUTUBE,
    INSTAGRAM,
}

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
