package com.example.web

import com.example.media.AppRuntimeConfig
import com.example.media.TmdbCredentialProvider
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch

fun main() {
    val envToken = System.getenv("TMDB_API_READ_ACCESS_TOKEN")?.trim().orEmpty()
        .takeUnless { it.startsWith("MY_TMDB_") }
        .orEmpty()
    val envKey = System.getenv("TMDB_API_KEY")?.trim().orEmpty()
        .takeUnless { it.startsWith("MY_TMDB_") }
        .orEmpty()
    val envProxy = System.getenv("TMDB_PROXY_BASE_URL")?.trim().orEmpty()

    if (envToken.isNotEmpty() || envKey.isNotEmpty()) {
        val maskBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val maskHex = maskBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val tokenBlob = if (envToken.isNotEmpty()) {
            TmdbCredentialProvider.encodeMaskedHex(envToken, maskBytes)
        } else {
            val buildToken = TmdbCredentialProvider(
                tokenBlobHex = WebBuildConfig.TMDB_TOKEN_BLOB,
                keyBlobHex = WebBuildConfig.TMDB_KEY_BLOB,
                maskHex = WebBuildConfig.TMDB_MASK_HEX,
            ).readAccessToken().orEmpty()
            TmdbCredentialProvider.encodeMaskedHex(buildToken, maskBytes)
        }
        val keyBlob = if (envKey.isNotEmpty()) {
            TmdbCredentialProvider.encodeMaskedHex(envKey, maskBytes)
        } else {
            val buildKey = TmdbCredentialProvider(
                tokenBlobHex = WebBuildConfig.TMDB_TOKEN_BLOB,
                keyBlobHex = WebBuildConfig.TMDB_KEY_BLOB,
                maskHex = WebBuildConfig.TMDB_MASK_HEX,
            ).apiKey().orEmpty()
            TmdbCredentialProvider.encodeMaskedHex(buildKey, maskBytes)
        }
        AppRuntimeConfig.configure(
            tmdbProxyBaseUrl = envProxy.ifBlank { WebBuildConfig.TMDB_PROXY_BASE_URL },
            tmdbMaskHex = maskHex,
            tmdbTokenBlob = tokenBlob,
            tmdbKeyBlob = keyBlob,
        )
    } else {
        AppRuntimeConfig.configure(
            tmdbProxyBaseUrl = envProxy.ifBlank { WebBuildConfig.TMDB_PROXY_BASE_URL },
            tmdbMaskHex = WebBuildConfig.TMDB_MASK_HEX,
            tmdbTokenBlob = WebBuildConfig.TMDB_TOKEN_BLOB,
            tmdbKeyBlob = WebBuildConfig.TMDB_KEY_BLOB,
        )
    }

    val host = System.getenv("HOST")?.trim()?.ifEmpty { "0.0.0.0" } ?: "0.0.0.0"
    val port = System.getenv("PORT")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080
    val corsOrigins = System.getenv("MEDIAX_CORS_ORIGINS")?.split(',')
        ?.map { it.trim() }
        ?.filter { it.startsWith("http://") || it.startsWith("https://") }
        ?.toSet()
        ?: emptySet()
    val allowAllCors = System.getenv("MEDIAX_CORS_ALLOW_ALL").equals("true", ignoreCase = true)

    val server = MediaXWebServer(
        host = host,
        port = port,
        allowedCorsOrigins = corsOrigins,
        allowWildcardCors = allowAllCors,
    )
    server.start()

    val latch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
        latch.countDown()
    })

    val actualPort = server.boundPort
    println("MediaX Web App v${WebBuildConfig.VERSION_NAME} running!")
    println("  Local:   http://localhost:$actualPort")
    findLanIps().forEach { ip ->
        println("  Network: http://$ip:$actualPort  (Open in iOS Safari -> Share -> Add to Home Screen)")
    }
    latch.await()
}

private fun findLanIps(): List<String> = runCatching {
    val ips = mutableListOf<String>()
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
    for (ni in interfaces) {
        if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
        for (addr in ni.inetAddresses) {
            if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                addr.hostAddress?.let { ips.add(it) }
            }
        }
    }
    ips
}.getOrDefault(emptyList())
