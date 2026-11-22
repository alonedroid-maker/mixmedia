package com.example.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.media.FuzzyTitleMatcher
import com.example.network.VideoLinkExtractor

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InternalBrowserScreen(
    initialUrl: String,
    initialTitle: String,
    isBookmarked: Boolean,
    strings: AppStrings,
    onClose: () -> Unit,
    onOpenExternal: (String) -> Unit,
    onCopyUrl: (String) -> Unit,
    onShareUrl: (String) -> Unit,
    onToggleBookmark: (url: String, title: String) -> Unit,
    onSendToResolver: (String) -> Unit,
    onRequestDownloadConfirmation: (url: String, fileName: String, fileTypeLabel: String, pageUrl: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var addressBarText by remember(initialUrl) { mutableStateOf(initialUrl) }
    var pageTitle by remember(initialTitle) { mutableStateOf(initialTitle) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var sslErrorMessage by remember { mutableStateOf<String?>(null) }
    var pageErrorMessage by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val focusManager = LocalFocusManager.current

    fun navigateToAddress(raw: String) {
        val trimmed = raw.trim()
        val normalized = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        if (FuzzyTitleMatcher.isSafeHttpUrl(normalized)) {
            sslErrorMessage = null
            pageErrorMessage = null
            webViewRef?.loadUrl(normalized)
        }
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onClose()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Top Browser Header & Address Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("browser_close_button"),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = strings.discover.detailsBack)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pageTitle.ifBlank { currentUrl },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = currentUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    IconButton(
                        onClick = { onToggleBookmark(currentUrl, pageTitle) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            contentDescription = strings.discover.actionBookmarkPage,
                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    IconButton(
                        onClick = { onSendToResolver(currentUrl) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = strings.discover.actionSendToResolver)
                    }

                    IconButton(
                        onClick = { onOpenExternal(currentUrl) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = strings.discover.actionOpenExternalBrowser)
                    }
                }

                // Editable Address Bar + Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = { webViewRef?.takeIf { it.canGoBack() }?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(
                        onClick = { webViewRef?.takeIf { it.canGoForward() }?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(
                        onClick = {
                            sslErrorMessage = null
                            pageErrorMessage = null
                            webViewRef?.reload()
                        },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = addressBarText,
                            onValueChange = { addressBarText = it },
                            placeholder = { Text(strings.discover.browserAddressPlaceholder, maxLines = 1) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("browser_url_input"),
                            textStyle = MaterialTheme.typography.bodySmall,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    focusManager.clearFocus()
                                    navigateToAddress(addressBarText)
                                }
                            ),
                        )
                    }

                    IconButton(
                        onClick = { navigateToAddress(initialUrl) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(Icons.Rounded.Home, contentDescription = "Home")
                    }
                    IconButton(
                        onClick = { onCopyUrl(currentUrl) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = strings.actionCopyUrl)
                    }
                    IconButton(
                        onClick = { onShareUrl(currentUrl) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = strings.discover.detailsShare)
                    }
                }

                if (isLoading && loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (sslErrorMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Column {
                        Text(
                            text = strings.discover.browserSslErrorTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = strings.discover.browserSslErrorDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        if (pageErrorMessage != null && sslErrorMessage == null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    text = pageErrorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("internal_webview"),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadProgress = newProgress
                            isLoading = newProgress < 100
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) {
                                pageTitle = title
                            }
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val targetUrl = request?.url?.toString() ?: return false
                            // Block unsafe schemes such as file://, javascript:, content://
                            if (!FuzzyTitleMatcher.isSafeHttpUrl(targetUrl)) {
                                return true
                            }
                            if (VideoLinkExtractor.isDirectDownloadableUrl(targetUrl)) {
                                val fileName = VideoLinkExtractor.extractFilename(targetUrl)
                                val ext = VideoLinkExtractor.extractExtension(targetUrl) ?: "FILE"
                                onRequestDownloadConfirmation(targetUrl, fileName, ext, currentUrl)
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (url != null && FuzzyTitleMatcher.isSafeHttpUrl(url)) {
                                currentUrl = url
                                addressBarText = url
                            }
                            isLoading = true
                            sslErrorMessage = null
                            pageErrorMessage = null
                            canGoBack = view?.canGoBack() ?: false
                            canGoForward = view?.canGoForward() ?: false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null && FuzzyTitleMatcher.isSafeHttpUrl(url)) {
                                currentUrl = url
                                addressBarText = url
                            }
                            isLoading = false
                            canGoBack = view?.canGoBack() ?: false
                            canGoForward = view?.canGoForward() ?: false
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                pageErrorMessage = error?.description?.toString()
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?,
                        ) {
                            // Never bypass SSL verification silently
                            handler?.cancel()
                            sslErrorMessage = strings.discover.browserSslErrorDesc
                            isLoading = false
                        }
                    }

                    setDownloadListener(
                        DownloadListener { url, _, contentDisposition, mimetype, _ ->
                            if (!FuzzyTitleMatcher.isSafeHttpUrl(url)) return@DownloadListener
                            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                                .ifBlank { VideoLinkExtractor.extractFilename(url) }
                            val typeLabel = VideoLinkExtractor.extractExtension(guessedName)
                                ?: mimetype?.substringAfter('/')?.uppercase()
                                ?: "FILE"
                            onRequestDownloadConfirmation(url, guessedName, typeLabel, currentUrl)
                        }
                    )

                    if (FuzzyTitleMatcher.isSafeHttpUrl(initialUrl)) {
                        loadUrl(initialUrl)
                    }
                    webViewRef = this
                }
            },
        )
    }
}
