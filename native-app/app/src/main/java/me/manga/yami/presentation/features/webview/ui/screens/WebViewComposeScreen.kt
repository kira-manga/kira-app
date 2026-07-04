package me.manga.yamiapk.presentation.features.webview.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewComposeScreen(
    api: String,
    initialUrl: String,
    modifier: Modifier = Modifier,
    onSaveHeaders: (Map<String, String>?, String) -> Unit,
    onClose: (Map<String, String>?, String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // State variables
    var savedHeaders by remember { mutableStateOf<Map<String, String>?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var webViewError by remember { mutableStateOf(false) }
    var recreationKey by remember { mutableStateOf(0) }

    // Safe WebView cleanup function
    fun cleanupWebView(webView: WebView?) {
        webView?.let { view ->
            try {
                view.clearFocus()
                view.stopLoading()
                view.clearCache(true)
                view.clearHistory()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    // Create WebView only once or recreate if crashed
    LaunchedEffect(webViewError, recreationKey) {
        withContext(Dispatchers.Main) {
            // Clean up previous WebView
            cleanupWebView(webViewInstance)
            webViewInstance = null

            // Add delay to ensure complete cleanup
            delay(100)

            val webView = WebView(context).apply {
                // Prevent focus issues
                isFocusable = false
                isFocusableInTouchMode = false

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true

                    // ✅ ANR FIX: Less aggressive caching to reduce network dependency
                    cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK // Changed from LOAD_DEFAULT

                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false

                    // Performance optimizations
                    setRenderPriority(WebSettings.RenderPriority.HIGH)
                    allowContentAccess = false
                    allowFileAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false

                    // Reduce memory usage and stability improvements
                    mediaPlaybackRequiresUserGesture = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    setGeolocationEnabled(false)
                    setSupportMultipleWindows(false)

                    // ✅ ANR FIX: Set network available once to reduce WebView's network monitoring
                    setNetworkAvailable(true)

                    // Additional stability settings
                    textZoom = 100
                    defaultTextEncodingName = "utf-8"
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                    setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        pageTitle = title.orEmpty()
                    }

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progress = newProgress.coerceIn(0, 100)
                        isLoading = newProgress < 100
                    }
                }

                webViewClient = object : WebViewClient() {
                    private val urlValidationCache = mutableMapOf<String, Boolean>()

                    private fun isAllowed(url: String): Boolean {
                        return urlValidationCache.getOrPut(url) {
                            try {
                                if (url.startsWith("about:") || url.startsWith("data:")) {
                                    return@getOrPut true
                                }

                                val uri = url.toUri()
                                if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) {
                                    return@getOrPut false
                                }

                                initialUrl.toUri().host?.let { host ->
                                    uri.host?.endsWith(host, true) == true
                                } ?: true
                            } catch (e: Exception) {
                                true
                            }
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()

                        if (url.startsWith("about:") || url.startsWith("data:")) {
                            return false
                        }

                        if (request.isForMainFrame) {
                            return if (isAllowed(url)) {
                                false
                            } else {
                                true
                            }
                        } else {
                            val scheme = request.url.scheme?.lowercase()
                            if (scheme == "javascript" || scheme == "file") {
                                return true
                            }
                            return false
                        }
                    }

                    // ✅ ANR FIX: Move cookie operations to background thread
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        try {
                            val initialHost = initialUrl.toUri().host
                            if (initialHost != null &&
                                request.url.toString().contains(initialHost, ignoreCase = true)) {

                                // ✅ ANR FIX: Move blocking cookie operation to background
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val cookieManager = CookieManager.getInstance()
                                        cookieManager.getCookie(initialUrl)?.let { cookie ->
                                            val headers = request.requestHeaders.toMutableMap().apply {
                                                put("Cookie", cookie)
                                            }
                                            // Update on main thread
                                            withContext(Dispatchers.Main) {
                                                savedHeaders = headers
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Ignore cookie errors
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore errors
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        isLoading = true
                        webViewError = false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isLoading = false
                        url?.let { newUrl ->
                            if (historyIndex == -1 || history.getOrNull(historyIndex) != newUrl) {
                                while (history.size >= 50) {
                                    history.removeAt(0)
                                    historyIndex = maxOf(0, historyIndex - 1)
                                }

                                if (historyIndex < history.lastIndex) {
                                    val toRemove = history.size - historyIndex - 1
                                    repeat(toRemove) { history.removeAt(history.lastIndex) }
                                }

                                history.add(newUrl)
                                historyIndex = history.lastIndex
                            }
                            currentUrl = newUrl
                            canGoBack = historyIndex > 0
                            canGoForward = historyIndex < history.lastIndex
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail
                    ): Boolean {
                        // Clean up and trigger recreation
                        cleanupWebView(view)
                        webViewError = true
                        recreationKey++

                        // Reset state
                        history.clear()
                        historyIndex = -1
                        savedHeaders = null
                        webViewInstance = null

                        return true
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        isLoading = false

                        if (errorCode == ERROR_HOST_LOOKUP ||
                            errorCode == ERROR_CONNECT ||
                            errorCode == ERROR_TIMEOUT) {
                            return
                        }
                    }
                }
            }

            webViewInstance = webView
            webViewError = false

            try {
                webView.loadUrl(initialUrl)
            } catch (e: Exception) {
                webViewError = true
                recreationKey++
            }
        }
    }

    // Handle lifecycle events
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            webViewInstance?.let { webView ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        try {
                            if (!webViewError) {
                                webView.onResume()
                                webView.resumeTimers()
                            }
                        } catch (e: Exception) {
                            // Ignore lifecycle errors
                        }
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        try {
                            webView.onPause()
                            webView.pauseTimers()
                        } catch (e: Exception) {
                            // Ignore lifecycle errors
                        }
                    }
                    Lifecycle.Event.ON_DESTROY -> {
                        cleanupWebView(webView)
                    }
                    else -> {}
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cleanupWebView(webViewInstance)
        }
    }

    // Handle Android back button
    BackHandler {
        webViewInstance?.let { webView ->
            if (historyIndex > 0) {
                historyIndex--
                history.getOrNull(historyIndex)?.let { url ->
                    try {
                        webView.loadUrl(url)
                    } catch (e: Exception) {
                        cleanupWebView(webView)
                        onClose(savedHeaders, api)
                    }
                }
            } else {
                cleanupWebView(webView)
                onClose(savedHeaders, api)
            }
        } ?: run {
            onClose(savedHeaders, api)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        cleanupWebView(webViewInstance)
                        onClose(savedHeaders, api)
                    }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = pageTitle.ifEmpty { "Loading..." },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentUrl,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            webViewInstance?.let { webView ->
                                if (historyIndex > 0) {
                                    historyIndex--
                                    history.getOrNull(historyIndex)?.let {
                                        try {
                                            webView.loadUrl(it)
                                        } catch (e: Exception) {
                                            // Ignore navigation errors
                                        }
                                    }
                                }
                            }
                        },
                        enabled = canGoBack && !isLoading
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(
                        onClick = {
                            webViewInstance?.let { webView ->
                                if (historyIndex < history.lastIndex) {
                                    historyIndex++
                                    history.getOrNull(historyIndex)?.let {
                                        try {
                                            webView.loadUrl(it)
                                        } catch (e: Exception) {
                                            // Ignore navigation errors
                                        }
                                    }
                                }
                            }
                        },
                        enabled = canGoForward && !isLoading
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(
                        onClick = {
                            webViewInstance?.let { webView ->
                                try {
                                    webView.reload()
                                } catch (e: Exception) {
                                    webViewError = true
                                    recreationKey++
                                }
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(
                        onClick = { onSaveHeaders(savedHeaders, api) },
                        enabled = savedHeaders != null && !isLoading
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save Headers")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                LinearProgressIndicator(
                    progress = if (progress > 0) progress / 100f else 0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
            }

            // Force recreation by conditionally showing AndroidView
            if (webViewInstance != null && !webViewError) {
                AndroidView(
                    factory = { context ->
                        webViewInstance!!
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        // Minimal update logic to prevent issues
                        try {
                            if (!webViewError) {
                                // Only perform safe operations if needed
                                if (view.parent == null) {
                                    // WebView is not attached, this shouldn't happen
                                    // but if it does, trigger recreation
                                    webViewError = true
                                    recreationKey++
                                }
                            }
                        } catch (e: Exception) {
                            webViewError = true
                            recreationKey++
                        }
                    }
                )
            } else {
                // Show loading state when WebView is being recreated
                Text(
                    text = if (webViewError) "WebView crashed. Recreating..." else "Loading WebView...",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}













//package me.manga.yamiapk.presentation.features.webview.ui.screens

//
//import android.annotation.SuppressLint
//import android.view.ViewGroup
//import android.webkit.CookieManager
//import android.webkit.RenderProcessGoneDetail
//import android.webkit.WebChromeClient
//import android.webkit.WebResourceRequest
//import android.webkit.WebResourceResponse
//import android.webkit.WebSettings
//import android.webkit.WebView
//import android.webkit.WebViewClient
//import androidx.activity.compose.BackHandler
//import androidx.compose.foundation.layout.Column
//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.foundation.layout.fillMaxWidth
//import androidx.compose.foundation.layout.height
//import androidx.compose.foundation.layout.padding
//import androidx.compose.material3.ExperimentalMaterial3Api
//import androidx.compose.material3.Icon
//import androidx.compose.material3.IconButton
//import androidx.compose.material3.LinearProgressIndicator
//import androidx.compose.material3.MaterialTheme
//import androidx.compose.material3.Scaffold
//import androidx.compose.material3.Text
//import androidx.compose.material3.TopAppBar
//import androidx.compose.material.icons.Icons
//import androidx.compose.material.icons.automirrored.filled.ArrowBack
//import androidx.compose.material.icons.automirrored.filled.ArrowForward
//import androidx.compose.material.icons.filled.Close
//import androidx.compose.material.icons.filled.Refresh
//import androidx.compose.material.icons.filled.Save
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.DisposableEffect
//import androidx.compose.runtime.LaunchedEffect
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateListOf
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.platform.LocalLifecycleOwner
//import androidx.compose.ui.text.style.TextOverflow
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.viewinterop.AndroidView
//import androidx.core.net.toUri
//import androidx.lifecycle.Lifecycle
//import androidx.lifecycle.LifecycleEventObserver
//import androidx.lifecycle.compose.LocalLifecycleOwner
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.withContext
//import me.manga.yamiapk.data.local.dao.SourcesDao
//import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
//import me.manga.yamiapk.sources_repositry.data.MangaSource
//
//@OptIn(ExperimentalMaterial3Api::class)
//@SuppressLint("SetJavaScriptEnabled")
//@Composable
//fun WebViewComposeScreen(
//    api: String,
//    initialUrl: String,
//    modifier: Modifier = Modifier,
//    onSaveHeaders: (Map<String, String>?, String) -> Unit,
//    onClose: (Map<String, String>?, String) -> Unit,
//
//) {
//    val context = LocalContext.current
//    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
//
//    // State variables
//    var savedHeaders by remember { mutableStateOf<Map<String, String>?>(null) }
//    var pageTitle by remember { mutableStateOf("") }
//    var currentUrl by remember { mutableStateOf(initialUrl) }
//    var canGoBack by remember { mutableStateOf(false) }
//    var canGoForward by remember { mutableStateOf(false) }
//    var progress by remember { mutableStateOf(0) }
//    var isLoading by remember { mutableStateOf(false) }
//    val history = remember { mutableStateListOf<String>() }
//    var historyIndex by remember { mutableStateOf(-1) }
//    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
//    var webViewError by remember { mutableStateOf(false) }
//    var recreationKey by remember { mutableStateOf(0) } // Key to force AndroidView recreation
//
//
//    // Safe WebView cleanup function
//    fun cleanupWebView(webView: WebView?) {
//        webView?.let { view ->
//            try {
//                view.clearFocus()
//                view.stopLoading()
//                view.clearCache(true)
//                view.clearHistory()
//                // Remove from parent if attached
//                (view.parent as? ViewGroup)?.removeView(view)
//                view.destroy()
//            } catch (e: Exception) {
//                // Ignore cleanup errors
//            }
//        }
//    }
//
//    // Create WebView only once or recreate if crashed
//    LaunchedEffect(webViewError, recreationKey) {
//        withContext(Dispatchers.Main) {
//            // Clean up previous WebView
//            cleanupWebView(webViewInstance)
//            webViewInstance = null
//
//            // Add delay to ensure complete cleanup
//            delay(100)
//
//            val webView = WebView(context).apply {
//                // Prevent focus issues
//                isFocusable = false
//                isFocusableInTouchMode = false
//
//                settings.apply {
//                    javaScriptEnabled = true
//                    domStorageEnabled = true
//                    databaseEnabled = true
//                    cacheMode = WebSettings.LOAD_DEFAULT
//                    loadWithOverviewMode = true
//                    useWideViewPort = true
//                    setSupportZoom(true)
//                    builtInZoomControls = true
//                    displayZoomControls = false
//
//                    // Performance optimizations
//                    setRenderPriority(WebSettings.RenderPriority.HIGH)
//                    allowContentAccess = false
//                    allowFileAccess = false
//                    allowFileAccessFromFileURLs = false
//                    allowUniversalAccessFromFileURLs = false
//
//                    // Reduce memory usage and stability improvements
//                    mediaPlaybackRequiresUserGesture = true
//                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
//                    setGeolocationEnabled(false)
//                    setSupportMultipleWindows(false)
//
//                    // Additional stability settings
//                    textZoom = 100
//                    defaultTextEncodingName = "utf-8"
//                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
//                    setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
//                }
//
//                webChromeClient = object : WebChromeClient() {
//                    override fun onReceivedTitle(view: WebView?, title: String?) {
//                        pageTitle = title.orEmpty()
//                    }
//
//                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
//                        progress = newProgress.coerceIn(0, 100)
//                        isLoading = newProgress < 100
//                    }
//                }
//
//                webViewClient = object : WebViewClient() {
//                    private val urlValidationCache = mutableMapOf<String, Boolean>()
//
//                    private fun isAllowed(url: String): Boolean {
//                        return urlValidationCache.getOrPut(url) {
//                            try {
//                                if (url.startsWith("about:") || url.startsWith("data:")) {
//                                    return@getOrPut true
//                                }
//
//                                val uri = url.toUri()
//                                if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) {
//                                    return@getOrPut false
//                                }
//
//                                initialUrl.toUri().host?.let { host ->
//                                    uri.host?.endsWith(host, true) == true
//                                } ?: true
//                            } catch (e: Exception) {
//                                true
//                            }
//                        }
//                    }
//
//                    override fun shouldOverrideUrlLoading(
//                        view: WebView,
//                        request: WebResourceRequest
//                    ): Boolean {
//                        val url = request.url.toString()
//
//                        if (url.startsWith("about:") || url.startsWith("data:")) {
//                            return false
//                        }
//
//                        if (request.isForMainFrame) {
//                            return if (isAllowed(url)) {
//                                false
//                            } else {
//                                true
//                            }
//                        } else {
//                            val scheme = request.url.scheme?.lowercase()
//                            if (scheme == "javascript" || scheme == "file") {
//                                return true
//                            }
//                            return false
//                        }
//                    }
//
//                    override fun shouldInterceptRequest(
//                        view: WebView,
//                        request: WebResourceRequest
//                    ): WebResourceResponse? {
//                        try {
//                            val initialHost = initialUrl.toUri().host
//                            if (initialHost != null &&
//                                request.url.toString().contains(initialHost, ignoreCase = true)) {
//
//                                val cookieManager = CookieManager.getInstance()
//                                cookieManager.getCookie(initialUrl)?.let { cookie ->
//                                    val headers = request.requestHeaders.toMutableMap().apply {
//                                        put("Cookie", cookie)
//                                    }
//                                    savedHeaders = headers
//                                }
//                            }
//                        } catch (e: Exception) {
//                        }
//                        return super.shouldInterceptRequest(view, request)
//                    }
//
//                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
//                        super.onPageStarted(view, url, favicon)
//                        isLoading = true
//                        webViewError = false
//                    }
//
//                    override fun onPageFinished(view: WebView?, url: String?) {
//                        super.onPageFinished(view, url)
//                        isLoading = false
//                        url?.let { newUrl ->
//                            if (historyIndex == -1 || history.getOrNull(historyIndex) != newUrl) {
//                                while (history.size >= 50) {
//                                    history.removeAt(0)
//                                    historyIndex = maxOf(0, historyIndex - 1)
//                                }
//
//                                if (historyIndex < history.lastIndex) {
//                                    val toRemove = history.size - historyIndex - 1
//                                    repeat(toRemove) { history.removeAt(history.lastIndex) }
//                                }
//
//                                history.add(newUrl)
//                                historyIndex = history.lastIndex
//                            }
//                            currentUrl = newUrl
//                            canGoBack = historyIndex > 0
//                            canGoForward = historyIndex < history.lastIndex
//                        }
//                    }
//
//                    override fun onRenderProcessGone(
//                        view: WebView,
//                        detail: RenderProcessGoneDetail
//                    ): Boolean {
//                        // Clean up and trigger recreation
//                        cleanupWebView(view)
//                        webViewError = true
//                        recreationKey++ // Force AndroidView recreation
//
//                        // Reset state
//                        history.clear()
//                        historyIndex = -1
//                        savedHeaders = null
//                        webViewInstance = null
//
//                        return true
//                    }
//
//                    override fun onReceivedError(
//                        view: WebView?,
//                        errorCode: Int,
//                        description: String?,
//                        failingUrl: String?
//                    ) {
//                        super.onReceivedError(view, errorCode, description, failingUrl)
//                        isLoading = false
//
//                        if (errorCode == ERROR_HOST_LOOKUP ||
//                            errorCode == ERROR_CONNECT ||
//                            errorCode == ERROR_TIMEOUT) {
//                            return
//                        }
//                    }
//                }
//            }
//
//            webViewInstance = webView
//            webViewError = false
//
//            try {
//                webView.loadUrl(initialUrl)
//            } catch (e: Exception) {
//                webViewError = true
//                recreationKey++
//            }
//        }
//    }
//
//    // Handle lifecycle events
//    DisposableEffect(lifecycleOwner) {
//        val observer = LifecycleEventObserver { _, event ->
//            webViewInstance?.let { webView ->
//                when (event) {
//                    Lifecycle.Event.ON_RESUME -> {
//                        try {
//                            if (!webViewError) {
//                                webView.onResume()
//                                webView.resumeTimers()
//                            }
//                        } catch (e: Exception) {
//                        }
//                    }
//                    Lifecycle.Event.ON_PAUSE -> {
//                        try {
//                            webView.onPause()
//                            webView.pauseTimers()
//                        } catch (e: Exception) {
//                        }
//                    }
//                    Lifecycle.Event.ON_DESTROY -> {
//                        cleanupWebView(webView)
//                    }
//                    else -> {}
//                }
//            }
//        }
//
//        lifecycleOwner.lifecycle.addObserver(observer)
//        onDispose {
//            lifecycleOwner.lifecycle.removeObserver(observer)
//            cleanupWebView(webViewInstance)
//        }
//    }
//
//    // Handle Android back button
//    BackHandler {
//        webViewInstance?.let { webView ->
//            if (historyIndex > 0) {
//                historyIndex--
//                history.getOrNull(historyIndex)?.let { url ->
//                    try {
//                        webView.loadUrl(url)
//                    } catch (e: Exception) {
//                        cleanupWebView(webView)
//                        onClose(savedHeaders, api)
//                    }
//                }
//            } else {
//                cleanupWebView(webView)
//                onClose(savedHeaders, api)
//            }
//        } ?: run {
//            onClose(savedHeaders, api)
//        }
//    }
//
//    Scaffold(
//        topBar = {
//            TopAppBar(
//                navigationIcon = {
//                    IconButton(onClick = {
//                        cleanupWebView(webViewInstance)
//                        onClose(savedHeaders, api)
//                    }) {
//                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
//                    }
//                },
//                title = {
//                    Column {
//                        Text(
//                            text = pageTitle.ifEmpty { "Loading..." },
//                            maxLines = 1,
//                            overflow = TextOverflow.Ellipsis
//                        )
//                        Text(
//                            text = currentUrl,
//                            style = MaterialTheme.typography.bodySmall,
//                            maxLines = 1,
//                            overflow = TextOverflow.Ellipsis
//                        )
//                    }
//                },
//                actions = {
//                    IconButton(
//                        onClick = {
//                            webViewInstance?.let { webView ->
//                                if (historyIndex > 0) {
//                                    historyIndex--
//                                    history.getOrNull(historyIndex)?.let {
//                                        try {
//                                            webView.loadUrl(it)
//                                        } catch (e: Exception) {
//                                        }
//                                    }
//                                }
//                            }
//                        },
//                        enabled = canGoBack && !isLoading
//                    ) {
//                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
//                    }
//                    IconButton(
//                        onClick = {
//                            webViewInstance?.let { webView ->
//                                if (historyIndex < history.lastIndex) {
//                                    historyIndex++
//                                    history.getOrNull(historyIndex)?.let {
//                                        try {
//                                            webView.loadUrl(it)
//                                        } catch (e: Exception) {
//                                        }
//                                    }
//                                }
//                            }
//                        },
//                        enabled = canGoForward && !isLoading
//                    ) {
//                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
//                    }
//                    IconButton(
//                        onClick = {
//                            webViewInstance?.let { webView ->
//                                try {
//                                    webView.reload()
//                                } catch (e: Exception) {
//                                    webViewError = true
//                                    recreationKey++
//                                }
//                            }
//                        },
//                        enabled = !isLoading
//                    ) {
//                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
//                    }
//                    IconButton(
//                        onClick = { onSaveHeaders(savedHeaders, api) },
//                        enabled = savedHeaders != null && !isLoading
//                    ) {
//                        Icon(Icons.Default.Save, contentDescription = "Save Headers")
//                    }
//                }
//            )
//        },
//        modifier = modifier
//    ) { padding ->
//        Column(
//            Modifier
//                .fillMaxSize()
//                .padding(padding)
//        ) {
//            if (isLoading) {
//                LinearProgressIndicator(
//                    progress = if (progress > 0) progress / 100f else 0f,
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .height(4.dp)
//                )
//            }
//
//            // Force recreation by conditionally showing AndroidView
//            if (webViewInstance != null && !webViewError) {
//                AndroidView(
//                    factory = { context ->
//                        webViewInstance!!
//                    },
//                    modifier = Modifier.fillMaxSize(),
//                    update = { view ->
//                        // Minimal update logic to prevent issues
//                        try {
//                            if (!webViewError) {
//                                // Only perform safe operations if needed
//                                if (view.parent == null) {
//                                    // WebView is not attached, this shouldn't happen
//                                    // but if it does, trigger recreation
//                                    webViewError = true
//                                    recreationKey++
//                                }
//                            }
//                        } catch (e: Exception) {
//                            webViewError = true
//                            recreationKey++
//                        }
//                    }
//                )
//            } else {
//                // Show loading state when WebView is being recreated
//                Text(
//                    text = if (webViewError) "WebView crashed. Recreating..." else "Loading WebView...",
//                    modifier = Modifier.padding(16.dp)
//                )
//            }
//        }
//    }
//}