package com.androdevlinux.webview.webview

/**
 * Represents the loading state of the webview
 */
enum class WebViewLoadingState {
    IDLE,
    LOADING,
    LOADED,
    ERROR
}

/**
 * State class for webview
 */
data class WebViewState(
    val loadingState: WebViewLoadingState = WebViewLoadingState.IDLE,
    val currentUrl: String? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val errorMessage: String? = null
)
