package com.androdevlinux.webview.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

enum class ThemeMode(val displayName: String) {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System")
}

/**
 * Get system dark theme preference.
 * Returns true if system is in dark mode, false otherwise.
 */
@Composable
expect fun getSystemDarkTheme(): Boolean

@Composable
fun AppTheme(
    themeMode: ThemeMode,
    content: @Composable (isDarkTheme: Boolean) -> Unit
) {
    val systemIsDark = getSystemDarkTheme()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemIsDark
    }

    MaterialTheme(
        colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme
    ) {
        content(isDarkTheme)
    }
}
