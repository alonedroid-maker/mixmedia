import java.io.InputStreamReader
import java.util.Properties
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

val releaseInfoFile = rootProject.file("release-info.properties")
if (!releaseInfoFile.exists()) {
    throw GradleException("Missing release-info.properties at project root: ${releaseInfoFile.absolutePath}")
}
val releaseProps = Properties().apply {
    releaseInfoFile.inputStream().use { stream ->
        load(InputStreamReader(stream, Charsets.UTF_8))
    }
}
val releaseVersionName = releaseProps.getProperty("VERSION_NAME")?.trim().orEmpty()
    .ifEmpty { throw GradleException("VERSION_NAME in release-info.properties must not be empty.") }
val releaseVersionCode = releaseProps.getProperty("VERSION_CODE")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    ?: throw GradleException("VERSION_CODE in release-info.properties must be a positive integer.")

val generatedConfigDir = layout.buildDirectory.dir("generated/desktop-config/kotlin")

val generateDesktopBuildConfig by tasks.registering {
    val outDir = generatedConfigDir.get().asFile
    outputs.dir(outDir)
    inputs.file(releaseInfoFile)
    val envFile = rootProject.file(".env")
    if (envFile.exists()) inputs.file(envFile)
    inputs.property("versionName", releaseVersionName)
    inputs.property("versionCode", releaseVersionCode)
    inputs.property("envToken", System.getenv("TMDB_API_READ_ACCESS_TOKEN").orEmpty())
    inputs.property("envKey", System.getenv("TMDB_API_KEY").orEmpty())
    inputs.property("envProxy", System.getenv("TMDB_PROXY_BASE_URL").orEmpty())

    doLast {
        val dotEnvProps = Properties().apply {
            if (envFile.exists()) {
                envFile.inputStream().use { stream -> load(InputStreamReader(stream, Charsets.UTF_8)) }
            }
        }
        fun resolveSecret(name: String): String {
            val fromEnv = System.getenv(name)?.trim().orEmpty()
            val candidate = if (fromEnv.isNotEmpty()) fromEnv else dotEnvProps.getProperty(name)?.trim().orEmpty()
            return if (candidate.isEmpty() || candidate.startsWith("MY_TMDB_")) "" else candidate
        }
        fun xorMaskToHex(secret: String, maskBytes: ByteArray): String {
            if (secret.isEmpty()) return ""
            val raw = secret.toByteArray(Charsets.UTF_8)
            val out = StringBuilder(raw.size * 2)
            for (i in raw.indices) {
                val masked = (raw[i].toInt() xor maskBytes[i % maskBytes.size].toInt()) and 0xFF
                out.append("%02x".format(masked))
            }
            return out.toString()
        }

        val appId = "com.aistudio.mixmedia.vpxkqm"
        val tmdbMaskSeed = "mx|${releaseVersionCode}|${appId}|win".toByteArray(Charsets.UTF_8)
        val tmdbMaskBytes = ByteArray(32) { idx ->
            val b = tmdbMaskSeed[idx % tmdbMaskSeed.size].toInt()
            ((b * 31 + idx * 17 + 0x5A) and 0xFF).toByte()
        }
        val tmdbMaskHex = tmdbMaskBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val tokenBlob = xorMaskToHex(resolveSecret("TMDB_API_READ_ACCESS_TOKEN"), tmdbMaskBytes)
        val keyBlob = xorMaskToHex(resolveSecret("TMDB_API_KEY"), tmdbMaskBytes)
        val proxyRaw = resolveSecret("TMDB_PROXY_BASE_URL")
        val safeProxy = if (proxyRaw.startsWith("https://")) proxyRaw.replace("\"", "\\\"") else ""

        val pkgDir = outDir.resolve("com/example/desktop").apply { mkdirs() }
        pkgDir.resolve("DesktopBuildConfig.kt").writeText(
            """
            package com.example.desktop

            object DesktopBuildConfig {
                const val VERSION_NAME: String = "${releaseVersionName.replace("\"", "\\\"")}"
                const val VERSION_CODE: Int = $releaseVersionCode
                const val TMDB_PROXY_BASE_URL: String = "$safeProxy"
                const val TMDB_MASK_HEX: String = "$tmdbMaskHex"
                const val TMDB_TOKEN_BLOB: String = "$tokenBlob"
                const val TMDB_KEY_BLOB: String = "$keyBlob"
            }
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }
}

sourceSets {
    main {
        kotlin.srcDir(generatedConfigDir)
    }
}

tasks.matching { it.name == "compileKotlin" }.configureEach {
    dependsOn(generateDesktopBuildConfig)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

application {
    mainClass.set("com.example.desktop.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.org.json)
    implementation(libs.flatlaf)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

val packageDesktopLibs by tasks.registering(Sync::class) {
    dependsOn(tasks.jar)
    into(layout.buildDirectory.dir("desktop-dist/lib"))
    from(tasks.jar.get().archiveFile)
    from(configurations.runtimeClasspath)
}
