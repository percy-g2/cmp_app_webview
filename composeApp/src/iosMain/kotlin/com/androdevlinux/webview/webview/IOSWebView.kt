package com.androdevlinux.webview.webview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import platform.Foundation.*
import platform.WebKit.*
import platform.UIKit.*
import platform.darwin.NSObject
import platform.Photos.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

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
    private var imageLongPressHandler: JavaScriptMessageHandler? = null
    
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
        
        // Register image long-press handler
        setupImageLongPressHandler(webView)
        
        // Setup TradingView handlers (must be done before page loads)
        setupTradingViewHandlers(webView)
        
        // Register any pending interfaces
        pendingInterfaces.forEach { (name, handler) ->
            addJavaScriptInterface(name, handler)
        }
        pendingInterfaces.clear()
        
        // Trigger initial state update
        updateState(WebViewLoadingState.IDLE, null)
    }
    
    private fun setupImageLongPressHandler(webView: WKWebView) {
        val userContentController = webView.configuration.userContentController
        
        // Remove existing handler if it exists
        imageLongPressHandler?.let {
            userContentController.removeScriptMessageHandlerForName("imageLongPress")
        }
        
        // Create new handler
        imageLongPressHandler = JavaScriptMessageHandler { imageUrl ->
            handleImageLongPress(imageUrl)
        }
        
        // Add handler
        userContentController.addScriptMessageHandler(
            imageLongPressHandler!!,
            "imageLongPress"
        )
        
        // Inject JavaScript for image long-press detection
        val script = """
            (function() {
                function setupImageLongPress() {
                    document.addEventListener('contextmenu', function(e) {
                        var target = e.target;
                        if (target.tagName === 'IMG') {
                            e.preventDefault();
                            var imageUrl = target.src || target.getAttribute('data-src') || '';
                            if (imageUrl) {
                                window.webkit.messageHandlers.imageLongPress.postMessage(imageUrl);
                            }
                        }
                    }, true);
                }
                
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', setupImageLongPress);
                } else {
                    setupImageLongPress();
                }
            })();
        """.trimIndent()
        
        val wkScript = WKUserScript(
            source = script,
            injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentEnd,
            forMainFrameOnly = false
        )
        
        userContentController.addUserScript(wkScript)
    }
    
    @OptIn(ExperimentalForeignApi::class)
    private fun handleImageLongPress(imageUrl: String) {
        val alertController = UIAlertController.alertControllerWithTitle(
            "Image Options",
            "Choose an action",
            UIAlertControllerStyleActionSheet
        )
        
        // Download option
        val downloadAction = UIAlertAction.actionWithTitle(
            "Download Image",
            UIAlertActionStyleDefault
        ) { _ ->
            downloadImage(imageUrl)
        }
        alertController.addAction(downloadAction)
        
        // Copy URL option
        val copyAction = UIAlertAction.actionWithTitle(
            "Copy Image URL",
            UIAlertActionStyleDefault
        ) { _ ->
            copyImageUrl(imageUrl)
        }
        alertController.addAction(copyAction)
        
        // Cancel option
        val cancelAction = UIAlertAction.actionWithTitle(
            "Cancel",
            UIAlertActionStyleCancel,
            null
        )
        alertController.addAction(cancelAction)
        
        // Present alert
        val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController
        rootViewController?.presentViewController(alertController, animated = true, completion = null)
    }
    
    private fun downloadImage(imageUrl: String) {
        val nsUrl = NSURL.URLWithString(imageUrl) ?: return
        
        NSURLSession.sharedSession.dataTaskWithURL(nsUrl) { data, response, error ->
            if (data != null && error == null) {
                val image = UIImage.imageWithData(data)
                if (image != null) {
                    // Use Photos framework to save image
                    PHPhotoLibrary.sharedPhotoLibrary().performChanges({
                        PHAssetChangeRequest.creationRequestForAssetFromImage(image)
                    }, completionHandler = { success, error ->
                        if (error != null) {
                            println("Error saving image to photo library: $error")
                        }
                    })
                }
            }
        }.resume()
    }
    
    private fun copyImageUrl(imageUrl: String) {
        val pasteboard = UIPasteboard.generalPasteboard
        pasteboard.string = imageUrl
    }
    
    private var lastDownloadTime: Long = 0
    private var lastCopyTime: Long = 0
    private val DEBOUNCE_MS: Long = 1000 // 1 second debounce
    
    private fun setupTradingViewHandlers(webView: WKWebView) {
        val userContentController = webView.configuration.userContentController
        
        // Add handler for TradingView download actions
        val downloadHandler = JavaScriptMessageHandler { message ->
            val now = platform.Foundation.NSDate().timeIntervalSince1970.toLong() * 1000
            if ((now - lastDownloadTime) < DEBOUNCE_MS) {
                return@JavaScriptMessageHandler
            }
            lastDownloadTime = now
            
            val parts = message.split("|")
            if (parts.size >= 2) {
                val imageData = parts[0]
                val filename = parts[1]
                handleBlobOrDataUrlDownload(imageData, filename)
            }
        }
        
        // Add handler for copy actions
        val copyHandler = JavaScriptMessageHandler { imageData ->
            val now = platform.Foundation.NSDate().timeIntervalSince1970.toLong() * 1000
            if ((now - lastCopyTime) < DEBOUNCE_MS) {
                return@JavaScriptMessageHandler
            }
            lastCopyTime = now
            
            copyImageToClipboard(imageData)
        }
        
        messageHandlers["tradingViewDownload"] = downloadHandler
        messageHandlers["tradingViewCopy"] = copyHandler
        
        userContentController.addScriptMessageHandler(downloadHandler, "tradingViewDownload")
        userContentController.addScriptMessageHandler(copyHandler, "tradingViewCopy")
        
        // Note: JavaScript injection is done via evaluateJavaScript in didFinishNavigation
        // because WKUserScript is blocked for non-app-bound domains
    }
    
    
    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    private fun handleBlobOrDataUrlDownload(url: String, filename: String? = null) {
        if (url.startsWith("data:")) {
            // Handle data URL (base64 encoded image)
            // Store the full data URL to preserve it through permission flow
            val fullDataUrl = url
            
            // Check authorization status first BEFORE decoding the image
            val authStatus = PHPhotoLibrary.authorizationStatus()
            
            // Use raw values to compare (0 = NotDetermined, 1 = Restricted, 2 = Denied, 3 = Authorized, 4 = Limited)
            val authorizedValue = 3L // PHAuthorizationStatusAuthorized
            val limitedValue = 4L // PHAuthorizationStatusLimited  
            val notDeterminedValue = 0L // PHAuthorizationStatusNotDetermined
            
            val authStatusValue = authStatus.toLong()
            
            if (authStatusValue == authorizedValue || authStatusValue == limitedValue) {
                // Permission already granted - decode and save immediately
                if (!fullDataUrl.startsWith("data:image/")) {
                    return
                }
                
                val commaIndex = fullDataUrl.indexOf(',')
                if (commaIndex == -1 || commaIndex >= fullDataUrl.length - 1) {
                    return
                }
                
                val base64Data = fullDataUrl.substring(commaIndex + 1).replace("\\s".toRegex(), "")
                val decodedData = NSData.create(base64Encoding = base64Data)
                val image = decodedData?.let { UIImage.imageWithData(it) }
                
                if (image != null) {
                    saveImageToPhotoLibrary(image)
                }
            } else if (authStatusValue == notDeterminedValue) {
                // Request permission first - don't decode image yet
                PHPhotoLibrary.requestAuthorization { status ->
                    val statusValue = status.toLong()
                    if (statusValue == authorizedValue || statusValue == limitedValue) {
                        // Permission granted - NOW decode and save the image
                        // Verify data URL format
                        if (!fullDataUrl.startsWith("data:image/")) {
                            return@requestAuthorization
                        }
                        
                        // Extract base64 data from the full data URL
                        // Data URL format: data:image/png;base64,<base64data> or data:image/jpeg;base64,<base64data>
                        val commaIndex = fullDataUrl.indexOf(',')
                        if (commaIndex == -1 || commaIndex >= fullDataUrl.length - 1) {
                            return@requestAuthorization
                        }
                        
                        val base64Data = fullDataUrl.substring(commaIndex + 1)
                        
                        // Remove any whitespace/newlines that might corrupt the base64 data
                        val cleanBase64Data = base64Data.replace("\\s".toRegex(), "")
                        
                        // Verify base64 data looks valid (should contain only valid base64 chars)
                        if (!cleanBase64Data.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
                            // Try to fix common issues
                            val fixedBase64 = cleanBase64Data.replace("[^A-Za-z0-9+/=]".toRegex(), "")
                            if (fixedBase64 != cleanBase64Data) {
                                // Use fixed version
                                val decodedData = NSData.create(base64Encoding = fixedBase64)
                                if (decodedData != null) {
                                    val imageToSave = UIImage.imageWithData(decodedData)
                                    if (imageToSave != null) {
                                        val imgSize = imageToSave.size
                                        val imgWidth = imgSize.useContents { width }
                                        val imgHeight = imgSize.useContents { height }
                                        if (imgWidth > 0 && imgHeight > 0) {
                                            dispatch_async(dispatch_get_main_queue()) {
                                                saveImageToPhotoLibrary(imageToSave)
                                            }
                                        }
                                        return@requestAuthorization
                                    }
                                }
                            }
                        }
                        
                        // Decode the image
                        val decodedData = NSData.create(base64Encoding = cleanBase64Data)
                        if (decodedData == null) {
                            return@requestAuthorization
                        }
                        
                        val imageToSave = UIImage.imageWithData(decodedData)
                        if (imageToSave == null) {
                            return@requestAuthorization
                        }
                        
                        val imgSize = imageToSave.size
                        val imgWidth = imgSize.useContents { width }
                        val imgHeight = imgSize.useContents { height }
                        
                        // Verify image is not blank (check if it has valid pixel data)
                        if (imgWidth <= 0 || imgHeight <= 0) {
                            return@requestAuthorization
                        }
                        
                        // Save on main thread
                        dispatch_async(dispatch_get_main_queue()) {
                            saveImageToPhotoLibrary(imageToSave)
                        }
                    }
                }
            }
        } else if (url.startsWith("blob:")) {
            // Blob URLs should be converted to data URLs via JavaScript before reaching here
            // This is a fallback that shouldn't normally be called
            // Try to convert via JavaScript if webView is available
            webView?.evaluateJavaScript("""
                fetch('$url').then(function(response) {
                    return response.blob();
                }).then(function(blob) {
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        window.webkit.messageHandlers.tradingViewDownload.postMessage(reader.result + '|chart_snapshot.png');
                    };
                    reader.readAsDataURL(blob);
                });
            """.trimIndent()) { _, _ -> }
        }
    }
    
    private fun handleBlobOrDataUrlDownload(url: String) {
        handleBlobOrDataUrlDownload(url, null)
    }
    
    @OptIn(ExperimentalForeignApi::class)
    private fun saveImageToPhotoLibrary(image: UIImage) {
        // Verify image is valid before saving
        val size = image.size
        val width = size.useContents { width }
        val height = size.useContents { height }
        
        if (width <= 0 || height <= 0) {
            return
        }
        
        PHPhotoLibrary.sharedPhotoLibrary().performChanges({
            PHAssetChangeRequest.creationRequestForAssetFromImage(image)
        }, completionHandler = { success, error ->
            if (error != null) {
                println("Error saving image to photo library: $error")
            }
        })
    }
    
    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    private fun copyImageToClipboard(imageData: String) {
        val pasteboard = UIPasteboard.generalPasteboard
        
        if (imageData.startsWith("data:")) {
            // Handle base64 image data
            val base64Data = imageData.substringAfter(",")
            // Decode base64 string to NSData
            val decodedData = NSData.create(base64Encoding = base64Data)
            val image: UIImage? = decodedData?.let { UIImage.imageWithData(it) }
            
            if (image != null) {
                // Copy image to clipboard
                pasteboard.image = image
            } else {
                // Fallback: copy URL
                pasteboard.string = imageData
            }
        } else {
            // Copy URL
            pasteboard.string = imageData
        }
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
        
        // Remove image long-press handler
        imageLongPressHandler?.let {
            try {
                webView.configuration.userContentController.removeScriptMessageHandlerForName("imageLongPress")
            } catch (e: Exception) {
                // Ignore errors during cleanup
            }
        }
        
        // Remove TradingView handlers
        try {
            webView.configuration.userContentController.removeScriptMessageHandlerForName("tradingViewDownload")
            webView.configuration.userContentController.removeScriptMessageHandlerForName("tradingViewCopy")
        } catch (e: Exception) {
            // Ignore errors during cleanup
        }
    }
    
    private fun setupWebView(webView: WKWebView) {
        val configuration = webView.configuration
        // Configuration is already set up in PlatformWebView factory
        // Just verify settings are correct
        configuration.preferences.javaScriptEnabled = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        configuration.allowsInlineMediaPlayback = true
        
        // Note: limitsNavigationsToAppBoundDomains is set in PlatformWebView factory
        // before the WebView is created, as it must be set during configuration creation
        
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
                val host = webView.URL?.host
                updateState(WebViewLoadingState.LOADED, url)
                
                // Inject JavaScript for image long-press handling
                injectImageLongPressHandler(webView)
                
                // Try to inject JavaScript handler for TradingView
                // This will only work if the domain is properly configured in WKAppBoundDomains
                webView.evaluateJavaScript("""
                    setTimeout(function() {
                        if (window.__tradingViewHandlerInjected) {
                            return;
                        }
                        window.__tradingViewHandlerInjected = true;
                        console.log('TradingView snapshot handler injected (iOS via evaluateJavaScript)');
                        
                        // Track recent actions to prevent duplicates
                        var lastDownloadTime = 0;
                        var lastCopyTime = 0;
                        var downloadInProgress = false;
                        var copyInProgress = false;
                        var DEBOUNCE_MS = 1000;
                        
                        // Intercept all clicks
                        document.addEventListener('click', function(e) {
                            var target = e.target;
                            var text = (target.textContent || target.innerText || '').toLowerCase().trim();
                            
                            var isDownloadButton = text.includes('download') || text.includes('download image');
                            var isCopyButton = text.includes('copy') || text.includes('copy image');
                            
                            // Walk up DOM to find download links
                            var current = target;
                            var linkElement = null;
                            while (current && current !== document.body) {
                                if (current.tagName === 'A' && current.hasAttribute('download')) {
                                    linkElement = current;
                                    break;
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
                                        return false;
                                    }
                                    
                                    e.preventDefault();
                                    e.stopPropagation();
                                    e.stopImmediatePropagation();
                                    
                                    downloadInProgress = true;
                                    lastDownloadTime = now;
                                    
                                    if (href.startsWith('data:')) {
                                        window.webkit.messageHandlers.tradingViewDownload.postMessage(href + '|' + downloadName);
                                    } else {
                                        fetch(href).then(function(response) {
                                            return response.blob();
                                        }).then(function(blob) {
                                            var reader = new FileReader();
                                            reader.onloadend = function() {
                                                window.webkit.messageHandlers.tradingViewDownload.postMessage(reader.result + '|' + downloadName);
                                                setTimeout(function() { downloadInProgress = false; }, DEBOUNCE_MS);
                                            };
                                            reader.readAsDataURL(blob);
                                        }).catch(function(err) {
                                            console.error('Error converting blob:', err);
                                            downloadInProgress = false;
                                        });
                                    }
                                    return false;
                                }
                            }
                            
                            // Handle download button click
                            if (isDownloadButton && !downloadInProgress) {
                                var now = Date.now();
                                if ((now - lastDownloadTime) < DEBOUNCE_MS) {
                                    return;
                                }
                                
                                downloadInProgress = true;
                                lastDownloadTime = now;
                                
                                setTimeout(function() {
                                    var img = document.querySelector('img[src^="blob:"]') || 
                                             document.querySelector('img[src^="data:"]') ||
                                             document.querySelector('canvas');
                                    
                                    if (img) {
                                        var src = img.src || (img.tagName === 'CANVAS' ? img.toDataURL('image/png') : null);
                                        if (src && (src.startsWith('blob:') || src.startsWith('data:'))) {
                                            if (src.startsWith('data:')) {
                                                window.webkit.messageHandlers.tradingViewDownload.postMessage(src + '|chart_snapshot.png');
                                            } else {
                                                fetch(src).then(function(response) {
                                                    return response.blob();
                                                }).then(function(blob) {
                                                    var reader = new FileReader();
                                                    reader.onloadend = function() {
                                                        window.webkit.messageHandlers.tradingViewDownload.postMessage(reader.result + '|chart_snapshot.png');
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
                            if (isCopyButton && !copyInProgress) {
                                var now = Date.now();
                                if ((now - lastCopyTime) < DEBOUNCE_MS) {
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
                                            if (src.startsWith('data:')) {
                                                window.webkit.messageHandlers.tradingViewCopy.postMessage(src);
                                            } else {
                                                fetch(src).then(function(response) {
                                                    return response.blob();
                                                }).then(function(blob) {
                                                    var reader = new FileReader();
                                                    reader.onloadend = function() {
                                                        window.webkit.messageHandlers.tradingViewCopy.postMessage(reader.result);
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
                                        if (imgSrc.startsWith('data:')) {
                                            window.webkit.messageHandlers.tradingViewCopy.postMessage(imgSrc);
                                        } else {
                                            fetch(imgSrc).then(function(response) {
                                                return response.blob();
                                            }).then(function(blob) {
                                                var reader = new FileReader();
                                                reader.onloadend = function() {
                                                    window.webkit.messageHandlers.tradingViewCopy.postMessage(reader.result);
                                                };
                                                reader.readAsDataURL(blob);
                                            });
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
                                            if (link.hasAttribute('data-download-handled')) {
                                                return;
                                            }
                                            link.setAttribute('data-download-handled', 'true');
                                            
                                            link.addEventListener('click', function(e) {
                                                var href = link.getAttribute('href');
                                                if (href && (href.startsWith('blob:') || href.startsWith('data:'))) {
                                                    var now = Date.now();
                                                    if (downloadInProgress || (now - lastDownloadTime) < DEBOUNCE_MS) {
                                                        return false;
                                                    }
                                                    
                                                    e.preventDefault();
                                                    e.stopPropagation();
                                                    e.stopImmediatePropagation();
                                                    
                                                    downloadInProgress = true;
                                                    lastDownloadTime = now;
                                                    
                                                    if (href.startsWith('data:')) {
                                                        window.webkit.messageHandlers.tradingViewDownload.postMessage(href + '|' + (link.getAttribute('download') || 'chart_snapshot.png'));
                                                    } else {
                                                        fetch(href).then(function(response) {
                                                            return response.blob();
                                                        }).then(function(blob) {
                                                            var reader = new FileReader();
                                                            reader.onloadend = function() {
                                                                window.webkit.messageHandlers.tradingViewDownload.postMessage(reader.result + '|' + (link.getAttribute('download') || 'chart_snapshot.png'));
                                                                setTimeout(function() { downloadInProgress = false; }, DEBOUNCE_MS);
                                                            };
                                                            reader.readAsDataURL(blob);
                                                        }).catch(function(err) {
                                                            console.error('Error converting blob:', err);
                                                            downloadInProgress = false;
                                                        });
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
                    }, 1000);
                """.trimIndent()) { _, error ->
                }
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
            
            @ObjCSignatureOverride
            override fun webView(
                webView: WKWebView,
                decidePolicyForNavigationAction: WKNavigationAction,
                decisionHandler: (WKNavigationActionPolicy) -> Unit
            ) {
                val url = decidePolicyForNavigationAction.request.URL?.absoluteString
                val host = decidePolicyForNavigationAction.request.URL?.host
                if (url != null) {
                    if (isDownloadableFile(url)) {
                        handleDownload(url)
                        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                        return
                    } else if (url.startsWith("data:image/")) {
                        // Handle data URLs directly (blob URLs can't be handled without JavaScript)
                        handleBlobOrDataUrlDownload(url)
                        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                        return
                    } else if (url.startsWith("blob:")) {
                        // Blob URLs need to be converted via JavaScript
                        // Convert blob URL to data URL using JavaScript
                        // Add a small delay to ensure blob is ready
                        webView.evaluateJavaScript("""
                            (function() {
                                setTimeout(function() {
                                    fetch('$url').then(function(response) {
                                        if (!response.ok) {
                                            console.error('Fetch failed:', response.status, response.statusText);
                                            return;
                                        }
                                        return response.blob();
                                    }).then(function(blob) {
                                        if (!blob) {
                                            console.error('Failed to get blob from response');
                                            return;
                                        }
                                        console.log('Blob size:', blob.size, 'type:', blob.type);
                                        var reader = new FileReader();
                                        reader.onerror = function(err) {
                                            console.error('FileReader error:', err);
                                        };
                                        reader.onloadend = function() {
                                            var result = reader.result;
                                            if (!result || typeof result !== 'string') {
                                                console.error('Invalid FileReader result:', typeof result);
                                                return;
                                            }
                                            console.log('Data URL created, length:', result.length);
                                            window.webkit.messageHandlers.tradingViewDownload.postMessage(result + '|chart_snapshot.png');
                                        };
                                        reader.readAsDataURL(blob);
                                    }).catch(function(err) {
                                        console.error('Error converting blob:', err);
                                    });
                                }, 100);
                            })();
                        """.trimIndent()) { _, error ->
                            if (error != null) {
                                println("Error converting blob URL: $error")
                            }
                        }
                        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                        return
                    }
                }
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            }
            
            @ObjCSignatureOverride
            override fun webView(
                webView: WKWebView,
                decidePolicyForNavigationResponse: WKNavigationResponse,
                decisionHandler: (WKNavigationResponsePolicy) -> Unit
            ) {
                val response = decidePolicyForNavigationResponse.response
                val url = response.URL?.absoluteString
                
                // Handle data URLs
                if (url != null && url.startsWith("data:image/")) {
                    handleBlobOrDataUrlDownload(url)
                    decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
                    return
                }
                
                // For blob URLs, convert them via JavaScript
                if (url != null && url.startsWith("blob:")) {
                    // Convert blob URL to data URL using JavaScript with delay
                    webView.evaluateJavaScript("""
                        (function() {
                            setTimeout(function() {
                                console.log('Converting blob URL in response:', '$url');
                                fetch('$url', {
                                    method: 'GET',
                                    cache: 'no-cache'
                                }).then(function(response) {
                                    if (!response.ok) {
                                        console.error('Fetch failed:', response.status);
                                        return Promise.reject('HTTP ' + response.status);
                                    }
                                    return response.blob();
                                }).then(function(blob) {
                                    if (!blob || blob.size === 0) {
                                        console.error('Invalid blob:', blob ? 'size=' + blob.size : 'null');
                                        return;
                                    }
                                    console.log('Blob ready - size:', blob.size, 'type:', blob.type);
                                    var reader = new FileReader();
                                    reader.onload = function() {
                                        var result = reader.result;
                                        if (result && typeof result === 'string' && result.startsWith('data:image/')) {
                                            console.log('Data URL created, length:', result.length);
                                            window.webkit.messageHandlers.tradingViewDownload.postMessage(result + '|chart_snapshot.png');
                                        } else {
                                            console.error('Invalid data URL result');
                                        }
                                    };
                                    reader.onerror = function(err) {
                                        console.error('FileReader error:', err);
                                    };
                                    reader.readAsDataURL(blob);
                                }).catch(function(err) {
                                    console.error('Error converting blob:', err);
                                });
                            }, 200);
                        })();
                    """.trimIndent()) { _, error ->
                        if (error != null) {
                            println("Error converting blob URL in response: $error")
                        }
                    }
                    decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
                    return
                }
                
                // Check Content-Type header for image downloads
                val httpResponse = response as? platform.Foundation.NSHTTPURLResponse
                val mimeType = httpResponse?.allHeaderFields?.get("Content-Type") as? String
                if (mimeType != null && mimeType.startsWith("image/")) {
                    // This is an image download - try to handle it
                    if (url != null && !url.startsWith("blob:")) {
                        handleDownload(url)
                        decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
                        return
                    }
                }
                
                // Check Content-Disposition header for downloads
                val contentDisposition = httpResponse?.allHeaderFields?.get("Content-Disposition") as? String
                if (contentDisposition != null && contentDisposition.contains("attachment", ignoreCase = true)) {
                    // This is a download - handle it
                    if (url != null) {
                        handleDownload(url)
                        decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
                        return
                    }
                }
                
                decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
            }
        }
        
        // Set UIDelegate for download handling
        webView.UIDelegate = object : NSObject(), WKUIDelegateProtocol {
            @ObjCSignatureOverride
            override fun webView(
                webView: WKWebView,
                createWebViewWithConfiguration: WKWebViewConfiguration,
                forNavigationAction: WKNavigationAction,
                windowFeatures: WKWindowFeatures
            ): WKWebView? {
                // Open links in the same webview
                if (forNavigationAction.targetFrame == null) {
                    webView.loadRequest(forNavigationAction.request)
                }
                return null
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
    
    @OptIn(ExperimentalForeignApi::class)
    private fun handleDownload(url: String) {
        // For image URLs, try to download and save to photo library
        if (url.contains(".jpg") || url.contains(".jpeg") || url.contains(".png") || 
            url.contains(".gif") || url.contains(".webp") || url.startsWith("data:image/")) {
            if (url.startsWith("data:image/")) {
                handleBlobOrDataUrlDownload(url)
            } else {
                // Download image and save to photo library
                val nsUrl = NSURL.URLWithString(url) ?: return
                NSURLSession.sharedSession.dataTaskWithURL(nsUrl) { data, response, error ->
                    if (data != null && error == null) {
                        val image = UIImage.imageWithData(data)
                        if (image != null) {
                            PHPhotoLibrary.sharedPhotoLibrary().performChanges({
                                PHAssetChangeRequest.creationRequestForAssetFromImage(image)
                            }, completionHandler = { _, _ -> })
                        }
                    }
                }.resume()
            }
        } else {
            // For other file types, open in Safari
            val nsUrl = NSURL.URLWithString(url) ?: return
            UIApplication.sharedApplication.openURL(nsUrl)
        }
    }
    
    private fun injectImageLongPressHandler(webView: WKWebView) {
        val script = """
            (function() {
                var images = document.querySelectorAll('img');
                images.forEach(function(img) {
                    img.addEventListener('contextmenu', function(e) {
                        e.preventDefault();
                        var imageUrl = img.src;
                        window.webkit.messageHandlers.imageLongPress.postMessage(imageUrl);
                    });
                });
            })();
        """.trimIndent()
        
        webView.evaluateJavaScript(script) { _, _ -> }
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
                println("Error evaluating JavaScript: $error")
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
    
    actual fun setCookie(url: String, cookie: String) {
        val webView = this.webView ?: return
        val cookieStore = webView.configuration.websiteDataStore.httpCookieStore
        
        // Parse cookie string (format: "name=value; path=/; domain=.example.com")
        val cookieParts = cookie.split(";").map { it.trim() }
        val nameValue = cookieParts.firstOrNull()?.split("=") ?: return
        val cookieName: String = nameValue.firstOrNull() ?: return
        val cookieValue: String = nameValue.getOrNull(1) ?: ""
        
        // Create NSURL from the provided URL
        val nsUrl = NSURL.URLWithString(url) ?: return
        
        // Create HTTPCookie using string keys
        val cookieProperties = mutableMapOf<Any?, Any>()
        cookieProperties["name"] = cookieName
        cookieProperties["value"] = cookieValue
        val urlHost: String = (nsUrl.host as? String) ?: ""
        val urlPath: String = (nsUrl.path as? String) ?: "/"
        cookieProperties["domain"] = urlHost
        cookieProperties["path"] = urlPath
        
        // Parse additional properties from cookie string
        cookieParts.drop(1).forEach { part ->
            when {
                part.startsWith("path=", ignoreCase = true) -> {
                    val pathValue: String = part.substringAfter("=").trim()
                    cookieProperties["path"] = pathValue
                }
                part.startsWith("domain=", ignoreCase = true) -> {
                    val domainValue: String = part.substringAfter("=").trim()
                    cookieProperties["domain"] = domainValue
                }
                part.startsWith("expires=", ignoreCase = true) -> {
                    // Handle expiration if needed
                }
                part.equals("secure", ignoreCase = true) -> {
                    cookieProperties["secure"] = "TRUE"
                }
                part.startsWith("httponly", ignoreCase = true) -> {
                    cookieProperties["HTTPOnly"] = "TRUE"
                }
            }
        }
        
        @OptIn(ExperimentalForeignApi::class)
        val httpCookie = NSHTTPCookie.cookieWithProperties(cookieProperties) ?: return
        
        cookieStore.setCookie(httpCookie) { }
    }
    
    actual fun getCookies(url: String): String? {
        val webView = this.webView ?: return null
        val cookieStore = webView.configuration.websiteDataStore.httpCookieStore
        val nsUrl = NSURL.URLWithString(url) ?: return null
        
        var cookiesString: String? = null
        val semaphore = platform.darwin.dispatch_semaphore_create(0)
        
        cookieStore.getAllCookies { cookies ->
            @OptIn(ExperimentalForeignApi::class)
            val relevantCookies = cookies?.filter { cookie ->
                val cookieObj = cookie as? NSHTTPCookie ?: return@filter false
                val cookieDomain = cookieObj.domain as? String ?: ""
                val urlHost = (nsUrl.host as? String) ?: ""
                cookieDomain.contains(urlHost) || urlHost.contains(cookieDomain.removePrefix("."))
            }
            
            cookiesString = relevantCookies?.joinToString("; ") { cookie ->
                @OptIn(ExperimentalForeignApi::class)
                val cookieObj = cookie as? NSHTTPCookie ?: return@joinToString ""
                val name = cookieObj.name as? String ?: ""
                val value = cookieObj.value as? String ?: ""
                "$name=$value"
            }
            
            platform.darwin.dispatch_semaphore_signal(semaphore)
        }
        
        platform.darwin.dispatch_semaphore_wait(semaphore, platform.darwin.DISPATCH_TIME_FOREVER)
        return cookiesString
    }
    
    @OptIn(ExperimentalForeignApi::class)
    actual fun clearCookies() {
        val webView = this.webView ?: return
        val cookieStore = webView.configuration.websiteDataStore.httpCookieStore
        
        cookieStore.getAllCookies { cookies ->
            cookies?.forEach { cookie ->
                val cookieObj = cookie as? NSHTTPCookie
                if (cookieObj != null) {
                    cookieStore.deleteCookie(cookieObj) { }
                }
            }
        }
    }
    
    actual fun removeCookie(url: String, cookieName: String) {
        val webView = this.webView ?: return
        val cookieStore = webView.configuration.websiteDataStore.httpCookieStore
        val nsUrl = NSURL.URLWithString(url) ?: return
        
        cookieStore.getAllCookies { cookies ->
            @OptIn(ExperimentalForeignApi::class)
            cookies?.filter { cookie ->
                val cookieObj = cookie as? NSHTTPCookie ?: return@filter false
                val name = cookieObj.name as? String ?: ""
                name == cookieName && run {
                    val cookieDomain = cookieObj.domain as? String ?: ""
                    val urlHost = (nsUrl.host as? String) ?: ""
                    cookieDomain.contains(urlHost) || urlHost.contains(cookieDomain.removePrefix("."))
                }
            }?.forEach { cookie ->
                val cookieObj = cookie as? NSHTTPCookie
                if (cookieObj != null) {
                    cookieStore.deleteCookie(cookieObj) { }
                }
            }
        }
    }
    
    actual fun dispose() {
        // Remove message handlers before clearing
        val webViewToCleanup = webView
        if (webViewToCleanup != null) {
            cleanupWebView(webViewToCleanup)
        }
        messageHandlers.clear()
        pendingInterfaces.clear()
        imageLongPressHandler = null
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
            val webView = WKWebView()
            val config = webView.configuration
            
            // Set basic preferences
            config.preferences.javaScriptEnabled = true
            config.preferences.javaScriptCanOpenWindowsAutomatically = true
            config.allowsInlineMediaPlayback = true
            
            // Note: WKAppBoundDomains removed from Info.plist to allow JavaScript injection
            // Security is handled via navigation delegate instead
            
            webView.apply {
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
