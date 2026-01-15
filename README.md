# TradingView WebView App

A Kotlin Multiplatform mobile application that displays TradingView charts for cryptocurrency trading pairs using Compose Multiplatform and native WebView components.

## Architecture Overview

This application follows a **Kotlin Multiplatform Mobile (KMM)** architecture with shared Compose UI and platform-specific WebView implementations. Android hosts the shared UI in a Compose `Activity`, while iOS hosts it inside a SwiftUI app via a `UIViewControllerRepresentable` bridge.

### Project Structure

* [/composeApp](./composeApp/src) contains the Kotlin Multiplatform code:
  - [commonMain](./composeApp/src/commonMain/kotlin) - Shared Compose UI and abstractions
  - [androidMain](./composeApp/src/androidMain/kotlin) - Android `actual` implementations
  - [iosMain](./composeApp/src/iosMain/kotlin) - iOS `actual` implementations

* [/iosApp](./iosApp/iosApp) contains the SwiftUI host app for iOS

### Architecture Layers

#### 1. **App Entry Points**
   - **Android** (`androidMain/kotlin/com/androdevlinux/webview/MainActivity.kt`)
     - `MainActivity` hosts Compose and calls `App()`
   - **iOS** (`iosApp/iosApp/ContentView.swift`)
     - SwiftUI `ContentView` embeds `MainViewController()` from Kotlin via `UIViewControllerRepresentable`
   - **Shared UI host** (`iosMain/kotlin/com/androdevlinux/webview/MainViewController.kt`)
     - `MainViewController()` creates the Compose UI for iOS

#### 2. **Presentation Layer** (`commonMain/kotlin/com/androdevlinux/webview/`)
   - **App.kt**: Main composable with Material 3 UI
     - Tab navigation for crypto pairs (BTC, ETH, SOL, BNB)
     - Theme management (Light/Dark/System) and TradingView theme parameter
     - Loading and error state handling from WebView state callbacks
   - **ThemeUtils.kt**: Expect/actual system theme detection
     - `getSystemDarkTheme()` implemented per platform

#### 3. **WebView Abstraction Layer** (`commonMain/kotlin/com/androdevlinux/webview/webview/`)
   - **WebViewState.kt**
     - `WebViewLoadingState`: `IDLE`, `LOADING`, `LOADED`, `ERROR`
     - `WebViewState`: URL, navigation flags, error message
   - **WebViewController.kt** (expect class)
     - Common interface for WebView operations (`loadUrl`, `evaluateJavaScript`, navigation, etc.)
     - Factory: `createWebViewController()`
   - **WebViewComposable.kt**
     - High-level Compose component that wires `PlatformWebView` and `WebViewController`
     - Registers JavaScript interfaces and emits `WebViewState` updates
     - Uses `PlatformWebView` expect/actual composable

#### 4. **Platform-Specific Implementations**

   **Android** (`androidMain/kotlin/com/androdevlinux/webview/webview/AndroidWebView.kt`)
   - Uses Android `WebView` inside `AndroidView`
   - JavaScript bridge via `@JavascriptInterface`
   - `WebViewClient`/`WebChromeClient` for navigation and error handling
   - Web settings: JS, DOM storage, zoom, mixed content support

   **iOS** (`iosMain/kotlin/com/androdevlinux/webview/webview/IOSWebView.kt`)
   - Uses `WKWebView` inside `UIKitView`
   - JavaScript bridge via `WKScriptMessageHandler`
   - `WKNavigationDelegate` for navigation and error handling
   - Injected bridge script for native callbacks

### Data Flow

```
User Interaction (Tab Selection/Theme Toggle)
    ↓
App.kt (State + Theme)
    ↓
WebViewComposable (URL load + state callbacks)
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
