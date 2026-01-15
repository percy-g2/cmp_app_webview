# TradingView WebView App

A Kotlin Multiplatform mobile application that displays TradingView charts for cryptocurrency trading pairs using Compose Multiplatform and native WebView components.

## Architecture Overview

This application follows a **Kotlin Multiplatform Mobile (KMM)** architecture pattern, sharing business logic and UI code across Android and iOS platforms while leveraging platform-specific WebView implementations.

### Project Structure

* [/composeApp](./composeApp/src) contains the shared Compose Multiplatform code:
  - [commonMain](./composeApp/src/commonMain/kotlin) - Shared code for all platforms
  - [androidMain](./composeApp/src/androidMain/kotlin) - Android-specific implementations
  - [iosMain](./composeApp/src/iosMain/kotlin) - iOS-specific implementations

* [/iosApp](./iosApp/iosApp) contains the iOS application entry point and SwiftUI integration

### Architecture Layers

#### 1. **Presentation Layer** (`commonMain/kotlin/com/androdevlinux/webview/`)
   - **App.kt**: Main composable with Material 3 UI
     - Tab-based navigation for crypto pairs (BTC, ETH, SOL, BNB)
     - Theme management (Light/Dark/System)
     - Top app bar with theme toggle
     - Bottom navigation bar
     - Loading and error state handling
   
   - **ThemeUtils.kt**: Platform-agnostic theme detection
     - `getSystemDarkTheme()`: Expect function for platform-specific theme detection

#### 2. **WebView Abstraction Layer** (`commonMain/kotlin/com/androdevlinux/webview/webview/`)
   
   **WebViewState.kt**
   - `WebViewLoadingState`: Enum for loading states (IDLE, LOADING, LOADED, ERROR)
   - `WebViewState`: Data class holding webview state (URL, navigation, errors)
   
   **WebViewController.kt** (expect class)
   - Common interface for webview operations across platforms
   - Methods: `loadUrl()`, `evaluateJavaScript()`, `addJavaScriptInterface()`, `reload()`, `goBack()`, `goForward()`, etc.
   - Factory function: `createWebViewController()`
   
   **WebViewComposable.kt**
   - `WebViewComposable`: High-level Compose component
   - Manages WebViewController lifecycle
   - Handles JavaScript interface registration
   - Provides state change callbacks

#### 3. **Platform-Specific Implementations**

   **Android** (`androidMain/kotlin/com/androdevlinux/webview/webview/AndroidWebView.kt`)
   - Uses Android's `WebView` component
   - `AndroidView` composable for Compose integration
   - JavaScript bridge via `@JavascriptInterface`
   - WebViewClient and WebChromeClient for navigation and error handling
   - Features: JavaScript enabled, DOM storage, zoom controls, mixed content support
   
   **iOS** (`iosMain/kotlin/com/androdevlinux/webview/webview/IOSWebView.kt`)
   - Uses `WKWebView` from WebKit framework
   - `UIKitView` composable for Compose integration
   - JavaScript bridge via `WKScriptMessageHandler`
   - WKNavigationDelegate for navigation and error handling
   - Injects JavaScript bridge code for native communication

### Data Flow

```
User Interaction (Tab Selection/Theme Toggle)
    ↓
App.kt (State Management)
    ↓
WebViewComposable (URL Change)
    ↓
WebViewController (Platform-specific)
    ↓
Native WebView (Android WebView / iOS WKWebView)
    ↓
State Updates → WebViewState → App.kt (UI Updates)
```

### Key Features

1. **Multi-platform WebView**: Unified API across Android and iOS
2. **JavaScript Bridge**: Bidirectional communication between web content and native code
3. **State Management**: Reactive state updates for loading, errors, and navigation
4. **Theme Support**: Light, Dark, and System theme modes
5. **Tab Navigation**: Quick switching between crypto trading pairs
6. **Error Handling**: User-friendly error messages and loading indicators

### Platform-Specific Details

**Android:**
- Uses `android.webkit.WebView`
- JavaScript interfaces via `@JavascriptInterface` annotation
- WebViewClient for page lifecycle events
- Supports mixed content and file access

**iOS:**
- Uses `WKWebView` from WebKit
- Message handlers via `WKScriptMessageHandler`
- WKNavigationDelegate for navigation events
- JavaScript injection for bridge setup

## Build and Run

### Build and Run Android Application

To build and run the development version of the Android app, use the run configuration from the run widget
in your IDE's toolbar or build it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :composeApp:assembleDebug
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:assembleDebug
  ```

### Build and Run iOS Application

To build and run the development version of the iOS app, use the run configuration from the run widget
in your IDE's toolbar or open the [/iosApp](./iosApp) directory in Xcode and run it from there.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
