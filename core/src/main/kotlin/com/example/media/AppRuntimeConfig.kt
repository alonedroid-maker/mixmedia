package com.example.media

/**
 * Cross-platform runtime configuration holder for TMDB proxy URL and masked credentials.
 * Lazily reads generated BuildConfig on Android or DesktopBuildConfig on Windows/JVM,
 * and also supports explicit initialization via [configure].
 */
object AppRuntimeConfig {
    @Volatile
    private var explicitProxyBaseUrl: String? = null

    @Volatile
    private var explicitMaskHex: String? = null

    @Volatile
    private var explicitTokenBlob: String? = null

    @Volatile
    private var explicitKeyBlob: String? = null

    @Volatile
    private var loadedFromGenerated = false

    @Volatile
    private var generatedProxyBaseUrl: String = ""

    @Volatile
    private var generatedMaskHex: String = ""

    @Volatile
    private var generatedTokenBlob: String = ""

    @Volatile
    private var generatedKeyBlob: String = ""

    val tmdbProxyBaseUrl: String
        get() {
            ensureGeneratedLoaded()
            return explicitProxyBaseUrl ?: generatedProxyBaseUrl
        }

    val tmdbMaskHex: String
        get() {
            ensureGeneratedLoaded()
            return explicitMaskHex ?: generatedMaskHex
        }

    val tmdbTokenBlob: String
        get() {
            ensureGeneratedLoaded()
            return explicitTokenBlob ?: generatedTokenBlob
        }

    val tmdbKeyBlob: String
        get() {
            ensureGeneratedLoaded()
            return explicitKeyBlob ?: generatedKeyBlob
        }

    fun configure(
        tmdbProxyBaseUrl: String = "",
        tmdbMaskHex: String = "",
        tmdbTokenBlob: String = "",
        tmdbKeyBlob: String = "",
    ) {
        this.explicitProxyBaseUrl = tmdbProxyBaseUrl
        this.explicitMaskHex = tmdbMaskHex
        this.explicitTokenBlob = tmdbTokenBlob
        this.explicitKeyBlob = tmdbKeyBlob
        this.loadedFromGenerated = true
    }

    private fun ensureGeneratedLoaded() {
        if (loadedFromGenerated) return
        synchronized(this) {
            if (loadedFromGenerated) return
            val candidates = listOf(
                "com.example.BuildConfig",
                "com.example.desktop.DesktopBuildConfig",
            )
            for (className in candidates) {
                try {
                    val clazz = Class.forName(className)
                    generatedProxyBaseUrl = readStaticString(clazz, "TMDB_PROXY_BASE_URL")
                    generatedMaskHex = readStaticString(clazz, "TMDB_MASK_HEX")
                    generatedTokenBlob = readStaticString(clazz, "TMDB_TOKEN_BLOB")
                    generatedKeyBlob = readStaticString(clazz, "TMDB_KEY_BLOB")
                    break
                } catch (_: Throwable) {
                    // Try next platform config class
                }
            }
            loadedFromGenerated = true
        }
    }

    private fun readStaticString(clazz: Class<*>, fieldName: String): String =
        try {
            (clazz.getField(fieldName).get(null) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
}
