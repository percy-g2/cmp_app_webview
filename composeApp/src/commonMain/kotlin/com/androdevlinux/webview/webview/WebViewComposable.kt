package com.androdevlinux.webview.webview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/**
 * JavaScript interface handler type
 */
typealias JavaScriptHandler = (String) -> Unit

/**
 * Composable WebView component
 * 
 * @param url The URL to load
 * @param modifier Modifier for the webview
 * @param onStateChanged Callback when webview state changes
 * @param javascriptInterfaces Map of interface names to handlers for JavaScript-to-native communication
 */
@Composable
fun WebViewComposable(
    url: String,
    modifier: Modifier = Modifier,
    onStateChanged: ((WebViewState) -> Unit)? = null,
    javascriptInterfaces: Map<String, JavaScriptHandler> = emptyMap()
) {
    val state = remember { mutableStateOf(WebViewState()) }
    val controller = remember { createWebViewController() }
    var isInitialized by remember { mutableStateOf(false) }
    val registeredInterfaces = remember { mutableSetOf<String>() }
    
    // Register JavaScript interfaces when initialized
    LaunchedEffect(javascriptInterfaces, isInitialized) {
        if (isInitialized) {
            javascriptInterfaces.forEach { (name, handler) ->
                // Only register if not already registered
                if (!registeredInterfaces.contains(name)) {
                    controller.addJavaScriptInterface(name, handler)
                    registeredInterfaces.add(name)
                }
            }
            // Remove interfaces that are no longer in the map
            registeredInterfaces.removeAll { it !in javascriptInterfaces.keys }
        }
    }
    
    // Load URL when it changes and webview is initialized
    LaunchedEffect(url, isInitialized) {
        if (isInitialized) {
            kotlinx.coroutines.delay(100) // Small delay to ensure WebView is ready
            controller.loadUrl(url)
        } else {
            // Try to load URL even if not initialized yet (will be stored as pending)
            controller.loadUrl(url)
        }
    }
    
    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            controller.dispose()
        }
    }
    
    // Platform-specific webview implementation
    PlatformWebView(
        controller = controller,
        modifier = modifier.fillMaxSize(),
        onStateChanged = { newState ->
            state.value = newState
            onStateChanged?.invoke(newState)
            // Mark as initialized after first state update
            if (!isInitialized) {
                isInitialized = true
            }
        }
    )
}

/**
 * Platform-specific webview composable
 */
@Composable
expect fun PlatformWebView(
    controller: WebViewController,
    modifier: Modifier = Modifier,
    onStateChanged: ((WebViewState) -> Unit)? = null
)
