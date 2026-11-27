package com.example.web

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

object UrlSecurityGuard {

    fun validateExternalHttpUrl(rawUrl: String): HttpUrl? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 4096) return null
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        val scheme = parsed.scheme.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        val host = parsed.host.trim().lowercase(Locale.US)
        if (host.isEmpty()) return null
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return null
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (_: UnknownHostException) {
            return null
        }
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            return null
        }
        return parsed
    }

    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        return when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == 0) return false                           // 0.0.0.0/8 ("this network")
        if (b0 == 10) return false                          // 10.0.0.0/8 (RFC 1918)
        if (b0 == 100 && b1 in 64..127) return false        // 100.64.0.0/10 (CGNAT)
        if (b0 == 127) return false                         // 127.0.0.0/8 (loopback)
        if (b0 == 169 && b1 == 254) return false            // 169.254.0.0/16 (link-local / cloud metadata)
        if (b0 == 172 && b1 in 16..31) return false         // 172.16.0.0/12 (RFC 1918)
        if (b0 == 192 && b1 == 0 && (bytes[2].toInt() and 0xFF) == 0) return false
        if (b0 == 192 && b1 == 168) return false            // 192.168.0.0/16 (RFC 1918)
        if (b0 == 198 && (b1 == 18 || b1 == 19)) return false // 198.18.0.0/15 (benchmark)
        if (b0 >= 224) return false                         // Multicast / reserved
        return true
    }

    private fun isPublicIpv6(address: Inet6Address): Boolean {
        val bytes = address.address
        if (bytes.size != 16) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        // Unique Local Addresses fc00::/7
        if ((first and 0xFE) == 0xFC) return false
        // Link-local fe80::/10
        if (first == 0xFE && (second and 0xC0) == 0x80) return false
        // Unspecified :: or ::1
        val allZeroPrefix = (0 until 15).all { bytes[it].toInt() == 0 }
        if (allZeroPrefix && (bytes[15].toInt() == 0 || bytes[15].toInt() == 1)) return false
        // IPv4-mapped ::ffff:0:0/96
        val isIpv4Mapped = (0 until 10).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xFF) == 0xFF &&
            (bytes[11].toInt() and 0xFF) == 0xFF
        if (isIpv4Mapped) {
            return isPublicIpv4(bytes.copyOfRange(12, 16))
        }
        return true
    }

    val safeDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val cleanHost = hostname.trim().lowercase(Locale.US)
            if (cleanHost == "localhost" || cleanHost.endsWith(".localhost") || cleanHost.endsWith(".local") || cleanHost.endsWith(".internal")) {
                throw UnknownHostException("Blocked non-public host: $cleanHost")
            }
            val resolved = Dns.SYSTEM.lookup(hostname)
            if (resolved.isEmpty() || resolved.any { !isPublicAddress(it) }) {
                throw UnknownHostException("Blocked non-public IP address for host: $cleanHost")
            }
            return resolved
        }
    }

    fun createSafeOkHttpClient(
        followRedirects: Boolean,
        connectTimeoutSeconds: Long = 16L,
        readTimeoutSeconds: Long = 20L,
    ): OkHttpClient = OkHttpClient.Builder()
        .dns(safeDns)
        .followRedirects(followRedirects)
        .followSslRedirects(followRedirects)
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(16, TimeUnit.SECONDS)
        .build()

    fun sanitizeDownloadFileName(raw: String?, fallback: String = "mediax-video.mp4"): String {
        val candidate = raw?.trim().orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\r\\n\\t\"<>|:*?\\x00-\\x1F]"), "_")
            .trim('.', ' ')
        return candidate.take(180).ifBlank { fallback }
    }
}
