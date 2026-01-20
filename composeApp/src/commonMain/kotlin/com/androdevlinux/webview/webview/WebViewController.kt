package com.androdevlinux.webview.webview

/**
 * Common interface for webview operations across platforms
 */
expect class WebViewController {
    /**
     * Load a URL in the webview
     */
    fun loadUrl(url: String)
    
    /**
     * Evaluate JavaScript in the webview
     * @param script JavaScript code to execute
     * @param callback Callback with the result (null if error)
     */
    fun evaluateJavaScript(script: String, callback: ((String?) -> Unit)? = null)
    
    /**
     * Add a JavaScript interface that can be called from JavaScript
     * @param name Interface name (e.g., "NativeBridge")
     * @param handler Handler function that receives messages from JavaScript
     */
    fun addJavaScriptInterface(name: String, handler: (String) -> Unit)
    
    /**
     * Reload the current page
     */
    fun reload()
    
    /**
     * Go back in history
     */
    fun goBack()
    
    /**
     * Go forward in history
     */
    fun goForward()
    
    /**
     * Check if webview can go back
     */
    fun canGoBack(): Boolean
    
    /**
     * Check if webview can go forward
     */
    fun canGoForward(): Boolean
    
    /**
     * Get current URL
     */
    fun getCurrentUrl(): String?
    
    /**
     * Set a cookie for a given URL
     * @param url The URL domain for the cookie
     * @param cookie The cookie string (e.g., "name=value; path=/; domain=.example.com")
     */
    fun setCookie(url: String, cookie: String)
    
    /**
     * Get cookies for a given URL
     * @param url The URL to get cookies for
     * @return Cookie string or null if no cookies found
     */
    fun getCookies(url: String): String?
    
    /**
     * Clear all cookies
     */
    fun clearCookies()
    
    /**
     * Remove a specific cookie by name and URL
     * @param url The URL domain for the cookie
     * @param cookieName The name of the cookie to remove
     */
    fun removeCookie(url: String, cookieName: String)
    
    /**
     * Cleanup resources
     */
    fun dispose()
}

/**
 * Factory function to create a WebViewController instance
 */
expect fun createWebViewController(): WebViewController
