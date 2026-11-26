package com.example.media

/**
 * Decodes build-time XOR-masked TMDB credentials injected via GitHub Repository Secrets
 * or AI Studio Secrets without keeping plaintext JWT/API-key literals in binary string tables.
 * Credentials stay strictly in memory for TMDB API request authorization and are never logged
 * or shown in UI.
 */
class TmdbCredentialProvider(
    private val tokenBlobHex: String = AppRuntimeConfig.tmdbTokenBlob,
    private val keyBlobHex: String = AppRuntimeConfig.tmdbKeyBlob,
    private val maskHex: String = AppRuntimeConfig.tmdbMaskHex,
) {
    private val decodedToken: String? by lazy {
        decodeMaskedHex(tokenBlobHex, maskHex)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("MY_TMDB_") }
    }

    private val decodedApiKey: String? by lazy {
        decodeMaskedHex(keyBlobHex, maskHex)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("MY_TMDB_") }
    }

    fun hasDirectCredentials(): Boolean = decodedToken != null || decodedApiKey != null

    fun authMode(): String = when {
        decodedToken != null -> "bearer"
        decodedApiKey != null -> "api_key"
        else -> "none"
    }

    fun readAccessToken(): String? = decodedToken

    fun apiKey(): String? = decodedApiKey

    companion object {
        fun encodeMaskedHex(plain: String, maskBytes: ByteArray): String {
            if (plain.isEmpty() || maskBytes.isEmpty()) return ""
            val raw = plain.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(raw.size * 2)
            for (i in raw.indices) {
                val b = (raw[i].toInt() xor maskBytes[i % maskBytes.size].toInt()) and 0xFF
                sb.append("%02x".format(b))
            }
            return sb.toString()
        }

        fun decodeMaskedHex(blobHex: String, maskHex: String): String? {
            val cleanBlob = blobHex.trim()
            val cleanMask = maskHex.trim()
            if (cleanBlob.isEmpty() || cleanMask.isEmpty()) return null
            if (cleanBlob.length % 2 != 0 || cleanMask.length % 2 != 0) return null

            val maskBytes = hexToBytes(cleanMask) ?: return null
            val blobBytes = hexToBytes(cleanBlob) ?: return null
            if (maskBytes.isEmpty() || blobBytes.isEmpty()) return null

            val out = ByteArray(blobBytes.size)
            for (i in blobBytes.indices) {
                out[i] = (blobBytes[i].toInt() xor maskBytes[i % maskBytes.size].toInt()).toByte()
            }
            return out.toString(Charsets.UTF_8)
        }

        private fun hexToBytes(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
