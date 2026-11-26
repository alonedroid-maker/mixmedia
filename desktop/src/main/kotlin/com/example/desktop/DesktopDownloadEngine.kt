package com.example.desktop

import com.example.download.DownloadPersistence
import com.example.download.DownloadSaveLocation
import com.example.download.DownloadSegment
import com.example.download.DownloadStatus
import com.example.download.DownloadTask
import com.example.download.EnqueueResult
import com.example.download.QueueConcurrency
import com.example.network.CurlRedirectResolver
import com.example.network.VideoLinkExtractor
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Pure-JVM multi-connection HTTP download manager for Windows 7..11+ Desktop.
 */
class DesktopDownloadEngine(
    private val persistenceStore: DesktopPersistence,
    private val resolver: CurlRedirectResolver = CurlRedirectResolver(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadPersistence = DownloadPersistence(persistenceStore.downloadsFile)
    private val stagingDir = File(persistenceStore.downloadsFile.parentFile, "downloads_part").apply { mkdirs() }

    private val dispatcher = Dispatcher().apply {
        maxRequests = 48
        maxRequestsPerHost = 24
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val initialSettings = persistenceStore.loadSettings()

    private val _tasks = MutableStateFlow(
        downloadPersistence.loadTasks().map { task ->
            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.RESOLVING) {
                task.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
            } else {
                task
            }
        }
    )
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _concurrencyLimit = MutableStateFlow(QueueConcurrency.fromCode(initialSettings.concurrencyLimitCode))
    val concurrencyLimit: StateFlow<QueueConcurrency> = _concurrencyLimit.asStateFlow()

    private val _connectionsPerDownload = MutableStateFlow(initialSettings.connectionsPerDownload.coerceIn(1, 8))
    val connectionsPerDownload: StateFlow<Int> = _connectionsPerDownload.asStateFlow()

    private val _saveLocation = MutableStateFlow(
        DownloadSaveLocation(
            treeUriString = initialSettings.downloadDirectoryPath,
            displayPath = initialSettings.downloadDirectoryPath,
        )
    )
    val saveLocation: StateFlow<DownloadSaveLocation> = _saveLocation.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun setCustomSaveDirectory(dir: File) {
        val resolved = dir.apply { mkdirs() }.absolutePath
        val updated = persistenceStore.loadSettings().copy(downloadDirectoryPath = resolved)
        persistenceStore.saveSettings(updated)
        _saveLocation.value = DownloadSaveLocation(treeUriString = resolved, displayPath = resolved)
    }

    fun resetSaveLocationToDefault() {
        setCustomSaveDirectory(DesktopPersistence.defaultDownloadsDir())
    }

    fun setConcurrencyLimit(limit: QueueConcurrency) {
        _concurrencyLimit.value = limit
        val updated = persistenceStore.loadSettings().copy(concurrencyLimitCode = limit.code)
        persistenceStore.saveSettings(updated)
        scheduleNextFromQueue()
    }

    fun setConnectionsPerDownload(threads: Int) {
        val clamped = threads.coerceIn(1, 8)
        _connectionsPerDownload.value = clamped
        val updated = persistenceStore.loadSettings().copy(connectionsPerDownload = clamped)
        persistenceStore.saveSettings(updated)
    }

    fun findExistingConflict(url: String, fileName: String): DownloadTask? {
        val sanitized = VideoLinkExtractor.sanitizeFileName(fileName)
        return _tasks.value.firstOrNull {
            it.url == url || it.fileName.equals(sanitized, ignoreCase = true)
        }
    }

    fun enqueueDownload(
        url: String,
        suggestedFileName: String,
        mimeType: String? = null,
        customUserAgent: String? = null,
        allowDuplicateRename: Boolean = false,
        replaceExistingId: String? = null,
    ): EnqueueResult {
        val parsed = url.trim().toHttpUrlOrNull()
        if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
            val failed = DownloadTask(
                id = UUID.randomUUID().toString(),
                url = url,
                fileName = VideoLinkExtractor.sanitizeFileName(suggestedFileName),
                status = DownloadStatus.FAILED,
                errorMessage = "Only HTTP/HTTPS URLs are supported.",
            )
            return EnqueueResult.Started(failed)
        }

        if (replaceExistingId != null) {
            removeTask(replaceExistingId)
        } else if (!allowDuplicateRename) {
            findExistingConflict(parsed.toString(), suggestedFileName)?.let {
                return EnqueueResult.Conflict(it)
            }
        }

        val targetDir = File(_saveLocation.value.displayPath).apply { mkdirs() }
        val uniqueName = makeUniqueFileName(targetDir, VideoLinkExtractor.sanitizeFileName(suggestedFileName))
        val partFile = File(stagingDir, "${UUID.randomUUID()}.part")
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = parsed.toString(),
            fileName = uniqueName,
            mimeType = mimeType ?: VideoLinkExtractor.guessMimeType(uniqueName),
            status = DownloadStatus.QUEUED,
            threadCount = _connectionsPerDownload.value,
            localPartPath = partFile.absolutePath,
            destinationLabel = targetDir.absolutePath,
            customUserAgent = customUserAgent,
        )
        _tasks.update { listOf(task) + it }
        persist()
        scheduleNextFromQueue()
        return EnqueueResult.Started(task)
    }

    fun pauseTask(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        updateTask(taskId) {
            if (it.status == DownloadStatus.COMPLETED) it
            else it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
        }
        scheduleNextFromQueue()
    }

    fun resumeTask(taskId: String) {
        updateTask(taskId) {
            if (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) {
                it.copy(status = DownloadStatus.QUEUED, errorMessage = null, speedBytesPerSec = 0L)
            } else it
        }
        scheduleNextFromQueue()
    }

    fun retryTask(taskId: String) = resumeTask(taskId)

    fun prioritizeTask(taskId: String) {
        val current = _tasks.value.firstOrNull { it.id == taskId } ?: return
        _tasks.update { list -> listOf(current) + list.filterNot { it.id == taskId } }
        if (current.status == DownloadStatus.PAUSED || current.status == DownloadStatus.FAILED) {
            updateTask(taskId) { it.copy(status = DownloadStatus.QUEUED, errorMessage = null) }
        }
        persist()
        scheduleNextFromQueue()
    }

    fun removeTask(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        val existing = _tasks.value.firstOrNull { it.id == taskId }
        existing?.localPartPath?.let { path -> runCatching { File(path).delete() } }
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        persist()
        scheduleNextFromQueue()
    }

    fun clearFinished() {
        _tasks.update { list ->
            list.filterNot { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.CANCELLED }
        }
        persist()
    }

    fun pauseAll() {
        _tasks.value.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
            .forEach { task ->
                activeJobs.remove(task.id)?.cancel()
                updateTask(task.id) { it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) }
            }
    }

    fun resumeAll() {
        _tasks.update { list ->
            list.map {
                if (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) {
                    it.copy(status = DownloadStatus.QUEUED, errorMessage = null)
                } else it
            }
        }
        persist()
        scheduleNextFromQueue()
    }

    private fun scheduleNextFromQueue() {
        val limit = _concurrencyLimit.value.maxActive
        val activeCount = _tasks.value.count {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.RESOLVING
        }
        val slots = (limit - activeCount).coerceAtLeast(0)
        if (slots == 0) return

        val nextQueued = _tasks.value.filter { it.status == DownloadStatus.QUEUED }.take(slots)
        for (task in nextQueued) {
            if (activeJobs.containsKey(task.id)) continue
            val job = scope.launch { runDownloadTask(task.id) }
            activeJobs[task.id] = job
        }
    }

    private suspend fun runDownloadTask(taskId: String) {
        try {
            val initial = _tasks.value.firstOrNull { it.id == taskId } ?: return
            updateTask(taskId) { it.copy(status = DownloadStatus.RESOLVING, errorMessage = null) }

            val targetUrl = initial.url
            val probe = probeDownloadHeaders(targetUrl, initial.customUserAgent)
            val totalBytes = probe.contentLength
            val supportsRange = probe.acceptsRange && totalBytes > 512 * 1024L
            val partFile = File(initial.localPartPath).apply { parentFile?.mkdirs() }
            val ua = initial.customUserAgent ?: CurlRedirectResolver.DESKTOP_UA

            val threads = if (supportsRange) initial.threadCount.coerceIn(1, 8) else 1
            val segments = if (initial.segments.size == threads && initial.totalBytes == totalBytes && totalBytes > 0L) {
                initial.segments
            } else if (supportsRange && threads > 1) {
                buildSegments(totalBytes, threads)
            } else {
                listOf(DownloadSegment(0, 0L, if (totalBytes > 0L) totalBytes - 1L else -1L, 0L))
            }

            if (totalBytes > 0L) {
                RandomAccessFile(partFile, "rw").use { raf ->
                    if (raf.length() != totalBytes) raf.setLength(totalBytes)
                }
            }

            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.DOWNLOADING,
                    totalBytes = totalBytes,
                    supportsRange = supportsRange,
                    segments = segments,
                    mimeType = probe.mimeType ?: it.mimeType,
                )
            }

            val totalDownloaded = AtomicLong(segments.sumOf { it.downloadedBytes })
            val segmentProgress = ConcurrentHashMap<Int, Long>()
            segments.forEach { segmentProgress[it.index] = it.downloadedBytes }

            coroutineScope {
                val ticker = launch {
                    var prevBytes = totalDownloaded.get()
                    var prevTs = System.currentTimeMillis()
                    while (isActive) {
                        delay(450)
                        val nowTs = System.currentTimeMillis()
                        val nowBytes = totalDownloaded.get()
                        val elapsedMs = (nowTs - prevTs).coerceAtLeast(1L)
                        val speed = ((nowBytes - prevBytes) * 1000L / elapsedMs).coerceAtLeast(0L)
                        prevBytes = nowBytes
                        prevTs = nowTs

                        val updatedSegs = segments.map { seg ->
                            seg.copy(downloadedBytes = segmentProgress[seg.index] ?: seg.downloadedBytes)
                        }
                        updateTask(taskId) { cur ->
                            if (cur.status != DownloadStatus.DOWNLOADING) cur
                            else cur.copy(
                                downloadedBytes = nowBytes,
                                speedBytesPerSec = speed,
                                segments = updatedSegs,
                            )
                        }
                    }
                }

                try {
                    if (supportsRange && segments.size > 1) {
                        segments.map { seg ->
                            async {
                                downloadRangeSegment(
                                    url = targetUrl,
                                    userAgent = ua,
                                    partFile = partFile,
                                    segment = seg,
                                    onDelta = { delta, segTotal ->
                                        totalDownloaded.addAndGet(delta)
                                        segmentProgress[seg.index] = segTotal
                                    },
                                )
                            }
                        }.awaitAll()
                    } else {
                        downloadSingleStream(
                            url = targetUrl,
                            userAgent = ua,
                            partFile = partFile,
                            resumeOffset = if (supportsRange) partFile.length() else 0L,
                            onDelta = { delta, total ->
                                totalDownloaded.set(total)
                                segmentProgress[0] = total
                            },
                        )
                    }
                } finally {
                    ticker.cancel()
                }
            }

            val finalTask = _tasks.value.firstOrNull { it.id == taskId } ?: return
            val destDir = File(_saveLocation.value.displayPath).apply { mkdirs() }
            val destFile = File(destDir, finalTask.fileName)
            if (!partFile.renameTo(destFile)) {
                partFile.copyTo(destFile, overwrite = true)
                partFile.delete()
            }

            val finalSize = destFile.length()
            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    downloadedBytes = finalSize,
                    totalBytes = if (it.totalBytes > 0L) it.totalBytes else finalSize,
                    speedBytesPerSec = 0L,
                    savedLocationLabel = destFile.absolutePath,
                    publicUriString = destFile.toURI().toString(),
                    localPartPath = destFile.absolutePath,
                    completedAt = System.currentTimeMillis(),
                )
            }
        } catch (_: CancellationException) {
            // Pause/remove already updated task state
        } catch (e: Exception) {
            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    speedBytesPerSec = 0L,
                    errorMessage = e.localizedMessage?.take(140) ?: e.javaClass.simpleName,
                )
            }
        } finally {
            activeJobs.remove(taskId)
            scheduleNextFromQueue()
        }
    }

    private fun downloadRangeSegment(
        url: String,
        userAgent: String,
        partFile: File,
        segment: DownloadSegment,
        onDelta: (delta: Long, segTotal: Long) -> Unit,
    ) {
        val currentStart = segment.startByte + segment.downloadedBytes
        if (segment.endByte >= 0L && currentStart > segment.endByte) return

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=$currentStart-${segment.endByte}")
            .build()

        client.newCall(req).execute().use { resp ->
            if (resp.code != 206 && resp.code != 200) {
                throw IllegalStateException("HTTP ${resp.code} on segment ${segment.index}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty body")
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(currentStart)
                body.byteStream().use { input ->
                    val buf = ByteArray(32 * 1024)
                    var segDone = segment.downloadedBytes
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        raf.write(buf, 0, read)
                        segDone += read
                        onDelta(read.toLong(), segDone)
                    }
                }
            }
        }
    }

    private fun downloadSingleStream(
        url: String,
        userAgent: String,
        partFile: File,
        resumeOffset: Long,
        onDelta: (delta: Long, total: Long) -> Unit,
    ) {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
        if (resumeOffset > 0L) {
            reqBuilder.header("Range", "bytes=$resumeOffset-")
        }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val append = resp.code == 206 && resumeOffset > 0L
            var total = if (append) resumeOffset else 0L
            val body = resp.body ?: throw IllegalStateException("Empty response body")
            FileOutputStream(partFile, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        total += read
                        onDelta(read.toLong(), total)
                    }
                }
            }
        }
    }

    private data class HeaderProbe(
        val contentLength: Long,
        val acceptsRange: Boolean,
        val mimeType: String?,
    )

    private fun probeDownloadHeaders(url: String, customUa: String?): HeaderProbe {
        val ua = customUa ?: CurlRedirectResolver.DESKTOP_UA
        runCatching {
            val headReq = Request.Builder().url(url).head().header("User-Agent", ua).build()
            client.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    val accepts = resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                    val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()
                    if (len > 0L || accepts) {
                        return HeaderProbe(len, accepts, mime)
                    }
                }
            }
        }
        return runCatching {
            val rangeReq = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(rangeReq).execute().use { resp ->
                val cr = resp.header("Content-Range")
                val total = cr?.substringAfterLast('/')?.toLongOrNull()
                    ?: resp.header("Content-Length")?.toLongOrNull()
                    ?: -1L
                val accepts = resp.code == 206 || cr != null
                val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()
                HeaderProbe(total, accepts, mime)
            }
        }.getOrElse { HeaderProbe(-1L, false, null) }
    }

    private fun buildSegments(totalBytes: Long, count: Int): List<DownloadSegment> {
        val chunk = totalBytes / count
        return (0 until count).map { idx ->
            val start = idx * chunk
            val end = if (idx == count - 1) totalBytes - 1L else ((idx + 1) * chunk - 1L)
            DownloadSegment(idx, start, end, 0L)
        }
    }

    private fun makeUniqueFileName(dir: File, desired: String): String {
        val clean = desired.ifBlank { "mediax_download.mp4" }
        var candidate = File(dir, clean)
        if (!candidate.exists()) return clean
        val dot = clean.lastIndexOf('.')
        val stem = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            val next = "$stem ($counter)$ext"
            candidate = File(dir, next)
            counter++
        }
        return candidate.name
    }

    private fun updateTask(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == taskId) transform(it) else it } }
        persist()
    }

    private fun persist() {
        downloadPersistence.saveTasks(_tasks.value)
    }
}
