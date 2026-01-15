package com.androdevlinux.webview

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun AndroidApp() {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    App(onThemeChanged = { isDarkTheme ->
        if (window != null) {
            updateSystemBars(window, view, isDarkTheme)
        }
    })
}

private fun updateSystemBars(
    window: android.view.Window,
    view: android.view.View,
    isDarkTheme: Boolean
) {
    val controller = WindowCompat.getInsetsController(window, view)
    val useDarkIcons = !isDarkTheme
    controller.isAppearanceLightStatusBars = useDarkIcons
    controller.isAppearanceLightNavigationBars = useDarkIcons
}
