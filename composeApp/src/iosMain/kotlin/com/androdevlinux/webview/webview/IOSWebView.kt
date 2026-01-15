package com.androdevlinux.webview.webview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.*
import platform.darwin.NSObject

/**
 * JavaScript message handler for iOS
 */
class JavaScriptMessageHandler(
    private val handler: (String) -> Unit
) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage
    ) {
        val message = didReceiveScriptMessage.body as? String ?: return
        handler(message)
    }
}

/**
 * iOS implementation of WebViewController
 */
actual class WebViewController {
    private var webView: WKWebView? = null
    private var stateCallback: ((WebViewState) -> Unit)? = null
    private val messageHandlers = mutableMapOf<String, JavaScriptMessageHandler>()
    private val pendingInterfaces = mutableMapOf<String, (String) -> Unit>()
    private var isInitialized = false
    
    fun initialize(webView: WKWebView, stateCallback: ((WebViewState) -> Unit)?) {
        // If already initialized with the same webview, skip
        if (isInitialized && this.webView == webView) {
            return
        }
        
        // Clean up previous webview if it exists and is different
        if (this.webView != null && this.webView != webView) {
            cleanupWebView(this.webView!!)
        }
        
        this.webView = webView
        this.stateCallback = stateCallback
        isInitialized = true
        
        setupWebView(webView)
        
        // Register any pending interfaces
        pendingInterfaces.forEach { (name, handler) ->
            addJavaScriptInterface(name, handler)
        }
        pendingInterfaces.clear()
        
        // Trigger initial state update
        updateState(WebViewLoadingState.IDLE, null)
    }
    
    private fun cleanupWebView(webView: WKWebView) {
        // Remove all message handlers from the old webview
        messageHandlers.keys.forEach { name ->
            try {
                webView.configuration.userContentController.removeScriptMessageHandlerForName(name)
            } catch (e: Exception) {
                // Ignore errors during cleanup
            }
        }
    }
    
    private fun setupWebView(webView: WKWebView) {
        val configuration = webView.configuration
        configuration.preferences.javaScriptEnabled = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        
        // Set navigation delegate
        webView.navigationDelegate = object : NSObject(), WKNavigationDelegateProtocol {
            @ObjCSignatureOverride
            override fun webView(
                webView: WKWebView,
                didStartProvisionalNavigation: WKNavigation?
            ) {
                val url = webView.URL?.absoluteString
                updateState(WebViewLoadingState.LOADING, url)
            }
            
            @ObjCSignatureOverride
            override fun webView(
                webView: WKWebView,
                didFinishNavigation: WKNavigation?
            ) {
                val url = webView.URL?.absoluteString
                updateState(WebViewLoadingState.LOADED, url)
            }
            
            @ObjCSignatureOverride
            override fun webView(
                webView: WKWebView,
                didFailNavigation: WKNavigation?,
                withError: platform.Foundation.NSError
            ) {
                val url = webView.URL?.absoluteString
                val errorMsg = withError.localizedDescription
                updateState(WebViewLoadingState.ERROR, url, errorMsg)
            }
            
            @ObjCSignatureOverride
            override fun webView(
                webView: WKWebView,
                didFailProvisionalNavigation: WKNavigation?,
                withError: platform.Foundation.NSError
            ) {
                val url = webView.URL?.absoluteString
                val errorMsg = withError.localizedDescription
                updateState(WebViewLoadingState.ERROR, url, errorMsg)
            }
        }
    }
    
    private fun updateState(
        loadingState: WebViewLoadingState,
        url: String? = null,
        errorMessage: String? = null
    ) {
        val state = WebViewState(
            loadingState = loadingState,
            currentUrl = url ?: webView?.URL?.absoluteString,
            canGoBack = webView?.canGoBack() ?: false,
            canGoForward = webView?.canGoForward() ?: false,
            errorMessage = errorMessage
        )
        stateCallback?.invoke(state)
    }
    
    actual fun loadUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        val request = NSURLRequest.requestWithURL(nsUrl)
        webView?.loadRequest(request)
    }
    
    actual fun evaluateJavaScript(script: String, callback: ((String?) -> Unit)?) {
        webView?.evaluateJavaScript(script) { result, error ->
            if (error != null) {
                callback?.invoke(null)
            } else {
                val resultString = result?.toString()?.removeSurrounding("\"")
                callback?.invoke(resultString)
            }
        }
    }
    
    actual fun addJavaScriptInterface(name: String, handler: (String) -> Unit) {
        if (webView != null) {
            val userContentController = webView?.configuration?.userContentController ?: return
            
            // Remove existing handler if it exists to avoid duplicate registration
            if (messageHandlers.containsKey(name)) {
                userContentController.removeScriptMessageHandlerForName(name)
                messageHandlers.remove(name)
            }
            
            val messageHandler = JavaScriptMessageHandler(handler)
            messageHandlers[name] = messageHandler
            
            // Add the script message handler
            userContentController.addScriptMessageHandler(
                messageHandler,
                name
            )
            
            // Inject JavaScript bridge code
            val bridgeScript = """
                window.$name = {
                    postMessage: function(message) {
                        window.webkit.messageHandlers.$name.postMessage(message);
                    }
                };
            """.trimIndent()
            
            val wkScript = WKUserScript(
                source = bridgeScript,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = false
            )
            
            userContentController.addUserScript(wkScript)
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
        return webView?.URL?.absoluteString
    }
    
    actual fun dispose() {
        // Remove message handlers before clearing
        val webViewToCleanup = webView
        if (webViewToCleanup != null) {
            cleanupWebView(webViewToCleanup)
        }
        messageHandlers.clear()
        pendingInterfaces.clear()
        webView = null
        stateCallback = null
        isInitialized = false
    }
}

/**
 * Factory function to create iOS WebViewController
 */
actual fun createWebViewController(): WebViewController = WebViewController()

/**
 * iOS platform-specific WebView composable
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformWebView(
    controller: WebViewController,
    modifier: Modifier,
    onStateChanged: ((WebViewState) -> Unit)?
) {
    UIKitView(
        factory = {
            WKWebView().apply {
                controller.initialize(this, onStateChanged)
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { webView ->
            // Update callback if needed (webview instance should remain the same)
            controller.initialize(webView, onStateChanged)
        }
    )
}
