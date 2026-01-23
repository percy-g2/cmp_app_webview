package com.androdevlinux.webview.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLDecoder
import android.util.Base64
import android.webkit.URLUtil
import android.graphics.BitmapFactory
import java.io.FileOutputStream
import java.io.File
import androidx.core.content.FileProvider

/**
 * JavaScript interface bridge for Android
 */
class JavaScriptBridge(
    private val handler: (String) -> Unit
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        handler(message)
    }
}

/**
 * Android implementation of WebViewController
 */
actual class WebViewController {
    private var webView: WebView? = null
    private var stateCallback: ((WebViewState) -> Unit)? = null
    private val javascriptInterfaces = mutableMapOf<String, (String) -> Unit>()
    private val pendingInterfaces = mutableMapOf<String, (String) -> Unit>()
    private var pendingUrl: String? = null
    private var context: Context? = null
    private var lastDownloadTime: Long = 0
    private var lastCopyTime: Long = 0
    private val DEBOUNCE_MS: Long = 1000 // 1 second debounce
    private var handlerInjected: Boolean = false
    
    fun initialize(webView: WebView, stateCallback: ((WebViewState) -> Unit)?, initialUrl: String? = null, context: Context? = null) {
        this.webView = webView
        this.stateCallback = stateCallback
        this.context = context
        setupWebView(webView)
        
        // Register any pending interfaces
        pendingInterfaces.forEach { (name, handler) ->
            addJavaScriptInterface(name, handler)
        }
        pendingInterfaces.clear()
        
        // Setup TradingView-specific handlers
        setupTradingViewHandlers()
        
        // Load initial URL if provided, or pending URL
        val urlToLoad = initialUrl ?: pendingUrl
        urlToLoad?.let { url ->
            // Use postDelayed to ensure WebView is fully initialized and attached
            webView.postDelayed({
                try {
                    if (webView.windowToken != null) {
                        webView.loadUrl(url)
                    } else {
                        // If not attached yet, try again
                        webView.post {
                            webView.loadUrl(url)
                        }
                    }
                } catch (e: Exception) {
                    updateState(WebViewLoadingState.ERROR, null, e.message)
                }
            }, 100)
            pendingUrl = null
        }
        
        // Trigger initial state update
        updateState(WebViewLoadingState.IDLE, null)
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true
        }
        
        // Enable cookie support
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                updateState(WebViewLoadingState.LOADING, url)
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateState(WebViewLoadingState.LOADED, url)
                
                // Inject JavaScript to intercept TradingView chart snapshot download/copy actions
                // Delay to ensure TradingView widget is loaded
                view?.postDelayed({
                    injectTradingViewSnapshotHandler(view)
                }, 1000)
            }
            
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val errorMsg = error?.description?.toString() ?: "Unknown error"
                updateState(WebViewLoadingState.ERROR, view?.url, errorMsg)
            }
            
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Handle download URLs and blob/data URLs
                val url = request?.url?.toString() ?: return false
                if (isDownloadableFile(url)) {
                    handleDownload(url)
                    return true
                } else if (url.startsWith("blob:") || url.startsWith("data:")) {
                    // Handle blob/data URLs - these are typically TradingView chart snapshots
                    handleBlobOrDataUrlDownload(url, null, null)
                    return true
                }
                // Allow all other URLs to load in WebView
                return false
            }
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Handle file chooser if needed
                return super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
            }
        }
        
        // Enable long-press context menu for images
        webView.setOnLongClickListener { view ->
            val result = (view as WebView).hitTestResult
            when (result.type) {
                WebView.HitTestResult.IMAGE_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val imageUrl = result.extra
                    if (imageUrl != null) {
                        handleImageLongPress(imageUrl)
                    }
                    true
                }
                else -> false
            }
        }
    }
    
    private fun isDownloadableFile(url: String): Boolean {
        val downloadableExtensions = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg",
            ".pdf", ".zip", ".rar", ".doc", ".docx", ".xls", ".xlsx"
        )
        return downloadableExtensions.any { url.lowercase().contains(it) }
    }
    
    private fun handleDownload(url: String) {
        val ctx = context ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            ctx.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: try to download via DownloadManager
            downloadFile(ctx, url)
        }
    }
    
    private fun downloadFile(context: Context, url: String) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val request = android.app.DownloadManager.Request(Uri.parse(url))
            
            // Get filename from URL
            val fileName = url.substringAfterLast("/").let {
                if (it.contains("?")) it.substringBefore("?") else it
            }.let {
                URLDecoder.decode(it, "UTF-8")
            }
            
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setTitle("Downloading $fileName")
            
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun handleImageLongPress(imageUrl: String) {
        val ctx = context ?: return
        
        // Create a menu with options: Download and Copy
        val options = arrayOf("Download Image", "Copy Image URL")
        
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Image Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> downloadImage(ctx, imageUrl)
                    1 -> copyImageUrl(ctx, imageUrl)
                }
            }
            .show()
    }
    
    private fun downloadImage(context: Context, imageUrl: String) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val request = android.app.DownloadManager.Request(Uri.parse(imageUrl))
            
            // Get filename from URL
            val fileName = imageUrl.substringAfterLast("/").let {
                if (it.contains("?")) it.substringBefore("?") else it
            }.let {
                URLDecoder.decode(it, "UTF-8")
            }.let {
                if (it.isEmpty() || !it.contains(".")) {
                    "image_${System.currentTimeMillis()}.jpg"
                } else {
                    it
                }
            }
            
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setTitle("Downloading $fileName")
            request.setMimeType("image/*")
            
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun copyImageUrl(context: Context, imageUrl: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Image URL", imageUrl)
        clipboard.setPrimaryClip(clip)
    }
    
    private fun injectTradingViewSnapshotHandler(webView: WebView?) {
        val ctx = context ?: return
        
        // Prevent duplicate injection
        if (handlerInjected) {
            return
        }
        handlerInjected = true
        
        val script = """
            (function() {
                // Prevent duplicate injection
                if (window.__tradingViewHandlerInjected) {
                    console.log('TradingView handler already injected, skipping');
                    return;
                }
                window.__tradingViewHandlerInjected = true;
                console.log('TradingView snapshot handler injected');
                
                // Track recent actions to prevent duplicates
                var lastDownloadTime = 0;
                var lastCopyTime = 0;
                var downloadInProgress = false;
                var copyInProgress = false;
                var DEBOUNCE_MS = 1000; // 1 second debounce
                
                // Intercept all clicks - TradingView might use buttons or divs
                document.addEventListener('click', function(e) {
                    var target = e.target;
                    var text = (target.textContent || target.innerText || '').toLowerCase().trim();
                    
                    // Check for download/copy button text
                    var isDownloadButton = text.includes('download') || text.includes('download image');
                    var isCopyButton = text.includes('copy') || text.includes('copy image');
                    
                    // Walk up the DOM to find parent elements
                    var current = target;
                    var linkElement = null;
                    var buttonElement = null;
                    
                    while (current && current !== document.body) {
                        if (current.tagName === 'A' && current.hasAttribute('download')) {
                            linkElement = current;
                            break;
                        }
                        if (current.tagName === 'BUTTON' || current.getAttribute('role') === 'button') {
                            buttonElement = current;
                        }
                        current = current.parentElement;
                    }
                    
                    // Handle download link
                    if (linkElement) {
                        var href = linkElement.getAttribute('href');
                        var downloadName = linkElement.getAttribute('download') || 'chart_snapshot.png';
                        
                        if (href && (href.startsWith('blob:') || href.startsWith('data:'))) {
                            var now = Date.now();
                            if (downloadInProgress || (now - lastDownloadTime) < DEBOUNCE_MS) {
                                console.log('Download already in progress or too soon, skipping');
                                return false;
                            }
                            
                            e.preventDefault();
                            e.stopPropagation();
                            e.stopImmediatePropagation();
                            console.log('Intercepted download link:', href);
                            
                            downloadInProgress = true;
                            lastDownloadTime = now;
                            
                            if (window.AndroidDownloadHandler) {
                                if (href.startsWith('data:')) {
                                    window.AndroidDownloadHandler.handleDownload(href, downloadName);
                                } else {
                                    // Convert blob to data URL
                                    fetch(href).then(function(response) {
                                        return response.blob();
                                    }).then(function(blob) {
                                        var reader = new FileReader();
                                        reader.onloadend = function() {
                                            window.AndroidDownloadHandler.handleDownload(reader.result, downloadName);
                                            setTimeout(function() { downloadInProgress = false; }, DEBOUNCE_MS);
                                        };
                                        reader.readAsDataURL(blob);
                                    }).catch(function(err) {
                                        console.error('Error converting blob:', err);
                                        downloadInProgress = false;
                                    });
                                }
                            }
                            return false;
                        }
                    }
                    
                    // Handle download button click
                    if (isDownloadButton && window.AndroidDownloadHandler && !downloadInProgress) {
                        var now = Date.now();
                        if ((now - lastDownloadTime) < DEBOUNCE_MS) {
                            console.log('Download too soon, skipping');
                            return;
                        }
                        
                        downloadInProgress = true;
                        lastDownloadTime = now;
                        
                        // Try to find image or canvas element
                        setTimeout(function() {
                            var img = document.querySelector('img[src^="blob:"]') || 
                                     document.querySelector('img[src^="data:"]') ||
                                     document.querySelector('canvas');
                            
                            if (img) {
                                var src = img.src || (img.tagName === 'CANVAS' ? img.toDataURL('image/png') : null);
                                if (src && (src.startsWith('blob:') || src.startsWith('data:'))) {
                                    console.log('Found image for download:', src);
                                    if (src.startsWith('data:')) {
                                        window.AndroidDownloadHandler.handleDownload(src, 'chart_snapshot.png');
                                    } else {
                                        fetch(src).then(function(response) {
                                            return response.blob();
                                        }).then(function(blob) {
                                            var reader = new FileReader();
                                            reader.onloadend = function() {
                                                window.AndroidDownloadHandler.handleDownload(reader.result, 'chart_snapshot.png');
                                            };
                                            reader.readAsDataURL(blob);
                                        });
                                    }
                                }
                            }
                            setTimeout(function() { downloadInProgress = false; }, DEBOUNCE_MS);
                        }, 100);
                    }
                    
                    // Handle copy button click
                    if (isCopyButton && window.AndroidCopyHandler && !copyInProgress) {
                        var now = Date.now();
                        if ((now - lastCopyTime) < DEBOUNCE_MS) {
                            console.log('Copy too soon, skipping');
                            return;
                        }
                        
                        copyInProgress = true;
                        lastCopyTime = now;
                        
                        setTimeout(function() {
                            var img = document.querySelector('img[src^="blob:"]') || 
                                     document.querySelector('img[src^="data:"]') ||
                                     document.querySelector('canvas');
                            
                            if (img) {
                                var src = img.src || (img.tagName === 'CANVAS' ? img.toDataURL('image/png') : null);
                                if (src && (src.startsWith('blob:') || src.startsWith('data:'))) {
                                    console.log('Found image for copy:', src);
                                    if (src.startsWith('data:')) {
                                        window.AndroidCopyHandler.copyImage(src);
                                    } else {
                                        fetch(src).then(function(response) {
                                            return response.blob();
                                        }).then(function(blob) {
                                            var reader = new FileReader();
                                            reader.onloadend = function() {
                                                window.AndroidCopyHandler.copyImage(reader.result);
                                            };
                                            reader.readAsDataURL(blob);
                                        });
                                    }
                                }
                            }
                            setTimeout(function() { copyInProgress = false; }, DEBOUNCE_MS);
                        }, 100);
                    }
                }, true);
                
                // Intercept Clipboard API writes
                var originalWrite = navigator.clipboard.write;
                if (originalWrite) {
                    navigator.clipboard.write = function(data) {
                        return originalWrite.call(this, data).then(function() {
                            if (data && data[0] && data[0].type && data[0].type.startsWith('image/')) {
                                console.log('Intercepted clipboard write for image');
                                if (window.AndroidCopyHandler && data[0].blob) {
                                    var reader = new FileReader();
                                    reader.onloadend = function() {
                                        window.AndroidCopyHandler.copyImage(reader.result);
                                    };
                                    reader.readAsDataURL(data[0].blob);
                                }
                            }
                        });
                    };
                }
                
                // Intercept copy events
                document.addEventListener('copy', function(e) {
                    var selection = window.getSelection();
                    if (selection && selection.rangeCount > 0) {
                        var range = selection.getRangeAt(0);
                        var img = range.commonAncestorContainer.nodeType === 1 
                            ? range.commonAncestorContainer.querySelector('img')
                            : range.commonAncestorContainer.parentElement?.querySelector('img');
                        
                        if (img) {
                            var imgSrc = img.src || img.getAttribute('data-src');
                            if (imgSrc && (imgSrc.startsWith('blob:') || imgSrc.startsWith('data:'))) {
                                e.preventDefault();
                                console.log('Intercepted copy event:', imgSrc);
                                if (window.AndroidCopyHandler) {
                                    if (imgSrc.startsWith('data:')) {
                                        window.AndroidCopyHandler.copyImage(imgSrc);
                                    } else {
                                        fetch(imgSrc).then(function(response) {
                                            return response.blob();
                                        }).then(function(blob) {
                                            var reader = new FileReader();
                                            reader.onloadend = function() {
                                                window.AndroidCopyHandler.copyImage(reader.result);
                                            };
                                            reader.readAsDataURL(blob);
                                        });
                                    }
                                }
                            }
                        }
                    }
                });
                
                // Monitor for dynamically created download links
                var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        mutation.addedNodes.forEach(function(node) {
                            if (node.nodeType === 1) {
                                var links = node.querySelectorAll ? node.querySelectorAll('a[download]') : [];
                                links.forEach(function(link) {
                                    link.addEventListener('click', function(e) {
                                        var href = link.getAttribute('href');
                                        if (href && (href.startsWith('blob:') || href.startsWith('data:'))) {
                                            e.preventDefault();
                                            e.stopPropagation();
                                            console.log('Intercepted dynamic download link:', href);
                                            if (window.AndroidDownloadHandler) {
                                                if (href.startsWith('data:')) {
                                                    window.AndroidDownloadHandler.handleDownload(href, link.getAttribute('download') || 'chart_snapshot.png');
                                                } else {
                                                    fetch(href).then(function(response) {
                                                        return response.blob();
                                                    }).then(function(blob) {
                                                        var reader = new FileReader();
                                                        reader.onloadend = function() {
                                                            window.AndroidDownloadHandler.handleDownload(reader.result, link.getAttribute('download') || 'chart_snapshot.png');
                                                        };
                                                        reader.readAsDataURL(blob);
                                                    });
                                                }
                                            }
                                        }
                                    }, true);
                                });
                            }
                        });
                    });
                });
                
                observer.observe(document.body, {
                    childList: true,
                    subtree: true
                });
            })();
        """.trimIndent()
        
        webView?.evaluateJavascript(script, null)
    }
    
    private fun handleBlobOrDataUrlDownload(url: String, contentDisposition: String?, mimetype: String?) {
        val ctx = context ?: return
        
        if (url.startsWith("data:")) {
            // Handle data URL (base64 encoded image)
            try {
                val base64Data = url.substringAfter(",")
                val imageData = Base64.decode(base64Data, Base64.DEFAULT)
                
                val fileName = contentDisposition?.substringAfter("filename=")?.removeSurrounding("\"")
                    ?: "chart_snapshot_${System.currentTimeMillis()}.png"
                
                // Save to app's external files directory first
                val downloadsDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { it.write(imageData) }
                
                // Use MediaStore API for Android 10+ (scoped storage)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimetype ?: "image/png")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    
                    val uri = ctx.contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        contentValues
                    )
                    
                    uri?.let {
                        ctx.contentResolver.openOutputStream(it)?.use { outputStream ->
                            outputStream.write(imageData)
                        }
                    }
                } else {
                    // For older Android versions, use DownloadManager
                    val downloadManager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                    val request = android.app.DownloadManager.Request(Uri.fromFile(file))
                    request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setTitle("Downloading $fileName")
                    request.setMimeType("image/png")
                    downloadManager.enqueue(request)
                }
            } catch (e: Exception) {
                android.util.Log.e("TradingView", "Error saving image", e)
                e.printStackTrace()
            }
        } else if (url.startsWith("blob:")) {
            // Handle blob URL - need to fetch it via JavaScript
            webView?.evaluateJavascript("""
                (function() {
                    fetch('$url').then(function(response) {
                        return response.blob();
                    }).then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64data = reader.result;
                            AndroidDownloadHandler.handleBlobDownload(base64data, '${contentDisposition ?: "chart_snapshot.png"}');
                        };
                        reader.readAsDataURL(blob);
                    });
                })();
            """.trimIndent(), null)
        }
    }
    
    private fun updateState(
        loadingState: WebViewLoadingState,
        url: String? = null,
        errorMessage: String? = null
    ) {
        val state = WebViewState(
            loadingState = loadingState,
            currentUrl = url ?: webView?.url,
            canGoBack = webView?.canGoBack() ?: false,
            canGoForward = webView?.canGoForward() ?: false,
            errorMessage = errorMessage
        )
        stateCallback?.invoke(state)
    }
    
    actual fun loadUrl(url: String) {
        webView?.let { view ->
            // Ensure we're on the main thread
            view.post {
                view.loadUrl(url)
            }
        } ?: run {
            // Store URL to load when WebView is initialized
            pendingUrl = url
        }
    }
    
    actual fun evaluateJavaScript(script: String, callback: ((String?) -> Unit)?) {
        webView?.evaluateJavascript(script) { result ->
            callback?.invoke(result?.removeSurrounding("\""))
        }
    }
    
    actual fun addJavaScriptInterface(name: String, handler: (String) -> Unit) {
        javascriptInterfaces[name] = handler
        if (webView != null) {
            webView?.addJavascriptInterface(
                JavaScriptBridge(handler),
                name
            )
        } else {
            // Store for later registration when webview is initialized
            pendingInterfaces[name] = handler
        }
    }
    
    private fun setupTradingViewHandlers() {
        val ctx = context ?: return
        
        // Add handler for TradingView download actions
        webView?.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun handleDownload(url: String, filename: String) {
                    val now = System.currentTimeMillis()
                    if ((now - lastDownloadTime) < DEBOUNCE_MS) {
                        return
                    }
                    lastDownloadTime = now
                    
                    try {
                        if (url.startsWith("data:")) {
                            handleBlobOrDataUrlDownload(url, "filename=$filename", "image/png")
                        } else {
                            handleDownload(url)
                        }
                    } catch (e: Exception) {
                    }
                }
                
                @JavascriptInterface
                fun handleBlobDownload(base64Data: String, filename: String) {
                    try {
                        val imageData = Base64.decode(base64Data.substringAfter(","), Base64.DEFAULT)
                        val fileName = filename.removeSurrounding("\"").takeIf { it.isNotEmpty() } 
                            ?: "chart_snapshot_${System.currentTimeMillis()}.png"
                        
                        // Save to app's external files directory first
                        val downloadsDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        val file = File(downloadsDir, fileName)
                        FileOutputStream(file).use { it.write(imageData) }
                        
                        // Use MediaStore API for Android 10+ (scoped storage)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            val contentValues = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            }
                            
                            val uri = ctx.contentResolver.insert(
                                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                contentValues
                            )
                            
                            uri?.let {
                                ctx.contentResolver.openOutputStream(it)?.use { outputStream ->
                                    outputStream.write(imageData)
                                }
                            }
                        } else {
                            // For older Android versions, use DownloadManager
                            val downloadManager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                            val request = android.app.DownloadManager.Request(Uri.fromFile(file))
                            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            request.setTitle("Downloading $fileName")
                            request.setMimeType("image/png")
                            downloadManager.enqueue(request)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TradingView", "Error in handleBlobDownload", e)
                    }
                }
                
                @JavascriptInterface
                fun copyImage(imageData: String) {
                    val now = System.currentTimeMillis()
                    if ((now - lastCopyTime) < DEBOUNCE_MS) {
                        return
                    }
                    lastCopyTime = now
                    
                    try {
                        copyImageToClipboard(ctx, imageData)
                    } catch (e: Exception) {
                        android.util.Log.e("TradingView", "Error in handleDownload", e)
                    }
                }
            },
            "AndroidDownloadHandler"
        )
        
        // Add handler for copy actions
        webView?.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun copyImage(imageUrl: String) {
                    val now = System.currentTimeMillis()
                    if ((now - lastCopyTime) < DEBOUNCE_MS) {
                        return
                    }
                    lastCopyTime = now
                    
                    try {
                        copyImageToClipboard(ctx, imageUrl)
                    } catch (e: Exception) {
                        android.util.Log.e("TradingView", "Error in copyImage", e)
                    }
                }
            },
            "AndroidCopyHandler"
        )
        
        // Make handlers available immediately
        webView?.evaluateJavascript("""
            window.AndroidDownloadHandler = window.AndroidDownloadHandler || {};
            window.AndroidCopyHandler = window.AndroidCopyHandler || {};
            console.log('Android handlers registered');
        """.trimIndent(), null)
    }
    
    private fun copyImageToClipboard(context: Context, imageData: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            
            if (imageData.startsWith("data:")) {
                // Handle base64 image data - copy directly as image
                val base64Data = imageData.substringAfter(",")
                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                
                if (bitmap != null) {
                    // Use ClipData.newUri with FileProvider for Android 7.0+
                    // Try to copy as image file first (better compatibility)
                    try {
                        // Save to internal storage and use FileProvider
                        val file = File(context.filesDir, "clipboard_image_${System.currentTimeMillis()}.png")
                        FileOutputStream(file).use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        }
                        
                        // Use FileProvider to create a content URI
                        val fileProvider = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        
                        // Grant read URI permission to system UI (needed for clipboard preview)
                        try {
                            context.grantUriPermission(
                                "com.android.systemui",
                                fileProvider,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("TradingView", "Could not grant URI permission to system UI", e)
                        }
                        
                        // Create ClipData with URI
                        val clip = android.content.ClipData.newUri(
                            context.contentResolver,
                            "Image",
                            fileProvider
                        )
                        
                        clipboard.setPrimaryClip(clip)
                    } catch (e: Exception) {
                        android.util.Log.w("TradingView", "Failed to copy as file, using data URI", e)
                        // Fallback: copy as data URI string
                        val clip = android.content.ClipData.newPlainText("Image", imageData)
                        clipboard.setPrimaryClip(clip)
                    }
                } else {
                    // Fallback: copy as text
                    val clip = android.content.ClipData.newPlainText("Image URL", imageData)
                    clipboard.setPrimaryClip(clip)
                }
            } else {
                // Handle URL
                val clip = android.content.ClipData.newPlainText("Image URL", imageData)
                clipboard.setPrimaryClip(clip)
            }
        } catch (e: Exception) {
            android.util.Log.e("TradingView", "Error copying to clipboard", e)
            // Fallback: copy as text
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Image URL", imageData)
                clipboard.setPrimaryClip(clip)
            } catch (e2: Exception) {
                android.util.Log.e("TradingView", "Error in fallback copy", e2)
            }
        }
    }
    
    actual fun reload() {
        webView?.reload()
    }
    
    actual fun goBack() {
        webView?.goBack()
    }
    
    actual fun goForward() {
        webView?.goForward()
    }
    
    actual fun canGoBack(): Boolean {
        return webView?.canGoBack() ?: false
    }
    
    actual fun canGoForward(): Boolean {
        return webView?.canGoForward() ?: false
    }
    
    actual fun getCurrentUrl(): String? {
        return webView?.url
    }
    
    actual fun setCookie(url: String, cookie: String) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setCookie(url, cookie)
        cookieManager.flush()
    }
    
    actual fun getCookies(url: String): String? {
        val cookieManager = CookieManager.getInstance()
        return cookieManager.getCookie(url)
    }
    
    actual fun clearCookies() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }
    
    actual fun removeCookie(url: String, cookieName: String) {
        val cookieManager = CookieManager.getInstance()
        // Android CookieManager doesn't have a direct way to remove a single cookie
        // The best approach is to set the cookie with an expired date (past date)
        // This effectively removes the cookie
        try {
            val nsUrl = java.net.URL(url)
            val domain = nsUrl.host
            // Set cookie with expired date to remove it
            val expiredCookie = "$cookieName=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; domain=$domain"
            cookieManager.setCookie(url, expiredCookie)
            cookieManager.flush()
        } catch (e: Exception) {
            // Fallback: try to remove by setting empty value
            cookieManager.setCookie(url, "$cookieName=")
            cookieManager.flush()
        }
    }
    
    actual fun dispose() {
        webView?.destroy()
        webView = null
        stateCallback = null
        javascriptInterfaces.clear()
    }
}

/**
 * Factory function to create Android WebViewController
 */
actual fun createWebViewController(): WebViewController = WebViewController()

/**
 * Android platform-specific WebView composable
 */
@Composable
actual fun PlatformWebView(
    controller: WebViewController,
    modifier: Modifier,
    onStateChanged: ((WebViewState) -> Unit)?
) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Initialize without URL - it will be loaded via loadUrl() call
                controller.initialize(this, onStateChanged, null, ctx)
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { webView ->
            // Ensure WebView is properly set up when updated
            if (webView.parent == null) {
                // WebView might not be attached yet
            }
        }
    )
}
