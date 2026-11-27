import java.io.File
import java.io.InputStreamReader
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

abstract class GenerateDesktopBuildConfigTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseInfoFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val envFile: RegularFileProperty

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val envToken: Property<String>

    @get:Input
    abstract val envKey: Property<String>

    @get:Input
    abstract val envProxy: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dotEnvProps = Properties().apply {
            val f = envFile.asFile.orNull
            if (f != null && f.exists()) {
                f.inputStream().use { stream -> load(InputStreamReader(stream, Charsets.UTF_8)) }
            }
        }

        fun resolveSecret(envVal: String, keyName: String): String {
            val fromEnv = envVal.trim()
            val candidate = if (fromEnv.isNotEmpty()) fromEnv else dotEnvProps.getProperty(keyName)?.trim().orEmpty()
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

        val verName = versionName.get()
        val verCode = versionCode.get()
        val appId = "com.aistudio.mixmedia.vpxkqm"
        val tmdbMaskSeed = "mx|${verCode}|${appId}|win".toByteArray(Charsets.UTF_8)
        val tmdbMaskBytes = ByteArray(32) { idx ->
            val b = tmdbMaskSeed[idx % tmdbMaskSeed.size].toInt()
            ((b * 31 + idx * 17 + 0x5A) and 0xFF).toByte()
        }
        val tmdbMaskHex = tmdbMaskBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val tokenBlob = xorMaskToHex(resolveSecret(envToken.get(), "TMDB_API_READ_ACCESS_TOKEN"), tmdbMaskBytes)
        val keyBlob = xorMaskToHex(resolveSecret(envKey.get(), "TMDB_API_KEY"), tmdbMaskBytes)
        val proxyRaw = resolveSecret(envProxy.get(), "TMDB_PROXY_BASE_URL")
        val safeProxy = if (proxyRaw.startsWith("https://")) proxyRaw.replace("\"", "\\\"") else ""

        val outRoot = outputDir.get().asFile
        val pkgDir = outRoot.resolve("com/example/desktop").apply { mkdirs() }
        pkgDir.resolve("DesktopBuildConfig.kt").writeText(
            """
            package com.example.desktop

            object DesktopBuildConfig {
                const val VERSION_NAME: String = "${verName.replace("\"", "\\\"")}"
                const val VERSION_CODE: Int = $verCode
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

val releaseInfoFileObj = rootProject.file("release-info.properties")
if (!releaseInfoFileObj.exists()) {
    throw GradleException("Missing release-info.properties at project root: ${releaseInfoFileObj.absolutePath}")
}
val releaseProps = Properties().apply {
    releaseInfoFileObj.inputStream().use { stream ->
        load(InputStreamReader(stream, Charsets.UTF_8))
    }
}
val releaseVersionName = releaseProps.getProperty("VERSION_NAME")?.trim().orEmpty()
    .ifEmpty { throw GradleException("VERSION_NAME in release-info.properties must not be empty.") }
val releaseVersionCode = releaseProps.getProperty("VERSION_CODE")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    ?: throw GradleException("VERSION_CODE in release-info.properties must be a positive integer.")

val generatedConfigDir = layout.buildDirectory.dir("generated/desktop-config/kotlin")
val envFileObj = rootProject.file(".env")

val generateDesktopBuildConfig = tasks.register<GenerateDesktopBuildConfigTask>("generateDesktopBuildConfig") {
    releaseInfoFile.set(releaseInfoFileObj)
    if (envFileObj.exists()) {
        envFile.set(envFileObj)
    }
    versionName.set(releaseVersionName)
    versionCode.set(releaseVersionCode)
    envToken.set(providers.environmentVariable("TMDB_API_READ_ACCESS_TOKEN").orElse(""))
    envKey.set(providers.environmentVariable("TMDB_API_KEY").orElse(""))
    envProxy.set(providers.environmentVariable("TMDB_PROXY_BASE_URL").orElse(""))
    outputDir.set(generatedConfigDir)
}

sourceSets {
    main {
        kotlin.srcDir(generateDesktopBuildConfig.flatMap { it.outputDir })
    }
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
