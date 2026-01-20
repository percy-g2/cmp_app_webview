package com.androdevlinux.webview.webview

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
    
    fun initialize(webView: WebView, stateCallback: ((WebViewState) -> Unit)?, initialUrl: String? = null) {
        this.webView = webView
        this.stateCallback = stateCallback
        setupWebView(webView)
        
        // Register any pending interfaces
        pendingInterfaces.forEach { (name, handler) ->
            addJavaScriptInterface(name, handler)
        }
        pendingInterfaces.clear()
        
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
                // Allow all URLs to load in WebView
                return false
            }
        }
        
        webView.webChromeClient = WebChromeClient()
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
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Initialize without URL - it will be loaded via loadUrl() call
                controller.initialize(this, onStateChanged, null)
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
