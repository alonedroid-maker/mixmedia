package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.download.DownloadEngine
import com.example.download.DownloadStatus
import com.example.download.DownloadTask
import com.example.download.QueueConcurrency
import com.example.network.CurlRedirectResolver
import com.example.network.CurlResolutionResult
import com.example.network.GitHubUpdateChecker
import com.example.network.GitHubUpdateResult
import com.example.network.SocialPlatform
import com.example.network.UnsupportedPlatformException
import com.example.network.VideoLinkExtractor
import com.example.network.YouTubeStreamOption
import com.example.security.DeveloperAccessVerifier
import java.io.IOException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppDestination {
    DISCOVER,
    RESOLVER,
    DOWNLOADS,
    SETTINGS,
}

enum class DownloadFilter {
    ALL,
    ACTIVE,
    QUEUED,
    COMPLETED,
}

sealed interface ExtractorUiState {
    data object Idle : ExtractorUiState
    data class Loading(
        val liveLog: String = "",
        val currentHop: Int = 1,
    ) : ExtractorUiState

    data class Success(val result: CurlResolutionResult) : ExtractorUiState
    data class Error(
        val errorType: ErrorType,
        val verboseLog: String = "",
    ) : ExtractorUiState
}

enum class ErrorType {
    EMPTY_URL,
    INVALID_URL,
    NO_VIDEO_FOUND,
    YOUTUBE_UNSUPPORTED,
    INSTAGRAM_UNSUPPORTED,
    HOST_UNREACHABLE,
    NETWORK_GENERIC,
    CANCELLED,
}

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Completed(val result: GitHubUpdateResult) : UpdateCheckState
}

data class LinkExtractorScreenState(
    val language: AppLanguage = AppLanguage.EN,
    val destination: AppDestination = AppDestination.RESOLVER,
    val downloadFilter: DownloadFilter = DownloadFilter.ALL,
    val inputText: String = "",
    val uiState: ExtractorUiState = ExtractorUiState.Idle,
    val isLogExpanded: Boolean = false,
    val isVlcInterceptorEnabled: Boolean = false,
    val isVlcInstalled: Boolean = false,
    val isDeveloperMode: Boolean = false,
    val capturedFromExternalIntent: Boolean = false,
    val detectedClipboardUrl: String? = null,
    val sessionHistory: List<CurlResolutionResult> = emptyList(),
    val updateCheckState: UpdateCheckState = UpdateCheckState.Idle,
    val pendingYouTubeResult: CurlResolutionResult? = null,
)

class LinkExtractorViewModel(
    private val resolver: CurlRedirectResolver = CurlRedirectResolver(),
    private val updateChecker: GitHubUpdateChecker = GitHubUpdateChecker(),
) : ViewModel() {

    private val _screenState = MutableStateFlow(LinkExtractorScreenState())
    val screenState: StateFlow<LinkExtractorScreenState> = _screenState.asStateFlow()

    private var activeJob: Job? = null
    private var lastIgnoredClipboardUrl: String? = null

    fun initializePreferences(
        language: AppLanguage,
        vlcEnabled: Boolean,
        vlcInstalled: Boolean,
        developerMode: Boolean,
    ) {
        _screenState.update {
            it.copy(
                language = language,
                isVlcInterceptorEnabled = vlcEnabled,
                isVlcInstalled = vlcInstalled,
                isDeveloperMode = developerMode,
            )
        }
    }

    fun onClipboardTextObserved(rawClipboardText: String?) {
        val candidate = rawClipboardText?.let { VideoLinkExtractor.extractInputUrl(it) } ?: return
        val currentState = _screenState.value
        val alreadyLoaded = candidate == currentState.inputText.trim() ||
            candidate == (currentState.uiState as? ExtractorUiState.Success)?.result?.directVideoUrl ||
            candidate == lastIgnoredClipboardUrl
        if (!alreadyLoaded) {
            _screenState.update { it.copy(detectedClipboardUrl = candidate) }
        }
    }

    fun acceptDetectedClipboardUrl() {
        val url = _screenState.value.detectedClipboardUrl ?: return
        _screenState.update {
            it.copy(
                destination = AppDestination.RESOLVER,
                inputText = url,
                detectedClipboardUrl = null,
                capturedFromExternalIntent = false,
            )
        }
        executeExtraction()
    }

    fun dismissDetectedClipboardUrl() {
        lastIgnoredClipboardUrl = _screenState.value.detectedClipboardUrl
        _screenState.update { it.copy(detectedClipboardUrl = null) }
    }

    fun setLanguage(language: AppLanguage) {
        _screenState.update { it.copy(language = language) }
    }

    fun navigateTo(destination: AppDestination) {
        _screenState.update { it.copy(destination = destination) }
    }

    fun setDownloadFilter(filter: DownloadFilter) {
        _screenState.update { it.copy(downloadFilter = filter) }
    }

    fun syncVlcStatus(enabled: Boolean, installed: Boolean) {
        _screenState.update {
            it.copy(
                isVlcInterceptorEnabled = enabled,
                isVlcInstalled = installed,
            )
        }
    }

    fun onVlcInterceptorToggled(enabled: Boolean) {
        _screenState.update { it.copy(isVlcInterceptorEnabled = enabled) }
    }

    fun tryUnlockDeveloperMode(password: String): Boolean {
        return if (DeveloperAccessVerifier.verify(password)) {
            _screenState.update { it.copy(isDeveloperMode = true) }
            true
        } else {
            false
        }
    }

    fun disableDeveloperMode() {
        _screenState.update {
            it.copy(
                isDeveloperMode = false,
                isLogExpanded = false,
            )
        }
    }

    fun checkForAppUpdates(currentVersion: String) {
        if (_screenState.value.updateCheckState is UpdateCheckState.Checking) return
        viewModelScope.launch {
            _screenState.update { it.copy(updateCheckState = UpdateCheckState.Checking) }
            val result = updateChecker.checkForUpdates(currentVersion)
            _screenState.update { it.copy(updateCheckState = UpdateCheckState.Completed(result)) }
        }
    }

    fun handleIncomingExternalUrl(url: String) {
        _screenState.update {
            it.copy(
                destination = AppDestination.RESOLVER,
                inputText = url,
                detectedClipboardUrl = null,
                capturedFromExternalIntent = true,
            )
        }
        executeExtraction()
    }

    fun onInputChanged(newText: String) {
        _screenState.update {
            it.copy(
                inputText = newText,
                capturedFromExternalIntent = false,
            )
        }
    }

    fun onClearInput() {
        activeJob?.cancel()
        _screenState.update {
            it.copy(
                inputText = "",
                capturedFromExternalIntent = false,
                uiState = ExtractorUiState.Idle,
                pendingYouTubeResult = null,
            )
        }
    }

    fun onToggleLogExpanded() {
        _screenState.update { it.copy(isLogExpanded = !it.isLogExpanded) }
    }

    fun onCancelExecution() {
        activeJob?.cancel()
        activeJob = null
        val currentLog = (_screenState.value.uiState as? ExtractorUiState.Loading)?.liveLog.orEmpty()
        _screenState.update {
            it.copy(
                uiState = if (currentLog.isBlank() || !it.isDeveloperMode) {
                    ExtractorUiState.Idle
                } else {
                    ExtractorUiState.Error(
                        errorType = ErrorType.CANCELLED,
                        verboseLog = currentLog,
                    )
                }
            )
        }
    }

    fun onSelectHistoryItem(item: CurlResolutionResult) {
        _screenState.update {
            it.copy(
                inputText = item.sourceUrl.ifBlank { it.inputText },
                capturedFromExternalIntent = false,
                uiState = ExtractorUiState.Success(item),
            )
        }
    }

    fun enqueueResolvedDownload(engine: DownloadEngine, result: CurlResolutionResult): DownloadTask? {
        val directUrl = result.directVideoUrl ?: return null
        return engine.enqueue(
            url = directUrl,
            sourcePlayUrl = result.sourceUrl.takeIf { it.isNotBlank() },
            suggestedFileName = result.filename,
            customUserAgent = result.customUserAgent,
        )
    }

    fun selectYouTubeOption(option: YouTubeStreamOption): CurlResolutionResult? {
        val baseResult = _screenState.value.pendingYouTubeResult
            ?: (_screenState.value.uiState as? ExtractorUiState.Success)?.result
            ?: return null

        val updated = baseResult.copy(
            directVideoUrl = option.url,
            filename = option.suggestedFileName,
            extension = option.container,
            fileSizeFormatted = option.fileSizeFormatted,
            customUserAgent = option.userAgent,
        )
        addSuccessResult(updated)
        _screenState.update { it.copy(pendingYouTubeResult = null) }
        return updated
    }

    fun openYouTubeFormatPicker() {
        val current = (_screenState.value.uiState as? ExtractorUiState.Success)?.result ?: return
        if (current.youtubeOptions.isNotEmpty()) {
            _screenState.update { it.copy(pendingYouTubeResult = current) }
        }
    }

    fun dismissYouTubeFormatPicker() {
        _screenState.update { it.copy(pendingYouTubeResult = null) }
    }

    fun executeExtraction(cookieHeader: String? = null) {
        val rawInput = _screenState.value.inputText.trim()
        if (rawInput.isEmpty()) {
            _screenState.update {
                it.copy(
                    uiState = ExtractorUiState.Error(ErrorType.EMPTY_URL),
                    pendingYouTubeResult = null,
                )
            }
            return
        }

        if (VideoLinkExtractor.looksLikeRawCurlTrace(rawInput)) {
            val parsed = resolver.resolveFromRawTrace(rawInput)
            if (parsed.directVideoUrl != null) {
                addSuccessResult(parsed)
            } else {
                _screenState.update {
                    it.copy(
                        uiState = ExtractorUiState.Error(
                            errorType = ErrorType.NO_VIDEO_FOUND,
                            verboseLog = parsed.verboseLog,
                        )
                    )
                }
            }
            return
        }

        val extractedUrl = VideoLinkExtractor.extractInputUrl(rawInput)
        if (extractedUrl == null) {
            _screenState.update {
                it.copy(
                    uiState = ExtractorUiState.Error(ErrorType.INVALID_URL),
                    pendingYouTubeResult = null,
                )
            }
            return
        }

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _screenState.update {
                it.copy(
                    pendingYouTubeResult = null,
                    uiState = ExtractorUiState.Loading(
                        liveLog = "$ curl -L -v \"$extractedUrl\"\n",
                        currentHop = 1,
                    ),
                )
            }

            try {
                val result = resolver.resolve(
                    inputUrl = extractedUrl,
                    cookieHeader = cookieHeader,
                ) { traceSoFar, hop ->
                    _screenState.update { current ->
                        if (current.uiState is ExtractorUiState.Loading) {
                            current.copy(
                                uiState = ExtractorUiState.Loading(
                                    liveLog = traceSoFar,
                                    currentHop = hop,
                                )
                            )
                        } else {
                            current
                        }
                    }
                }

                if (result.directVideoUrl != null) {
                    addSuccessResult(result)
                    if (result.youtubeOptions.isNotEmpty()) {
                        _screenState.update { it.copy(pendingYouTubeResult = result) }
                    }
                } else {
                    _screenState.update {
                        it.copy(
                            uiState = ExtractorUiState.Error(
                                errorType = ErrorType.NO_VIDEO_FOUND,
                                verboseLog = result.verboseLog,
                            ),
                            isLogExpanded = it.isDeveloperMode,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsupportedPlatformException) {
                val errType = when (e.platform) {
                    SocialPlatform.YOUTUBE -> ErrorType.YOUTUBE_UNSUPPORTED
                    SocialPlatform.INSTAGRAM -> ErrorType.INSTAGRAM_UNSUPPORTED
                }
                _screenState.update {
                    it.copy(
                        uiState = ExtractorUiState.Error(
                            errorType = errType,
                            verboseLog = e.verboseLog,
                        ),
                        isLogExpanded = it.isDeveloperMode,
                    )
                }
            } catch (_: UnknownHostException) {
                val currentLog = (_screenState.value.uiState as? ExtractorUiState.Loading)?.liveLog.orEmpty()
                _screenState.update {
                    it.copy(
                        uiState = ExtractorUiState.Error(
                            errorType = ErrorType.HOST_UNREACHABLE,
                            verboseLog = currentLog,
                        )
                    )
                }
            } catch (_: IOException) {
                val currentLog = (_screenState.value.uiState as? ExtractorUiState.Loading)?.liveLog.orEmpty()
                _screenState.update {
                    it.copy(
                        uiState = ExtractorUiState.Error(
                            errorType = ErrorType.NETWORK_GENERIC,
                            verboseLog = currentLog,
                        )
                    )
                }
            } catch (_: Exception) {
                val currentLog = (_screenState.value.uiState as? ExtractorUiState.Loading)?.liveLog.orEmpty()
                _screenState.update {
                    it.copy(
                        uiState = ExtractorUiState.Error(
                            errorType = ErrorType.NETWORK_GENERIC,
                            verboseLog = currentLog,
                        )
                    )
                }
            }
        }
    }

    private fun addSuccessResult(result: CurlResolutionResult) {
        _screenState.update { state ->
            val updatedHistory = (listOf(result) + state.sessionHistory.filterNot {
                it.directVideoUrl == result.directVideoUrl
            }).take(10)
            state.copy(
                uiState = ExtractorUiState.Success(result),
                sessionHistory = updatedHistory,
            )
        }
    }
}

fun filterDownloadTasks(tasks: List<DownloadTask>, filter: DownloadFilter): List<DownloadTask> {
    return when (filter) {
        DownloadFilter.ALL -> tasks
        DownloadFilter.ACTIVE -> tasks.filter { it.isActive || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }
        DownloadFilter.QUEUED -> tasks.filter { it.status == DownloadStatus.QUEUED }
        DownloadFilter.COMPLETED -> tasks.filter { it.status == DownloadStatus.COMPLETED }
    }
}
